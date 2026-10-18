package com.ryu.sprdflasher.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.ryu.sprdflasher.ui.compose.catalog.InteractiveHighlight
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 全局玻璃性能开关。
 * ScrollOptimization 在滚动开始时把它置为 true，滚动停止后延时恢复 false：
 *  - 滚动中：按钮去掉 lens 着色器与 layerBlock 动态变换（静态折射视觉差异极小）
 *  - 停止后：恢复官方完整效果，包括按下跟手光晕
 * 这样长列表（工具页）滚动时的着色器批次大幅减少，且不改动任何布局与配色。
 */
object CheapGlass {
    // 用 Compose 快照状态而不是普通字段：滚动的某个瞬间恰好没有按钮重组，
    // 普通字段的变化就会被漏掉，导致滚动结束后廉价模式一直卡在开启/关闭状态。
    private var _enabled by androidx.compose.runtime.mutableStateOf(false)
    var enabled: Boolean
        get() = _enabled
        set(value) { _enabled = value }
}

/**
 * GlassButton —— 视觉与交互完全对齐官方 LiquidButton（catalog/components/LiquidButton.kt）。
 *
 * 官方原始参数：
 *  - effects: vibrancy() + blur(2dp) + lens(12dp, 24dp)
 *  - height 48dp，胶囊形（Capsule）
 *  - 按下时 tanh 跟手位移 + InteractiveHighlight 光晕跟随手指
 *
 * 保留原调用签名（backdrop/text/role/height），页面代码零改动。
 */
enum class GlassRole(val tint: Color, val textColor: Color) {
    Primary(Color(0xFF4F7CFF), Color.White),
    Success(Color(0xFF22C55E), Color.White),
    Danger(Color(0xFFEF4444), Color.White),
    Warning(Color(0xFFD97706), Color.White),
    Secondary(Color(0x30FFFFFF), Color(0xFF0B1220))
}

@Composable
fun GlassButton(
    backdrop: Backdrop,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: GlassRole = GlassRole.Secondary,
    height: Dp = 48.dp,
    cornerRadius: Dp = 16.dp,
    fontSize: Float = 14f,
    bold: Boolean = false,
    enabled: Boolean = true
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val cornerShape = remember(cornerRadius) { RoundedRectangle(cornerRadius) }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 高性能模式且未按下时，去掉 lens 与 layerBlock：静止按钮不需要每帧折射
    val cheap = CheapGlass.enabled && !pressed

    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .height(height)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { cornerShape },
                effects = {
                    vibrancy()
                    blur(2.dp.toPx())
                    if (!cheap) {
                        lens(12.dp.toPx(), 24.dp.toPx())
                    }
                },
                layerBlock = if (cheap) null else {
                    {
                        val width = size.width
                        val h = size.height
                        val progress = interactiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4.dp.toPx() / size.height, progress)
                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)
                        val maxDragScale = 4.dp.toPx() / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX = scale + maxDragScale *
                                abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                                (width / h).coerceAtMost(1f)
                        scaleY = scale + maxDragScale *
                                abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                                (h / width).coerceAtMost(1f)
                    }
                },
                onDrawSurface = {
                    // 角色色：官方用 BlendMode.Hue 上色，再叠一层半透明
                    if (role != GlassRole.Secondary) {
                        drawRect(role.tint, blendMode = BlendMode.Hue)
                        drawRect(role.tint.copy(alpha = 0.75f))
                    }
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .then(interactiveHighlight.modifier)
            .then(interactiveHighlight.gestureModifier)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = role.textColor,
            fontSize = fontSize.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/** 顶部状态胶囊（保留原组件，样式不变） */
@Composable
fun StatusPill(text: String, connected: Boolean, modifier: Modifier = Modifier) {
    val bg = if (connected) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
    val fg = if (connected) Color(0xFF2E7D32) else Color(0xFFB91C1C)
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .height(28.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
