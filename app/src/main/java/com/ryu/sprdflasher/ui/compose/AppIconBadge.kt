package com.ryu.sprdflasher.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ryu.sprdflasher.R

/**
 * AppIconBadge —— 顶栏左侧的应用图标。
 *
 * 直接使用工程里的 ic_launcher（动漫头像），裁成圆角显示，
 * 外面套一圈半透明白边，和玻璃风格协调。
 */
@Composable
fun AppIconBadge(
    modifier: Modifier = Modifier,
    size: Dp = 30.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(Color(0x33FFFFFF))
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_icon),
            contentDescription = "应用图标",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size - 4.dp)
                .clip(RoundedCornerShape(size / 3.6f))
                .background(Color.Transparent)
        )
    }
}
