package com.laughing.musicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laughing.musicplayer.core.model.EqPreset
import com.laughing.musicplayer.ui.components.AppleCard
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel
import kotlin.math.roundToInt

private val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

@Composable
fun EqualizerScreen(
    viewModel: MusicPlayerViewModel,
    onBack: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val eq = prefs.eqState
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("equalizer_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp).testTag("eq_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại",
                            tint = colors.accent
                        )
                    }
                    Column {
                        Text(
                            text = "Equalizer 10 Băng Tần",
                            style = MaterialTheme.typography.headlineMedium,
                            color = colors.textPrimary
                        )
                        playback.currentTrack?.let { track ->
                            Text(
                                text = "Thể loại: ${track.genre.displayNameVi}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
                TextButton(
                    onClick = {
                        viewModel.updateEqState(
                            eq.copy(
                                preset = EqPreset.FLAT,
                                bandGainsDb = EqPreset.FLAT.gainsDb,
                                preampDb = 0f
                            )
                        )
                    },
                    modifier = Modifier.testTag("eq_reset_btn")
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, tint = colors.accent)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Đặt lại", color = colors.accent)
                }
            }
        }

        // Master Enable Card
        item {
            AppleCard(cornerRadius = 22.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = colors.accent)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Bộ xử lý DSP Media3",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (eq.enabled) "Đang bật (${eq.preset.displayNameVi})" else "Đang tắt (Âm thanh gốc)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                    Switch(
                        checked = eq.enabled,
                        onCheckedChange = { viewModel.updateEqState(eq.copy(enabled = it)) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accent),
                        modifier = Modifier.testTag("eq_master_switch")
                    )
                }
            }
        }

        // Presets
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Preset âm thanh",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(EqPreset.entries) { preset ->
                        FilterChip(
                            selected = eq.preset == preset,
                            onClick = { viewModel.selectEqPreset(preset) },
                            label = { Text(preset.displayNameVi) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accent,
                                selectedLabelColor = Color.White,
                                labelColor = colors.textPrimary
                            ),
                            modifier = Modifier.testTag("eq_preset_${preset.name.lowercase()}")
                        )
                    }
                }
            }
        }

        // 10-Band Vertical Sliders
        item {
            AppleCard(cornerRadius = 22.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("+12 dB", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                        Text("0 dB", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                        Text("-12 dB", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BAND_LABELS.forEachIndexed { idx, freqLabel ->
                            val gain = eq.bandGainsDb.getOrElse(idx) { 0f }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "${if (gain > 0f) "+" else ""}${(gain * 10f).roundToInt() / 10f}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (eq.enabled) colors.accent else colors.textSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Box(
                                    modifier = Modifier
                                        .height(170.dp)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Slider(
                                        value = gain,
                                        enabled = eq.enabled,
                                        onValueChange = { newVal ->
                                            val updated = eq.bandGainsDb.toMutableList()
                                            while (updated.size < 10) updated.add(0f)
                                            updated[idx] = (newVal * 2f).roundToInt() / 2f
                                            viewModel.updateEqState(
                                                eq.copy(
                                                    preset = EqPreset.CUSTOM,
                                                    bandGainsDb = updated
                                                )
                                            )
                                        },
                                        valueRange = -12f..12f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = colors.accent,
                                            activeTrackColor = colors.accent,
                                            inactiveTrackColor = colors.divider
                                        ),
                                        modifier = Modifier
                                            .graphicsLayer {
                                                rotationZ = 270f
                                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                                            }
                                            .layout { measurable, constraints ->
                                                val placeable = measurable.measure(
                                                    Constraints(
                                                        minWidth = constraints.minHeight,
                                                        maxWidth = constraints.maxHeight,
                                                        minHeight = constraints.minWidth,
                                                        maxHeight = constraints.maxWidth
                                                    )
                                                )
                                                layout(placeable.height, placeable.width) {
                                                    placeable.place(
                                                        -(placeable.width - placeable.height) / 2,
                                                        -(placeable.height - placeable.width) / 2
                                                    )
                                                }
                                            }
                                            .width(160.dp)
                                            .testTag("eq_band_$idx")
                                    )
                                }
                                Text(
                                    text = freqLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Preamp & DSP Options
        item {
            AppleCard(cornerRadius = 22.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Tiền khuếch đại (Preamp)",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "${if (eq.preampDb > 0f) "+" else ""}${(eq.preampDb * 10f).roundToInt() / 10f} dB",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.accent
                        )
                    }
                    Slider(
                        value = eq.preampDb,
                        enabled = eq.enabled,
                        onValueChange = { valRounded ->
                            val rounded = (valRounded * 2f).roundToInt() / 2f
                            viewModel.updateEqState(eq.copy(preampDb = rounded))
                        },
                        valueRange = -12f..12f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accent,
                            activeTrackColor = colors.accent,
                            inactiveTrackColor = colors.divider
                        ),
                        modifier = Modifier.testTag("eq_preamp_slider")
                    )

                    EqOptionSwitchRow(
                        title = "Chống méo tiếng (Clipping Protection)",
                        subtitle = "Soft-clipping limiter tránh vỡ âm khi tăng Bass",
                        checked = eq.clippingProtection,
                        onCheckedChange = { viewModel.updateEqState(eq.copy(clippingProtection = it)) }
                    )

                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))

                    EqOptionSwitchRow(
                        title = "Auto-EQ theo thể loại",
                        subtitle = "Tự chuyển cấu hình khi đổi bài (Bolero, Ballad, EDM...)",
                        checked = eq.autoEqByGenre,
                        onCheckedChange = { viewModel.updateEqState(eq.copy(autoEqByGenre = it)) }
                    )

                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))

                    val rgSign = if (playback.effectiveReplayGainDb > 0f) "+" else ""
                    val rgVal = (playback.effectiveReplayGainDb * 10f).roundToInt() / 10f
                    EqOptionSwitchRow(
                        title = "Chuẩn hóa âm lượng (ReplayGain / EBU R128)",
                        subtitle = "Cân bằng âm lượng Telegram, YouTube & Offline (Hiện tại: $rgSign$rgVal dB)",
                        checked = eq.loudnessNormalization,
                        onCheckedChange = { viewModel.updateEqState(eq.copy(loudnessNormalization = it)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EqOptionSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accent)
        )
    }
}
