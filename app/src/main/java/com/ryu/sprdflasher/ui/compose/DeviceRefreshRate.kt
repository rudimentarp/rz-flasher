package com.ryu.sprdflasher.ui.compose

import android.content.Context
import android.view.Display
import android.view.WindowManager
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt

/**
 * DeviceRefreshRate —— 设备刷新率感知。
 *
 * <h3>为什么需要</h3>
 * 动画时长若写死，在高刷屏上的手感会和 60Hz 明显不同：
 *  - 60Hz：每帧 16.7ms，动画看起来"重"
 *  - 90Hz：每帧 11.1ms
 *  - 120Hz：每帧 8.3ms，同样的补间会显得更快、更锐
 *  - 144Hz：每帧 6.9ms，帧预算最紧，动画参数过度就会掉帧
 *
 * 所以这里按刷新率分组，给出合适的补间时长与弹簧刚度，
 * 保证「同一手势在 60/90/120/144Hz 上手感一致」。
 *
 * 同时提供 [frameBudgetMs] 供需要分帧的任务参考。
 */
@Stable
class DeviceRefreshRate(
    /** 检测到的刷新率，例如 60 / 90 / 120 / 144 */
    val hz: Int,
    /** 每帧预算（毫秒） */
    val frameBudgetMs: Float
) {

    /** 是否高刷屏（>=90Hz） */
    val isHighRefresh: Boolean get() = hz >= 90

    /** 是否超高刷（>=120Hz） */
    val isUltraRefresh: Boolean get() = hz >= 120

    // ------------------------------------------------------------------
    // 时长换算
    // ------------------------------------------------------------------

    /**
     * 把「以 60Hz 为基准」的时长换算成当前刷新率下的等效时长。
     *
     * 高刷屏上同样一段物理运动可以用更短时间表达而依然顺滑，
     * 反之低刷屏需要略长，避免掉帧导致的卡顿感。
     */
    fun scaleDuration(base60Ms: Int): Int {
        return when {
            hz >= 144 -> (base60Ms * 0.78f).roundToInt()
            hz >= 120 -> (base60Ms * 0.84f).roundToInt()
            hz >= 90 -> (base60Ms * 0.92f).roundToInt()
            else -> base60Ms
        }
    }

    /** 按压反馈的短补间（基准 90ms） */
    val pressDownMs: Int get() = scaleDuration(90)

    /** 文字/颜色过渡（基准 180ms） */
    val fadeMs: Int get() = scaleDuration(180)

    /** Tab 滑动落位（基准时长，用于非弹簧场景） */
    val tabSettleMs: Int get() = scaleDuration(260)

    // ------------------------------------------------------------------
    // 弹簧参数
    // ------------------------------------------------------------------

    /**
     * 按压回弹弹簧。
     *
     * 高刷屏每帧间隔更短，同样的 stiffness 在 144Hz 上会"跑得过快"，
     * 所以高刷时适当降低刚度、提高阻尼，让观感与 60Hz 一致。
     */
    fun pressSpring(): SpringSpec<Float> {
        return when {
            hz >= 144 -> SpringSpec(dampingRatio = 0.52f, stiffness = 420f)
            hz >= 120 -> SpringSpec(dampingRatio = 0.48f, stiffness = 470f)
            hz >= 90 -> SpringSpec(dampingRatio = 0.42f, stiffness = 540f)
            else -> SpringSpec(dampingRatio = 0.32f, stiffness = 620f)
        }
    }

    /** Tab 滑块落位弹簧：要"稳"不要弹得太野 */
    fun tabSpring(): SpringSpec<Float> {
        return when {
            hz >= 144 -> SpringSpec(dampingRatio = 0.86f, stiffness = 340f)
            hz >= 120 -> SpringSpec(dampingRatio = 0.82f, stiffness = 380f)
            hz >= 90 -> SpringSpec(dampingRatio = 0.76f, stiffness = 430f)
            else -> SpringSpec(dampingRatio = 0.70f, stiffness = 480f)
        }
    }

    /** 位置补间：拖动松手后的吸附 */
    fun tabSnapSpec(): TweenSpec<Float> = TweenSpec(
        durationMillis = tabSettleMs,
        easing = ComposeEasing
    )

    override fun toString(): String = "DeviceRefreshRate(${hz}Hz, ${frameBudgetMs}ms/frame)"

    companion object {
        /** 吸附用缓动：先快后慢，落点干脆 */
        val ComposeEasing = androidx.compose.animation.core.FastOutSlowInEasing

        /** 从 View 推断刷新率 */
        fun fromView(view: android.view.View?): DeviceRefreshRate {
            val hz = detectFromView(view)
            return DeviceRefreshRate(hz, 1000f / hz)
        }
    }
}

private fun detectFromView(view: android.view.View?): Int {
    try {
        if (view != null && android.os.Build.VERSION.SDK_INT >= 30) {
            val v = view.display
            if (v != null) {
                val mode = v.mode
                val refresh = mode.refreshRate
                if (refresh > 0f) return normalizeHz(refresh)
            }
        }
    } catch (t: Throwable) {
        // 忽略，走兜底
    }
    return 60
}

private fun detectFromWindow(context: Context?): Int {
    try {
        val wm = context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val d: Display? = wm?.defaultDisplay
        if (d != null) {
            val r = d.refreshRate
            if (r > 0f) return normalizeHz(r)
        }
    } catch (t: Throwable) {
    }
    return 60
}

/** 把实测刷新率（可能是 59.94 / 119.88 这类）归一到标准档位 */
fun normalizeHz(raw: Float): Int {
    return when {
        raw >= 138f -> 144
        raw >= 115f -> 120
        raw >= 85f -> 90
        raw >= 55f -> 60
        else -> 60
    }
}

/** Compose 里取当前设备刷新率 */
@Composable
fun rememberDeviceRefreshRate(): DeviceRefreshRate {
    val view = LocalView.current
    val context = LocalContext.current
    return remember(view, context) {
        val fromView = detectFromView(view)
        val hz = if (fromView != 60) fromView else detectFromWindow(context)
        DeviceRefreshRate(hz, 1000f / hz)
    }
}
