package fr.homeiptv.v2

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String
)

internal object UpdateManager {
    private const val CURRENT_VERSION_CODE = 4
    private const val API = "https://api.github.com/repos/9BillyTheDev2/HOME-IPTV/releases?per_page=20"
    private const val MAX_APK_BYTES = 120L * 1024L * 1024L

    suspend fun findUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val releases = JSONArray(String(readUrl(API), Charsets.UTF_8))
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft", true)) continue
            val assets = release.optJSONArray("assets") ?: continue
            val descriptor = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name") == "home-iptv-update.json" } ?: continue
            val descriptorUrl = descriptor.optString("browser_download_url")
            if (!descriptorUrl.startsWith("https://")) continue
            val metadata = JSONObject(String(readUrl(descriptorUrl), Charsets.UTF_8))
            val code = metadata.optInt("versionCode", 0)
            if (code <= CURRENT_VERSION_CODE) return@withContext null
            val apkName = metadata.optString("apkFileName")
            val apk = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name") == apkName } ?: continue
            val apkUrl = apk.optString("browser_download_url")
            val checksum = metadata.optString("sha256").lowercase()
            if (!apkUrl.startsWith("https://") || !checksum.matches(Regex("[0-9a-f]{64}"))) continue
            return@withContext UpdateInfo(code, metadata.optString("versionName"), apkUrl, checksum)
        }
        null
    }

    suspend fun downloadAndOpenInstaller(activity: Activity, update: UpdateInfo): String? =
        withContext(Dispatchers.IO) {
            try {
                val directory = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(directory, "home-iptv-\${update.versionCode}.apk")
                val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                }
                try {
                    if (connection.responseCode !in 200..299) error("Téléchargement refusé : HTTP \${connection.responseCode}.")
                    val length = connection.contentLengthLong
                    if (length > MAX_APK_BYTES) error("L’APK dépasse la taille autorisée.")
                    connection.inputStream.use { input ->
                        apkFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_APK_BYTES) error("L’APK dépasse la taille autorisée.")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val actual = apkFile.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                if (actual != update.sha256) {
                    apkFile.delete()
                    error("La vérification de l’APK a échoué. L’application installée n’a pas été modifiée.")
                }
                withContext(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !activity.packageManager.canRequestPackageInstalls()
                    ) {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:\${activity.packageName}")
                            )
                        )
                        return@withContext "Autorise HOME IPTV à installer les mises à jour dans les réglages Android, puis relance la vérification."
                    }
                    val uri = FileProvider.getUriForFile(
                        activity,
                        "\${activity.packageName}.fileprovider",
                        apkFile
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(intent)
                    null
                }
            } catch (error: Exception) {
                error.message ?: "Impossible de préparer la mise à jour."
            }
        }

    private fun readUrl(address: String): ByteArray {
        val url = URL(address)
        require(url.protocol == "https") { "La vérification des mises à jour exige HTTPS." }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "HOME-IPTV-Android")
        }
        return try {
            if (connection.responseCode !in 200..299) error("GitHub répond HTTP \${connection.responseCode}.")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
