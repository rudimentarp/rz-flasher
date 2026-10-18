package com.ryu.sprdflasher.ui.compose.catalog

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * InteractiveHighlight —— 照搬自官方 Catalog
 * （app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt）
 *
 * 按下时手指位置出现一团柔光，并跟随移动。用 AGSL 着色器画圆形径向渐变，
 * 不支持时退化为整块 25% 白色叠加。
 *
 * 这是官方"液态"手感最直接的来源之一：光跟着手指走。
 */
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero

    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader: RuntimeShader? =
        if (isRuntimeShaderSupported()) {
            RuntimeShader(
                """
                uniform float2 size;
                layout(color) uniform half4 color;
                uniform float radius;
                uniform float2 position;
                half4 main(float2 coord) {
                    float dist = distance(coord, position);
                    float intensity = smoothstep(radius, radius * 0.5, dist);
                    return color * intensity;
                }
                """
            )
        } else null

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) {
            if (shader != null) {
                drawRect(
                    Color.White.copy(0.08f * progress),
                    blendMode = BlendMode.Plus
                )
                shader.apply {
                    val pos = position(size, positionAnimation.value)
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(0.15f * progress))
                    setFloatUniform("radius", size.minDimension * 1.5f)
                    setFloatUniform(
                        "position",
                        pos.x.coerceIn(0f, size.width),
                        pos.y.coerceIn(0f, size.height)
                    )
                }
                drawRect(
                    ShaderBrush(shader.asComposeShader()),
                    blendMode = BlendMode.Plus
                )
            } else {
                drawRect(Color.White.copy(0.25f * progress), blendMode = BlendMode.Plus)
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            startPosition = down.position
            animationScope.launch {
                launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                launch { positionAnimation.snapTo(startPosition) }
            }
            var id = down.id
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == id }
                if (change == null || !change.pressed) {
                    animationScope.launch {
                        launch {
                            pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec)
                        }
                        launch {
                            positionAnimation.animateTo(startPosition, positionAnimationSpec)
                        }
                    }
                    break
                }
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }
    }
}
