package com.example.feliratfordito

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.util.TypedValue
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Beállítások képernyő. Minden változás azonnal mentődik. */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("beallitasok", MODE_PRIVATE) }

    /** Jelzi a fő képernyőnek, hogy a fordítási beállítás változott. */
    private fun bump() {
        prefs.edit().putInt("settingsVer", prefs.getInt("settingsVer", 0) + 1).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        // Fordító
        val group = findViewById<RadioGroup>(R.id.engineGroup)
        group.check(if (prefs.getString("engine", "gemini") == "google") R.id.engineGoogle else R.id.engineGemini)
        group.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString("engine", if (id == R.id.engineGoogle) "google" else "gemini").apply()
            bump()
        }

        fun bindText(viewId: Int, key: String, default: String) {
            val edit = findViewById<TextInputEditText>(viewId)
            edit.setText(prefs.getString(key, default))
            edit.doAfterTextChanged {
                prefs.edit().putString(key, it?.toString()?.trim().orEmpty()).apply()
                bump()
            }
        }
        bindText(R.id.keyEdit, "geminiKey", "")
        bindText(R.id.gameEdit, "game", "")
        bindText(R.id.modelEdit, "geminiModel", Gemini.DEFAULT_MODEL)

        // Egyenleg és fogyasztás
        val topupEdit = findViewById<TextInputEditText>(R.id.topupEdit)
        findViewById<Button>(R.id.topupBtn).setOnClickListener {
            val v = topupEdit.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
            if (v == null || v < 0) {
                Toast.makeText(this, "Adj meg egy összeget dollárban, pl. 5", Toast.LENGTH_SHORT).show()
            } else {
                UsageStore.setBalance(this, v)
                topupEdit.setText("")
                refreshUsage()
                Toast.makeText(this, "Egyenleg beállítva: ${UsageStore.usd(v)}", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.studioBtn).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/billing")))
            } catch (e: Exception) { }
        }
        fun bindPrice(viewId: Int, key: String, default: Double) {
            val edit = findViewById<TextInputEditText>(viewId)
            edit.setText(prefs.getFloat(key, default.toFloat()).toString())
            edit.doAfterTextChanged {
                val v = it?.toString()?.replace(',', '.')?.toFloatOrNull()
                if (v != null && v >= 0) {
                    prefs.edit().putFloat(key, v).apply()
                    refreshUsage()
                }
            }
        }
        bindPrice(R.id.priceInEdit, "priceIn", UsageStore.DEFAULT_PRICE_IN)
        bindPrice(R.id.priceOutEdit, "priceOut", UsageStore.DEFAULT_PRICE_OUT)
        refreshUsage()

        // Leolvasás módja
        val readGroup = findViewById<RadioGroup>(R.id.readGroup)
        readGroup.check(if (prefs.getString("readMode", "photo") == "video") R.id.readVideo else R.id.readPhoto)
        readGroup.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString("readMode", if (id == R.id.readVideo) "video" else "photo").apply()
        }

        // Megjelenítés módja
        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)
        modeGroup.check(if (prefs.getString("displayMode", "ar") == "card") R.id.modeCard else R.id.modeAr)
        modeGroup.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString("displayMode", if (id == R.id.modeCard) "card" else "ar").apply()
        }

        // Betűméret
        val sample = findViewById<TextView>(R.id.sizeSample)
        val slider = findViewById<Slider>(R.id.sizeSlider)
        val size = prefs.getFloat("meret", 26f).coerceIn(14f, 48f)
        slider.value = (Math.round(size / 2f) * 2f).coerceIn(14f, 48f)
        sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, slider.value)
        slider.addOnChangeListener { _, value, _ ->
            sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
            prefs.edit().putFloat("meret", value).apply()
        }

        val enSwitch = findViewById<MaterialSwitch>(R.id.enSwitch)
        enSwitch.isChecked = prefs.getBoolean("angol", true)
        enSwitch.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("angol", checked).apply() }

        val bottomSwitch = findViewById<MaterialSwitch>(R.id.bottomSwitch)
        bottomSwitch.isChecked = prefs.getBoolean("panelLent", false)
        bottomSwitch.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("panelLent", checked).apply() }

        // Névjegy, frissítés
        val current = try {
            PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
        } catch (e: Exception) {
            0L
        }
        findViewById<TextView>(R.id.versionText).text = "Feliratfordító – $current. verzió"
        val info = findViewById<TextView>(R.id.updateInfo)
        findViewById<Button>(R.id.checkUpdateBtn).setOnClickListener {
            info.text = "Keresés…"
            Thread {
                val latest = try {
                    val c = URL("https://api.github.com/repos/kondasadam/FeliratFordito/releases/latest")
                        .openConnection() as HttpURLConnection
                    c.connectTimeout = 5000
                    c.readTimeout = 5000
                    val v = if (c.responseCode == 200) {
                        JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                            .optString("tag_name").removePrefix("v").toLongOrNull()
                    } else null
                    c.disconnect()
                    v
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    info.text = when {
                        latest == null -> "Nem sikerült ellenőrizni (nincs internet?)."
                        latest > current -> "Elérhető a $latest. verzió! Lépj vissza, és koppints a „Frissítés elérhető” gombra."
                        else -> "A legfrissebb verziót használod."
                    }
                }
            }.start()
        }
    }

    private fun refreshUsage() {
        val st = UsageStore.stats(this)
        findViewById<TextView>(R.id.balanceText).text =
            st.balance?.let { UsageStore.usdHuf(it) } ?: "Add meg a feltöltött összeget"
        findViewById<TextView>(R.id.usageText).text = buildString {
            append("Ma: ${st.todayReq} kérés · ${UsageStore.usdHuf(st.todayCost)}\n")
            append("Ebben a hónapban: ${st.monthReq} kérés · ${UsageStore.usdHuf(st.monthCost)}\n")
            append("Feltöltés óta elhasznált: ${UsageStore.usdHuf(st.spent)}")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUsage()
    }
}
