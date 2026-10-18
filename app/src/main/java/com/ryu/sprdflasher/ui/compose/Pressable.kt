package com.ryu.sprdflasher.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * 可点击修饰符，暴露 interactionSource 给缩放/高光动画使用。
 */
internal fun Modifier.pressable(
    interaction: MutableInteractionSource,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = this.composed {
    clickable(
        interactionSource = interaction,
        indication = ripple(),
        enabled = enabled,
        onClick = onClick
    )
}
