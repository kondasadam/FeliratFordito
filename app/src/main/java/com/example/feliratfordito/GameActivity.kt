package com.example.feliratfordito

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

/** Egy játék saját oldala: indítás, felvételek, fordítási memória, profil. */
class GameActivity : AppCompatActivity() {

    private var gameId = -1L
    private lateinit var game: GameStore.Game

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        gameId = intent.getLongExtra("gameId", -1L)
        val g = GameStore.find(this, gameId)
        if (g == null) {
            finish()
            return
        }
        game = g

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.startBtn).setOnClickListener {
            GameStore.setActive(this, gameId)
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.recBtn).setOnClickListener {
            startActivity(Intent(this, SummaryActivity::class.java).putExtra("gameId", gameId))
        }
        findViewById<Button>(R.id.memClearBtn).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Törlöd a játék fordítási memóriáját? Utána a szövegeket újra a Gemini fordítja.")
                .setPositiveButton("Törlés") { _, _ ->
                    TranslationMemory.clearFor(this, gameId)
                    refresh()
                }
                .setNegativeButton("Mégse", null)
                .show()
        }

        val nameEdit = findViewById<TextInputEditText>(R.id.nameEdit)
        nameEdit.setText(game.name)
        nameEdit.doAfterTextChanged {
            val n = it?.toString()?.trim().orEmpty()
            if (n.isNotEmpty()) {
                game.name = n
                save()
                refreshHeader()
            }
        }

        val profileSwitch = findViewById<MaterialSwitch>(R.id.profileSwitch)
        profileSwitch.isChecked = game.useProfile
        profileSwitch.setOnCheckedChangeListener { _, checked ->
            game.useProfile = checked
            save()
            updateProfileInfo()
        }

        val notesEdit = findViewById<TextInputEditText>(R.id.notesEdit)
        notesEdit.setText(game.notes)
        notesEdit.doAfterTextChanged {
            game.notes = it?.toString().orEmpty()
            save()
            updateProfileInfo()
        }

        findViewById<Button>(R.id.profileBtn).setOnClickListener {
            val builtin = GameProfiles.builtin(game.name)
            if (builtin != null) {
                notesEdit.setText(builtin)
                return@setOnClickListener
            }
            if (Gemini.apiKey(this).isEmpty()) {
                Toast.makeText(this, "A profil készítéséhez Gemini-kulcs kell (Beállítások)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val info = findViewById<TextView>(R.id.profileInfo)
            info.text = "Profil készül a Geminivel…"
            Thread {
                val text = GameProfiles.generate(this, game.name)
                runOnUiThread {
                    if (text != null) {
                        notesEdit.setText(text)
                        info.text = "Kész. Nézd át, és javítsd, ha valami nem stimmel."
                    } else {
                        info.text = "Nem sikerült (${Gemini.lastError})."
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.deleteBtn).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Törlöd a(z) „${game.name}” játékot? A fordítási memóriája is törlődik (a felvételek megmaradnak).")
                .setPositiveButton("Törlés") { _, _ ->
                    GameStore.delete(this, gameId)
                    finish()
                }
                .setNegativeButton("Mégse", null)
                .show()
        }

        refreshHeader()
        updateProfileInfo()
    }

    override fun onResume() {
        super.onResume()
        if (::game.isInitialized) refresh()
    }

    private fun save() = GameStore.update(this, game)

    private fun refreshHeader() {
        findViewById<MaterialToolbar>(R.id.toolbar).title = game.name
        findViewById<TextView>(R.id.heroTitle).text = game.name
    }

    private fun refresh() {
        val sessions = SessionStore.load(this)
        var n = 0
        for (i in 0 until sessions.length()) if (sessions.getJSONObject(i).optLong("gameId", -1L) == gameId) n++
        val mem = TranslationMemory.countFor(this, gameId)
        findViewById<TextView>(R.id.recInfo).text = if (n == 0) "Még nincs felvett beszélgetés." else "$n felvett beszélgetés"
        findViewById<TextView>(R.id.memInfo).text = "Fordítási memória: $mem megjegyzett szöveg"
        findViewById<TextView>(R.id.heroInfo).text = "$n felvétel · $mem megjegyzett szöveg"
    }

    private fun updateProfileInfo() {
        findViewById<TextView>(R.id.profileInfo).text = when {
            !game.useProfile -> "A profil ki van kapcsolva."
            game.notes.isNotBlank() -> "Saját profil használatban."
            GameProfiles.builtin(game.name) != null -> "Beépített profil használatban."
            else -> "Nincs profil – nyomd meg a „Profil készítése” gombot."
        }
    }
}
