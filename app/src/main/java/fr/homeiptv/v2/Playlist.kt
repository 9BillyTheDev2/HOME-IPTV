package fr.homeiptv.v2

data class PlaylistEntry(
    val name: String,
    val url: String,
    val group: String = "Autres",
    val logo: String? = null,
    val tvgId: String? = null
)

/** Small M3U/M3U8 parser. It accepts quoted attributes, UTF-8 BOMs and CRLF files. */
object M3uParser {
    private val attribute = Regex("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))")

    fun parse(text: String): List<PlaylistEntry> {
        val lines = text.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val out = mutableListOf<PlaylistEntry>()
        var title: String? = null
        var attrs = emptyMap<String, String>()
        var extGroup: String? = null
        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val comma = line.indexOf(',')
                    val header = if (comma >= 0) line.substring(0, comma) else line
                    title = if (comma >= 0) line.substring(comma + 1).trim() else "Chaîne"
                    attrs = attribute.findAll(header).associate { m ->
                        m.groupValues[1].lowercase() to (m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value.orEmpty())
                    }
                    extGroup = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                title != null && (line.startsWith("https://", ignoreCase = true) || line.startsWith("http://", ignoreCase = true)) -> {
                    val cleanTitle = title!!.ifBlank { "Chaîne" }
                    val group = normalizeFrenchLabel(attrs["group-title"]?.ifBlank { null } ?: extGroup ?: "Autres")
                    out += PlaylistEntry(cleanTitle, line, group, attrs["tvg-logo"], attrs["tvg-id"])
                    title = null
                }
            }
        }
        return out.distinctBy { it.url.trim() }
    }

}

fun normalizeFrenchLabel(raw: String): String {
    val value = raw.trim().replace(Regex("\\s+"), " ")
    val frenchPrefix = Regex("^(fr|fra|france|french|francais|français)(?=\\b|\\s|[-_|·])", RegexOption.IGNORE_CASE)
    val match = frenchPrefix.find(value) ?: return value.ifBlank { "Autres" }
    val remainder = value.substring(match.range.last + 1).trimStart(' ', '-', '_', '|', '·', ':')
    return if (remainder.isBlank()) "France" else "France · $remainder"
}
