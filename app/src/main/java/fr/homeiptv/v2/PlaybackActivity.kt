package fr.homeiptv.v2

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

private fun preferredDecoderSelector(preference: DecoderPreference): MediaCodecSelector {
    val delegate = MediaCodecSelector.DEFAULT
    val preferHardware = preference == DecoderPreference.HARDWARE
    return MediaCodecSelector { mimeType, secure, tunneling ->
        val available = delegate.getDecoderInfos(mimeType, secure, tunneling)
        val preferred = available.filter { it.hardwareAccelerated == preferHardware }
        preferred.ifEmpty { available }
    }
}

private fun findHttpStatus(error: Throwable): Int? {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
    }
    return null
}

class PlaybackActivity : Activity() {
    private var player: ExoPlayer? = null
    private var recent: RecentPlayback? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private val saveProgressTask = object : Runnable {
        override fun run() {
            savePlaybackProgress()
            if (player != null) progressHandler.postDelayed(this, 5_000L)
        }
    }

    private fun savePlaybackProgress() {
        val currentPlayer = player ?: return
        val record = recent ?: return
        if (record.kind == "live") return
        val position = currentPlayer.currentPosition.coerceAtLeast(0L)
        if (position > 0L) {
            val updated = record.copy(positionMs = position)
            recent = updated
            runCatching { SourceStore(this).savePlaybackProgress(updated) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val uri = Uri.parse(url)
        if ((!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) || uri.host.isNullOrBlank()) {
            finish()
            return
        }
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY).orEmpty()
        recent = RecentPlayback(
            kind = kind,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty(),
            image = intent.getStringExtra(EXTRA_IMAGE),
            url = url,
            positionMs = intent.getLongExtra(EXTRA_POSITION, 0L),
            category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty(),
            sourceKey = sourceKey
        ).takeIf { it.kind.isNotBlank() && it.title.isNotBlank() }

        val view = PlayerView(this).apply { layoutParams = ViewGroup.LayoutParams(-1, -1); useController = true }
        setContentView(view)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val decoderPreference = runCatching {
            DecoderPreference.valueOf(intent.getStringExtra(EXTRA_DECODER).orEmpty())
        }.getOrDefault(DecoderPreference.HARDWARE)
        val renderersFactory = DefaultRenderersFactory(this).setEnableDecoderFallback(true).setMediaCodecSelector(
            preferredDecoderSelector(decoderPreference)
        )
        var fallbackTried = false
        var resumePositionApplied = false
        val resumePosition = intent.getLongExtra(EXTRA_POSITION, 0L).coerceAtLeast(0L)
        val fallbackUrl = intent.getStringExtra(EXTRA_FALLBACK_URL)
        player = ExoPlayer.Builder(this, renderersFactory).setMediaSourceFactory(mediaSourceFactory).build().also { exo ->
            view.player = exo
            val inferredMimeType = when {
                url.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                url.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                uri.lastPathSegment?.substringAfterLast('.')?.equals("ts", ignoreCase = true) == true -> "video/mp2t"
                else -> null
            }
            val mediaItem = MediaItem.Builder().setUri(uri).apply {
                inferredMimeType?.let { setMimeType(it) }
            }.build()
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (!resumePositionApplied && playbackState == Player.STATE_READY && resumePosition > 0L && kind != "live") {
                        exo.seekTo(resumePosition)
                        resumePositionApplied = true
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!fallbackTried && !fallbackUrl.isNullOrBlank()) {
                        val fallbackUri = Uri.parse(fallbackUrl)
                        if ((fallbackUri.scheme.equals("http", true) || fallbackUri.scheme.equals("https", true)) && !fallbackUri.host.isNullOrBlank()) {
                            fallbackTried = true
                            val fallbackMimeType = when {
                                fallbackUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                                fallbackUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                                fallbackUri.lastPathSegment?.substringAfterLast('.')?.equals("ts", ignoreCase = true) == true -> "video/mp2t"
                                else -> null
                            }
                            val fallbackItem = MediaItem.Builder().setUri(fallbackUri).apply {
                                fallbackMimeType?.let { setMimeType(it) }
                            }.build()
                            exo.setMediaItem(fallbackItem)
                            exo.prepare()
                            exo.playWhenReady = true
                            return
                        }
                    }
                    val explanation = when (error.errorCodeName) {
                        "ERROR_CODE_IO_BAD_HTTP_STATUS" -> "Le serveur a refusé le flux."
                        "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
                        "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" -> "Connexion au flux impossible."
                        "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
                        "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED" -> "Le format du flux n’est pas pris en charge."
                        "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
                        "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES" -> "Le codec audio ou vidéo n’est pas pris en charge par cet appareil."
                        else -> "Lecture impossible."
                    }
                    val httpCode = findHttpStatus(error)
                    val statusLine = httpCode?.let { "\nRéponse du serveur : HTTP $it" }.orEmpty()
                    AlertDialog.Builder(this@PlaybackActivity)
                        .setTitle("Lecture impossible")
                        .setMessage("$explanation$statusLine\nCode : ${error.errorCodeName}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            })
            exo.setMediaItem(mediaItem)
            exo.prepare()
            exo.playWhenReady = true
        }
        progressHandler.postDelayed(saveProgressTask, 5_000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onStop() {
        progressHandler.removeCallbacks(saveProgressTask)
        val currentPlayer = player
        val record = recent
        if (currentPlayer != null && record != null) {
            val position = if (record.kind == "live") 0L else currentPlayer.currentPosition.coerceAtLeast(0L)
            runCatching { SourceStore(this).saveRecent(record.copy(positionMs = position)) }
        }
        player?.release()
        player = null
        super.onStop()
    }

    companion object {
        const val EXTRA_URL = "home_iptv_stream_url"
        const val EXTRA_KIND = "home_iptv_recent_kind"
        const val EXTRA_TITLE = "home_iptv_recent_title"
        const val EXTRA_SUBTITLE = "home_iptv_recent_subtitle"
        const val EXTRA_IMAGE = "home_iptv_recent_image"
        const val EXTRA_CATEGORY = "home_iptv_recent_category"
        const val EXTRA_POSITION = "home_iptv_recent_position"
        const val EXTRA_DECODER = "home_iptv_decoder_preference"
        const val EXTRA_SOURCE_KEY = "home_iptv_source_key"
        const val EXTRA_FALLBACK_URL = "home_iptv_fallback_url"
    }
}
