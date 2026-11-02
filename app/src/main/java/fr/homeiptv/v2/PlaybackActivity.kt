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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val uri = Uri.parse(url)
        if ((!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) || uri.host.isNullOrBlank()) {
            finish()
            return
        }
        val view = PlayerView(this).apply { layoutParams = ViewGroup.LayoutParams(-1, -1); useController = true }
        setContentView(view)
        player = ExoPlayer.Builder(this).build().also { exo ->
            view.player = exo
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            exo.playWhenReady = true
        }
    }
    override fun onStop() { player?.release(); player = null; super.onStop() }
    companion object { const val EXTRA_URL = "home_iptv_stream_url" }
}
