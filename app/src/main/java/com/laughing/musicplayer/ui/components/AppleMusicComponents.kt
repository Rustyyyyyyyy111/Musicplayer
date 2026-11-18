package com.laughing.musicplayer.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.laughing.musicplayer.R
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.ThemeMode
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.model.toAudioQualityDisplay
import com.laughing.musicplayer.ui.theme.AppleMotion
import com.laughing.musicplayer.ui.theme.LiquidGlassBase
import com.laughing.musicplayer.ui.theme.LocalLiquidGlassPalette
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.theme.applePressScale
import com.laughing.musicplayer.ui.theme.rememberReducedMotionEnabled

@Composable
fun AppleMusicSurfaceBackground(
    artworkUri: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val palette = LocalLiquidGlassPalette.current

    Box(modifier = modifier.fillMaxSize()) {
        if (colors.themeMode == ThemeMode.LIQUID_GLASS) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LiquidGlassBase)
            )
            if (!artworkUri.isNullOrBlank()) {
                AsyncImage(
                    model = artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(48.dp)
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.img_default_artwork_1794853244685),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(44.dp)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                palette.dominant.copy(alpha = 0.72f),
                                palette.muted.copy(alpha = 0.80f),
                                LiquidGlassBase.copy(alpha = 0.90f)
                            )
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.pageBackground)
            )
        }
        content()
    }
}

@Composable
fun AppleCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val reducedMotion = rememberReducedMotionEnabled()
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(cornerRadius)
    val border = if (colors.themeMode == ThemeMode.LIQUID_GLASS) {
        BorderStroke(0.75.dp, colors.glassStroke)
    } else if (colors.themeMode == ThemeMode.LIGHT) {
        BorderStroke(0.5.dp, colors.divider)
    } else null

    Surface(
        modifier = if (onClick != null) {
            modifier
                .applePressScale(interactionSource = interactionSource, reducedMotion = reducedMotion)
                .clip(shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick
                )
        } else modifier,
        shape = shape,
        color = colors.cardSurface,
        border = border,
        shadowElevation = if (colors.themeMode == ThemeMode.LIGHT) 3.dp else 0.dp,
        tonalElevation = if (colors.themeMode == ThemeMode.LIGHT) 1.dp else 0.dp
    ) {
        content()
    }
}

@Composable
fun AudioQualityBadge(
    track: Track,
    onDarkSurface: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = LocalMusicPlayerColors.current
    val quality = remember(track.format, track.bitrateKbps, track.sampleRateHz, track.bitDepth) {
        track.toAudioQualityDisplay()
    }
    val containerColor = when {
        onDarkSurface -> Color.White.copy(alpha = 0.14f)
        colors.themeMode == ThemeMode.LIQUID_GLASS -> Color.White.copy(alpha = 0.16f)
        colors.themeMode == ThemeMode.DARK -> Color.White.copy(alpha = 0.10f)
        else -> colors.elevatedSurface.copy(alpha = 0.85f)
    }
    val borderStroke = when {
        onDarkSurface -> BorderStroke(0.6.dp, Color.White.copy(alpha = 0.22f))
        colors.themeMode == ThemeMode.LIQUID_GLASS -> BorderStroke(0.6.dp, colors.glassStroke)
        else -> BorderStroke(0.5.dp, colors.divider)
    }
    val primaryTextColor = if (onDarkSurface) Color.White else colors.textPrimary
    val secondaryTextColor = if (onDarkSurface) Color.White.copy(alpha = 0.80f) else colors.textSecondary
    val accentTint = if (onDarkSurface) Color(0xFFFF6482) else colors.accent

    Surface(
        modifier = modifier.testTag("audio_quality_badge"),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = borderStroke
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 7.dp else 10.dp,
                vertical = if (compact) 3.dp else 4.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (quality.isLossless) {
                Icon(
                    imageVector = Icons.Filled.HighQuality,
                    contentDescription = "Lossless",
                    tint = accentTint,
                    modifier = Modifier.size(if (compact) 12.dp else 14.dp)
                )
                Text(
                    text = "Lossless",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accentTint,
                    maxLines = 1
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor
                )
            }
            Text(
                text = quality.formatBadge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = primaryTextColor,
                maxLines = 1
            )
            if (quality.specText.isNotBlank()) {
                Text(
                    text = "• ${quality.specText}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ArtworkImage(
    artworkUri: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp
) {
    val shape = RoundedCornerShape(cornerRadius)
    if (artworkUri.isNullOrBlank()) {
        Image(
            painter = painterResource(R.drawable.img_default_artwork_1794853244685),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape)
        )
    } else {
        AsyncImage(
            model = artworkUri,
            contentDescription = contentDescription,
            placeholder = painterResource(R.drawable.img_default_artwork_1794853244685),
            error = painterResource(R.drawable.img_default_artwork_1794853244685),
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape)
        )
    }
}

@Composable
fun AppleMusicSegmentedSwitcher(
    selectedSource: MusicSource,
    onSelect: (MusicSource) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMusicPlayerColors.current
    val sources = MusicSource.entries
    val selectedIndex = sources.indexOf(selectedSource).coerceAtLeast(0)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .testTag("source_switcher"),
        shape = RoundedCornerShape(22.dp),
        color = colors.elevatedSurface.copy(alpha = if (colors.themeMode == ThemeMode.LIQUID_GLASS) 0.5f else 1f),
        border = if (colors.themeMode == ThemeMode.LIQUID_GLASS) BorderStroke(0.5.dp, colors.glassStroke) else null
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val segmentWidth = maxWidth / sources.size
            val indicatorOffset by animateDpAsState(
                targetValue = segmentWidth * selectedIndex,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
                label = "seg_offset"
            )

            Surface(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(segmentWidth)
                    .height(36.dp),
                shape = RoundedCornerShape(18.dp),
                color = if (colors.themeMode == ThemeMode.LIGHT) Color.White else colors.accent,
                shadowElevation = if (colors.themeMode == ThemeMode.LIGHT) 2.dp else 0.dp
            ) {}

            Row(modifier = Modifier.fillMaxSize()) {
                sources.forEach { src ->
                    val isSelected = src == selectedSource
                    val textColor by animateColorAsState(
                        targetValue = when {
                            isSelected && colors.themeMode == ThemeMode.LIGHT -> colors.accent
                            isSelected -> Color.White
                            else -> colors.textSecondary
                        },
                        label = "seg_text"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onSelect(src) }
                            .testTag("source_tab_${src.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = src.labelVi,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingMiniPlayer(
    track: Track?,
    isPlaying: Boolean,
    progress: Float,
    onTap: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    isBuffering: Boolean = false
) {
    if (track == null) return
    val colors = LocalMusicPlayerColors.current
    val reducedMotion = rememberReducedMotionEnabled()
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(22.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .applePressScale(interactionSource = interactionSource, reducedMotion = reducedMotion)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onTap
            )
            .testTag("floating_mini_player"),
        shape = shape,
        color = if (colors.themeMode == ThemeMode.LIQUID_GLASS) {
            Color(0xB8181820)
        } else {
            colors.cardSurface
        },
        border = BorderStroke(0.75.dp, colors.glassStroke),
        shadowElevation = 10.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(46.dp).then(artworkModifier)) {
                    AnimatedContent(
                        targetState = track.id,
                        transitionSpec = {
                            if (reducedMotion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                fadeIn(AppleMotion.mediaSpec(false)) togetherWith
                                    fadeOut(AppleMotion.mediaSpec(false))
                            }
                        },
                        label = "mini_art_change"
                    ) { _ ->
                        ArtworkImage(
                            artworkUri = track.artworkUri,
                            contentDescription = track.title,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 11.dp
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                AnimatedContent(
                    targetState = track.id,
                    transitionSpec = {
                        if (reducedMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            fadeIn(AppleMotion.mediaSpec(false)) togetherWith
                                fadeOut(AppleMotion.mediaSpec(false))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = "mini_meta_change"
                ) { _ ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${track.artist} • ${track.format.extension.uppercase()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(48.dp).testTag("mini_play_pause")
                ) {
                    val btnState = when {
                        isBuffering -> 0
                        isPlaying -> 1
                        else -> 2
                    }
                    AnimatedContent(
                        targetState = btnState,
                        transitionSpec = {
                            if (reducedMotion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                (fadeIn(AppleMotion.navSpec(false)) + scaleIn(initialScale = 0.85f)) togetherWith
                                    (fadeOut(AppleMotion.navSpec(false)) + scaleOut(targetScale = 0.85f))
                            }
                        },
                        label = "mini_play_pause_icon"
                    ) { state ->
                        when (state) {
                            0 -> CircularProgressIndicator(
                                modifier = Modifier
                                    .size(22.dp)
                                    .testTag("mini_loading_spinner"),
                                strokeWidth = 2.5.dp,
                                color = colors.accent
                            )
                            1 -> Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = "Tạm dừng",
                                tint = colors.textPrimary
                            )
                            else -> Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Phát",
                                tint = colors.textPrimary
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier.size(48.dp).testTag("mini_next")
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Bài tiếp theo",
                        tint = colors.textPrimary
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.5.dp),
                color = colors.accent,
                trackColor = colors.divider
            )
        }
    }
}

@Composable
fun TrackRowItem(
    track: Track,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onAddToQueue: () -> Unit,
    onDeleteLocal: () -> Unit,
    onEditGenre: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMusicPlayerColors.current
    var menuExpanded by remember { mutableStateOf(false) }

    val quality = remember(track.format, track.bitrateKbps, track.sampleRateHz, track.bitDepth) {
        track.toAudioQualityDisplay()
    }
    val metadataLine = remember(track.artist, track.telegramTopicName, quality) {
        buildString {
            append(track.artist)
            if (!track.telegramTopicName.isNullOrBlank()) {
                append(" • ")
                append(track.telegramTopicName)
            }
            append(" • ")
            if (quality.isLossless) append("Lossless • ")
            append(quality.formatBadge)
            if (quality.specText.isNotBlank()) {
                append(" • ")
                append(quality.specText)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isCurrent) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onPlay)
            .testTag("track_row_${track.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkImage(
                artworkUri = track.artworkUri,
                contentDescription = track.title,
                modifier = Modifier.size(48.dp),
                cornerRadius = 8.dp
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent) colors.accent else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = metadataLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Yêu thích",
                        tint = if (track.isFavorite) colors.accent else colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(48.dp).testTag("track_menu_${track.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Tùy chọn bài hát",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Thêm vào phát tiếp theo") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onAddToQueue()
                            }
                        )
                        if (track.source != MusicSource.OFFLINE) {
                            when (track.downloadStatus) {
                                DownloadStatus.COMPLETED -> {
                                    DropdownMenuItem(
                                        text = { Text("Đã lưu (${track.format.extension.uppercase()})") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.CloudDone,
                                                contentDescription = null,
                                                tint = colors.accent
                                            )
                                        },
                                        enabled = false,
                                        onClick = { menuExpanded = false }
                                    )
                                }
                                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                                    DropdownMenuItem(
                                        text = { Text("Đang tải ${(track.downloadProgress * 100).toInt()}%") },
                                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                        enabled = false,
                                        onClick = { menuExpanded = false }
                                    )
                                }
                                else -> {
                                    DropdownMenuItem(
                                        text = { Text("Tải về") },
                                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                        modifier = Modifier.testTag("download_btn_${track.id}"),
                                        onClick = {
                                            menuExpanded = false
                                            onDownload()
                                        }
                                    )
                                }
                            }
                        }
                        if (onEditGenre != null) {
                            DropdownMenuItem(
                                text = { Text("Đổi thể loại (${track.genre.displayNameVi})") },
                                onClick = {
                                    menuExpanded = false
                                    onEditGenre()
                                }
                            )
                        }
                        if (track.downloadStatus == DownloadStatus.COMPLETED || track.source == MusicSource.OFFLINE) {
                            DropdownMenuItem(
                                text = { Text("Xóa bản cục bộ") },
                                leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDeleteLocal()
                                }
                            )
                        }
                    }
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(
            thickness = 0.5.dp,
            color = colors.divider.copy(alpha = 0.45f),
            modifier = Modifier.padding(start = 76.dp, end = 16.dp)
        )
    }
}

fun formatDurationMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
