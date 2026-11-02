package fr.homeiptv.v2

import java.io.BufferedReader

data class PlaylistEntry(
    val name: String,
    val url: String,
    val group: String = "Autres",
    val logo: String? = null,
    val tvgId: String? = null
)

/** M3U/M3U8 parser with bounded streaming input for large remote playlists. */
object M3uParser {
    const val MAX_PLAYLIST_CHARACTERS = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 50_000
    private const val MAX_LINE_LENGTH = 16 * 1024
    const val playlistTooLargeMessage =
        "La playlist est trop volumineuse pour être chargée sur cet appareil. Essaie une source Xtream ou une playlist filtrée."

    private val attribute = Regex("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))")

    fun parse(text: String): List<PlaylistEntry> =
        parse(text.reader().buffered())

    /**
     * Reads bounded lines incrementally: neither the full response nor an unbounded line is
     * accumulated in memory. The caller owns and closes [reader].
     */
    fun parse(reader: BufferedReader, includeEntry: (PlaylistEntry) -> Boolean = { true }, stopAfterEntries: Int? = null): List<PlaylistEntry> {
        val out = ArrayList<PlaylistEntry>()
        val seenUrls = HashSet<String>()
        var title: String? = null
        var attrs = emptyMap<String, String>()
        var extGroup: String? = null
        var totalCharacters = 0L
        var pendingCharacter = -2
        var firstLine = true

        fun nextCharacter(): Int {
            val value = if (pendingCharacter != -2) pendingCharacter.also { pendingCharacter = -2 } else reader.read()
            if (value >= 0) {
                totalCharacters++
                if (totalCharacters > MAX_PLAYLIST_CHARACTERS) error(playlistTooLargeMessage)
            }
            return value
        }

        while (true) {
            val line = StringBuilder()
            var sawCharacter = false
            var lineEnded = false
            while (!lineEnded) {
                val c = nextCharacter()
                when {
                    c < 0 -> {
                        if (!sawCharacter) return out
                        lineEnded = true
                    }
                    c == '\n'.code -> lineEnded = true
                    c == '\r'.code -> {
                        val next = reader.read()
                        if (next >= 0) {
                            totalCharacters++
                            if (totalCharacters > MAX_PLAYLIST_CHARACTERS) error(playlistTooLargeMessage)
                            if (next != '\n'.code) pendingCharacter = next
                        }
                        lineEnded = true
                    }
                    else -> {
                        sawCharacter = true
                        if (line.length >= MAX_LINE_LENGTH) {
                            error("Une ligne de la playlist dépasse la taille maximale autorisée.")
                        }
                        line.append(c.toChar())
                    }
                }
            }

            var value = line.toString()
            if (firstLine) {
                value = value.removePrefix("\uFEFF")
                firstLine = false
            }
            value = value.trim()
            if (value.isEmpty()) continue

            when {
                value.startsWith("#EXTINF", ignoreCase = true) -> {
                    val comma = value.indexOf(',')
                    val header = if (comma >= 0) value.substring(0, comma) else value
                    title = if (comma >= 0) value.substring(comma + 1).trim() else "Chaîne"
                    attrs = attribute.findAll(header).associate { m ->
                        m.groupValues[1].lowercase() to (m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value.orEmpty())
                    }
                    extGroup = null
                }
                value.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = value.substringAfter(':').trim()
                value.startsWith("#") -> Unit
                title != null && (value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)) -> {
                    val urlKey = value.trim()
                    val cleanTitle = title!!.ifBlank { "Chaîne" }
                    val group = normalizeFrenchLabel(attrs["group-title"]?.ifBlank { null } ?: extGroup ?: "Autres")
                    val entry = PlaylistEntry(cleanTitle, value, group, attrs["tvg-logo"], attrs["tvg-id"])
                    if (includeEntry(entry) && seenUrls.add(urlKey)) {
                        if (out.size >= MAX_ENTRIES) error(playlistTooLargeMessage)
                        out += entry
                        if (stopAfterEntries != null && out.size >= stopAfterEntries) return out
                    }
                    title = null
                }
            }
        }
    }
}

fun normalizeFrenchLabel(raw: String): String {
    val value = raw.trim().replace(Regex("\\s+"), " ")
    val frenchPrefix = Regex("^(fr|fra|france|french|francais|français)(?=\\b|\\s|[-_|·])", RegexOption.IGNORE_CASE)
    val match = frenchPrefix.find(value) ?: return value.ifBlank { "Autres" }
    val remainder = value.substring(match.range.last + 1).trimStart(' ', '-', '_', '|', '·', ':')
    return if (remainder.isBlank()) "France" else "France · $remainder"
}
