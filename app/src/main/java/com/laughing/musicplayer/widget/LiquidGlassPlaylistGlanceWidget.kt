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
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
class LiquidGlassPlaylistGlanceWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStateManager.readSnapshot(context)
        val bitmap = WidgetStateManager.decodeWidgetArtwork(context, snapshot.artworkUri, maxDimPx = 144)
        provideContent {
            GlanceTheme {
                LiquidGlassPlaylistWidgetContent(snapshot = snapshot, artworkBitmap = bitmap)
            }
        }
    }
}

@Composable
@UnstableApi
private fun LiquidGlassPlaylistWidgetContent(
    snapshot: WidgetSnapshot,
    artworkBitmap: android.graphics.Bitmap?
) {
    val primaryText = ColorProvider(Color(0xFFFFFFFF))
    val secondaryText = ColorProvider(Color(0xFFD2D2DC))
    val glassItemBg = ColorProvider(Color(0x28FFFFFF))
    val accentRed = ColorProvider(Color(0xFFFA243C))

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_liquid_glass_bg))
            .cornerRadius(24.dp)
            .padding(14.dp)
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // Top frosted header card with current track & quick controls
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
                        .size(56.dp)
                        .cornerRadius(14.dp)
                        .clickable(actionStartActivity<MainActivity>())
                )

                Spacer(modifier = GlanceModifier.width(12.dp))

                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionStartActivity<MainActivity>())
                ) {
                    Text(
                        text = "LIQUID GLASS",
                        maxLines = 1,
                        style = TextStyle(
                            color = accentRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = snapshot.title,
                        maxLines = 1,
                        style = TextStyle(
                            color = primaryText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = snapshot.artist,
                        maxLines = 1,
                        style = TextStyle(
                            color = secondaryText,
                            fontSize = 12.sp
                        )
                    )
                }

                Spacer(modifier = GlanceModifier.width(6.dp))

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

                Spacer(modifier = GlanceModifier.width(6.dp))

                Box(
                    modifier = GlanceModifier
                        .size(36.dp)
                        .cornerRadius(18.dp)
                        .background(glassItemBg)
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
                        modifier = GlanceModifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(10.dp))

            Text(
                text = "Danh sách phát tiếp theo",
                style = TextStyle(
                    color = secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            )

            Spacer(modifier = GlanceModifier.height(6.dp))

            if (snapshot.queue.isEmpty()) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(glassItemBg)
                        .cornerRadius(14.dp)
                        .padding(12.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Chạm để mở Music Player và chọn bài hát",
                        style = TextStyle(color = primaryText, fontSize = 13.sp)
                    )
                }
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(snapshot.queue, itemId = { it.id.hashCode().toLong() }) { item ->
                        val isCurrent = item.id == snapshot.trackId
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .background(
                                    if (isCurrent) ColorProvider(Color(0x45FA243C)) else glassItemBg
                                )
                                .cornerRadius(12.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .clickable(
                                    actionStartActivity<MainActivity>(
                                        actionParametersOf(
                                            WidgetActionParams.TRACK_ID_EXTRA to item.id
                                        )
                                    )
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_widget_music_note),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(if (isCurrent) accentRed else secondaryText),
                                modifier = GlanceModifier.size(16.dp)
                            )
                            Spacer(modifier = GlanceModifier.width(8.dp))
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text(
                                    text = item.title,
                                    maxLines = 1,
                                    style = TextStyle(
                                        color = primaryText,
                                        fontSize = 13.sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                    )
                                )
                                Text(
                                    text = "${item.artist} • ${item.sourceLabel}",
                                    maxLines = 1,
                                    style = TextStyle(
                                        color = secondaryText,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@UnstableApi
class LiquidGlassPlaylistWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LiquidGlassPlaylistGlanceWidget()
}
