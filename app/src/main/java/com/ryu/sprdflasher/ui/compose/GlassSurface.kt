package com.ryu.sprdflasher.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * GlassSurface —— 基于官方 io.github.kyant0:backdrop 2.0.1 的玻璃容器。
 *
 * 用到的官方效果（全部来自库里真实的 AGSL 着色器，不是模拟）：
 *  - [blur]  背板模糊
 *  - [lens]  透镜折射 + 色散
 *  - [Highlight] 动态弧形高光（angle 控制光照方向，可做成跟随手动的动画）
 *  - [Shadow] / [InnerShadow] 外投影与内阴影
 *
 * @param highlightAngle 高光角度（度）。传动画值就能做出「光在流动」的效果
 * @param highlightAlpha 高光强度
 * @param falloff 高光衰减
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    blurRadius: Dp = 12.dp,
    refractionHeight: Dp = 16.dp,
    refractionAmount: Dp = 18.dp,
    chromaticAberration: Boolean = true,
    depthEffect: Boolean = false,
    highlightAngle: Float = 45f,
    highlightAlpha: Float = 1f,
    falloff: Float = 1f,
    showHighlight: Boolean = true,
    showShadow: Boolean = true,
    showInnerShadow: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(blurRadius.toPx())
                    lens(
                        refractionHeight = refractionHeight.toPx(),
                        refractionAmount = refractionAmount.toPx(),
                        depthEffect = depthEffect,
                        chromaticAberration = chromaticAberration
                    )
                },
                highlight = if (showHighlight) {
                    {
                        Highlight(
                            width = 0.5.dp,
                            alpha = highlightAlpha,
                            style = HighlightStyle.Default(
                                angle = highlightAngle,
                                falloff = falloff
                            )
                        )
                    }
                } else null,
                shadow = if (showShadow) { { Shadow.Default } } else null,
                innerShadow = if (showInnerShadow) { { InnerShadow.Default } } else null
            ),
        content = content
    )
}
