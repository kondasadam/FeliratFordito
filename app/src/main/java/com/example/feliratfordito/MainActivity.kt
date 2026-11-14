package com.example.feliratfordito

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Élő feliratfordító: a kamera képén a kijelölt sávban felismeri az angol szöveget
 * (ML Kit, a telefonon), azonnal lefordítja offline, majd pontosabban online
 * (Google Fordító, illetve Gemini a játék és az előzmények ismeretében).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var boxView: SubtitleBoxView
    private lateinit var translatedText: TextView
    private lateinit var sourceText: TextView
    private lateinit var statusText: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var updateBtn: Button
    private lateinit var recBtn: Button
    private var recDefaultTint: ColorStateList? = null

    private var camera: Camera? = null
    private lateinit var analysisExecutor: ExecutorService
    private val netExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var recognizer: TextRecognizer
    private lateinit var translator: Translator
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var translatorReady = false
    private val busy = AtomicBoolean(false)

    // A feliratsáv helye a képernyőn, 0..1 arányban
    @Volatile private var cropNorm = RectF(0.1f, 0.6f, 0.9f, 0.85f)
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0

    private var lastSource = ""      // amit utoljára lefordítottunk
    private var candidate = ""       // új szöveg, amit még egyszer látni kell, mielőtt fordítjuk
    private val recentReads = ArrayDeque<String>()   // utolsó leolvasások (a legmegbízhatóbb kiválasztásához)
    private var textSizeSp = 26f

    private val offlineCache = lruMap()
    private val googleCache = lruMap()
    private val geminiCache = lruMap()          // kulcs: a teljes felirat
    private var geminiShownFor = ""             // ehhez a felirathoz már Gemini-fordítás látszik

    private val recentHistory = ArrayDeque<String>()   // utolsó feliratok a Gemini-nek (háttér)

    // Felvétel
    private var recording = false
    private var recStart = 0L
    private val recLines = ArrayList<String>()
    private val recTranslations = HashMap<String, String>()

    private val prefs by lazy { getSharedPreferences("beallitasok", MODE_PRIVATE) }

    private fun lruMap() = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 300
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else showStatus("Kamera engedély nélkül nem működik. Engedélyezd a telefon beállításaiban.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        previewView = findViewById(R.id.previewView)
        boxView = findViewById(R.id.boxView)
        translatedText = findViewById(R.id.translatedText)
        sourceText = findViewById(R.id.sourceText)
        statusText = findViewById(R.id.statusText)
        zoomBar = findViewById(R.id.zoomBar)
        updateBtn = findViewById(R.id.updateBtn)
        recBtn = findViewById(R.id.recBtn)
        recDefaultTint = recBtn.backgroundTintList

        updateBtn.setOnClickListener { startUpdate() }
        recBtn.setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.summaryBtn).setOnClickListener {
            startActivity(Intent(this, SummaryActivity::class.java))
        }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { openSettings() }
        statusText.setOnClickListener { if (translatorReady) statusText.visibility = View.GONE }

        // Mentett beállítások
        cropNorm = RectF(
            prefs.getFloat("l", 0.1f), prefs.getFloat("t", 0.6f),
            prefs.getFloat("r", 0.9f), prefs.getFloat("b", 0.85f)
        )
        textSizeSp = prefs.getFloat("meret", 26f)
        translatedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        zoomBar.progress = prefs.getInt("zoom", 0)
        sourceText.visibility = if (prefs.getBoolean("angol", true)) View.VISIBLE else View.GONE

        boxView.setBox(cropNorm)
        boxView.onBoxChanged = { r ->
            cropNorm = RectF(r)
            prefs.edit().putFloat("l", r.left).putFloat("t", r.top)
                .putFloat("r", r.right).putFloat("b", r.bottom).apply()
            // élesség és fénymérés a keret új helyére (kis késleltetéssel)
            mainHandler.removeCallbacks(focusNow)
            mainHandler.postDelayed(focusNow, 400)
        }

        previewView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            viewW = v.width
            viewH = v.height
        }

        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                camera?.cameraControl?.setLinearZoom(progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putInt("zoom", zoomBar.progress).apply()
                mainHandler.removeCallbacks(focusNow)
                mainHandler.postDelayed(focusNow, 300)
            }
        })

        findViewById<Button>(R.id.smallerBtn).setOnClickListener { changeTextSize(-2f) }
        findViewById<Button>(R.id.biggerBtn).setOnClickListener { changeTextSize(2f) }
        findViewById<Button>(R.id.toggleEnBtn).setOnClickListener {
            val show = sourceText.visibility != View.VISIBLE
            sourceText.visibility = if (show) View.VISIBLE else View.GONE
            prefs.edit().putBoolean("angol", show).apply()
        }

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.HUNGARIAN)
                .build()
        )

        showStatus("Fordítási modell előkészítése…\n(az első indításkor internet kell, kb. 30 MB)")
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translatorReady = true
                statusText.visibility = View.GONE
            }
            .addOnFailureListener { e ->
                showStatus("A fordítási modell letöltése nem sikerült (${e.message}).\nKapcsolj be internetet, és indítsd újra az appot.")
            }

        analysisExecutor = Executors.newSingleThreadExecutor()
        checkForUpdate()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showStatus(msg: String) {
        statusText.text = msg
        statusText.visibility = View.VISIBLE
    }

    private fun changeTextSize(delta: Float) {
        textSizeSp = (textSizeSp + delta).coerceIn(12f, 60f)
        translatedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        prefs.edit().putFloat("meret", textSizeSp).apply()
    }

    // ---------- Kamera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val aspect = AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO)

                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // A lehető legnagyobb felbontás (4K, ha a telefon tudja) – a kis betűkhöz
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(aspect)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(3840, 2160),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera?.cameraControl?.setLinearZoom(zoomBar.progress / 100f)

                mainHandler.removeCallbacks(periodicFocus)
                mainHandler.postDelayed(periodicFocus, 800)
            } catch (e: Exception) {
                showStatus("A kamera nem indult el: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private val focusNow = Runnable { focusOnBox() }

    // 10 másodpercenként újra élesít a keretre (ha elmozdulna a telefon)
    private val periodicFocus = object : Runnable {
        override fun run() {
            focusOnBox()
            mainHandler.postDelayed(this, 10_000)
        }
    }

    /** Élesség és fénymérés a sárga keretre: így a fényes tévén sem folynak szét a betűk. */
    private fun focusOnBox() {
        val cam = camera ?: return
        if (previewView.width == 0 || previewView.height == 0) return
        val r = cropNorm
        val point = previewView.meteringPointFactory.createPoint(
            r.centerX() * previewView.width,
            r.centerY() * previewView.height,
            r.width().coerceIn(0.1f, 0.6f)
        )
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).disableAutoCancel().build()
        try {
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            // nem minden telefon támogatja – ilyenkor marad az automatikus
        }
    }

    // ---------- Képfeldolgozás (háttérszálon) ----------

    private fun analyze(proxy: ImageProxy) {
        if (busy.get() || !translatorReady || viewW == 0 || viewH == 0) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val crop: Bitmap? = try {
            extractCrop(proxy, rotation)
        } catch (e: Exception) {
            null
        } finally {
            proxy.close()
        }
        if (crop == null) return

        val input = enhance(crop)
        busy.set(true)
        recognizer.process(InputImage.fromBitmap(input, rotation))
            .addOnSuccessListener { result -> handleText(result) }
            .addOnFailureListener { busy.set(false) }
    }

    /**
     * A képernyőn kijelölt sávot átszámolja a kamerakép (puffer) koordinátáira.
     * A PreviewView "fillCenter" módban van: a kép kitölti a nézetet, a széleket levágja.
     */
    private fun cropRectInBuffer(bw: Int, bh: Int, rotation: Int): Rect? {
        val sideways = rotation == 90 || rotation == 270
        val iw = if (sideways) bh else bw   // a kép szélessége kijelzett helyzetben
        val ih = if (sideways) bw else bh

        val vw = viewW.toFloat()
        val vh = viewH.toFloat()
        val scale = maxOf(vw / iw, vh / ih)
        val offX = (vw - iw * scale) / 2f
        val offY = (vh - ih * scale) / 2f

        val box = cropNorm
        val l = ((box.left * vw - offX) / scale).coerceIn(0f, iw.toFloat())
        val t = ((box.top * vh - offY) / scale).coerceIn(0f, ih.toFloat())
        val r = ((box.right * vw - offX) / scale).coerceIn(0f, iw.toFloat())
        val b = ((box.bottom * vh - offY) / scale).coerceIn(0f, ih.toFloat())
        if (r - l < 8f || b - t < 8f) return null

        // kijelzett -> puffer koordináták (rotation = ennyivel kell órajárás szerint forgatni)
        val x0: Float; val y0: Float; val x1: Float; val y1: Float
        when (rotation) {
            90 -> { x0 = t; y0 = bh - r; x1 = b; y1 = bh - l }
            180 -> { x0 = bw - r; y0 = bh - b; x1 = bw - l; y1 = bh - t }
            270 -> { x0 = bw - b; y0 = l; x1 = bw - t; y1 = r }
            else -> { x0 = l; y0 = t; x1 = r; y1 = b }
        }
        val x = x0.toInt().coerceIn(0, bw - 1)
        val y = y0.toInt().coerceIn(0, bh - 1)
        val w = (x1.toInt() - x).coerceIn(1, bw - x)
        val h = (y1.toInt() - y).coerceIn(1, bh - y)
        return Rect(x, y, x + w, y + h)
    }

    /** Csak a kijelölt sávot másolja ki a kameraképből (gyors, nagy felbontásnál is). */
    private fun extractCrop(proxy: ImageProxy, rotation: Int): Bitmap? {
        val rect = cropRectInBuffer(proxy.width, proxy.height, rotation) ?: return null
        val plane = proxy.planes[0]
        val buf = plane.buffer
        buf.rewind()
        val rowStride = plane.rowStride
        val pxStride = plane.pixelStride   // RGBA: 4
        val w = rect.width()
        val h = rect.height()
        val rowBytes = w * 4
        val out = ByteBuffer.allocate(rowBytes * h)
        val row = ByteArray(rowBytes)
        for (yy in 0 until h) {
            buf.position((rect.top + yy) * rowStride + rect.left * pxStride)
            buf.get(row, 0, rowBytes)
            out.put(row)
        }
        out.rewind()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(out)
        return bmp
    }

    /** Szürkeárnyalat + kontrasztnövelés (+ nagyítás, ha kicsi): a mozgó háttér kevésbé zavar. */
    private fun enhance(src: Bitmap): Bitmap {
        val scale = if (src.width < 900 && src.height < 900) 2f else 1f
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val contrast = 1.5f
        val shift = (-0.5f * contrast + 0.5f) * 255f
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, shift,
                    0f, contrast, 0f, 0f, shift,
                    0f, 0f, contrast, 0f, shift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(out).drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
        return out
    }

    // ---------- Felismert szöveg (fő szálon) ----------

    private fun handleText(result: Text) {
        val blocks = result.textBlocks
            .sortedBy { it.boundingBox?.top ?: 0 }
            .map { block -> cleanup(block.lines.joinToString(" ") { it.text }) }
            .filter { s -> s.count { it.isLetter() } >= 3 }
        val source = blocks.joinToString("\n")

        if (source.isNotEmpty()) {
            recentReads.addLast(source)
            while (recentReads.size > 4) recentReads.removeFirst()
        }

        // Üres kép: marad az utolsó fordítás. Ugyanaz a felirat: nincs újrafordítás (nem villog).
        if (source.isEmpty() || similar(source, lastSource)) {
            candidate = ""
            busy.set(false)
            return
        }
        // Új szöveg: csak akkor fordítjuk, ha két egymás utáni képen is ugyanaz
        if (!similar(source, candidate)) {
            candidate = source
            busy.set(false)
            return
        }
        candidate = ""

        // Az utolsó leolvasások közül a leggyakoribb változat (egy elmosódott kép nem ront el szót)
        val best = recentReads.filter { similar(it, source) }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.key ?: source
        val finalBlocks = if (best == source) blocks else best.split("\n")

        lastSource = best
        sourceText.text = best
        if (recording && (recLines.isEmpty() || !similar(recLines.last(), best))) recLines.add(best)
        translateBlocks(finalBlocks)
    }

    // ---------- Szöveg tisztítása (szövegfelismerési hibák javítása) ----------

    private data class Line(val speaker: String?, val body: String)

    private val speakerRegex =
        Regex("^([A-Z][\\p{L}'.\\-]*(?:\\s[A-Z][\\p{L}'.\\-]*){0,3})\\s*:\\s*(.+)$")

    private val wordsAfterI = setOf(
        "appreciate", "am", "have", "had", "will", "would", "think", "know", "need", "want",
        "can", "can't", "cannot", "don't", "didn't", "do", "did", "was", "wasn't", "must",
        "should", "could", "see", "understand", "believe", "hope", "mean", "just", "really",
        "never", "also", "feel", "promise", "guess", "suppose", "wonder", "said", "told",
        "heard", "found", "thought", "love", "hate", "agree", "doubt", "assure", "remember",
        "forgot", "tried", "got", "saw", "knew", "wanted", "needed", "shall", "may", "might",
        "i'm", "i've", "i'll", "i'd", "imagine", "recommend", "apologize", "suggest", "expect"
    )

    private val realLWords = setOf(
        "like", "let", "look", "last", "long", "list", "left", "life", "lot", "love", "line", "less",
        "late", "led", "lay", "law", "low", "lost", "lead", "land", "luck", "lady", "lord", "into",
        "idea", "item", "isle", "ice", "icy", "ill", "its", "inn", "ink"
    )

    private fun fixGluedI(rest: String): String? {
        val r = rest.lowercase()
        if (r in wordsAfterI) return r
        if (r.length >= 6) {
            val best = wordsAfterI.filter { it.length >= 6 }.minByOrNull { levenshtein(it, r) }
            if (best != null && levenshtein(best, r) <= 2) return best
        }
        return null
    }

    private fun cleanup(s: String): String {
        var t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('’', '\'').replace('‘', '\'')
        t = t.replace(Regex("(?<=[A-Za-z]):(?=\\S)"), ": ")
        t = Regex("\\b[lI1]([a-z']{2,})\\b").replace(t) { m ->
            if (m.value.lowercase() in realLWords) m.value
            else fixGluedI(m.groupValues[1])?.let { "I $it" } ?: m.value
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun splitSpeaker(s: String): Line {
        val m = speakerRegex.find(s) ?: return Line(null, s)
        return Line(m.groupValues[1], m.groupValues[2])
    }

    private fun displayText(ln: Line) = ln.speaker?.let { "$it: ${ln.body}" } ?: ln.body

    private fun wordCount(s: String) = s.split(' ').count { it.any(Char::isLetter) }

    // ---------- Fordítás: azonnal offline -> Google -> (ha kint marad) Gemini ----------

    private fun showLines(lines: List<Line>, tr: List<String>, engine: String) {
        translatedText.text = lines.indices.joinToString("\n") { i ->
            lines[i].speaker?.let { "$it: ${tr[i]}" } ?: tr[i]
        }
        // szürkés = gyors offline, fehér = online (Google / Gemini)
        translatedText.setTextColor(if (engine == "offline") Color.rgb(190, 190, 190) else Color.WHITE)
        sourceText.text = if (engine == "offline") lastSource else "$lastSource   [$engine]"
        if (recording) recTranslations[lastSource] = translatedText.text.toString()
    }

    private fun translateBlocks(blocks: List<String>) {
        val key = blocks.joinToString("\n")
        val lines = blocks.map { splitSpeaker(it) }

        val history = recentHistory.toList()
        lines.forEach { recentHistory.addLast(displayText(it)) }
        while (recentHistory.size > 4) recentHistory.removeFirst()

        // Már lefordítottuk korábban?
        geminiCache[key]?.let { cached ->
            val parts = cached.split("\n")
            if (parts.size == lines.size) {
                geminiShownFor = key
                showLines(lines, parts, "Gemini")
                busy.set(false)
                return
            }
        }
        val googleHit = lines.map { googleCache[it.body] }

        val results = arrayOfNulls<String>(lines.size)
        var remaining = lines.size

        fun finishOne() {
            remaining--
            if (remaining == 0) {
                if (googleHit.all { it != null }) {
                    showLines(lines, googleHit.map { it!! }, "Google")
                } else {
                    showLines(lines, results.map { it ?: "" }, "offline")
                }
                busy.set(false)
                requestOnline(lines, googleHit, key, history)
            }
        }

        lines.forEachIndexed { i, ln ->
            val cached = offlineCache[ln.body]
            if (cached != null) {
                results[i] = cached
                finishOne()
            } else {
                translator.translate(ln.body)
                    .addOnSuccessListener { tr ->
                        offlineCache[ln.body] = tr
                        results[i] = tr
                        finishOne()
                    }
                    .addOnFailureListener {
                        results[i] = ln.body
                        finishOne()
                    }
            }
        }
    }

    private fun useGemini(): Boolean =
        prefs.getString("engine", "gemini") == "gemini" && Gemini.apiKey(this).isNotEmpty()

    private fun requestOnline(lines: List<Line>, googleHit: List<String?>, key: String, history: List<String>) {
        // 1) Google azonnal (gyors, nincs kerete)
        if (googleHit.any { it == null }) {
            netExecutor.execute {
                val out = lines.indices.map { i -> googleHit[i] ?: googleTranslate(lines[i].body) }
                if (out.any { it == null }) return@execute   // nincs internet: marad az offline
                runOnUiThread {
                    lines.forEachIndexed { i, ln -> googleCache[ln.body] = out[i]!! }
                    if (key == lastSource && geminiShownFor != key) showLines(lines, out.map { it!! }, "Google")
                }
            }
        }

        // 2) Gemini csak akkor, ha érdemes: van hosszabb mondat, és a felirat fél másodpercig kint marad.
        //    Egy feliratváltás = egyetlen kérés (a sorokat együtt küldjük), így kímélj a keretet.
        if (!useGemini() || lines.none { wordCount(it.body) > 3 }) return
        mainHandler.postDelayed({
            if (key != lastSource) return@postDelayed   // közben jött új felirat: nem kérdezzük
            netExecutor.execute {
                val tr = geminiTranslate(lines, history)
                runOnUiThread {
                    if (tr != null) {
                        geminiCache[key] = tr.joinToString("\n")
                        if (key == lastSource) {
                            geminiShownFor = key
                            showLines(lines, tr, "Gemini")
                        }
                    } else if (key == lastSource) {
                        sourceText.text = "$lastSource   [Google · Gemini: ${Gemini.lastError}]"
                    }
                }
            }
        }, 600)
    }

    /** Egy feliratváltás összes sora egyetlen Gemini-kérésben. Hiba vagy rossz sorszám esetén null. */
    private fun geminiTranslate(lines: List<Line>, history: List<String>): List<String>? {
        val game = Gemini.game(this)
        val system = buildString {
            append("Videójáték-feliratokat fordítasz angolról magyarra.")
            if (game.isNotEmpty()) append(" A játék: $game.")
            append(" Természetes, gördülékeny, beszélt magyar nyelven fordíts, ne szó szerint.")
            append(" A szöveg kamerás szövegfelismeréssel készült, lehetnek benne betűhibák: értelemszerűen javítsd őket.")
            append(" A személyneveket, helyneveket és a játék saját kifejezéseit hagyd eredetiben,")
            append(" kivéve ha a játék magyar változatában bevett fordításuk van.")
            append(" Pontosan annyi sort adj vissza, ahány sort kaptál, ugyanabban a sorrendben, sorszám nélkül.")
            append(" Csak a fordítást írd, magyarázat, idézőjel és a beszélő neve nélkül.")
        }
        val user = buildString {
            if (history.isNotEmpty()) {
                append("Előző feliratok (csak háttér, ne fordítsd le):\n")
                history.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            append("Fordítandó sorok (${lines.size} db):\n")
            lines.forEach { ln ->
                if (ln.speaker != null) append("[").append(ln.speaker).append("] ")
                append(ln.body).append('\n')
            }
        }
        val text = Gemini.generate(this, system, user, 500, 6000) ?: return null
        var out = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .map { it.replace(Regex("^(\\d+[.)]\\s*|-\\s+|\\[[^\\]]*\\]\\s*)"), "").trim('"', '„', '”') }
        if (lines.size == 1 && out.size > 1) out = listOf(out.joinToString(" "))
        if (out.size != lines.size) {
            Gemini.lastError = "eltérő sorszám"
            return null
        }
        return out
    }

    /** Google Fordító (online, kulcs nélkül). Hiba vagy internet hiánya esetén null. */
    private fun googleTranslate(text: String): String? {
        return try {
            val url = URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=hu&dt=t&q=" +
                    URLEncoder.encode(text, "UTF-8")
            )
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 2500
            c.readTimeout = 2500
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            try {
                if (c.responseCode != 200) return null
                val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val segs = JSONArray(body).getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until segs.length()) {
                    val seg = segs.optJSONArray(i) ?: continue
                    sb.append(seg.optString(0, ""))
                }
                sb.toString().trim().ifEmpty { null }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Felvétel ----------

    private fun toggleRecording() {
        if (!recording) {
            recording = true
            recStart = System.currentTimeMillis()
            recLines.clear()
            recTranslations.clear()
            if (lastSource.isNotEmpty()) {
                recLines.add(lastSource)
                recTranslations[lastSource] = translatedText.text.toString()
            }
            recBtn.text = "■ Stop"
            recBtn.backgroundTintList = ColorStateList.valueOf(Color.rgb(198, 40, 40))
            recBtn.setTextColor(Color.WHITE)
            Toast.makeText(this, "Felvétel elindult", Toast.LENGTH_SHORT).show()
        } else {
            recording = false
            recBtn.text = "● Rec"
            recBtn.backgroundTintList = recDefaultTint
            if (recLines.isEmpty()) {
                Toast.makeText(this, "Nem volt felirat a felvételben", Toast.LENGTH_SHORT).show()
                return
            }
            val id = System.currentTimeMillis()
            val arr = JSONArray()
            recLines.forEach { en ->
                arr.put(JSONObject().put("en", en).put("hu", recTranslations[en] ?: ""))
            }
            val session = JSONObject()
                .put("id", id)
                .put("start", recStart)
                .put("end", id)
                .put("game", Gemini.game(this))
                .put("lines", arr)
                .put("summary", "")
            SessionStore.add(this, session)
            val intent = Intent(this, SummaryActivity::class.java)
            if (Gemini.apiKey(this).isNotEmpty()) intent.putExtra("autoSummarize", id)
            startActivity(intent)
        }
    }

    // ---------- Beállítások ----------

    private fun openSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val rGemini = RadioButton(this).apply { id = View.generateViewId(); text = "Gemini (pontosabb, kulcs kell)" }
        val rGoogle = RadioButton(this).apply { id = View.generateViewId(); text = "Google Fordító" }
        val group = RadioGroup(this).apply { addView(rGemini); addView(rGoogle) }
        group.check(if (prefs.getString("engine", "gemini") == "google") rGoogle.id else rGemini.id)

        val keyEdit = EditText(this).apply {
            hint = "Gemini API-kulcs (másold be)"
            isSingleLine = true
            setText(prefs.getString("geminiKey", ""))
        }
        val help = TextView(this).apply {
            text = "Ingyenes kulcs: aistudio.google.com/apikey"
            textSize = 12f
        }
        val gameEdit = EditText(this).apply {
            hint = "Játék neve (pl. Hogwarts Legacy)"
            isSingleLine = true
            setText(prefs.getString("game", ""))
        }
        val modelLabel = TextView(this).apply {
            text = "Gemini modell (haladó, hagyd így)"
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }
        val modelEdit = EditText(this).apply {
            isSingleLine = true
            setText(prefs.getString("geminiModel", Gemini.DEFAULT_MODEL))
        }
        listOf(group, keyEdit, help, gameEdit, modelLabel, modelEdit).forEach { box.addView(it) }

        AlertDialog.Builder(this)
            .setTitle("Fordítás beállításai")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Mentés") { _, _ ->
                prefs.edit()
                    .putString("engine", if (group.checkedRadioButtonId == rGoogle.id) "google" else "gemini")
                    .putString("geminiKey", keyEdit.text.toString().trim())
                    .putString("game", gameEdit.text.toString().trim())
                    .putString("geminiModel", modelEdit.text.toString().trim().ifEmpty { Gemini.DEFAULT_MODEL })
                    .apply()
                geminiCache.clear()
                geminiShownFor = ""
                Gemini.pausedUntil = 0L
                Gemini.lastError = ""
                lastSource = ""   // a következő feliratot újrafordítja
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    // ---------- Segédfüggvények ----------

    /** Két szöveg "ugyanaz-e" (a szövegfelismerés apró eltéréseit elnézve). */
    private fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val maxLen = maxOf(a.length, b.length)
        if (kotlin.math.abs(a.length - b.length) > maxLen * 0.2) return false
        return 1.0 - levenshtein(a, b).toDouble() / maxLen >= 0.85
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    // ---------- Frissítés a GitHub-ról ----------

    private val apkUrl = "https://github.com/kondasadam/FeliratFordito/releases/latest/download/FeliratFordito.apk"
    private var downloading = false
    private var downloadedApk: File? = null

    private fun currentVersion(): Long = try {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
    } catch (e: Exception) {
        0L
    }

    private fun checkForUpdate() {
        Thread {
            try {
                val c = URL("https://api.github.com/repos/kondasadam/FeliratFordito/releases/latest")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode == 200) {
                    val json = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    val latest = json.optString("tag_name").removePrefix("v").toLongOrNull() ?: 0L
                    if (latest > currentVersion()) {
                        runOnUiThread {
                            updateBtn.text = "Frissítés elérhető"
                            updateBtn.visibility = View.VISIBLE
                        }
                    }
                }
                c.disconnect()
            } catch (e: Exception) {
                // nincs internet: nincs frissítésjelzés
            }
        }.start()
    }

    private fun startUpdate() {
        if (downloading) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            showStatus("Engedélyezd az appnak a frissítések telepítését,\nmajd lépj vissza, és nyomd meg újra a Frissítés gombot.")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) { }
            return
        }
        downloadedApk?.let { if (it.exists()) { installApk(it); return } }

        downloading = true
        if (translatorReady) statusText.visibility = View.GONE
        updateBtn.text = "Letöltés…"
        Thread {
            val file = File(File(cacheDir, "updates").apply { mkdirs() }, "FeliratFordito.apk")
            val ok = try {
                val c = URL(apkUrl).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000
                c.readTimeout = 30000
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    runOnUiThread { updateBtn.text = "Letöltés $pct%" }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                downloading = false
                if (ok) {
                    downloadedApk = file
                    updateBtn.text = "Telepítés"
                    installApk(file)
                } else {
                    updateBtn.text = "Frissítés (újra)"
                    showStatus("A frissítés letöltése nem sikerült. Ellenőrizd az internetet.")
                }
            }
        }.start()
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            showStatus("A telepítő nem indult el: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
        netExecutor.shutdown()
        recognizer.close()
        translator.close()
    }
}
