package com.ryu.sprdflasher.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.ryu.sprdflasher.R

/**
 * 背景：壁纸 + 全局高斯模糊。
 *
 * 直接用 GPU 的 RenderEffect 做真正的实时模糊 —— 画质最好，
 * 代价是滚动时每一帧都要重算一次全屏模糊（会略有掉帧）。
 *
 * 之前试过「降采样再放大」的省算力方案，视觉上会变成马赛克/色块，
 * 已经回退，这里保持原始的高质量渲染。
 *
 * @param blurRadius 模糊半径（px）。0 = 清晰
 */
@Composable
fun WallpaperBackground(
    modifier: Modifier = Modifier,
    blurRadius: Float = 12f,
    dim: Color = Color(0x33000000)
) {
    Box(modifier = modifier.fillMaxSize()) {
        // 壁纸本体：铺满裁剪 + 实时模糊
        Image(
            painter = painterResource(id = R.drawable.bg_wallpaper),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // RenderEffect 需要先离屏渲染
                    compositingStrategy = CompositingStrategy.Offscreen
                    renderEffect = if (blurRadius > 0f) {
                        android.graphics.RenderEffect.createBlurEffect(
                            blurRadius, blurRadius,
                            android.graphics.Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    } else null
                }
        )

        // 压暗：保证浅色文字和玻璃层级清晰
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(dim)
        )

        // 顶部微亮、底部微暗，让玻璃边缘的高光更明显
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color(0x14FFFFFF),
                    0.35f to Color(0x00FFFFFF),
                    1f to Color(0x22000000)
                )
            )
        }
    }
}

/**
 * 纯渐变色斑背景（备用）。
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        drawRect(
            brush = Brush.linearGradient(
                0f to Color(0xFF1A2233),
                0.5f to Color(0xFF243044),
                1f to Color(0xFF151B28)
            )
        )

        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0x6634D3C8),
                1f to Color(0x0034D3C8),
                center = Offset(w * 0.22f, h * 0.16f),
                radius = w * 0.75f
            ),
            radius = w * 0.75f,
            center = Offset(w * 0.22f, h * 0.16f)
        )

        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0x5C2F6FE0),
                1f to Color(0x002F6FE0),
                center = Offset(w * 0.92f, h * 0.42f),
                radius = w * 0.7f
            ),
            radius = w * 0.7f,
            center = Offset(w * 0.92f, h * 0.42f)
        )
    }
}

/** 设计系统色板 */
object GlassColors {
    val TextPrimary = Color(0xFF0B1220)
    val TextSecondary = Color(0xFF3B4A5F)
    val TextOnDark = Color(0xFFE8F0F8)
    val Primary = Color(0xFF3B82F6)
    val PrimaryDeep = Color(0xFF1D4ED8)
    val Success = Color(0xFF16A34A)
    val Danger = Color(0xFFDC2626)
    val Warning = Color(0xFFD97706)
}
