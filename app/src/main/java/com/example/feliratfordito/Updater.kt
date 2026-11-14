package com.example.feliratfordito

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Frissítés a GitHub-ról: ellenőrzés, letöltés, telepítő indítása.
 * [onState] a gomb feliratát kapja (null = nincs frissítés, a gomb rejtve).
 */
class Updater(private val act: Activity, private val onState: (String?) -> Unit) {

    private val apkUrl = "https://github.com/kondasadam/FeliratFordito/releases/latest/download/FeliratFordito.apk"
    private var downloading = false
    private var downloadedApk: File? = null
    var latestVersion = 0L
        private set

    fun currentVersion(): Long = try {
        PackageInfoCompat.getLongVersionCode(act.packageManager.getPackageInfo(act.packageName, 0))
    } catch (e: Exception) {
        0L
    }

    fun check() {
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
                    latestVersion = latest
                    if (latest > currentVersion()) act.runOnUiThread { onState("Frissítés") }
                }
                c.disconnect()
            } catch (e: Exception) {
                // nincs internet: nincs frissítésjelzés
            }
        }.start()
    }

    fun start() {
        if (downloading) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !act.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                act, "Engedélyezd az appnak a frissítések telepítését, majd lépj vissza, és nyomd meg újra a Frissítés gombot.",
                Toast.LENGTH_LONG
            ).show()
            try {
                act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
            } catch (e: Exception) { }
            return
        }
        downloadedApk?.let { if (it.exists()) { install(it); return } }

        downloading = true
        onState("Letöltés…")
        Thread {
            val file = File(File(act.cacheDir, "updates").apply { mkdirs() }, "FeliratFordito.apk")
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
                                    act.runOnUiThread { onState("$pct%") }
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
            act.runOnUiThread {
                downloading = false
                if (ok) {
                    downloadedApk = file
                    onState("Telepítés")
                    install(file)
                } else {
                    onState("Újra")
                    Toast.makeText(act, "A frissítés letöltése nem sikerült. Ellenőrizd az internetet.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun install(file: File) {
        try {
            val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(act, "A telepítő nem indult el: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
