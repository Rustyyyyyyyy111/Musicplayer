package com.laughing.musicplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.RepeatMode
import com.laughing.musicplayer.core.model.SleepTimerMode
import com.laughing.musicplayer.ui.components.ArtworkImage
import com.laughing.musicplayer.ui.components.AudioQualityBadge
import com.laughing.musicplayer.ui.components.formatDurationMs
import com.laughing.musicplayer.ui.theme.AppleMotion
import com.laughing.musicplayer.ui.theme.LiquidGlassBase
import com.laughing.musicplayer.ui.theme.LocalLiquidGlassPalette
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.theme.applePressScale
import com.laughing.musicplayer.ui.theme.rememberReducedMotionEnabled
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    viewModel: MusicPlayerViewModel,
    onDismiss: () -> Unit,
    onOpenEqualizer: () -> Unit,
    artworkSharedModifier: Modifier = Modifier
) {
    BackHandler { onDismiss() }

    val colors = LocalMusicPlayerColors.current
    val palette = LocalLiquidGlassPalette.current
    val reducedMotion = rememberReducedMotionEnabled()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val track = playback.currentTrack ?: return

    var showLyricsView by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showLyricsSearchDialog by remember { mutableStateOf(false) }

    val artworkScale by animateFloatAsState(
        targetValue = if (reducedMotion || playback.isPlaying) 1.0f else 0.90f,
        animationSpec = if (reducedMotion) AppleMotion.mediaSpec(true) else spring(dampingRatio = 0.72f),
        label = "art_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LiquidGlassBase)
            .testTag("now_playing_sheet")
    ) {
        // Edge-to-edge blurred artwork backdrop
        if (!track.artworkUri.isNullOrBlank()) {
            AsyncImage(
                model = track.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(52.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.dominant.copy(alpha = 0.78f),
                            palette.muted.copy(alpha = 0.85f),
                            LiquidGlassBase.copy(alpha = 0.94f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Thu gọn", tint = Color.White)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = track.source.labelVi.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.80f)
                    )
                    val spec = buildString {
                        append(track.format.extension.uppercase())
                        if (track.bitrateKbps > 0) append(" • ${track.bitrateKbps} kbps")
                        if (track.bitDepth != null) append(" • ${track.bitDepth}-bit")
                    }
                    Text(text = spec, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.60f))
                }
                val timerActive = playback.sleepTimerMode != SleepTimerMode.OFF
                IconButton(
                    onClick = { showSleepTimerDialog = true },
                    modifier = Modifier.size(48.dp).testTag("sleep_timer_btn")
                ) {
                    Icon(
                        Icons.Filled.Timer,
                        contentDescription = "Hẹn giờ tắt",
                        tint = if (timerActive) colors.accent else Color.White
                    )
                }
            }
            if (playback.sleepTimerMode != SleepTimerMode.OFF) {
                val timerBadgeText = when (playback.sleepTimerMode) {
                    SleepTimerMode.AFTER_MINUTES -> "Hẹn tắt còn ${formatDurationMs(playback.sleepTimerRemainingMs)}"
                    SleepTimerMode.END_OF_CURRENT_TRACK -> "Tắt sau bài hát hiện tại"
                    SleepTimerMode.AFTER_N_TRACKS -> "Tắt sau ${playback.sleepTimerRemainingTracks} bài nữa"
                    SleepTimerMode.OFF -> ""
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.accent.copy(alpha = 0.24f),
                        modifier = Modifier.clickable { showSleepTimerDialog = true }
                    ) {
                        Text(
                            text = timerBadgeText,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (!showLyricsView) {
                // Artwork
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .size(300.dp)
                            .then(artworkSharedModifier)
                            .scale(artworkScale),
                        shape = RoundedCornerShape(24.dp),
                        shadowElevation = if (playback.isPlaying) 22.dp else 10.dp,
                        color = Color.Transparent
                    ) {
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
                            label = "now_playing_artwork"
                        ) { _ ->
                            ArtworkImage(
                                artworkUri = track.artworkUri,
                                contentDescription = track.title,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 24.dp
                            )
                        }
                    }
                }
            } else {
                // Full-screen synchronized lyrics
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Lời bài hát (${lyricsState.result?.sourceLabel ?: "Chưa có"})",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                        Row {
                            TextButton(onClick = { viewModel.adjustLyricsOffset(-500L) }) {
                                Text("-0.5s", color = Color.White)
                            }
                            TextButton(onClick = { viewModel.adjustLyricsOffset(500L) }) {
                                Text("+0.5s", color = Color.White)
                            }
                            IconButton(onClick = { showLyricsSearchDialog = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Tìm lời thủ công", tint = Color.White)
                            }
                        }
                    }

                    val lines = lyricsState.result?.lines.orEmpty()
                    if (lines.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Chưa có lời bài hát cho bài này",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White.copy(alpha = 0.80f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { showLyricsSearchDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                                ) {
                                    Text("Tìm lời bài hát thủ công")
                                }
                            }
                        }
                    } else {
                        val effectivePos = playback.positionMs + lyricsState.offsetMs
                        val activeIdx = lines.indexOfLast { it.timestampMs <= effectivePos }.coerceAtLeast(0)
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            itemsIndexed(lines) { idx, line ->
                                val isActive = idx == activeIdx
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.seekTo(line.timestampMs) }
                                ) {
                                    Text(
                                        text = line.text,
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isActive) Color.White else Color.White.copy(alpha = 0.48f)
                                    )
                                    if (lyricsState.showRomanization && !line.romanization.isNullOrBlank()) {
                                        Text(
                                            text = line.romanization,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isActive) Color.White.copy(alpha = 0.86f) else Color.White.copy(alpha = 0.35f)
                                        )
                                    }
                                    if (lyricsState.showTranslation && !line.translation.isNullOrBlank()) {
                                        Text(
                                            text = line.translation,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White.copy(alpha = 0.68f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Title, Artist & Favorite + Offline Download
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        label = "now_playing_meta"
                    ) { _ ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = listOfNotNull(track.artist, track.telegramTopicName).distinct().joinToString(" • "),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.80f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (track.source != MusicSource.OFFLINE && track.downloadStatus != DownloadStatus.COMPLETED) {
                        IconButton(onClick = { viewModel.downloadTrack(track) }) {
                            Icon(Icons.Filled.Download, contentDescription = "Tải về", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { viewModel.toggleFavorite(track) }) {
                        Icon(
                            imageVector = if (track.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Yêu thích",
                            tint = if (track.isFavorite) colors.accent else Color.White
                        )
                    }
                }

                // Seekbar
                val dur = playback.durationMs.coerceAtLeast(1L)
                var draggingPos by remember(track.id) { mutableStateOf<Float?>(null) }
                val sliderVal = draggingPos ?: (playback.positionMs.toFloat() / dur).coerceIn(0f, 1f)

                Column {
                    Slider(
                        value = sliderVal,
                        onValueChange = { draggingPos = it },
                        onValueChangeFinished = {
                            draggingPos?.let { viewModel.seekTo((it * dur).toLong()) }
                            draggingPos = null
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatDurationMs(playback.positionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.74f)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AudioQualityBadge(
                                track = track,
                                onDarkSurface = true
                            )
                        }
                        Text(
                            text = formatDurationMs(playback.durationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.74f)
                        )
                    }
                }

                // Transport Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.toggleShuffle() }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Ngẫu nhiên",
                            tint = if (playback.shuffleEnabled) colors.accent else Color.White.copy(alpha = 0.74f)
                        )
                    }
                    IconButton(onClick = { viewModel.skipPrevious() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Bài trước", tint = Color.White, modifier = Modifier.size(38.dp))
                    }
                    val playBtnInteraction = remember { MutableInteractionSource() }
                    Surface(
                        modifier = Modifier
                            .size(72.dp)
                            .applePressScale(playBtnInteraction, reducedMotion = reducedMotion, targetScale = 0.94f)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = playBtnInteraction,
                                indication = LocalIndication.current
                            ) { viewModel.togglePlayPause() }
                            .testTag("now_playing_play_pause"),
                        shape = CircleShape,
                        color = Color.White
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AnimatedContent(
                                targetState = playback.isPlaying,
                                transitionSpec = {
                                    if (reducedMotion) {
                                        EnterTransition.None togetherWith ExitTransition.None
                                    } else {
                                        (fadeIn(AppleMotion.navSpec(false)) + scaleIn(initialScale = 0.84f)) togetherWith
                                            (fadeOut(AppleMotion.navSpec(false)) + scaleOut(targetScale = 0.84f))
                                    }
                                },
                                label = "now_playing_play_pause_icon"
                            ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playing) "Tạm dừng" else "Phát",
                                    tint = Color.Black,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.skipNext() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Bài tiếp theo", tint = Color.White, modifier = Modifier.size(38.dp))
                    }
                    IconButton(onClick = { viewModel.cycleRepeat() }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (playback.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = "Lặp lại",
                            tint = if (playback.repeatMode != RepeatMode.OFF) colors.accent else Color.White.copy(alpha = 0.74f)
                        )
                    }
                }

                // Bottom Utility Row: Lyrics, EQ, Cast, Queue
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showLyricsView = !showLyricsView }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Filled.Lyrics,
                            contentDescription = "Lời bài hát",
                            tint = if (showLyricsView) colors.accent else Color.White.copy(alpha = 0.80f)
                        )
                    }
                    IconButton(
                        onClick = onOpenEqualizer,
                        modifier = Modifier.size(48.dp).testTag("now_playing_eq_btn")
                    ) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = "Equalizer", tint = Color.White.copy(alpha = 0.80f))
                    }
                    IconButton(onClick = { viewModel.toggleRomanization() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Cast, contentDescription = "Chromecast / Thiết bị", tint = Color.White.copy(alpha = 0.80f))
                    }
                    IconButton(onClick = { showQueueSheet = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = "Hàng đợi", tint = Color.White.copy(alpha = 0.80f))
                    }
                }
            }
        }
    }

    if (showQueueSheet) {
        ModalBottomSheet(onDismissRequest = { showQueueSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Hàng đợi phát (${playback.queue.size} bài)", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.height(340.dp)) {
                    itemsIndexed(playback.queue) { idx, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.playTrack(item, playback.queue)
                                    showQueueSheet = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(item.artworkUri, item.title, Modifier.size(42.dp), 10.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    fontWeight = if (idx == playback.currentIndex) FontWeight.Bold else FontWeight.Normal,
                                    color = if (idx == playback.currentIndex) colors.accent else MaterialTheme.colorScheme.onSurface
                                )
                                Text(item.artist, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSleepTimerDialog) {
        var customMinutesText by remember { mutableStateOf("") }
        var nextTracksCount by remember { mutableStateOf("3") }
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Hẹn giờ tắt nhạc nâng cao") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Tắt theo thời gian (N phút)",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accent
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(15, 30, 45, 60).forEach { min ->
                            TextButton(
                                onClick = {
                                    viewModel.setSleepTimerMinutes(min)
                                    showSleepTimerDialog = false
                                }
                            ) {
                                Text("${min}p")
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customMinutesText,
                            onValueChange = { customMinutesText = it.filter(Char::isDigit) },
                            label = { Text("Tùy chỉnh (phút)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("sleep_custom_minutes_input")
                        )
                        Button(
                            onClick = {
                                val min = customMinutesText.toIntOrNull()?.coerceAtLeast(1) ?: 10
                                viewModel.setSleepTimerMinutes(min)
                                showSleepTimerDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                        ) { Text("Đặt") }
                    }

                    Text(
                        "Tắt theo bài hát",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accent,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    TextButton(
                        onClick = {
                            viewModel.setSleepTimerEndOfTrack()
                            showSleepTimerDialog = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("sleep_end_of_track_btn")
                    ) {
                        Text("Tắt sau bài hát hiện tại")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = nextTracksCount,
                            onValueChange = { nextTracksCount = it.filter(Char::isDigit) },
                            label = { Text("Số bài tiếp theo (N)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("sleep_n_tracks_input")
                        )
                        Button(
                            onClick = {
                                val count = nextTracksCount.toIntOrNull()?.coerceAtLeast(1) ?: 3
                                viewModel.setSleepTimerAfterTracks(count)
                                showSleepTimerDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            modifier = Modifier.testTag("sleep_n_tracks_apply")
                        ) { Text("Áp dụng") }
                    }

                    if (playback.sleepTimerMode != SleepTimerMode.OFF) {
                        TextButton(
                            onClick = {
                                viewModel.cancelSleepTimer()
                                showSleepTimerDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Hủy hẹn giờ đang chạy", color = colors.accent)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepTimerDialog = false }) { Text("Đóng") }
            }
        )
    }

    if (showLyricsSearchDialog) {
        var q by remember { mutableStateOf("${track.title} ${track.artist}") }
        AlertDialog(
            onDismissRequest = { showLyricsSearchDialog = false },
            title = { Text("Tìm kiếm lời bài hát (LRCLIB)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true)
                    Button(onClick = { viewModel.searchLyricsManual(q) }) { Text("Tìm lời") }
                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        itemsIndexed(lyricsState.searchResults) { _, res ->
                            TextButton(onClick = {
                                viewModel.applyManualLyrics(res)
                                showLyricsSearchDialog = false
                            }) {
                                Text("${res.sourceLabel} (${if (res.isSynced) "Đồng bộ" else "Thường"})")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLyricsSearchDialog = false }) { Text("Đóng") }
            }
        )
    }
}
