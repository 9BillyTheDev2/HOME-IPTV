package fr.homeiptv.v2

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlaybackActivity : Activity() {
    private var player: ExoPlayer? = null
    private var recent: RecentPlayback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val uri = Uri.parse(url)
        if ((!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) || uri.host.isNullOrBlank()) {
            finish()
            return
        }
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        recent = RecentPlayback(
            kind = kind,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty(),
            image = intent.getStringExtra(EXTRA_IMAGE),
            url = url,
            positionMs = intent.getLongExtra(EXTRA_POSITION, 0L),
            category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        ).takeIf { it.kind.isNotBlank() && it.title.isNotBlank() }

        val view = PlayerView(this).apply { layoutParams = ViewGroup.LayoutParams(-1, -1); useController = true }
        setContentView(view)
        player = ExoPlayer.Builder(this).build().also { exo ->
            view.player = exo
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            val position = intent.getLongExtra(EXTRA_POSITION, 0L).coerceAtLeast(0L)
            if (position > 0L) exo.seekTo(position)
            exo.playWhenReady = true
        }
    }

    override fun onStop() {
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
    }
}
