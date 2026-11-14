package com.example.feliratfordito

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip

/** Főmenü: bemutatja az appot, innen érhető el minden funkció. */
class HomeActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("beallitasok", MODE_PRIVATE) }
    private lateinit var updater: Updater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<View>(R.id.startBtn).setOnClickListener { openTranslator() }

        tile(R.id.tileLive, R.drawable.ic_camera, "Élő fordítás", "Irányítsd a kamerát a tévére") { openTranslator() }
        tile(R.id.tileRecordings, R.drawable.ic_notes, "Összefoglalók", "Felvett beszélgetések") {
            startActivity(Intent(this, SummaryActivity::class.java))
        }
        tile(R.id.tileSettings, R.drawable.ic_settings, "Beállítások", "Fordító, játék, megjelenés") { openSettings() }
        tile(R.id.tileHelp, R.drawable.ic_help, "Hogyan működik?", "Rövid útmutató") { showHelp() }

        feature(R.id.feat1, R.drawable.ic_bolt, "Azonnali fordítás",
            "Fél másodperc alatt megjelenik a magyar szöveg")
        feature(R.id.feat2, R.drawable.ic_brain, "Érti a jelenetet",
            "A Gemini ismeri a játékot és az előzményeket, a neveket nem fordítja le")
        feature(R.id.feat3, R.drawable.ic_notes, "Beszélgetés-összefoglaló",
            "Vedd fel a párbeszédet, és kérj róla magyar összefoglalót")
        feature(R.id.feat4, R.drawable.ic_offline, "Internet nélkül is megy",
            "Offline is fordít, internettel pontosabban")

        findViewById<Chip>(R.id.engineChip).setOnClickListener { openSettings() }
        findViewById<Chip>(R.id.gameChip).setOnClickListener { openSettings() }

        val updateCard = findViewById<View>(R.id.updateCard)
        val updateBtn = findViewById<Button>(R.id.updateBtn)
        updater = Updater(this) { state ->
            if (state == null) {
                updateCard.visibility = View.GONE
            } else {
                updateCard.visibility = View.VISIBLE
                updateBtn.text = state
                if (updater.latestVersion > 0) {
                    findViewById<TextView>(R.id.updateText).text = "Elérhető a ${updater.latestVersion}. verzió"
                }
            }
        }
        updateBtn.setOnClickListener { updater.start() }
        updater.check()

        findViewById<TextView>(R.id.versionText).text = "Feliratfordító · ${updater.currentVersion()}. verzió"
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val gemini = prefs.getString("engine", "gemini") == "gemini" && Gemini.apiKey(this).isNotEmpty()
        findViewById<Chip>(R.id.engineChip).apply {
            text = if (gemini) "● Gemini bekapcsolva" else "● Google Fordító"
            setTextColor(if (gemini) Color.rgb(129, 199, 132) else Color.rgb(255, 213, 79))
        }
        val game = Gemini.game(this)
        findViewById<Chip>(R.id.gameChip).text = if (game.isNotEmpty()) "🎮 $game" else "🎮 Játék megadása"

        val n = SessionStore.load(this).length()
        tileDesc(R.id.tileRecordings, if (n == 0) "Még nincs felvétel" else "$n felvett beszélgetés")
    }

    private fun openTranslator() = startActivity(Intent(this, MainActivity::class.java))
    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun tile(id: Int, icon: Int, title: String, desc: String, onClick: () -> Unit) {
        val v = findViewById<View>(id)
        v.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
        v.findViewById<TextView>(R.id.tileTitle).text = title
        v.findViewById<TextView>(R.id.tileDesc).text = desc
        v.setOnClickListener { onClick() }
    }

    private fun tileDesc(id: Int, desc: String) {
        findViewById<View>(id).findViewById<TextView>(R.id.tileDesc).text = desc
    }

    private fun feature(id: Int, icon: Int, title: String, desc: String) {
        val v = findViewById<View>(id)
        v.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
        v.findViewById<TextView>(R.id.rowTitle).text = title
        v.findViewById<TextView>(R.id.rowDesc).text = desc
    }

    private fun showHelp() {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt())
        }
        box.addView(TextView(this).apply {
            text = "Hogyan működik?"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val steps = listOf(
            "Tedd a telefont egy tartóba úgy, hogy lássa a tévét (állva vagy fekve is jó).",
            "Nyomd meg a „Fordítás indítása” gombot, és két ujjal nagyíts rá a tévére.",
            "Húzd a sárga keretet a felirat helyére. Dupla koppintás a kereten: alaphelyzet.",
            "A magyar szöveg az angol felirat helyén jelenik meg a képen. (A Beállításokban kérhetsz külön kártyát is.)",
            "Beszélgetés elején nyomd meg a piros gombot, a végén újra: kapsz egy összefoglalót.",
            "Pontosabb fordításhoz add meg a Gemini-kulcsot és a játék nevét a Beállításokban."
        )
        steps.forEachIndexed { i, s ->
            box.addView(TextView(this).apply {
                text = "${i + 1}.  $s"
                textSize = 15f
                setTextColor(Color.rgb(220, 224, 230))
                setPadding(0, (12 * dp).toInt(), 0, 0)
            })
        }
        BottomSheetDialog(this).apply {
            setContentView(box)
            show()
        }
    }
}
