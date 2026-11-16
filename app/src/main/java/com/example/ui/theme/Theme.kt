package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.palette.graphics.Palette
import com.example.core.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class LiquidGlassPalette(
    val dominant: Color = Color(0xFF3A0C18),
    val vibrant: Color = AppleCrimson,
    val muted: Color = Color(0xFF1A1624),
    val accent: Color = AppleCrimson
)

@Immutable
data class MusicPlayerColors(
    val themeMode: ThemeMode,
    val pageBackground: Color,
    val groupedSurface: Color,
    val cardSurface: Color,
    val elevatedSurface: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val glassStroke: Color,
    val navBarSurface: Color,
    val isDark: Boolean
)

val LocalMusicPlayerColors = staticCompositionLocalOf {
    MusicPlayerColors(
        themeMode = ThemeMode.DARK,
        pageBackground = OledBackground,
        groupedSurface = OledCardSurface,
        cardSurface = OledCardSurface,
        elevatedSurface = OledSurfaceElevated,
        divider = OledDivider,
        textPrimary = OledTextPrimary,
        textSecondary = OledTextSecondary,
        accent = AppleCrimson,
        glassStroke = OledDivider,
        navBarSurface = Color(0xEB121214),
        isDark = true
    )
}

val LocalLiquidGlassPalette = staticCompositionLocalOf { LiquidGlassPalette() }

val AppleMusicShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MusicPlayerTheme(
    themeMode: ThemeMode,
    liquidGlassPalette: LiquidGlassPalette = LiquidGlassPalette(),
    content: @Composable () -> Unit
) {
    val animatedDominant by animateColorAsState(
        targetValue = liquidGlassPalette.dominant,
        animationSpec = tween(durationMillis = 650),
        label = "lg_dominant"
    )
    val animatedVibrant by animateColorAsState(
        targetValue = liquidGlassPalette.vibrant,
        animationSpec = tween(durationMillis = 650),
        label = "lg_vibrant"
    )
    val animatedMuted by animateColorAsState(
        targetValue = liquidGlassPalette.muted,
        animationSpec = tween(durationMillis = 650),
        label = "lg_muted"
    )

    val resolvedPalette = LiquidGlassPalette(
        dominant = animatedDominant,
        vibrant = animatedVibrant,
        muted = animatedMuted,
        accent = if (themeMode == ThemeMode.LIQUID_GLASS) animatedVibrant else AppleCrimson
    )

    val customColors = when (themeMode) {
        ThemeMode.LIGHT -> MusicPlayerColors(
            themeMode = themeMode,
            pageBackground = LightGroupedBackground,
            groupedSurface = LightBackground,
            cardSurface = LightSurfaceCard,
            elevatedSurface = LightSurfaceSecondary,
            divider = LightDivider,
            textPrimary = LightTextPrimary,
            textSecondary = LightTextSecondary,
            accent = AppleCrimson,
            glassStroke = LightDivider,
            navBarSurface = Color(0xEBFFFFFF),
            isDark = false
        )
        ThemeMode.DARK -> MusicPlayerColors(
            themeMode = themeMode,
            pageBackground = OledBackground,
            groupedSurface = OledCardSurface,
            cardSurface = OledCardSurface,
            elevatedSurface = OledSurfaceElevated,
            divider = OledDivider,
            textPrimary = OledTextPrimary,
            textSecondary = OledTextSecondary,
            accent = AppleCrimson,
            glassStroke = OledDivider,
            navBarSurface = Color(0xE60B0B0D),
            isDark = true
        )
        ThemeMode.LIQUID_GLASS -> MusicPlayerColors(
            themeMode = themeMode,
            pageBackground = Color.Transparent,
            groupedSurface = LiquidGlassCardDark,
            cardSurface = Color(0x30FFFFFF),
            elevatedSurface = Color(0x42FFFFFF),
            divider = LiquidGlassBorder,
            textPrimary = Color.White,
            textSecondary = Color(0xD9F2F2F7),
            accent = AppleCrimsonDark,
            glassStroke = LiquidGlassBorder,
            navBarSurface = LiquidGlassNavSurface,
            isDark = true
        )
    }

    val m3Scheme = if (customColors.isDark) {
        darkColorScheme(
            primary = customColors.accent,
            onPrimary = Color.White,
            secondary = AppleCrimsonDark,
            background = if (themeMode == ThemeMode.DARK) OledBackground else LiquidGlassBase,
            surface = customColors.cardSurface,
            surfaceVariant = customColors.elevatedSurface,
            onBackground = customColors.textPrimary,
            onSurface = customColors.textPrimary,
            onSurfaceVariant = customColors.textSecondary,
            outline = customColors.divider
        )
    } else {
        lightColorScheme(
            primary = AppleCrimson,
            onPrimary = Color.White,
            secondary = AppleCrimsonDark,
            background = LightGroupedBackground,
            surface = LightSurfaceCard,
            surfaceVariant = LightSurfaceSecondary,
            onBackground = LightTextPrimary,
            onSurface = LightTextPrimary,
            onSurfaceVariant = LightTextSecondary,
            outline = LightDivider
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !customColors.isDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !customColors.isDark
        }
    }

    CompositionLocalProvider(
        LocalMusicPlayerColors provides customColors,
        LocalLiquidGlassPalette provides resolvedPalette
    ) {
        MaterialTheme(
            colorScheme = m3Scheme,
            typography = AppleMusicTypography,
            shapes = AppleMusicShapes,
            content = content
        )
    }
}

object PaletteExtractor {
    suspend fun extractFromUri(context: Context, uriString: String?): LiquidGlassPalette {
        if (uriString.isNullOrBlank()) return LiquidGlassPalette()
        return withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bitmap: Bitmap? = when (uri.scheme) {
                    "file", null -> BitmapFactory.decodeFile(uri.path ?: uriString, opts)
                    "content", "android.resource" -> context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                    else -> null
                }
                if (bitmap == null) return@runCatching LiquidGlassPalette()
                val palette = Palette.from(bitmap).maximumColorCount(16).generate()
                val dominant = palette.getDarkMutedColor(
                    palette.getDominantColor(0xFF280814.toInt())
                )
                val vibrant = palette.getVibrantColor(
                    palette.getLightVibrantColor(AppleCrimson.toArgb())
                )
                val muted = palette.getMutedColor(0xFF161322.toInt())
                bitmap.recycle()
                LiquidGlassPalette(
                    dominant = Color(dominant),
                    vibrant = Color(vibrant),
                    muted = Color(muted),
                    accent = Color(vibrant)
                )
            }.getOrDefault(LiquidGlassPalette())
        }
    }
}
