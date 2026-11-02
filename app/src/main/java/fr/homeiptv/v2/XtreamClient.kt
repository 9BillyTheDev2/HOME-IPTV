package fr.homeiptv.v2

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class XtreamItem(
    val id: String,
    val name: String,
    val categoryId: String? = null,
    val logo: String? = null,
    val extension: String? = null,
    val plot: String? = null,
    val year: String? = null
)

data class XtreamCategory(val id: String, val name: String, val ids: List<String> = listOf(id))

/** Client-side Xtream Codes API access. No server-side proxy and no credentials in logs. */
class XtreamClient(
    server: String,
    private val username: String,
    private val password: String
) {
    private val base = normalizeHttpsServer(server)

    suspend fun accountIsValid(): Boolean = (request() as? JSONObject)?.optJSONObject("user_info")?.optString("auth") == "1"

    suspend fun categories(kind: String): List<XtreamCategory> = withContext(Dispatchers.IO) {
        val parsed = array("get_${kind}_categories").mapNotNull { obj ->
            val id = obj.optString("category_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            XtreamCategory(id, normalizeFrenchLabel(obj.optString("category_name", "Autres")))
        }
        parsed.groupBy { it.name.lowercase() }.map { (name, categories) ->
            XtreamCategory(categories.first().id, categories.first().name, categories.map { it.id }.distinct())
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun items(kind: String, categoryIds: List<String>? = null): List<XtreamItem> = withContext(Dispatchers.IO) {
        val action = when (kind) {
            "live" -> "get_live_streams"
            "vod" -> "get_vod_streams"
            else -> "get_series"
        }
        val ids = categoryIds ?: listOf("")
        ids.flatMap { categoryId ->
          val params = if (categoryId.isBlank()) emptyMap() else mapOf("category_id" to categoryId)
          array(action, params).mapNotNull { obj ->
            val id = obj.optString(if (kind == "series") "series_id" else "stream_id").takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            XtreamItem(
                id = id,
                name = obj.optString("name", "Sans titre"),
                categoryId = obj.optString("category_id").ifBlank { null },
                logo = obj.optString(if (kind == "series") "cover" else "stream_icon").ifBlank { null },
                extension = obj.optString("container_extension").ifBlank { null },
                plot = obj.optString("plot").ifBlank { null },
                year = obj.optString("releaseDate").take(4).ifBlank { null }
            )
          }
        }.distinctBy { it.id }
    }

    suspend fun episodes(seriesId: String): List<XtreamItem> = withContext(Dispatchers.IO) {
        val json = request(mapOf("action" to "get_series_info", "series_id" to seriesId)) as? JSONObject
            ?: return@withContext emptyList()
        val seasons = json.optJSONObject("episodes") ?: return@withContext emptyList()
        buildList {
            val keys = seasons.keys()
            while (keys.hasNext()) {
                val season = seasons.optJSONArray(keys.next()) ?: continue
                for (i in 0 until season.length()) {
                    val ep = season.optJSONObject(i) ?: continue
                    val id = ep.optString("id").takeIf(String::isNotBlank) ?: continue
                    add(XtreamItem(id, ep.optString("title", "Épisode"), extension = ep.optString("container_extension").ifBlank { null }, plot = ep.optString("info").ifBlank { null }))
                }
            }
        }
    }

    fun streamUrl(kind: String, item: XtreamItem): String {
        val segment = if (kind == "live") "live" else if (kind == "vod") "movie" else "series"
        val ext = item.extension ?: if (kind == "live") "ts" else "mp4"
        return "$base/$segment/${Uri.encode(username)}/${Uri.encode(password)}/${Uri.encode(item.id)}.$ext"
    }

    private suspend fun array(action: String, extra: Map<String, String> = emptyMap()): List<JSONObject> = withContext(Dispatchers.IO) {
        val result = request(extra + ("action" to action))
        when (result) {
            is JSONArray -> (0 until result.length()).mapNotNull(result::optJSONObject)
            else -> emptyList()
        }
    }

    private suspend fun request(extra: Map<String, String> = emptyMap()): Any = withContext(Dispatchers.IO) {
        val uri = Uri.parse("$base/player_api.php").buildUpon()
            .appendQueryParameter("username", username)
            .appendQueryParameter("password", password)
        extra.forEach { (key, value) -> uri.appendQueryParameter(key, value) }
        val connection = (URL(uri.build().toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Le fournisseur a répondu HTTP $code")
            val trimmed = body.trim()
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed)
                else -> throw IllegalStateException("Réponse API Xtream inattendue")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeHttpsServer(value: String): String {
        val clean = value.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        val uri = Uri.parse(clean)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Adresse Xtream invalide : une URL HTTPS complète est requise."
        }
        return if (clean.endsWith("/player_api.php", ignoreCase = true)) {
            clean.dropLast("/player_api.php".length).trimEnd('/')
        } else clean
    }
}
