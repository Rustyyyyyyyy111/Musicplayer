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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.RepeatMode
import com.laughing.musicplayer.core.model.SleepTimerMode
import com.laughing.musicplayer.data.storage.AudioTagEditResult
import com.laughing.musicplayer.ui.components.ArtworkImage
import com.laughing.musicplayer.ui.components.AudioQualityBadge
import com.laughing.musicplayer.ui.components.CastPickerSheet
import com.laughing.musicplayer.ui.components.EditTagsSheet
import com.laughing.musicplayer.ui.components.NowPlayingMoreSheet
import com.laughing.musicplayer.ui.components.SleepModeSheet
import com.laughing.musicplayer.ui.components.SleepTimerAppleSheet
import com.laughing.musicplayer.ui.components.TrackInfoSheet
import com.laughing.musicplayer.ui.components.formatDurationMs
import com.laughing.musicplayer.ui.theme.AppleMotion
import com.laughing.musicplayer.ui.theme.LiquidGlassBase
import com.laughing.musicplayer.ui.theme.LocalLiquidGlassPalette
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.theme.applePressScale
import com.laughing.musicplayer.ui.theme.rememberReducedMotionEnabled
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    viewModel: MusicPlayerViewModel,
    onDismiss: () -> Unit,
    onOpenEqualizer: () -> Unit,
    artworkSharedModifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalMusicPlayerColors.current
    val palette = LocalLiquidGlassPalette.current
    val reducedMotion = rememberReducedMotionEnabled()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val allTracks by viewModel.allTracks.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val castState by viewModel.castUiState.collectAsStateWithLifecycle()
    val sleepCalmLockEnabled by viewModel.sleepCalmLockEnabled.collectAsStateWithLifecycle()
    val trackTechnicalInfo by viewModel.trackTechnicalInfo.collectAsStateWithLifecycle()
    val isSavingTags by viewModel.isSavingTrackTags.collectAsStateWithLifecycle()
    val rawTrack = playback.currentTrack ?: return
    val track = remember(rawTrack, allTracks) {
        allTracks.firstOrNull { it.id == rawTrack.id } ?: rawTrack
    }

    var showLyricsView by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showTrackInfoSheet by remember { mutableStateOf(false) }
    var showSleepModeSheet by remember { mutableStateOf(false) }
    var showEditTagsSheet by remember { mutableStateOf(false) }
    var showCastPickerSheet by remember { mutableStateOf(false) }
    var showLyricsSearchDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            sleepCalmLockEnabled -> viewModel.postStatusMessage("Nhấn giữ nút Mở khóa 1.5 giây để tắt Chế độ ngủ dịu mắt")
            showCastPickerSheet -> showCastPickerSheet = false
            showEditTagsSheet -> showEditTagsSheet = false
            showSleepModeSheet -> showSleepModeSheet = false
            showTrackInfoSheet -> showTrackInfoSheet = false
            showSleepTimerSheet -> showSleepTimerSheet = false
            showMoreSheet -> showMoreSheet = false
            showQueueSheet -> showQueueSheet = false
            else -> onDismiss()
        }
    }

    DisposableEffect(showCastPickerSheet) {
        if (showCastPickerSheet) {
            viewModel.startCastDiscovery()
        }
        onDispose {
            if (showCastPickerSheet) {
                viewModel.stopCastDiscovery()
            }
        }
    }

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
                val timerOrSleepActive = playback.sleepTimerMode != SleepTimerMode.OFF || sleepCalmLockEnabled
                IconButton(
                    onClick = { showMoreSheet = true },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("now_playing_more_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreHoriz,
                        contentDescription = "Tùy chọn khác",
                        tint = if (timerOrSleepActive) colors.accent else Color.White
                    )
                }
            }
            if (playback.sleepTimerMode != SleepTimerMode.OFF || castState.connectedDeviceName != null) {
                val badgeText = buildList {
                    when (playback.sleepTimerMode) {
                        SleepTimerMode.AFTER_MINUTES -> add(
                            if (playback.sleepFadeOutActive) {
                                "Giảm dần âm lượng • còn ${formatDurationMs(playback.sleepTimerRemainingMs)}"
                            } else {
                                "Hẹn tắt còn ${formatDurationMs(playback.sleepTimerRemainingMs)}"
                            }
                        )
                        SleepTimerMode.END_OF_CURRENT_TRACK -> add("Tắt sau bài hát hiện tại")
                        SleepTimerMode.AFTER_N_TRACKS -> add("Tắt sau ${playback.sleepTimerRemainingTracks} bài nữa")
                        SleepTimerMode.OFF -> Unit
                    }
                    castState.connectedDeviceName?.let { add("Cast: $it") }
                }.joinToString(" • ")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.accent.copy(alpha = 0.24f),
                        modifier = Modifier.clickable {
                            if (playback.sleepTimerMode != SleepTimerMode.OFF) {
                                showSleepTimerSheet = true
                            } else {
                                viewModel.refreshCastAndAudioOutputs()
                                showCastPickerSheet = true
                            }
                        }
                    ) {
                        Text(
                            text = badgeText,
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
                    if (track.source != MusicSource.OFFLINE) {
                        NowPlayingDownloadActionButton(
                            status = track.downloadStatus,
                            progress = track.downloadProgress,
                            onStartDownload = { viewModel.downloadTrack(track) },
                            onCancelDownload = { viewModel.cancelDownload(track) }
                        )
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
                            val playButtonState = when {
                                playback.isBuffering -> 0
                                playback.isPlaying -> 1
                                else -> 2
                            }
                            AnimatedContent(
                                targetState = playButtonState,
                                transitionSpec = {
                                    if (reducedMotion) {
                                        EnterTransition.None togetherWith ExitTransition.None
                                    } else {
                                        (fadeIn(AppleMotion.navSpec(false)) + scaleIn(initialScale = 0.84f)) togetherWith
                                            (fadeOut(AppleMotion.navSpec(false)) + scaleOut(targetScale = 0.84f))
                                    }
                                },
                                label = "now_playing_play_pause_icon"
                            ) { state ->
                                when (state) {
                                    0 -> CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .testTag("now_playing_loading_spinner"),
                                        strokeWidth = 3.dp,
                                        color = Color.Black
                                    )
                                    1 -> Icon(
                                        imageVector = Icons.Filled.Pause,
                                        contentDescription = "Tạm dừng",
                                        tint = Color.Black,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    else -> Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "Phát",
                                        tint = Color.Black,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
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
                    IconButton(
                        onClick = {
                            viewModel.refreshCastAndAudioOutputs()
                            showCastPickerSheet = true
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("now_playing_cast_btn")
                    ) {
                        Icon(
                            imageVector = if (castState.connectedDeviceName != null) {
                                Icons.Filled.CastConnected
                            } else {
                                Icons.Filled.Cast
                            },
                            contentDescription = "Chromecast / Thiết bị",
                            tint = if (castState.connectedDeviceName != null) {
                                colors.accent
                            } else {
                                Color.White.copy(alpha = 0.80f)
                            }
                        )
                    }
                    IconButton(onClick = { showQueueSheet = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = "Hàng đợi", tint = Color.White.copy(alpha = 0.80f))
                    }
                }
            }
        }

        // Sleep Mode Dim & Hold-to-Unlock Accidental-Touch Shield
        if (sleepCalmLockEnabled) {
            SleepCalmLockOverlay(
                trackTitle = track.title,
                trackArtist = track.artist,
                playback = playback,
                accentColor = colors.accent,
                onUnlock = { viewModel.setSleepCalmLockEnabled(false) }
            )
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

    if (showMoreSheet) {
        NowPlayingMoreSheet(
            track = track,
            playback = playback,
            sleepCalmLockEnabled = sleepCalmLockEnabled,
            onDismiss = { showMoreSheet = false },
            onOpenSleepTimer = {
                showMoreSheet = false
                showSleepTimerSheet = true
            },
            onOpenTrackInfo = {
                showMoreSheet = false
                viewModel.inspectTrackTechnicalInfo(track)
                showTrackInfoSheet = true
            },
            onOpenSleepMode = {
                showMoreSheet = false
                showSleepModeSheet = true
            },
            onOpenEditTags = {
                showMoreSheet = false
                showEditTagsSheet = true
            }
        )
    }

    if (showSleepTimerSheet) {
        SleepTimerAppleSheet(
            playback = playback,
            onDismiss = { showSleepTimerSheet = false },
            onSelectMinutes = { min -> viewModel.setSleepTimerMinutes(min) },
            onSelectEndOfTrack = { viewModel.setSleepTimerEndOfTrack() },
            onSelectAfterTracks = { count -> viewModel.setSleepTimerAfterTracks(count) },
            onCancelTimer = { viewModel.cancelSleepTimer() }
        )
    }

    if (showTrackInfoSheet) {
        TrackInfoSheet(
            track = track,
            info = trackTechnicalInfo,
            onDismiss = { showTrackInfoSheet = false },
            onEditTagsClick = {
                showTrackInfoSheet = false
                showEditTagsSheet = true
            }
        )
    }

    if (showSleepModeSheet) {
        SleepModeSheet(
            playback = playback,
            sleepCalmLockEnabled = sleepCalmLockEnabled,
            onDismiss = { showSleepModeSheet = false },
            onToggleFadeOut = { enabled -> viewModel.setSleepFadeOutEnabled(enabled) },
            onToggleCalmLock = { enabled -> viewModel.setSleepCalmLockEnabled(enabled) },
            onStart30MinSleepMode = {
                viewModel.setSleepFadeOutEnabled(true)
                viewModel.setSleepTimerMinutes(30)
            },
            onStartEndOfTrackSleepMode = {
                viewModel.setSleepFadeOutEnabled(true)
                viewModel.setSleepTimerEndOfTrack()
            }
        )
    }

    if (showEditTagsSheet) {
        EditTagsSheet(
            track = track,
            isSaving = isSavingTags,
            onDismiss = { showEditTagsSheet = false },
            onDownloadOfflineFirst = { viewModel.downloadTrack(track) },
            onSaveTags = { req ->
                viewModel.saveOfflineTrackTags(track, req) { result ->
                    if (result is AudioTagEditResult.Success) {
                        showEditTagsSheet = false
                    }
                }
            }
        )
    }

    if (showCastPickerSheet) {
        CastPickerSheet(
            castState = castState,
            onDismiss = { showCastPickerSheet = false },
            onRefresh = { viewModel.refreshCastAndAudioOutputs() },
            onSelectCastRoute = { routeId -> viewModel.selectCastRoute(routeId) },
            onDisconnectCast = { viewModel.disconnectCast() },
            onOpenSystemAudioOutput = { viewModel.openSystemAudioOutput(context) },
            onOpenBluetoothSettings = { viewModel.openBluetoothSettings(context) }
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

@Composable
private fun SleepCalmLockOverlay(
    trackTitle: String,
    trackArtist: String,
    playback: com.laughing.musicplayer.audio.PlaybackState,
    accentColor: Color,
    onUnlock: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var isHolding by remember { mutableStateOf(false) }

    val timerText = when (playback.sleepTimerMode) {
        SleepTimerMode.AFTER_MINUTES -> "Tắt nhạc sau ${formatDurationMs(playback.sleepTimerRemainingMs)}"
        SleepTimerMode.END_OF_CURRENT_TRACK -> "Sẽ dừng sau bài hát hiện tại"
        SleepTimerMode.AFTER_N_TRACKS -> "Sẽ dừng sau ${playback.sleepTimerRemainingTracks} bài nữa"
        SleepTimerMode.OFF -> "Chế độ ngủ dịu mắt đang bật"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.84f))
            .pointerInput(Unit) {
                detectTapGestures { /* Absorb stray taps while Sleep Calm Lock is active */ }
            }
            .testTag("sleep_calm_lock_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Bedtime,
                contentDescription = "Chế độ ngủ",
                tint = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.size(38.dp)
            )
            Text(
                text = timerText,
                style = MaterialTheme.typography.labelLarge,
                color = accentColor.copy(alpha = 0.90f),
                textAlign = TextAlign.Center
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = trackTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = trackArtist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.52f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color.White.copy(alpha = if (isHolding) 0.18f else 0.10f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isHolding = true
                                val holdJob = scope.launch {
                                    val totalMs = 1500L
                                    val stepMs = 40L
                                    var elapsed = 0L
                                    while (elapsed < totalMs) {
                                        delay(stepMs)
                                        elapsed += stepMs
                                        holdProgress = (elapsed.toFloat() / totalMs).coerceIn(0f, 1f)
                                    }
                                    onUnlock()
                                }
                                val released = tryAwaitRelease()
                                holdJob.cancel()
                                isHolding = false
                                if (released || holdProgress < 1f) {
                                    holdProgress = 0f
                                }
                            }
                        )
                    }
                    .testTag("sleep_calm_unlock_btn")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp)) {
                        CircularProgressIndicator(
                            progress = { if (isHolding) holdProgress else 0f },
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = accentColor,
                            trackColor = Color.White.copy(alpha = 0.22f)
                        )
                        Icon(
                            imageVector = Icons.Filled.LockOpen,
                            contentDescription = "Mở khóa",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = if (isHolding) "Đang mở khóa..." else "Nhấn giữ 1.5s để mở khóa",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.88f)
                    )
                }
            }
        }
    }
}

@Composable
fun NowPlayingDownloadActionButton(
    status: DownloadStatus,
    progress: Float,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (status) {
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
            val pct = (progress.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.14f),
                border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.28f)),
                modifier = modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onCancelDownload)
                    .semantics { contentDescription = "Hủy tải về ($pct%)" }
                    .testTag("now_playing_download_progress")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (status == DownloadStatus.DOWNLOADING && progress > 0f) {
                        CircularProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.28f)
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White
                        )
                    }
                    Text(
                        text = "$pct%",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.testTag("now_playing_download_percent")
                    )
                }
            }
        }
        DownloadStatus.COMPLETED -> {
            IconButton(
                onClick = {},
                enabled = false,
                colors = IconButtonDefaults.iconButtonColors(
                    disabledContentColor = Color(0xFF4ADE80)
                ),
                modifier = modifier
                    .size(48.dp)
                    .testTag("now_playing_downloaded_btn")
            ) {
                Icon(
                    imageVector = Icons.Rounded.OfflinePin,
                    contentDescription = "Đã tải về"
                )
            }
        }
        else -> {
            IconButton(
                onClick = onStartDownload,
                modifier = modifier
                    .size(48.dp)
                    .testTag("now_playing_download_btn")
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Tải về",
                    tint = Color.White
                )
            }
        }
    }
}
