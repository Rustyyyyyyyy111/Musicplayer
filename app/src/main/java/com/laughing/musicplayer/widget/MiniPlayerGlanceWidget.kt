package com.laughing.musicplayer.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.util.UnstableApi
import com.laughing.musicplayer.MainActivity
import com.laughing.musicplayer.R

@UnstableApi
class MiniPlayerGlanceWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStateManager.readSnapshot(context)
        val bitmap = WidgetStateManager.decodeWidgetArtwork(context, snapshot.artworkUri, maxDimPx = 128)
        provideContent {
            GlanceTheme {
                MiniPlayerWidgetContent(snapshot = snapshot, artworkBitmap = bitmap)
            }
        }
    }
}

@Composable
@UnstableApi
private fun MiniPlayerWidgetContent(
    snapshot: WidgetSnapshot,
    artworkBitmap: android.graphics.Bitmap?
) {
    val primaryText = ColorProvider(Color(0xFFFFFFFF))
    val secondaryText = ColorProvider(Color(0xFFB8B8C2))
    val accentRed = ColorProvider(Color(0xFFFA243C))

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_mini_bg))
            .cornerRadius(22.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val artProvider = if (artworkBitmap != null) {
                ImageProvider(artworkBitmap)
            } else {
                ImageProvider(R.drawable.img_default_artwork_1794853244685)
            }

            Image(
                provider = artProvider,
                contentDescription = snapshot.title,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier
                    .size(52.dp)
                    .cornerRadius(12.dp)
                    .clickable(actionStartActivity<MainActivity>())
            )

            Spacer(modifier = GlanceModifier.width(10.dp))

            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(actionStartActivity<MainActivity>())
            ) {
                Text(
                    text = snapshot.title,
                    maxLines = 1,
                    style = TextStyle(
                        color = primaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = snapshot.artist,
                    maxLines = 1,
                    style = TextStyle(
                        color = secondaryText,
                        fontSize = 12.sp
                    )
                )
                Text(
                    text = snapshot.sourceLabel,
                    maxLines = 1,
                    style = TextStyle(
                        color = accentRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            Spacer(modifier = GlanceModifier.width(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = GlanceModifier
                        .size(36.dp)
                        .cornerRadius(18.dp)
                        .clickable(
                            actionRunCallback<PlaybackControlActionCallback>(
                                actionParametersOf(WidgetActionParams.CONTROL_ACTION to WidgetActionParams.ACTION_PREVIOUS)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_prev),
                        contentDescription = "Bài trước",
                        colorFilter = ColorFilter.tint(primaryText),
                        modifier = GlanceModifier.size(20.dp)
                    )
                }

                Spacer(modifier = GlanceModifier.width(4.dp))

                Box(
                    modifier = GlanceModifier
                        .size(40.dp)
                        .cornerRadius(20.dp)
                        .background(accentRed)
                        .clickable(
                            actionRunCallback<PlaybackControlActionCallback>(
                                actionParametersOf(WidgetActionParams.CONTROL_ACTION to WidgetActionParams.ACTION_PLAY_PAUSE)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(
                            if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
                        ),
                        contentDescription = if (snapshot.isPlaying) "Tạm dừng" else "Phát",
                        colorFilter = ColorFilter.tint(primaryText),
                        modifier = GlanceModifier.size(22.dp)
                    )
                }

                Spacer(modifier = GlanceModifier.width(4.dp))

                Box(
                    modifier = GlanceModifier
                        .size(36.dp)
                        .cornerRadius(18.dp)
                        .clickable(
                            actionRunCallback<PlaybackControlActionCallback>(
                                actionParametersOf(WidgetActionParams.CONTROL_ACTION to WidgetActionParams.ACTION_NEXT)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_next),
                        contentDescription = "Bài tiếp theo",
                        colorFilter = ColorFilter.tint(primaryText),
                        modifier = GlanceModifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@UnstableApi
class MiniPlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MiniPlayerGlanceWidget()
}
