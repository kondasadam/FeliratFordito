package com.example.feliratfordito

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
    private lateinit var statusCard: MaterialCardView
    private lateinit var translationCard: MaterialCardView
    private lateinit var recFab: FloatingActionButton
    private lateinit var recIndicator: TextView
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var settingsVer = -1
    private lateinit var overlay: TranslationOverlayView
    private var arMode = true          // fordítás a felirat helyén (a képen)
    private var emptyReads = 0
    private var lastTextSeenAt = 0L      // mikor láttunk utoljára szöveget a keretben
    private var lastCommitAt = 0L        // mikor jelent meg az utolsó új felirat
    private var candidateCount = 0       // hány egymás utáni képen volt ugyanaz az új szöveg
    private var pendingImg: Bitmap? = null   // a legutóbbi felirat képe (a Gemini ebből olvas Profi módban)

    /** A kivágott sáv helye a nézetben (a felismert szöveg visszavetítéséhez). */
    private class CropMap(val l: Float, val t: Float, val scale: Float, val offX: Float, val offY: Float) {
        var enh: Float = 1f          // OCR előtti nagyítás
        var sample: Float = 1f       // a kép ennyiszer kisebb a teljes felbontásnál (fénykép dekódolás)
        var img: Bitmap? = null      // a kivágott sáv színesben, álló helyzetben (háttérszín mintához)
    }

    private var imageCapture: ImageCapture? = null
    private val capturing = AtomicBoolean(false)
    private var readMode = "screen"   // photo = éles fényképekből (pontos), video = videóképből (gyors)

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
        overlay = findViewById(R.id.overlay)
        translatedText = findViewById(R.id.translatedText)
        sourceText = findViewById(R.id.sourceText)
        statusText = findViewById(R.id.statusText)
        statusCard = findViewById(R.id.statusCard)
        translationCard = findViewById(R.id.translationCard)
        zoomBar = findViewById(R.id.zoomBar)
        recFab = findViewById(R.id.recFab)
        recIndicator = findViewById(R.id.recIndicator)

        recFab.setOnClickListener { toggleRecording() }
        findViewById<View>(R.id.menuBtn).setOnClickListener { finish() }   // vissza a főmenübe
        boxView.onDoubleTap = { resetBox() }
        statusCard.setOnClickListener { if (translatorReady) statusCard.visibility = View.GONE }

        cropNorm = loadBox()
        zoomBar.progress = prefs.getInt("zoom", 0)

        boxView.setBox(cropNorm)
        boxView.onBoxChanged = { r ->
            cropNorm = RectF(r)
            saveBox(r)
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
                if (fromUser) camera?.cameraControl?.setLinearZoom(progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) = zoomChanged()
        })
        findViewById<View>(R.id.zoomOut).setOnClickListener { setLinearZoom(zoomBar.progress - 10) }
        findViewById<View>(R.id.zoomIn).setOnClickListener { setLinearZoom(zoomBar.progress + 10) }

        // Két ujjas nagyítás / kicsinyítés, mint a kameránál
        boxView.onPinch = { factor ->
            camera?.let { cam ->
                val zs = cam.cameraInfo.zoomState.value
                if (zs != null) {
                    val ratio = (zs.zoomRatio * factor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(ratio)
                }
            }
        }
        boxView.onPinchEnd = { zoomChanged() }

        SpellFix.load(this)
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
                statusCard.visibility = View.GONE
            }
            .addOnFailureListener { e ->
                showStatus("A fordítási modell letöltése nem sikerült (${e.message}).\nKapcsolj be internetet, és indítsd újra az appot.")
            }

        analysisExecutor = Executors.newSingleThreadExecutor()
        if (!prefs.getBoolean("readModeV3", false)) {
            // új alapértelmezés: a képernyőn látható képből olvasunk (a videókép sok telefonon kis felbontású)
            prefs.edit().putString("readMode", "screen").putBoolean("readModeV3", true).apply()
        }
        readMode = prefs.getString("readMode", "screen") ?: "screen"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showStatus(msg: String) {
        statusText.text = msg
        statusCard.visibility = View.VISIBLE
    }

    private fun setLinearZoom(progress: Int) {
        val p = progress.coerceIn(0, 100)
        zoomBar.progress = p
        camera?.cameraControl?.setLinearZoom(p / 100f)
        zoomChanged()
    }

    private fun zoomChanged() {
        prefs.edit().putInt("zoom", zoomBar.progress).apply()
        mainHandler.removeCallbacks(focusNow)
        mainHandler.postDelayed(focusNow, 300)
    }

    // ---------- Keret helye: álló és fekvő helyzetben külön megjegyezve ----------

    private fun isPortrait() = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    private fun loadBox(): RectF {
        val sfx = if (isPortrait()) "_p" else ""
        val def = if (isPortrait()) RectF(0.05f, 0.55f, 0.95f, 0.68f) else RectF(0.1f, 0.6f, 0.9f, 0.85f)
        return RectF(
            prefs.getFloat("l$sfx", def.left), prefs.getFloat("t$sfx", def.top),
            prefs.getFloat("r$sfx", def.right), prefs.getFloat("b$sfx", def.bottom)
        )
    }

    private fun saveBox(r: RectF) {
        val sfx = if (isPortrait()) "_p" else ""
        prefs.edit().putFloat("l$sfx", r.left).putFloat("t$sfx", r.top)
            .putFloat("r$sfx", r.right).putFloat("b$sfx", r.bottom).apply()
    }

    private fun resetBox() {
        val sfx = if (isPortrait()) "_p" else ""
        prefs.edit().remove("l$sfx").remove("t$sfx").remove("r$sfx").remove("b$sfx").apply()
        cropNorm = loadBox()
        boxView.setBox(cropNorm)
        mainHandler.postDelayed(focusNow, 300)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rot = previewView.display?.rotation ?: Surface.ROTATION_0
        preview?.targetRotation = rot
        analysis?.targetRotation = rot
        imageCapture?.targetRotation = rot
        // elforgatás után más a nézet képaránya: a közös képkivágás miatt újrakötjük a kamerát
        previewView.post { if (camera != null) startCamera() }
        cropNorm = loadBox()
        boxView.setBox(cropNorm)
        candidate = ""
        mainHandler.removeCallbacks(focusNow)
        mainHandler.postDelayed(focusNow, 600)
    }

    // ---------- Menü és beállítások ----------

    override fun onResume() {
        super.onResume()
        applyDisplayPrefs()
        val v = prefs.getInt("settingsVer", 0)
        if (settingsVer != -1 && v != settingsVer) {
            // változott a fordító / kulcs / játék: a következő feliratot újrafordítjuk
            geminiCache.clear()
            geminiShownFor = ""
            Gemini.pausedUntil = 0L
            Gemini.lastError = ""
            lastSource = ""
        }
        settingsVer = v
        val m = prefs.getString("readMode", "screen") ?: "screen"
        if (m != readMode) {
            readMode = m
            if (camera != null) startCamera()
        }
    }

    private fun applyDisplayPrefs() {
        val gname = Gemini.game(this)
        findViewById<TextView>(R.id.gameLabel).apply {
            text = "🎮 $gname"
            visibility = if (gname.isEmpty()) View.GONE else View.VISIBLE
        }
        arMode = prefs.getString("displayMode", "ar") == "ar"
        overlay.visibility = if (arMode) View.VISIBLE else View.GONE
        translationCard.visibility = if (arMode) View.GONE else View.VISIBLE
        textSizeSp = prefs.getFloat("meret", 26f)
        translatedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        sourceText.visibility = if (prefs.getBoolean("angol", true)) View.VISIBLE else View.GONE
        val lp = translationCard.layoutParams as FrameLayout.LayoutParams
        val bottom = prefs.getBoolean("panelLent", false)
        lp.gravity = if (bottom) Gravity.BOTTOM else Gravity.TOP
        lp.bottomMargin = if (bottom) (120 * resources.displayMetrics.density).toInt() else lp.topMargin
        translationCard.layoutParams = lp
    }

    // ---------- Kamera ----------

    private fun startCamera() {
        // a közös képkivágáshoz (viewport) a nézetnek már méretének kell lennie
        if (previewView.viewPort == null) {
            previewView.post { startCamera() }
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val aspect = AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO)

                val rot = previewView.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder()
                    .setTargetRotation(rot)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(aspect)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(3840, 2160), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                            )
                            .build()
                    )
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                this.preview = preview
                provider.unbindAll()
                mainHandler.removeCallbacks(captureTick)
                mainHandler.removeCallbacks(screenTick)

                if (readMode == "screen") {
                    // Képernyőről olvasás: pontosan azt a képet olvassuk, amit az élő képen látsz
                    imageCapture = null
                    analysis = null
                    camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group(preview))
                    mainHandler.postDelayed(screenTick, 600)
                } else if (readMode == "photo") {
                    // Pontos mód: folyamatos, a lehető legnagyobb felbontású fényképek
                    val ic = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                        .setTargetRotation(rot)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(aspect)
                                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                                .build()
                        )
                        .build()
                    imageCapture = ic
                    analysis = null
                    camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group(preview, ic))
                    mainHandler.postDelayed(captureTick, 600)
                } else {
                    // Gyors mód: videókép (4K, ha a telefon tudja)
                    val an = ImageAnalysis.Builder()
                        .setTargetRotation(rot)
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
                    an.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                    analysis = an
                    imageCapture = null
                    camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group(preview, an))
                }
                camera?.cameraControl?.setLinearZoom(zoomBar.progress / 100f)
                camera?.cameraInfo?.zoomState?.observe(this) { zs ->
                    zoomBar.progress = (zs.linearZoom * 100).toInt()
                }

                mainHandler.removeCallbacks(periodicFocus)
                mainHandler.postDelayed(periodicFocus, 800)
            } catch (e: Exception) {
                showStatus("A kamera nem indult el: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Közös képkivágás: a fénykép / videókép pontosan azt a területet fedi, ami az élő képen látszik. */
    private fun group(vararg cases: androidx.camera.core.UseCase): UseCaseGroup {
        val b = UseCaseGroup.Builder()
        previewView.viewPort?.let { b.setViewPort(it) }
        cases.forEach { b.addUseCase(it) }
        return b.build()
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
        val cropped: Pair<Bitmap, CropMap>? = try {
            extractCrop(proxy, rotation)
        } catch (e: Exception) {
            null
        } finally {
            proxy.close()
        }
        if (cropped == null) return
        processCrop(cropped.first, rotation, cropped.second, 1.5f)
    }

    /** Közös feldolgozás: álló helyzetbe forgatás, kontrasztjavítás, szövegfelismerés. */
    private var sharpAvg = 0.0
    @Volatile private var lastOcrAt = 0L

    /** Élesség becslése (Laplace-szűrő átlagos erőssége egy kicsinyített szürke képen). */
    private fun sharpness(b: Bitmap): Double {
        val w = 160
        val h = maxOf(8, b.height * w / maxOf(1, b.width))
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(w * h) { i ->
            val c = px[i]
            (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
        }
        var sum = 0L
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            sum += kotlin.math.abs(4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w])
        }
        return sum.toDouble() / ((w - 2) * (h - 2))
    }

    private fun processCrop(crop: Bitmap, rotation: Int, map: CropMap, contrast: Float) {
        // Elmosódott képkocka (bemozdulás, fókuszálás): kihagyjuk, ha nemrég volt jobb
        val sharp = try { sharpness(crop) } catch (e: Exception) { 0.0 }
        val nowMs = System.currentTimeMillis()
        if (readMode != "photo" && sharpAvg > 0 && sharp < sharpAvg * 0.6 && nowMs - lastOcrAt < 700) {
            sharpAvg = sharpAvg * 0.95 + sharp * 0.05
            return
        }
        sharpAvg = if (sharpAvg == 0.0) sharp else sharpAvg * 0.9 + sharp * 0.1
        lastOcrAt = nowMs
        val upright = if (rotation == 0) crop else
            Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        map.img = upright
        val (input, enh) = enhance(upright, contrast)
        map.enh = enh
        busy.set(true)
        recognizer.process(InputImage.fromBitmap(input, 0))
            .addOnSuccessListener { result -> handleText(result, map) }
            .addOnFailureListener { busy.set(false) }
    }

    // ---------- Képernyőről olvasás (alapértelmezett) ----------

    @Volatile private var lastCropInfo = ""

    private val screenTick = object : Runnable {
        override fun run() {
            if (readMode != "screen") return
            if (camera != null && translatorReady && !busy.get() && !capturing.get() && viewW > 0) {
                val full: Bitmap? = try { previewView.bitmap } catch (e: Exception) { null }
                if (full != null) {
                    val sx = full.width.toFloat() / previewView.width
                    val sy = full.height.toFloat() / previewView.height
                    val r = cropNorm
                    val x = (r.left * previewView.width * sx).toInt().coerceIn(0, full.width - 2)
                    val y = (r.top * previewView.height * sy).toInt().coerceIn(0, full.height - 2)
                    val w = ((r.right - r.left) * previewView.width * sx).toInt().coerceIn(2, full.width - x)
                    val h = ((r.bottom - r.top) * previewView.height * sy).toInt().coerceIn(2, full.height - y)
                    val crop = Bitmap.createBitmap(full, x, y, w, h)
                    // a kivágás képpontjai -> nézet koordináták
                    val map = CropMap(x.toFloat(), y.toFloat(), 1f / sx, 0f, 0f)
                    lastCropInfo = "${w}×${h} px"
                    capturing.set(true)
                    analysisExecutor.execute {
                        try {
                            processCrop(crop, 0, map, 1.2f)
                        } finally {
                            capturing.set(false)
                        }
                    }
                }
            }
            mainHandler.postDelayed(this, 120)
        }
    }

    // ---------- Pontos mód: folyamatos, nagy felbontású fényképek ----------

    private val captureTick = object : Runnable {
        override fun run() {
            val ic = imageCapture ?: return
            if (!busy.get() && !capturing.get() && translatorReady && viewW > 0) {
                capturing.set(true)
                try {
                    ic.takePicture(analysisExecutor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                processStill(image)
                            } finally {
                                capturing.set(false)
                            }
                        }
                        override fun onError(exception: ImageCaptureException) {
                            capturing.set(false)
                        }
                    })
                } catch (e: Exception) {
                    capturing.set(false)
                }
            }
            mainHandler.postDelayed(this, 120)
        }
    }

    /** A fényképből (JPEG) csak a kijelölt sávot dekódolja – gyors, és teljes felbontású. */
    private fun processStill(image: ImageProxy) {
        val rotation = image.imageInfo.rotationDegrees
        val result: Pair<Bitmap, CropMap>? = try {
            val cr = cropRectInBuffer(image.width, image.height, rotation, image.cropRect)
            if (cr == null) null else {
                val buf = image.planes[0].buffer
                buf.rewind()
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                @Suppress("DEPRECATION")
                val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                val sample = if (cr.first.width() > 2600 || cr.first.height() > 2600) 2 else 1
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = decoder?.decodeRegion(cr.first, opts)
                decoder?.recycle()
                cr.second.sample = sample.toFloat()
                if (bmp == null) null else Pair(bmp, cr.second)
            }
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
        if (result != null) processCrop(result.first, rotation, result.second, 1.2f)
    }

    /**
     * A képernyőn kijelölt sávot átszámolja a kamerakép (puffer) koordinátáira.
     * A PreviewView "fillCenter" módban van: a kép kitölti a nézetet, a széleket levágja.
     */
    private fun cropRectInBuffer(bw: Int, bh: Int, rotation: Int, visibleRect: Rect?): Pair<Rect, CropMap>? {
        // A képnek az a része, ami az élő képen látszik (a viewport miatt minden use case-nél ugyanaz)
        val vis = if (visibleRect != null && visibleRect.width() > 0 && visibleRect.height() > 0
            && visibleRect.right <= bw && visibleRect.bottom <= bh) visibleRect else Rect(0, 0, bw, bh)
        val cw = vis.width()
        val ch = vis.height()
        val sideways = rotation == 90 || rotation == 270
        val iw = if (sideways) ch else cw   // a látható rész szélessége kijelzett helyzetben
        val ih = if (sideways) cw else ch

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

        // kijelzett -> a látható rész koordinátái (rotation = ennyivel kell órajárás szerint forgatni)
        val x0: Float; val y0: Float; val x1: Float; val y1: Float
        when (rotation) {
            90 -> { x0 = t; y0 = ch - r; x1 = b; y1 = ch - l }
            180 -> { x0 = cw - r; y0 = ch - b; x1 = cw - l; y1 = ch - t }
            270 -> { x0 = cw - b; y0 = l; x1 = cw - t; y1 = r }
            else -> { x0 = l; y0 = t; x1 = r; y1 = b }
        }
        val x = (vis.left + x0.toInt()).coerceIn(0, bw - 1)
        val y = (vis.top + y0.toInt()).coerceIn(0, bh - 1)
        val w = (x1.toInt() - x0.toInt()).coerceIn(1, bw - x)
        val h = (y1.toInt() - y0.toInt()).coerceIn(1, bh - y)
        return Pair(Rect(x, y, x + w, y + h), CropMap(l, t, scale, offX, offY))
    }

    /** Csak a kijelölt sávot másolja ki a kameraképből (gyors, nagy felbontásnál is). */
    private fun extractCrop(proxy: ImageProxy, rotation: Int): Pair<Bitmap, CropMap>? {
        val (rect, map) = cropRectInBuffer(proxy.width, proxy.height, rotation, proxy.cropRect) ?: return null
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
        return Pair(bmp, map)
    }

    /** Szürkeárnyalat + kontrasztnövelés (+ nagyítás, ha kicsi): a mozgó háttér kevésbé zavar. */
    private fun enhance(src: Bitmap, contrast: Float): Pair<Bitmap, Float> {
        val scale = if (src.width < 900 && src.height < 900) 2f else 1f
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
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
        return Pair(out, scale)
    }

    // ---------- Felismert szöveg (fő szálon) ----------

    private class Found(val text: String, val rect: RectF, val lineH: Float, val lines: Int, val bg: Int)

    /** Felismert szövegdoboz -> hely a képernyőn. */
    private fun toViewRect(b: Rect, m: CropMap): RectF {
        val k = m.sample / m.enh   // felismert koordináta -> teljes felbontású kijelzett koordináta
        return RectF(
            m.offX + (m.l + b.left * k) * m.scale,
            m.offY + (m.t + b.top * k) * m.scale,
            m.offX + (m.l + b.right * k) * m.scale,
            m.offY + (m.t + b.bottom * k) * m.scale
        )
    }

    /** Az eredeti szöveg körüli háttérszín (a doboz széle mentén mintavételezve). */
    private fun sampleBg(b: Rect, m: CropMap): Int {
        val img = m.img ?: return Color.rgb(20, 20, 24)
        val l = (b.left / m.enh).toInt() - 3
        val t = (b.top / m.enh).toInt() - 3
        val r = (b.right / m.enh).toInt() + 3
        val bt = (b.bottom / m.enh).toInt() + 3
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        fun add(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= img.width || y >= img.height) return
            val c = img.getPixel(x, y)
            rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++
        }
        val stepX = maxOf(1, (r - l) / 24)
        val stepY = maxOf(1, (bt - t) / 8)
        var x = l
        while (x <= r) { add(x, t); add(x, bt); x += stepX }
        var y = t
        while (y <= bt) { add(l, y); add(r, y); y += stepY }
        if (n == 0) return Color.rgb(20, 20, 24)
        return Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
    }

    private fun handleText(result: Text, map: CropMap) {
        val kept = result.textBlocks
            .sortedBy { it.boundingBox?.top ?: 0 }
            .mapNotNull { block ->
                val t = cleanup(block.lines.joinToString(" ") { it.text })
                val box = block.boundingBox
                if (t.count { it.isLetter() } < 3 || box == null) null
                else {
                    val lineH = (block.lines.firstOrNull()?.boundingBox?.height() ?: box.height()) * map.sample / map.enh * map.scale
                    Found(t, toViewRect(box, map), lineH, block.lines.size, sampleBg(box, map))
                }
            }
        val blocks = kept.map { it.text }
        val rects = kept.map { it.rect }
        val lineHeights = kept.map { it.lineH }
        val lineCounts = kept.map { it.lines }
        val colors = kept.map { it.bg }
        val source = blocks.joinToString("\n")

        // A felirat eltűnt: csak ha kb. 1,2 mp-ig nincs szöveg, vesszük le (egy-egy kihagyott kép miatt nem villan)
        val now = System.currentTimeMillis()
        if (source.isEmpty()) {
            emptyReads++
            if (now - lastTextSeenAt > 1200) overlay.clear()
        } else {
            emptyReads = 0
            lastTextSeenAt = now
        }

        if (source.isNotEmpty()) {
            recentReads.addLast(source)
            while (recentReads.size > 6) recentReads.removeFirst()
        }

        // Üres kép: marad az utolsó fordítás. Ugyanaz a felirat: nincs újrafordítás (nem villog).
        // Ugyanaz a felirat (kis leolvasási eltérésekkel), vagy friss felirat, ami még nem tér el lényegesen
        val sameAsShown = lastSource.isNotEmpty() &&
            (sameSubtitle(source, lastSource) || (now - lastCommitAt < 1000 && textSimilarity(source, lastSource) > 0.5))
        if (source.isEmpty() || sameAsShown) {
            if (source.isNotEmpty()) {
                // ugyanaz a felirat: a fordítás kövesse, ha kicsit elmozdult a kép
                if (overlay.count == rects.size) overlay.moveRects(rects)
                else if (overlay.count == 0 && lastTranslations.isNotEmpty()) {
                    overlay.setRects(rects, lineHeights, lineCounts, colors)
                    overlay.setTexts(lastTranslations)
                }
            }
            candidate = ""
            candidateCount = 0
            busy.set(false)
            return
        }
        // Új szöveg: csak akkor fordítjuk, ha több egymás utáni képen is ugyanaz (videónál 3, fényképnél 2)
        if (candidate.isNotEmpty() && sameSubtitle(source, candidate)) candidateCount++ else {
            candidate = source
            candidateCount = 1
        }
        val needed = if (readMode == "photo") 1 else 2
        if (candidateCount < needed) {
            busy.set(false)
            return
        }
        candidate = ""
        candidateCount = 0
        lastCommitAt = now

        // Az utolsó leolvasások közül a leggyakoribb változat (egy elmosódott kép nem ront el szót)
        // Szavankénti szavazás az utolsó leolvasások között: egy-egy rosszul olvasott betű kiesik
        val best = voteText(recentReads.filter { sameSubtitle(it, source) }).ifEmpty { source }
        val finalBlocks = if (best == source) blocks else best.split("\n")

        lastSource = best
        sourceText.text = best
        lastTranslations = emptyList()
        pendingImg = map.img
        overlay.setRects(rects, lineHeights, lineCounts, colors)
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
        t = t.replace(Regex("\\s+"), " ").trim()
        // angol helyesírás-javítás a felismerési hibákra (beépített szólista)
        return SpellFix.fixText(t)
    }

    private fun splitSpeaker(s: String): Line {
        val m = speakerRegex.find(s) ?: return Line(null, s)
        return Line(m.groupValues[1], m.groupValues[2])
    }

    private fun displayText(ln: Line) = ln.speaker?.let { "$it: ${ln.body}" } ?: ln.body

    private fun wordCount(s: String) = s.split(' ').count { it.any(Char::isLetter) }

    // ---------- Fordítás: azonnal offline -> Google -> (ha kint marad) Gemini ----------

    private var lastTranslations: List<String> = emptyList()

    private fun showLines(lines: List<Line>, tr: List<String>, engine: String) {
        val texts = lines.indices.map { i -> lines[i].speaker?.let { "$it: ${tr[i]}" } ?: tr[i] }
        lastTranslations = texts
        overlay.setTexts(texts)
        translatedText.text = texts.joinToString("\n")
        // szürkés = gyors offline, fehér = online (Google / Gemini)
        translatedText.setTextColor(if (engine == "offline") Color.rgb(190, 190, 190) else Color.WHITE)
        val info = if (lastCropInfo.isNotEmpty()) " · $lastCropInfo" else ""
        sourceText.text = if (engine == "offline") "$lastSource   [offline$info]" else "$lastSource   [$engine$info]"
        if (recording) recTranslations[lastSource] = translatedText.text.toString()
    }

    private fun translateBlocks(blocks: List<String>) {
        val key = blocks.joinToString("\n")
        val lines = blocks.map { splitSpeaker(it) }

        val history = recentHistory.toList()
        lines.forEach { recentHistory.addLast(displayText(it)) }
        while (recentHistory.size > 6) recentHistory.removeFirst()

        // Fordítási memória: ha minden sort ismerünk, ingyen és azonnal kiírjuk
        val mem = lines.map { TranslationMemory.get(this, it.body) }
        if (mem.all { it != null }) {
            geminiShownFor = key
            showLines(lines, mem.map { it!! }, "memória")
            busy.set(false)
            return
        }

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
        val quality = prefs.getString("quality", "pro") ?: "pro"
        val geminiWillRun = useGemini() && !(lines.size == 1 && wordCount(lines[0].body) <= 3)
        // 1) Google azonnal – de Profi/Csúcs módban kihagyjuk (kevesebb szövegcsere); ott csak tartalék
        if (googleHit.any { it == null } && !(geminiWillRun && quality != "eco")) {
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
        if (!useGemini() || (lines.size == 1 && wordCount(lines[0].body) <= 3)) return
        val img = pendingImg
        mainHandler.postDelayed({
            if (key != lastSource) return@postDelayed   // közben jött új felirat: nem kérdezzük
            netExecutor.execute {
                val tr = geminiTranslate(lines, history, img)
                runOnUiThread {
                    if (tr != null) {
                        geminiCache[key] = tr.joinToString("\n")
                        lines.forEachIndexed { i, ln -> TranslationMemory.put(this, ln.body, tr[i]) }
                        if (key == lastSource) {
                            geminiShownFor = key
                            showLines(lines, tr, "Gemini")
                        }
                    } else if (key == lastSource) {
                        sourceText.text = "$lastSource   [Google · Gemini: ${Gemini.lastError}]"
                        // tartalék: Google-fordítás, ha a Gemini nem válaszolt
                        netExecutor.execute {
                            val out = lines.map { googleCache[it.body] ?: googleTranslate(it.body) }
                            if (out.any { it == null }) return@execute
                            runOnUiThread {
                                lines.forEachIndexed { i, ln -> googleCache[ln.body] = out[i]!! }
                                if (key == lastSource && geminiShownFor != key) showLines(lines, out.map { it!! }, "Google")
                            }
                        }
                    }
                }
            }
        }, 250)
    }

    /** Egy feliratváltás összes sora egyetlen Gemini-kérésben. Hiba vagy rossz sorszám esetén null. */
    /** Kép előkészítése a Geminihez: legfeljebb 1536 px, hogy a kis és kézírásos betűk is olvashatók maradjanak. */
    private fun toJpeg(src: Bitmap): ByteArray {
        val scale = minOf(1f, 1536f / maxOf(src.width, src.height))
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
        else src
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    private fun geminiTranslate(lines: List<Line>, history: List<String>, img: Bitmap?): List<String>? {
        val game = Gemini.game(this)
        // Minőség: eco = csak szöveg; pro = Flash-Lite + kép; max = Flash + kép
        val quality = prefs.getString("quality", "pro") ?: "pro"
        val useImage = quality != "eco" && img != null
        val model = if (quality == "max") "gemini-flash-latest" else Gemini.DEFAULT_MODEL
        val system = buildString {
            append("Videójáték-feliratokat fordítasz angolról magyarra.")
            if (game.isNotEmpty()) append(" A játék: $game.")
            append(" Természetes, gördülékeny, beszélt magyar nyelven fordíts, ne szó szerint.")
            if (useImage) {
                append(" Mellékelve a játék képernyőjének kivágása (a tévéről készült fotó): EZ AZ IGAZSÁG.")
                append(" Először olvasd el figyelmesen a képen látható angol szöveget (lehet dőlt vagy kézírásos betűtípus is), és azt fordítsd.")
                append(" A telefon szövegfelismerője által olvasott sorokat csak a sorok szétválasztásához és sorrendjéhez használd,")
                append(" a tartalmukban NE bízz: értelmetlen vagy nem létező szavakat (pl. 'NeNorY', 'Musclt') soha ne vegyél át,")
                append(" hanem a képről olvasd el, mi áll ott valójában. Ha egy szót a képen sem tudsz kiolvasni, a mondat értelméből pótold.")
            } else {
                append(" A szöveg kamerás szövegfelismeréssel készült, lehetnek benne betűhibák: értelemszerűen javítsd őket.")
            }
            append(" A személyneveket, helyneveket és a játék saját kifejezéseit hagyd eredetiben,")
            append(" kivéve ha a játék magyar változatában bevett fordításuk van.")
            append(" Ha menüpontokat vagy rövid feliratokat kapsz, a magyar játékokban szokásos rövid menüneveket használd")
            append(" (pl. Gear = Felszerelés, Inventory = Készlet, Map = Térkép, Quests = Küldetések).")
            append(" Pontosan annyi sort adj vissza, ahány sort kaptál, ugyanabban a sorrendben, sorszám nélkül.")
            append(" Csak a fordítást írd, magyarázat, idézőjel és a beszélő neve nélkül.")
            val profile = GameProfiles.current(this@MainActivity)
            if (profile.isNotEmpty()) {
                append("\n\nHáttér a játékról és KÖTELEZŐ szójegyzék (a nevek és kifejezések fordításánál ezt kövesd):\n")
                append(profile)
            }
        }
        val user = buildString {
            if (history.isNotEmpty()) {
                append("Előző feliratok (csak háttér, ne fordítsd le):\n")
                history.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            append(if (useImage) "A felismerő által olvasott sorok (${lines.size} db, a képen ellenőrizd):\n" else "Fordítandó sorok (${lines.size} db):\n")
            lines.forEach { ln ->
                if (ln.speaker != null) append("[").append(ln.speaker).append("] ")
                append(ln.body).append('\n')
            }
        }
        val jpeg = if (useImage) toJpeg(img!!) else null
        val text = Gemini.generate(this, system, user, 500, 7000, modelOverride = model, imageJpeg = jpeg) ?: return null
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

    private val recTick = object : Runnable {
        override fun run() {
            val sec = (System.currentTimeMillis() - recStart) / 1000
            recIndicator.text = String.format("● FELVÉTEL %02d:%02d  ·  %d felirat", sec / 60, sec % 60, recLines.size)
            mainHandler.postDelayed(this, 1000)
        }
    }

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
            recFab.setImageResource(R.drawable.ic_stop)
            recIndicator.visibility = View.VISIBLE
            mainHandler.post(recTick)
            Toast.makeText(this, "Felvétel elindult", Toast.LENGTH_SHORT).show()
        } else {
            recording = false
            recFab.setImageResource(R.drawable.ic_rec)
            recIndicator.visibility = View.GONE
            mainHandler.removeCallbacks(recTick)
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
                .put("gameId", GameStore.active(this)?.id ?: 0L)
                .put("lines", arr)
                .put("summary", "")
            SessionStore.add(this, session)
            val intent = Intent(this, SummaryActivity::class.java)
                .putExtra("gameId", GameStore.active(this)?.id ?: -1L)
            if (Gemini.apiKey(this).isNotEmpty()) intent.putExtra("autoSummarize", id)
            startActivity(intent)
        }
    }

    // ---------- Segédfüggvények ----------

    /** Két szöveg "ugyanaz-e" (a szövegfelismerés apró eltéréseit elnézve). */
    /** Több leolvasásból soronként és szavanként a leggyakoribb változatot rakja össze. */
    private fun voteText(reads: List<String>): String {
        if (reads.isEmpty()) return ""
        if (reads.size < 3) return reads.last()
        val split = reads.map { it.split("\n") }
        val lineCount = split.groupingBy { it.size }.eachCount().maxByOrNull { it.value }!!.key
        val rs = split.filter { it.size == lineCount }
        return (0 until lineCount).joinToString("\n") { li ->
            val lines = rs.map { it[li].split(" ") }
            val wc = lines.groupingBy { it.size }.eachCount().maxByOrNull { it.value }!!.key
            val same = lines.filter { it.size == wc }
            (0 until wc).joinToString(" ") { wi ->
                same.map { it[wi] }.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
            }
        }
    }

    /** 0..1 hasonlóság (betűszinten). */
    private fun textSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    /** Ugyanaz a felirat-e: betűszinten nagyon hasonló, vagy a szavak nagy része közös (a felismerés ingadozását elnézve). */
    private fun sameSubtitle(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (textSimilarity(a, b) >= 0.75) return true
        val wa = a.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
        val wb = b.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return false
        val common = wa.intersect(wb).size.toDouble()
        return common / minOf(wa.size, wb.size) >= 0.7 && common / maxOf(wa.size, wb.size) >= 0.5
    }

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
        mainHandler.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
        netExecutor.shutdown()
        recognizer.close()
        translator.close()
    }
}
