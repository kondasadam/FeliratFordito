package com.example.feliratfordito

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Felvett beszélgetések listája, összefoglalóval. */
class SummaryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val bodies = HashMap<Long, TextView>()
    private val dateFmt = SimpleDateFormat("yyyy.MM.dd. HH:mm", Locale("hu"))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_summary)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        list = findViewById(R.id.list)
        render()

        val auto = intent.getLongExtra("autoSummarize", -1L)
        if (auto > 0) summarize(auto, force = false)
    }

    private fun render() {
        list.removeAllViews()
        bodies.clear()

        val arr = SessionStore.load(this)
        if (arr.length() == 0) {
            list.addView(TextView(this).apply {
                text = "Még nincs felvétel.\n\nA fordító képernyőn a ● Rec gombbal indítsd a felvételt egy beszélgetés elején, " +
                    "a végén nyomd meg a ■ Stop gombot, és itt kapsz róla összefoglalót."
                setTextColor(Color.LTGRAY)
                textSize = 16f
                setPadding(dp(4), dp(8), dp(4), 0)
            })
            return
        }

        for (i in arr.length() - 1 downTo 0) {
            addCard(arr.getJSONObject(i))
        }
    }

    private fun addCard(s: JSONObject) {
        val id = s.optLong("id")
        val lines = s.optJSONArray("lines")
        val n = lines?.length() ?: 0
        val game = s.optString("game", "")

        val outer = MaterialCardView(this).apply {
            setCardBackgroundColor(Color.rgb(26, 30, 36))
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = 0
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(14)
        outer.layoutParams = lp
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        outer.addView(card)

        card.addView(TextView(this).apply {
            text = dateFmt.format(Date(s.optLong("start"))) + "  ·  $n felirat" + if (game.isNotEmpty()) "  ·  $game" else ""
            setTextColor(Color.rgb(255, 213, 79))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })

        val body = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
            setTextIsSelectable(true)
            val summary = s.optString("summary", "")
            text = if (summary.isNotEmpty()) summary else "Még nincs összefoglaló."
        }
        bodies[id] = body
        card.addView(body)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, onClick: () -> Unit) =
            MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onClick() }
            }
        row.addView(btn("Összefoglaló") { summarize(id, force = false) })
        row.addView(btn("Újra") { summarize(id, force = true) })
        row.addView(btn("Szöveg") { showTranscript(id) })
        row.addView(btn("Törlés") { confirmDelete(id) })
        card.addView(row)

        list.addView(outer)
    }

    private fun summarize(id: Long, force: Boolean) {
        val s = SessionStore.find(this, id) ?: return
        val body = bodies[id] ?: return
        val existing = s.optString("summary", "")
        if (existing.isNotEmpty() && !force) {
            body.text = existing
            return
        }
        val lines = s.optJSONArray("lines")
        if (lines == null || lines.length() == 0) {
            body.text = "Ebben a felvételben nincs felirat."
            return
        }
        // a végéről legfeljebb 400 sor
        val from = maxOf(0, lines.length() - 400)
        val transcript = StringBuilder()
        for (i in from until lines.length()) {
            transcript.append(lines.getJSONObject(i).optString("en")).append('\n')
        }
        val game = s.optString("game", "")

        val system = buildString {
            append("Egy videójáték párbeszédének feliratait kapod angolul, kamerás szövegfelismerésből, ezért lehetnek benne betűhibák és ismétlődések.")
            if (game.isNotEmpty()) append(" A játék: $game.")
            append(" Írj róla magyar összefoglalót: ki kivel beszélt, miről volt szó, és milyen fontos információ,")
            append(" döntés vagy feladat (küldetés, cél, helyszín) hangzott el. Rövid pontokba szedve, közérthetően.")
            append(" Ha volt választási lehetőség a párbeszédben, azt is említsd meg. A neveket hagyd eredetiben.")
            append(" Csak az összefoglalót írd, bevezető nélkül.")
        }

        body.text = "Összefoglaló készül…"
        Thread {
            val result = Gemini.generate(this, system, transcript.toString(), 1500, 40_000)
            runOnUiThread {
                if (result != null) {
                    SessionStore.update(this, id) { it.put("summary", result) }
                    body.text = result
                } else {
                    body.text = "Nem sikerült összefoglalót készíteni (${Gemini.lastError}).\n" +
                        "Ellenőrizd a Gemini-kulcsot a ⚙ beállításokban, vagy próbáld újra kicsit később."
                }
            }
        }.start()
    }

    private fun showTranscript(id: Long) {
        val s = SessionStore.find(this, id) ?: return
        val lines = s.optJSONArray("lines") ?: return
        val sb = StringBuilder()
        for (i in 0 until lines.length()) {
            val l = lines.getJSONObject(i)
            sb.append(l.optString("en"))
            val hu = l.optString("hu", "")
            if (hu.isNotEmpty()) sb.append("\n→ ").append(hu)
            sb.append("\n\n")
        }
        val tv = TextView(this).apply {
            text = sb.toString().trim()
            setTextIsSelectable(true)
            textSize = 15f
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Teljes szöveg")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Bezárás", null)
            .show()
    }

    private fun confirmDelete(id: Long) {
        AlertDialog.Builder(this)
            .setMessage("Törlöd ezt a felvételt?")
            .setPositiveButton("Törlés") { _, _ ->
                SessionStore.delete(this, id)
                render()
            }
            .setNegativeButton("Mégse", null)
            .show()
    }
}
