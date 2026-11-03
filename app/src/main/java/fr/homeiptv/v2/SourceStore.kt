package fr.homeiptv.v2

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SourceProfile(val id: String, val name: String, val kind: String, val endpoint: String, val user: String = "", val password: String = "")

data class RecentPlayback(
    val kind: String,
    val title: String,
    val subtitle: String,
    val image: String?,
    val url: String,
    val positionMs: Long = 0L,
    val category: String = "",
    val sourceKey: String = "",
    val watchedAt: Long = System.currentTimeMillis()
)

data class FavoriteContent(
    val key: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val image: String?,
    val url: String,
    val category: String,
    val sourceKey: String
)

/** Private device-local profile storage. Secrets are encrypted with an Android Keystore AES-GCM key. */
class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("home_iptv_sources", Context.MODE_PRIVATE)
    private val alias = "home_iptv_profile_key_v1"

    fun all(): List<SourceProfile> = runCatching {
        val arr = JSONArray(prefs.getString("profiles", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val secret = decrypt(o.optString("secret"))
            val parts = secret.split('\u0000', limit = 2)
            SourceProfile(o.getString("id"), o.optString("name"), o.optString("kind"), o.optString("endpoint"), parts.firstOrNull().orEmpty(), parts.getOrNull(1).orEmpty())
        }
    }.getOrDefault(emptyList())

    fun save(profile: SourceProfile) {
        val entries = all().filterNot { it.id == profile.id }.toMutableList().apply { add(profile) }
        val arr = JSONArray()
        entries.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("kind", p.kind).put("endpoint", p.endpoint)
                .put("secret", encrypt(p.user + '\u0000' + p.password)))
        }
        prefs.edit().putString("profiles", arr.toString()).putString("active", profile.id).apply()
    }

    fun active(): SourceProfile? {
        val id = prefs.getString("active", null)
        return all().firstOrNull { it.id == id } ?: all().firstOrNull()
    }

    fun setActive(id: String) { prefs.edit().putString("active", id).apply() }
    fun delete(id: String) {
        val entries = all().filterNot { it.id == id }
        val arr = JSONArray()
        entries.forEach { p -> arr.put(JSONObject().put("id", p.id).put("name", p.name).put("kind", p.kind).put("endpoint", p.endpoint).put("secret", encrypt(p.user + '\u0000' + p.password))) }
        prefs.edit().putString("profiles", arr.toString()).putString("active", entries.firstOrNull()?.id).apply()
    }

    fun recents(): List<RecentPlayback> = runCatching {
        val encrypted = prefs.getString("recent_playback", null).orEmpty()
        if (encrypted.isBlank()) return@runCatching emptyList()
        val arr = JSONArray(decrypt(encrypted))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = o.optString("kind")
            val title = o.optString("title")
            val url = o.optString("url")
            if (kind.isBlank() || title.isBlank() || url.isBlank()) return@mapNotNull null
            RecentPlayback(kind, title, o.optString("subtitle"), o.optString("image").ifBlank { null }, url,
                o.optLong("positionMs"), o.optString("category"), o.optString("sourceKey"), o.optLong("watchedAt", 0L))
        }
    }.getOrDefault(emptyList())

    fun saveRecent(item: RecentPlayback) {
        val entries = recents().filterNot { it.kind == item.kind }.toMutableList()
        entries.add(0, item)
        val arr = JSONArray()
        entries.take(3).forEach { recent ->
            arr.put(JSONObject().put("kind", recent.kind).put("title", recent.title).put("subtitle", recent.subtitle)
                .put("image", recent.image).put("url", recent.url).put("positionMs", recent.positionMs).put("category", recent.category)
                .put("sourceKey", recent.sourceKey).put("watchedAt", recent.watchedAt))
        }
        prefs.edit().putString("recent_playback", encrypt(arr.toString())).apply()
        saveHistory(item)
    }

    /** Updates the resume marker while playback is active, without adding a history event. */
    fun savePlaybackProgress(item: RecentPlayback) {
        val entries = recents().filterNot {
            it.kind == item.kind && it.title == item.title && it.sourceKey == item.sourceKey
        }.toMutableList()
        entries.add(0, item)
        val arr = JSONArray()
        entries.take(3).forEach { recent ->
            arr.put(JSONObject().put("kind", recent.kind).put("title", recent.title).put("subtitle", recent.subtitle)
                .put("image", recent.image).put("url", recent.url).put("positionMs", recent.positionMs).put("category", recent.category)
                .put("sourceKey", recent.sourceKey).put("watchedAt", recent.watchedAt))
        }
        prefs.edit().putString("recent_playback", encrypt(arr.toString())).apply()
        // Keep the matching history row in sync so the History screen can show
        // only items with a real resume position instead of every launch.
        val historyEntries = history().filterNot {
            it.kind == item.kind && it.title == item.title && it.sourceKey == item.sourceKey && it.url == item.url
        }.toMutableList()
        historyEntries.add(0, item)
        val historyArray = JSONArray()
        historyEntries.take(100).forEach { entry ->
            historyArray.put(JSONObject().put("kind", entry.kind).put("title", entry.title).put("subtitle", entry.subtitle)
                .put("image", entry.image).put("url", entry.url).put("positionMs", entry.positionMs)
                .put("category", entry.category).put("sourceKey", entry.sourceKey).put("watchedAt", entry.watchedAt))
        }
        prefs.edit().putString("watch_history", encrypt(historyArray.toString())).apply()
    }

    fun history(): List<RecentPlayback> = runCatching {
        val encrypted = prefs.getString("watch_history", null).orEmpty()
        // Preserve the three existing "last watched" entries when users install
        // this release for the first time and the longer history key is absent.
        if (encrypted.isBlank()) return@runCatching recents()
        val arr = JSONArray(decrypt(encrypted))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = o.optString("kind")
            val title = o.optString("title")
            val url = o.optString("url")
            if (kind.isBlank() || title.isBlank() || url.isBlank()) return@mapNotNull null
            RecentPlayback(kind, title, o.optString("subtitle"), o.optString("image").ifBlank { null }, url,
                o.optLong("positionMs"), o.optString("category"), o.optString("sourceKey"), o.optLong("watchedAt", 0L))
        }
    }.getOrDefault(emptyList())

    private fun saveHistory(item: RecentPlayback) {
        val entries = history().filterNot {
            it.kind == item.kind && it.title == item.title && it.subtitle == item.subtitle &&
                it.sourceKey == item.sourceKey && System.currentTimeMillis() - it.watchedAt < 5 * 60_000L
        }.toMutableList()
        entries.add(0, item)
        val arr = JSONArray()
        entries.take(100).forEach { entry ->
            arr.put(JSONObject().put("kind", entry.kind).put("title", entry.title).put("subtitle", entry.subtitle)
                .put("image", entry.image).put("url", entry.url).put("positionMs", entry.positionMs)
                .put("category", entry.category).put("sourceKey", entry.sourceKey).put("watchedAt", entry.watchedAt))
        }
        prefs.edit().putString("watch_history", encrypt(arr.toString())).apply()
    }

    fun favorites(): List<FavoriteContent> = runCatching {
        val encrypted = prefs.getString("favorites", null).orEmpty()
        if (encrypted.isBlank()) return@runCatching emptyList()
        val arr = JSONArray(decrypt(encrypted))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key")
            val title = o.optString("title")
            val url = o.optString("url")
            if (key.isBlank() || title.isBlank() || url.isBlank()) return@mapNotNull null
            FavoriteContent(key, o.optString("kind"), title, o.optString("subtitle"), o.optString("image").ifBlank { null },
                url, o.optString("category"), o.optString("sourceKey"))
        }
    }.getOrDefault(emptyList())

    fun toggleFavorite(item: FavoriteContent) {
        val entries = favorites().toMutableList()
        if (entries.any { it.key == item.key }) entries.removeAll { it.key == item.key } else entries.add(0, item)
        val arr = JSONArray()
        entries.take(500).forEach { favorite ->
            arr.put(JSONObject().put("key", favorite.key).put("kind", favorite.kind).put("title", favorite.title)
                .put("subtitle", favorite.subtitle).put("image", favorite.image).put("url", favorite.url)
                .put("category", favorite.category).put("sourceKey", favorite.sourceKey))
        }
        prefs.edit().putString("favorites", encrypt(arr.toString())).apply()
    }

    fun removeFavorite(key: String) {
        val entries = favorites().filterNot { it.key == key }
        val arr = JSONArray()
        entries.forEach { favorite ->
            arr.put(JSONObject().put("key", favorite.key).put("kind", favorite.kind).put("title", favorite.title)
                .put("subtitle", favorite.subtitle).put("image", favorite.image).put("url", favorite.url)
                .put("category", favorite.category).put("sourceKey", favorite.sourceKey))
        }
        prefs.edit().putString("favorites", encrypt(arr.toString())).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        val data = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
    }
}
