package com.laughing.musicplayer.ui.theme

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.annotation.DrawableRes
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.palette.graphics.Palette
import com.laughing.musicplayer.core.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Immutable
data class LiquidGlassPalette(
    val dominant: Color = Color(0xFF3A0C18),
    val vibrant: Color = AppleCrimson,
    val muted: Color = Color(0xFF1A1624),
    val accent: Color = AppleCrimson
)

@Immutable
data class CachedArtworkVisuals(
    val palette: LiquidGlassPalette = LiquidGlassPalette(),
    val blurredBitmap: ImageBitmap? = null
)

@Immutable
data class MusicPlayerColors(
    val themeMode: ThemeMode,
    val pageBackground: Color,
    val groupedSurface: Color,
    val cardSurface: Color,
    val elevatedSurface: Color,
    val modalSurface: Color,
    val modalCardSurface: Color,
    val modalBorder: Color,
    val modalScrim: Color,
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
        modalSurface = OledCardSurface,
        modalCardSurface = OledSurfaceElevated,
        modalBorder = OledDivider,
        modalScrim = LiquidGlassModalScrim,
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
            modalSurface = LightSurfaceCard,
            modalCardSurface = LightSurfaceSecondary,
            modalBorder = LightDivider,
            modalScrim = LiquidGlassModalScrim,
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
            modalSurface = OledCardSurface,
            modalCardSurface = OledSurfaceElevated,
            modalBorder = OledDivider,
            modalScrim = LiquidGlassModalScrim,
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
            modalSurface = LiquidGlassModalSurface,
            modalCardSurface = LiquidGlassModalCardSurface,
            modalBorder = LiquidGlassModalBorder,
            modalScrim = LiquidGlassModalScrim,
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

object ArtworkEffectsCache {
    private const val MAX_CACHE_ENTRIES = 24
    private const val TARGET_BLUR_MAX_PX = 128
    private const val BLUR_RADIUS = 16
    private const val MAX_REMOTE_IMAGE_BYTES = 2 * 1024 * 1024

    private val cache = LruCache<String, CachedArtworkVisuals>(MAX_CACHE_ENTRIES)

    suspend fun getOrCompute(
        context: Context,
        uriString: String?,
        httpClient: OkHttpClient? = null
    ): CachedArtworkVisuals {
        if (uriString.isNullOrBlank()) return CachedArtworkVisuals()
        synchronized(cache) { cache.get(uriString) }?.let { return it }

        val decoded = withContext(Dispatchers.IO) {
            decodeBoundedBitmap(context, uriString, httpClient, TARGET_BLUR_MAX_PX)
        } ?: return CachedArtworkVisuals()

        val visuals = withContext(Dispatchers.Default) {
            buildVisualsFromBitmap(decoded)
        }
        synchronized(cache) {
            cache.put(uriString, visuals)
        }
        return visuals
    }

    suspend fun getDefaultBlurredArtwork(context: Context, @DrawableRes resId: Int): ImageBitmap? {
        val key = "__res_blur_$resId"
        synchronized(cache) { cache.get(key) }?.blurredBitmap?.let { return it }

        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeResource(context.resources, resId, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, TARGET_BLUR_MAX_PX)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeResource(context.resources, resId, opts)
            }.getOrNull()
        } ?: return null

        val visuals = withContext(Dispatchers.Default) {
            buildVisualsFromBitmap(decoded)
        }
        synchronized(cache) {
            cache.put(key, visuals)
        }
        return visuals.blurredBitmap
    }

    internal fun buildVisualsFromBitmap(source: Bitmap): CachedArtworkVisuals {
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        val scale = minOf(1f, TARGET_BLUR_MAX_PX.toFloat() / max(w, h).toFloat())
        val targetW = (w * scale).roundToInt().coerceAtLeast(1)
        val targetH = (h * scale).roundToInt().coerceAtLeast(1)

        val resized = if (targetW != w || targetH != h) {
            Bitmap.createScaledBitmap(source, targetW, targetH, true).also {
                if (source != it && !source.isRecycled) source.recycle()
            }
        } else {
            source
        }

        val mutableBitmap = if (resized.config != Bitmap.Config.ARGB_8888 || !resized.isMutable) {
            resized.copy(Bitmap.Config.ARGB_8888, true).also {
                if (resized != it && !resized.isRecycled) resized.recycle()
            }
        } else {
            resized
        }

        val palette = Palette.from(mutableBitmap).maximumColorCount(16).generate()
        val dominant = palette.getDarkMutedColor(
            palette.getDominantColor(0xFF280814.toInt())
        )
        val vibrant = palette.getVibrantColor(
            palette.getLightVibrantColor(AppleCrimson.toArgb())
        )
        val muted = palette.getMutedColor(0xFF161322.toInt())

        stackBlurInPlace(mutableBitmap, BLUR_RADIUS)

        return CachedArtworkVisuals(
            palette = LiquidGlassPalette(
                dominant = Color(dominant),
                vibrant = Color(vibrant),
                muted = Color(muted),
                accent = Color(vibrant)
            ),
            blurredBitmap = mutableBitmap.asImageBitmap()
        )
    }

    private fun decodeBoundedBitmap(
        context: Context,
        uriString: String,
        httpClient: OkHttpClient?,
        maxEdgePx: Int
    ): Bitmap? {
        return runCatching {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "file", null -> {
                    val path = uri.path ?: uriString
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeFile(path, opts)
                }
                "content", "android.resource" -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                }
                "http", "https" -> {
                    val client = httpClient ?: return@runCatching null
                    val req = Request.Builder().url(uriString).build()
                    val bytes = client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val stream = resp.body?.byteStream() ?: return@use null
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val read = stream.read(buf)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_REMOTE_IMAGE_BYTES) return@use null
                            out.write(buf, 0, read)
                        }
                        out.toByteArray()
                    } ?: return@runCatching null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
        var inSampleSize = 1
        if (width <= 0 || height <= 0) return 1
        while ((width / (inSampleSize * 2)) >= maxEdgePx && (height / (inSampleSize * 2)) >= maxEdgePx) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun stackBlurInPlace(bitmap: Bitmap, radius: Int) {
        if (radius < 1) return
        val w = bitmap.width
        val h = bitmap.height
        if (w < 2 || h < 2) return
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in dv.indices) {
            dv[i] = i / divsum
        }

        var yi = 0
        var yw = 0
        val stack = Array(div) { IntArray(3) }
        val r1 = radius + 1

        for (y in 0 until h) {
            var rinsum = 0
            var ginsum = 0
            var binsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var rsum = 0
            var gsum = 0
            var bsum = 0

            for (i in -radius..radius) {
                val p = pix[yi + minOf(wm, maxOf(i, 0))]
                val sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                val rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            var stackpointer = radius
            for (x in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                val stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                if (y == 0) {
                    vmin[x] = minOf(x + radius + 1, wm)
                }
                val p = pix[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                stackpointer = (stackpointer + 1) % div
                val sirNext = stack[stackpointer % div]
                routsum += sirNext[0]
                goutsum += sirNext[1]
                boutsum += sirNext[2]
                rinsum -= sirNext[0]
                ginsum -= sirNext[1]
                binsum -= sirNext[2]
                yi++
            }
            yw += w
        }

        for (x in 0 until w) {
            var rinsum = 0
            var ginsum = 0
            var binsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var yp = -radius * w
            for (i in -radius..radius) {
                val yiLocal = maxOf(0, yp) + x
                val sir = stack[i + radius]
                sir[0] = r[yiLocal]
                sir[1] = g[yiLocal]
                sir[2] = b[yiLocal]
                val rbs = r1 - abs(i)
                rsum += r[yiLocal] * rbs
                gsum += g[yiLocal] * rbs
                bsum += b[yiLocal] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
            }
            yi = x
            var stackpointer = radius
            for (y in 0 until h) {
                pix[yi] = (0xff shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                val stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                if (x == 0) {
                    vmin[y] = minOf(y + r1, hm) * w
                }
                val p = x + vmin[y]
                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                stackpointer = (stackpointer + 1) % div
                val sirNext = stack[stackpointer]
                routsum += sirNext[0]
                goutsum += sirNext[1]
                boutsum += sirNext[2]
                rinsum -= sirNext[0]
                ginsum -= sirNext[1]
                binsum -= sirNext[2]
                yi += w
            }
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }
}

object PaletteExtractor {
    suspend fun extractFromUri(
        context: Context,
        uriString: String?,
        httpClient: OkHttpClient? = null
    ): LiquidGlassPalette {
        return ArtworkEffectsCache.getOrCompute(context, uriString, httpClient).palette
    }
}
