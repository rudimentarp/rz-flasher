package com.ryu.sprdflasher.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * SlidingGlassTabs —— 连体滑块式底部导航（修复回退不到第一个 Tab）。
 *
 * <h3>唯一的数据流向（关键）</h3>
 * 之前点击时会同时触发两处动画：手势层自己 launch 一次 animateTo，
 * 同时 selectedIndex 变化又让 LaunchedEffect 再 animateTo 一次。
 * 两个协程抢同一个 Animatable，后发起的取消前一个，滑块就停在中间 ——
 * 表现为「切回第一个 Tab 时滑块没回到最左」。
 *
 * 现在规定：
 *  - **点击只能调用 onSelect(idx)**，不自己动滑块
 *  - **滑块位置只由 LaunchedEffect(selectedIndex) 驱动**
 *  - 拖动是唯一例外：拖动期间 LaunchedEffect 会让路，松手后回到它接管
 */
@Composable
fun SlidingGlassTabs(
    backdrop: Backdrop,
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
    padding: Dp = 0.dp
) {
    if (titles.isEmpty()) return

    val density = LocalDensity.current
    val rate = rememberDeviceRefreshRate()
    val scope = rememberCoroutineScope()
    val count = titles.size

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val padPx = with(density) { padding.toPx() }
        val totalPx = with(density) { maxWidth.toPx() }
        val touchSlop = with(density) { 8.dp.toPx() }

        // 格子划分：[pad, W-pad] 等分
        val cellPx = (totalPx - padPx * 2) / count
        fun cellLeft(i: Int) = padPx + cellPx * i

        // 滑块左边缘：唯一事实来源
        val thumbX = remember { Animatable(cellLeft(0)) }

        // 拖动状态（拖动时 LaunchedEffect 不抢）。必须是 Compose 状态，
        // 否则 LaunchedEffect 读不到它的变化，重启后仍会抢动画。
        var dragging by remember { mutableStateOf(false) }

        // 唯一的定位入口
        LaunchedEffect(selectedIndex, cellPx, dragging) {
            if (!dragging) {
                thumbX.animateTo(cellLeft(selectedIndex), rate.tabSpring())
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {

            // ===== 1. 玻璃底栏 =====
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(height / 2) },
                        effects = {
                            blur(14.dp.toPx())
                            lens(
                                refractionHeight = 11.dp.toPx(),
                                refractionAmount = 10.dp.toPx(),
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            Highlight(
                                width = 0.6.dp,
                                alpha = 0.95f,
                                style = HighlightStyle.Default(angle = 45f)
                            )
                        },
                        shadow = { Shadow.Default }
                    )
            )

            // ===== 2. 滑块 =====
            // 滑块：比格子窄，形成"浮起的胶囊"，这是官方 Catalog 的做法
            val thumbInsetPx = cellPx * 0.09f
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset((thumbX.value + thumbInsetPx).roundToInt(), 0)
                    }
                    .padding(vertical = 1.dp)
                    .width(with(density) { (cellPx - thumbInsetPx * 2).toDp() })
                    .height(height - 2.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape((height - 2.dp) / 2) },
                        effects = {
                            blur(10.dp.toPx())
                            lens(
                                refractionHeight = 10.dp.toPx(),
                                refractionAmount = 15.dp.toPx(),
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            Highlight(
                                width = 0.5.dp,
                                alpha = 1.2f,
                                style = HighlightStyle.Default(angle = 45f)
                            )
                        },
                        shadow = null
                    )
            )

            // ===== 3. 文字 =====
            titles.forEachIndexed { index, title ->
                val isSel = index == selectedIndex
                val t by animateFloatAsState(
                    targetValue = if (isSel) 1f else 0f,
                    animationSpec = tween(rate.fadeMs, easing = FastOutSlowInEasing),
                    label = "tabText$index"
                )
                Box(
                    modifier = Modifier
                        .offset { IntOffset(cellLeft(index).roundToInt(), 0) }
                        .width(with(density) { cellPx.toDp() })
                        .height(height),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        color = lerpColor(Color(0xFF3B4A5F), Color(0xFF0B1220), t),
                        fontSize = 13.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 0.2.sp
                    )
                }
            }

            // ===== 4. 手势 =====
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(count, cellPx, padPx, touchSlop) {

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalDx = 0f
                            var isDrag = false

                            fun indexAt(x: Float): Int {
                                val raw = ((x - padPx) / cellPx).toInt()
                                return raw.coerceIn(0, count - 1)
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break

                                if (!change.pressed) {
                                    if (!isDrag) {
                                        // 点击：只切页，不动滑块
                                        val idx = indexAt(down.position.x)
                                        if (idx != selectedIndex) {
                                            onSelect(idx)
                                        }
                                    } else {
                                        // 拖动结束：吸附后切页
                                        dragging = false
                                        val target = ((thumbX.value - padPx) / cellPx)
                                            .roundToInt().coerceIn(0, count - 1)
                                        scope.launch {
                                            thumbX.animateTo(
                                                cellLeft(target), rate.tabSpring()
                                            )
                                        }
                                        if (target != selectedIndex) onSelect(target)
                                    }
                                    change.consume()
                                    break
                                }

                                val dx = change.positionChange().x
                                totalDx += dx

                                if (!isDrag && abs(totalDx) > touchSlop) {
                                    isDrag = true
                                    dragging = true
                                    scope.launch { thumbX.stop() }
                                }

                                if (isDrag) {
                                    change.consume()
                                    scope.launch {
                                        thumbX.snapTo(
                                            (thumbX.value + dx)
                                                .coerceIn(cellLeft(0), cellLeft(count - 1))
                                        )
                                    }
                                }
                            }
                        }
                    }
            )
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)
