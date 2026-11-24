package com.palmerintech.firetube.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Reports songs that should be playing but haven't made a sound [TIMEOUT_MS] after they were
 * asked to, so they can be reproduced later. The clock starts when an item becomes current with
 * play on, or when play is pressed; it stops as soon as audio plays, on pause, or on a new item.
 * One report per item per start. [log] receives the event name and its parameters.
 */
class StartWatch(
    private val player: Player,
    private val scope: CoroutineScope,
    private val isCasting: () -> Boolean,
    private val log: (String, Bundle) -> Unit,
) : Player.Listener {
    private var job: Job? = null
    private var lastError: PlaybackException? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        lastError = null
        restart()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = restart()

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) job?.cancel()
    }

    override fun onPlayerError(error: PlaybackException) {
        lastError = error
    }

    private fun restart() {
        job?.cancel()
        if (!player.playWhenReady || player.isPlaying) return
        val item = player.currentMediaItem ?: return
        job = scope.launch {
            delay(TIMEOUT_MS)
            if (player.isPlaying || player.currentMediaItem?.mediaId != item.mediaId) return@launch
            report(item)
        }
    }

    private fun report(item: MediaItem) {
        val track = MediaItems.trackOf(item)
        Timber.w("No sound %d ms after starting %s", TIMEOUT_MS, item.mediaId)
        log(EVENT, Bundle().apply {
            putString("video_id", item.mediaId)
            putString("title", track.title.take(MAX_PARAM))
            putString("artist", track.artist.take(MAX_PARAM))
            putString("player_state", stateName(player.playbackState))
            putString("error", lastError?.errorCodeName ?: "none")
            putLong("live", if (MediaItems.isLive(item)) 1 else 0)
            putLong("casting", if (isCasting()) 1 else 0)
        })
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> state.toString()
    }

    companion object {
        const val EVENT = "song_not_started"
        const val TIMEOUT_MS = 10_000L

        /** Analytics drops string parameters longer than this. */
        private const val MAX_PARAM = 100
    }
}
