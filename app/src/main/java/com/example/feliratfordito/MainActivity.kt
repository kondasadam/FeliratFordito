package com.example.feliratfordito

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Élő feliratfordító: a kamera képén a kijelölt sávban felismeri az angol szöveget
 * (ML Kit, a telefonon), és offline lefordítja magyarra (ML Kit Translate).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var boxView: SubtitleBoxView
    private lateinit var translatedText: TextView
    private lateinit var sourceText: TextView
    private lateinit var statusText: TextView
    private lateinit var zoomBar: SeekBar

    private var camera: Camera? = null
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var recognizer: TextRecognizer
    private lateinit var translator: Translator

    @Volatile private var translatorReady = false
    private val busy = AtomicBoolean(false)

    // A feliratsáv helye a képernyőn, 0..1 arányban
    @Volatile private var cropNorm = RectF(0.1f, 0.6f, 0.9f, 0.85f)
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0

    private var lastSource = ""      // amit utoljára lefordítottunk
    private var candidate = ""       // új szöveg, amit még egyszer látni kell, mielőtt fordítjuk
    private var textSizeSp = 26f

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 300
    }

    private val prefs by lazy { getSharedPreferences("beallitasok", MODE_PRIVATE) }

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

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(aspect)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1920, 1080),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
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
            } catch (e: Exception) {
                showStatus("A kamera nem indult el: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Kamerakép feldolgozása (háttérszálon). */
    private fun analyze(proxy: ImageProxy) {
        if (busy.get() || !translatorReady || viewW == 0 || viewH == 0) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val crop: Bitmap? = try {
            cropForBox(proxy.toBitmap(), rotation)
        } catch (e: Exception) {
            null
        } finally {
            proxy.close()
        }
        if (crop == null) return

        // Kis felbontású kivágásnál nagyítunk, így a szövegfelismerő pontosabb
        val input = if (crop.width < 900 && crop.height < 900)
            Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true) else crop

        busy.set(true)
        recognizer.process(InputImage.fromBitmap(input, rotation))
            .addOnSuccessListener { result -> handleText(result) }
            .addOnFailureListener { busy.set(false) }
    }

    /**
     * A képernyőn kijelölt sávot átszámolja a kamerakép (puffer) koordinátáira és kivágja.
     * A PreviewView "fillCenter" módban van: a kép kitölti a nézetet, a széleket levágja.
     */
    private fun cropForBox(full: Bitmap, rotation: Int): Bitmap? {
        val bw = full.width
        val bh = full.height
        val sideways = rotation == 90 || rotation == 270
        val iw = if (sideways) bh else bw   // a kép szélessége álló (kijelzett) helyzetben
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

        // Kijelzett koordináták -> puffer koordináták (rotation = ennyivel kell az órajárás szerint forgatni)
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
        return Bitmap.createBitmap(full, x, y, w, h)
    }

    /** Felismert szöveg kezelése (fő szálon). */
    private fun handleText(result: Text) {
        val blocks = result.textBlocks
            .sortedBy { it.boundingBox?.top ?: 0 }
            .map { block -> cleanup(block.lines.joinToString(" ") { it.text }) }
            .filter { s -> s.count { it.isLetter() } >= 3 }
        val source = blocks.joinToString("\n")

        // Üres kép: marad az utolsó fordítás. Ugyanaz a felirat: nincs újrafordítás (nem villog).
        if (source.isEmpty() || similar(source, lastSource)) {
            candidate = ""
            busy.set(false)
            return
        }
        // Új szöveg: csak akkor fordítjuk, ha két egymás utáni képen is ugyanaz
        // (így a beúszó/félig olvasott feliratok nem okoznak szemetet)
        if (!similar(source, candidate)) {
            candidate = source
            busy.set(false)
            return
        }
        candidate = ""
        lastSource = source
        sourceText.text = source
        translateBlocks(blocks)
    }

    // ---------- Szöveg tisztítása (szövegfelismerési hibák javítása) ----------

    private data class Line(val speaker: String?, val body: String)

    // "Ruth Singer: szöveg" -> beszélő + szöveg (a név nem megy a fordítóba)
    private val speakerRegex =
        Regex("^([A-Z][\\p{L}'.\\-]*(?:\\s[A-Z][\\p{L}'.\\-]*){0,3})\\s*:\\s*(.+)$")

    // Gyakori szavak "I" után: a felismerő az "I"-t gyakran "l"-nek olvassa és hozzáragasztja
    private val wordsAfterI = setOf(
        "appreciate", "am", "have", "had", "will", "would", "think", "know", "need", "want",
        "can", "can't", "cannot", "don't", "didn't", "do", "did", "was", "wasn't", "must",
        "should", "could", "see", "understand", "believe", "hope", "mean", "just", "really",
        "never", "also", "feel", "promise", "guess", "suppose", "wonder", "said", "told",
        "heard", "found", "thought", "love", "hate", "agree", "doubt", "assure", "remember",
        "forgot", "tried", "got", "saw", "knew", "wanted", "needed", "shall", "may", "might",
        "i'm", "i've", "i'll", "i'd", "imagine", "recommend", "apologize", "suggest", "expect"
    )

    private fun fixGluedI(rest: String): String? {
        val r = rest.lowercase()
        if (r in wordsAfterI) return r
        if (r.length >= 6) {
            // kis eltérés is elfogadható (pl. "apreclate" -> "appreciate")
            val best = wordsAfterI.filter { it.length >= 6 }.minByOrNull { levenshtein(it, r) }
            if (best != null && levenshtein(best, r) <= 2) return best
        }
        return null
    }

    private fun cleanup(s: String): String {
        // ékezetek le (angol szövegben nincs ékezet: "timé" -> "time")
        var t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('’', '\'').replace('‘', '\'')
        // "Singer:lappreciate" -> "Singer: lappreciate"
        t = t.replace(Regex("(?<=[A-Za-z]):(?=\\S)"), ": ")
        // "lappreciate" / "Iappreciate" -> "I appreciate"
        t = Regex("\\b[lI1]([a-z']{2,})\\b").replace(t) { m ->
            if (m.value.lowercase() in setOf("like", "let", "look", "last", "long", "list", "left", "life", "lot", "love", "line", "less", "late", "led", "lay", "law", "low", "lost", "lead", "land", "luck", "lady", "lord", "into", "idea", "item", "isle")) {
                m.value
            } else {
                fixGluedI(m.groupValues[1])?.let { "I $it" } ?: m.value
            }
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun splitSpeaker(s: String): Line {
        val m = speakerRegex.find(s) ?: return Line(null, s)
        return Line(m.groupValues[1], m.groupValues[2])
    }

    // ---------- Fordítás: azonnal offline, utána pontosabb online ----------

    private val netExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val onlineCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 300
    }

    private fun showLines(lines: List<Line>, tr: List<String>, online: Boolean) {
        translatedText.text = lines.indices.joinToString("\n") { i ->
            lines[i].speaker?.let { "$it: ${tr[i]}" } ?: tr[i]
        }
        // fehér = pontos online fordítás, szürkés = gyors offline fordítás
        translatedText.setTextColor(if (online) Color.WHITE else Color.rgb(190, 190, 190))
    }

    private fun translateBlocks(blocks: List<String>) {
        val key = blocks.joinToString("\n")
        val lines = blocks.map { splitSpeaker(it) }

        // Ha online már megvan, azonnal azt mutatjuk
        val onlineHit = lines.map { onlineCache[it.body] }
        if (onlineHit.all { it != null }) {
            showLines(lines, onlineHit.map { it!! }, online = true)
            busy.set(false)
            return
        }

        val results = arrayOfNulls<String>(lines.size)
        var remaining = lines.size

        fun finishOne() {
            remaining--
            if (remaining == 0) {
                showLines(lines, results.map { it ?: "" }, online = false)
                busy.set(false)
                requestOnline(lines, onlineHit, key)
            }
        }

        lines.forEachIndexed { i, ln ->
            val cached = cache[ln.body]
            if (cached != null) {
                results[i] = cached
                finishOne()
            } else {
                translator.translate(ln.body)
                    .addOnSuccessListener { tr ->
                        cache[ln.body] = tr
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

    private fun requestOnline(lines: List<Line>, cached: List<String?>, key: String) {
        netExecutor.execute {
            val out = lines.indices.map { i -> cached[i] ?: googleTranslate(lines[i].body) }
            if (out.any { it == null }) return@execute   // nincs internet: marad az offline
            runOnUiThread {
                lines.forEachIndexed { i, ln -> onlineCache[ln.body] = out[i]!! }
                // csak akkor cseréljük, ha közben nem jött új felirat
                if (key == lastSource) showLines(lines, out.map { it!! }, online = true)
            }
        }
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

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        netExecutor.shutdown()
        recognizer.close()
        translator.close()
    }
}
