package fr.homeiptv.v2

import android.content.Context

enum class LiveStreamFormat(val extension: String) {
    MPEG_TS("ts"),
    HLS("m3u8")
}

enum class DecoderPreference {
    HARDWARE,
    SOFTWARE
}

data class PlayerSettings(
    val liveStreamFormat: LiveStreamFormat = LiveStreamFormat.MPEG_TS,
    val decoderPreference: DecoderPreference = DecoderPreference.HARDWARE
)

class PlayerSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("home_iptv_player", Context.MODE_PRIVATE)

    fun get(): PlayerSettings {
        val format = runCatching {
            LiveStreamFormat.valueOf(prefs.getString(KEY_FORMAT, null).orEmpty())
        }.getOrDefault(LiveStreamFormat.MPEG_TS)
        val decoder = runCatching {
            DecoderPreference.valueOf(prefs.getString(KEY_DECODER, null).orEmpty())
        }.getOrDefault(DecoderPreference.HARDWARE)
        return PlayerSettings(format, decoder)
    }

    fun save(settings: PlayerSettings) {
        prefs.edit()
            .putString(KEY_FORMAT, settings.liveStreamFormat.name)
            .putString(KEY_DECODER, settings.decoderPreference.name)
            .apply()
    }

    private companion object {
        const val KEY_FORMAT = "live_stream_format"
        const val KEY_DECODER = "decoder_preference"
    }
}
