// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.DanmakuStyle

/**
 * 弹幕字号 / 透明度的调节条（播放页设置面板使用）。
 *
 * 外观沿用全站紧凑档控件：常态凸起面、选中按进去、焦点打亮。左边是名称，右边是读数，
 * 中间不画轨道——电视上真正要看的是「现在是多少」，一条细轨道在客厅距离上读不出来。
 *
 * **为什么不是 SeekBar**：SeekBar 会自己抢焦点、自己吃左右键，和四向焦点锁定打架，
 * 按到边缘会跳到别的控件上。这里左右键由控件自己逐档调，调到头就让出焦点，
 * 上下键仍然交给焦点系统在功能行之间移动。
 *
 * 值一律经过 [normalize] 归一后再回调，面板上的读数、写盘的值、下次读回来的值
 * 是同一个浮点值，不会出现「显示 130% 但和 130% 不相等」的抖档。
 */
internal class DanmakuSlider(
    context: Context,
    private val name: String,
    private val min: Float,
    private val max: Float,
    private val step: Float,
    initial: Float,
    private val normalize: (Float) -> Float,
    private val onChange: (Float) -> Unit,
) : LinearLayout(context) {
    private val title =
        TextView(context).apply {
            text = name
            textSize = 16f
            includeFontPadding = false
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }
    private val reading =
        TextView(context).apply {
            textSize = 16f
            includeFontPadding = false
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }

    var value: Float = normalize(initial)
        private set

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        id = View.generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        minimumHeight = dp(TvStyle.BUTTON_COMPACT + TvStyle.SOFT_BAND * 2)
        setPadding(
            dp(16 + TvStyle.SOFT_BAND),
            dp(TvStyle.SOFT_BAND),
            dp(16 + TvStyle.SOFT_BAND),
            dp(TvStyle.SOFT_BAND),
        )
        addView(title, LayoutParams(0, -2, 1f))
        addView(reading, LayoutParams(-2, -2))
        paint(false)
        setOnFocusChangeListener { view, focused ->
            paint(focused)
            TvStyle.focus(view, focused)
        }
        sync()
    }

    private fun paint(focused: Boolean) {
        val ctx = getContext()
        background =
            if (focused) {
                TvStyle.soft(
                    ctx,
                    TvStyle.lit,
                    TvStyle.RADIUS_PILL,
                    TvStyle.SOFT_BAND,
                    SoftStyle.RAISED,
                    glow = TvStyle.litGlow,
                    depth = 1.2f,
                )
            } else {
                TvStyle.soft(
                    ctx,
                    TvStyle.surface,
                    TvStyle.RADIUS_PILL,
                    TvStyle.SOFT_BAND,
                    SoftStyle.RAISED,
                )
            }
        title.setTextColor(if (focused) TvStyle.onLit else TvStyle.text)
        reading.setTextColor(if (focused) TvStyle.onLit else TvStyle.accent)
    }

    private fun sync() {
        reading.text = DanmakuStyle.percent(value)
        contentDescription = "$name ${DanmakuStyle.percent(value)}"
    }

    /** 调一档：到端返回 false，让焦点系统把这一下左右键交给相邻控件。 */
    private fun move(direction: Int): Boolean {
        val next = normalize(value + direction * step)
        if (next == value) return false
        value = next
        sync()
        onChange(next)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(1)
            else -> super.onKeyDown(keyCode, event)
        }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        isFocusable = enabled
        alpha = if (enabled) 1f else .45f
    }

    companion object {
        /** 字号滑条：0.6~2.0 倍，逐 0.1 档。 */
        fun scale(context: Context, value: Float, onChange: (Float) -> Unit) =
            DanmakuSlider(
                context,
                "字号",
                DanmakuStyle.SCALE_MIN,
                DanmakuStyle.SCALE_MAX,
                DanmakuStyle.SCALE_STEP,
                value,
                DanmakuStyle::scale,
                onChange,
            )

        /** 透明度滑条：20%~100%，逐 10% 档。 */
        fun alpha(context: Context, value: Float, onChange: (Float) -> Unit) =
            DanmakuSlider(
                context,
                "透明度",
                DanmakuStyle.ALPHA_MIN,
                DanmakuStyle.ALPHA_MAX,
                DanmakuStyle.ALPHA_STEP,
                value,
                DanmakuStyle::alpha,
                onChange,
            )
    }
}
