package com.laughing.musicplayer.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

object AppleMotion {
    const val NAV_DURATION_MS = 250
    const val MEDIA_DURATION_MS = 260
    const val ICON_DURATION_MS = 200
    const val PRESS_SCALE = 0.97f

    val EaseOut: CubicBezierEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val StandardEase: CubicBezierEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    fun <T> navSpec(reducedMotion: Boolean): FiniteAnimationSpec<T> =
        tween(
            durationMillis = if (reducedMotion) 0 else NAV_DURATION_MS,
            easing = EaseOut
        )

    fun <T> mediaSpec(reducedMotion: Boolean): FiniteAnimationSpec<T> =
        tween(
            durationMillis = if (reducedMotion) 0 else MEDIA_DURATION_MS,
            easing = EaseOut
        )
}

@Composable
fun rememberReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            scale <= 0f
        }.getOrDefault(false)
    }
}

fun Modifier.applePressScale(
    interactionSource: MutableInteractionSource,
    reducedMotion: Boolean = false,
    targetScale: Float = AppleMotion.PRESS_SCALE
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (!reducedMotion && isPressed) targetScale else 1f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else 130,
            easing = AppleMotion.EaseOut
        ),
        label = "apple_press_scale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
