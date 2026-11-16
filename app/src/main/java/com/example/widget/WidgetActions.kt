package com.example.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.common.util.UnstableApi
import com.example.MusicPlayerApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WidgetActionParams {
    val CONTROL_ACTION = ActionParameters.Key<String>("control_action")
    val TRACK_ID_EXTRA = ActionParameters.Key<String>("widget_play_track_id")

    const val ACTION_PLAY_PAUSE = "play_pause"
    const val ACTION_NEXT = "next"
    const val ACTION_PREVIOUS = "previous"
}

@UnstableApi
class PlaybackControlActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val action = parameters[WidgetActionParams.CONTROL_ACTION] ?: return
        val app = context.applicationContext as? MusicPlayerApplication ?: return
        val controller = app.container.playerController
        withContext(Dispatchers.Main.immediate) {
            when (action) {
                WidgetActionParams.ACTION_PLAY_PAUSE -> controller.togglePlayPause()
                WidgetActionParams.ACTION_NEXT -> controller.skipToNext()
                WidgetActionParams.ACTION_PREVIOUS -> controller.skipToPrevious()
            }
        }
        val state = controller.playbackState.value
        WidgetStateManager.publishPlaybackSnapshot(
            context = context,
            currentTrack = state.currentTrack,
            queue = state.queue,
            isPlaying = state.isPlaying
        )
    }
}
