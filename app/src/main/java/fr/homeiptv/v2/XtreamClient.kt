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

data class XtreamMediaDetails(
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val releaseDate: String?,
    val duration: String?,
    val director: String?,
    val genre: String?,
    val cast: String?,
    val plot: String?,
    val rating: String?
)

data class XtreamEpisodeInfo(val number: String, val item: XtreamItem, val duration: String?, val thumbnail: String?)
data class XtreamSeason(val number: String, val episodes: List<XtreamEpisodeInfo>)
data class XtreamSeriesDetails(val media: XtreamMediaDetails, val seasons: List<XtreamSeason>)

data class XtreamCategory(val id: String, val name: String, val ids: List<String> = listOf(id))

/** Client-side Xtream Codes API access. No server-side proxy and no credentials in logs. */
class XtreamClient(
    server: String,
    private val username: String,
    private val password: String
) {
    private val base = normalizeServer(server)

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

    suspend fun searchItems(kind: String, query: String, maxResults: Int = 120): List<XtreamItem> = withContext(Dispatchers.IO) {
        val action = when (kind) {
            "live" -> "get_live_streams"
            "vod" -> "get_vod_streams"
            else -> "get_series"
        }
        val uri = Uri.parse("$base/player_api.php").buildUpon()
            .appendQueryParameter("username", username)
            .appendQueryParameter("password", password)
            .appendQueryParameter("action", action)
            .build()
        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("Le fournisseur a répondu HTTP $code")
            val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (contentLength > MAX_SEARCH_RESPONSE_BYTES) error("Le catalogue est trop volumineux pour la recherche directe.")
            val needle = normalizeQuery(query)
            val results = ArrayList<XtreamItem>()
            val input = LimitedInputStream(connection.inputStream, MAX_SEARCH_RESPONSE_BYTES)
            val reader = android.util.JsonReader(input.reader(Charsets.UTF_8).buffered()).apply { isLenient = true }
            reader.use {
                it.beginArray()
                while (it.hasNext() && results.size < maxResults) {
                    var id = ""
                    var name = ""
                    var categoryId: String? = null
                    var logo: String? = null
                    var extension: String? = null
                    var plot: String? = null
                    var year: String? = null
                    it.beginObject()
                    while (it.hasNext()) {
                        when (val field = it.nextName()) {
                            if (kind == "series") "series_id" else "stream_id" -> id = it.nextStringValue()
                            "name" -> name = it.nextStringValue()
                            "category_id" -> categoryId = it.nextStringValue().ifBlank { null }
                            if (kind == "series") "cover" else "stream_icon" -> logo = it.nextStringValue().ifBlank { null }
                            "container_extension" -> extension = it.nextStringValue().ifBlank { null }
                            "plot" -> plot = it.nextStringValue().ifBlank { null }
                            "releaseDate" -> year = it.nextStringValue().take(4).ifBlank { null }
                            else -> it.skipValue()
                        }
                    }
                    it.endObject()
                    if (id.isNotBlank() && normalizeQuery(name).contains(needle)) {
                        results += XtreamItem(id, name.ifBlank { "Sans titre" }, categoryId, logo, extension, plot, year)
                    }
                }
            }
            results
        } finally {
            connection.disconnect()
        }
    }

    private fun android.util.JsonReader.nextStringValue(): String =
        if (peek() == android.util.JsonToken.NULL) { nextNull(); "" } else nextString()

    private fun normalizeQuery(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private class LimitedInputStream(input: java.io.InputStream, private val limit: Long) :
        java.io.FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0 && ++count > limit) error("Le catalogue dépasse la limite de sécurité de la recherche.")
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val allowed = (limit - count + 1L).coerceAtMost(length.toLong()).toInt()
            if (allowed <= 0) error("Le catalogue dépasse la limite de sécurité de la recherche.")
            val read = super.read(buffer, offset, allowed)
            if (read > 0) {
                count += read
                if (count > limit) error("Le catalogue dépasse la limite de sécurité de la recherche.")
            }
            return read
        }
    }

    companion object { private const val MAX_SEARCH_RESPONSE_BYTES = 512L * 1024 * 1024 }

    suspend fun vodDetails(item: XtreamItem): XtreamMediaDetails = withContext(Dispatchers.IO) {
        val response = request(mapOf("action" to "get_vod_info", "vod_id" to item.id)) as? JSONObject
        val info = response?.optJSONObject("info")
        buildMediaDetails(info, item.name, item.logo, item.plot, item.year)
    }

    suspend fun seriesDetails(item: XtreamItem): XtreamSeriesDetails = withContext(Dispatchers.IO) {
        val response = request(mapOf("action" to "get_series_info", "series_id" to item.id)) as? JSONObject
        val info = response?.optJSONObject("info")
        val media = buildMediaDetails(info, item.name, item.logo, item.plot, item.year)
        val rawSeasons = response?.optJSONObject("episodes")
        val seasons = mutableListOf<XtreamSeason>()
        if (rawSeasons != null) {
            val keys = rawSeasons.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            keys.forEach { seasonNumber ->
                val entries = rawSeasons.optJSONArray(seasonNumber) ?: return@forEach
                val episodes = buildList {
                    for (index in 0 until entries.length()) {
                        val episode = entries.optJSONObject(index) ?: continue
                        val id = episode.optString("id").takeIf(String::isNotBlank) ?: continue
                        val episodeInfo = episode.optJSONObject("info")
                        val title = episode.optString("title").ifBlank { "Épisode" }
                        val details = XtreamItem(
                            id = id,
                            name = title,
                            extension = episode.optString("container_extension").ifBlank { null },
                            plot = episodeInfo?.optString("plot")?.ifBlank { null } ?: episode.optString("plot").ifBlank { null }
                        )
                        add(
                            XtreamEpisodeInfo(
                                number = episode.optString("episode_num").ifBlank { (index + 1).toString() },
                                item = details,
                                duration = episodeInfo?.optString("duration")?.ifBlank { null }
                                    ?: episode.optString("duration").ifBlank { null },
                                thumbnail = episodeInfo?.optString("movie_image")?.ifBlank { null }
                                    ?: episode.optString("movie_image").ifBlank { null }
                            )
                        )
                    }
                }
                seasons += XtreamSeason(seasonNumber, episodes)
            }
        }
        XtreamSeriesDetails(media, seasons)
    }

    private fun buildMediaDetails(
        info: JSONObject?,
        fallbackTitle: String,
        fallbackPoster: String?,
        fallbackPlot: String?,
        fallbackYear: String?
    ): XtreamMediaDetails {
        fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            info?.optString(key)?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }
        }
        val backdropArray = info?.optJSONArray("backdrop_path")
        val backdrop = backdropArray?.optString(0)?.takeIf { it.isNotBlank() && it != "null" }
        return XtreamMediaDetails(
            title = field("name", "title") ?: fallbackTitle,
            poster = field("movie_image", "cover_big", "cover") ?: fallbackPoster,
            backdrop = backdrop ?: field("backdrop", "backdrop_path"),
            releaseDate = field("releasedate", "releaseDate", "year") ?: fallbackYear,
            duration = field("duration"),
            director = field("director"),
            genre = field("genre"),
            cast = field("cast", "actors"),
            plot = field("plot") ?: fallbackPlot,
            rating = field("rating")
        )
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

    private fun normalizeServer(value: String): String {
        val clean = value.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        val uri = Uri.parse(clean)
        require((uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) && !uri.host.isNullOrBlank()) {
            "Adresse Xtream invalide : une URL HTTP ou HTTPS complète est requise."
        }
        return if (clean.endsWith("/player_api.php", ignoreCase = true)) {
            clean.dropLast("/player_api.php".length).trimEnd('/')
        } else clean
    }
}
