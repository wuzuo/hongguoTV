// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 圆角裁剪：海报、封面图统一走这里。
 *
 * 用 outline 而不是叠加遮罩位图——不额外分配内存，焦点放大后依然锐利；
 * 图片本身的 `scaleType` 用 CENTER_CROP，圆角才看得出来。
 */
internal fun View.roundClip(radiusDp: Int = TvStyle.RADIUS_POSTER) {
    clipToOutline = true
    outlineProvider =
        object : android.view.ViewOutlineProvider() {
            override fun getOutline(target: View, outline: android.graphics.Outline) {
                outline.setRoundRect(
                    0,
                    0,
                    target.width,
                    target.height,
                    radiusDp * target.resources.displayMetrics.density,
                )
            }
        }
    addOnLayoutChangeListener { target, _, _, _, _, _, _, _, _ -> target.invalidateOutline() }
}

/**
 * 系统 AlertDialog 的新拟态外壳。
 *
 * 系统弹窗的窗口背景是一块纯色，跟新拟态材质对不上，因此统一在这里换成柔光底板：
 * 圆角 [TvStyle.RADIUS_DIALOG]、四周留出柔光带，标题 / 正文 / 按钮文字改用主题色。
 * 弹窗内部控件（列表项、复选框）仍是系统实现，这里只保证外壳与文字，不再逐个重写。
 */
internal fun decorateDialog(dialog: android.app.Dialog) {
    val window = dialog.window ?: return
    window.setBackgroundDrawable(
        TvStyle.soft(
            dialog.context,
            TvStyle.surfaceHigh,
            TvStyle.RADIUS_DIALOG,
            TvStyle.SOFT_BAND_LARGE,
            SoftStyle.RAISED,
            depth = 1.1f,
        )
    )
    val band = (TvStyle.SOFT_BAND_LARGE * dialog.context.resources.displayMetrics.density).toInt()
    window.decorView.setPadding(band, band, band, band)
    dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(TvStyle.textDim)
    dialog.context.resources
        .getIdentifier("alertTitle", "id", "android")
        .takeIf { it != 0 }
        ?.let { dialog.findViewById<android.widget.TextView>(it) }
        ?.apply {
            setTextColor(TvStyle.textStrong)
            setPadding(0, 0, 0, (6 * dialog.context.resources.displayMetrics.density).toInt())
        }
    (dialog as? android.app.AlertDialog)?.let { alert ->
        listOf(
                android.app.AlertDialog.BUTTON_POSITIVE,
                android.app.AlertDialog.BUTTON_NEGATIVE,
                android.app.AlertDialog.BUTTON_NEUTRAL,
            )
            .forEach { which ->
                runCatching { alert.getButton(which)?.setTextColor(TvStyle.accent) }
            }
    }
}

/**
 * 电视端设计令牌：新拟态（Neumorphism / Soft UI）。
 *
 * 全站只有一块材质，底色 / 凸起 / 凹陷是同色相的三档明度，层次由柔光表达：
 * 右下深投影 + 左上浅高光（见 [SoftDrawable]）。状态只有三个台阶：
 *
 * - 常态 = 凸起面 [surface]；
 * - 选中 = 按进去 [well]（开关、筛选项、当前页）；
 * - 焦点 = 被打亮的 [lit] 亮面板 + [onLit] 近黑字 + [litGlow] 光晕；
 * - 主操作 = 常亮的亮面板 [lit]。
 *
 * 强调色只做「线与小色块」：进度条、名次星标。选框不再带颜色。
 * 播放界面固定用暖白 + 黑压暗层，不跟主题走（它压在视频画面上）。
 */
internal object TvStyle {
    /** 当前主题；由 MainActivity.onCreate 按本机偏好覆盖，之后只经 [applyTheme] 改动。 */
    internal var theme: TvTheme = TvTheme.fallback
        private set

    /** 柔光缓存 key 的一部分：位图里烤着当前主题的投影色。 */
    internal val themeId: Int get() = theme.ordinal

    /** 切换主题：几何量（圆角、柔光带、按钮档位）完全不变。 */
    internal fun applyTheme(value: TvTheme) {
        if (theme == value) return
        theme = value
        Soft.trim()
    }

    // —— 材质 ——

    val background: Int get() = theme.base
    val surface: Int get() = theme.surface

    /** 抬升面：焦点容器、设置分组、抬一档的选中态。 */
    val surfaceHigh: Int get() = theme.surfaceHigh

    /** 凹陷面：搜索框、海报井、按进去的选中态。 */
    val well: Int get() = theme.well

    /** 更深的凹槽：禁用态、进度条轨道。 */
    val wellDeep: Int get() = theme.wellDeep

    val selectedWell: Int get() = theme.well

    /** 焦点面：材质里最亮的一档。 */
    val lit: Int get() = theme.lit
    val onLit: Int get() = theme.onLit
    val litGlow: Int get() = theme.litGlow

    val shadowDark: Int get() = theme.shadowDark
    val shadowLight: Int get() = theme.shadowLight
    val innerDark: Int get() = theme.innerDark
    val innerLight: Int get() = theme.innerLight

    /** 必须画一条线的地方（分组分隔线）。 */
    val separator: Int get() = theme.separator

    // —— 文字与强调 ——

    val accent: Int get() = theme.accent
    val tips: Int get() = theme.tips

    val text: Int get() = theme.text
    val textStrong: Int get() = theme.textStrong
    val textDim: Int get() = theme.textDim
    val muted: Int get() = theme.muted

    // —— 播放界面：不跟主题走 ——

    val playerText = Color.rgb(0xF4, 0xF3, 0xF1)
    val playerDim = 0xA6F4F3F1.toInt()
    val playerLine = 0x2EF4F3F1.toInt()
    val playerAccent = Color.rgb(0xF2, 0xC0, 0x8A)
    val overlay = 0xBF000000.toInt()
    val track = 0x99000000.toInt()

    // —— 比例与尺寸 ——

    const val POSTER_ASPECT = .7f

    const val RADIUS_POSTER = 12
    const val RADIUS_CARD = 14
    const val RADIUS_PILL = 26
    const val RADIUS_DIALOG = 24

    const val SOFT_BAND = 6
    const val SOFT_BAND_LARGE = 10

    const val FOCUS_SCALE = 1.05f
    const val DURATION = 180L

    const val BUTTON_COMPACT = 40
    const val BUTTON_NORMAL = 48
    const val BUTTON_HERO = 56
    const val BUTTON_PAD_COMPACT = 18
    const val BUTTON_PAD_HERO = 34
    const val BUTTON_TEXT_NORMAL = 16f
    const val BUTTON_TEXT_HERO = 17f
    const val BUTTON_MIN_WIDTH = 86

    const val GAP_INLINE = 10
    const val GAP_TIGHT = 6
    const val GAP_BLOCK = 14
    const val GAP_GROUP = 22

    /** 低内存设备在 onCreate 中置 false，焦点切换立即生效。 */
    var animate = true

    // —— 形状工厂 ——

    /** 新拟态底板：凸起 / 凹陷。所有控件的背景都应该走这里。 */
    internal fun soft(
        context: Context,
        fill: Int = surface,
        radius: Int = RADIUS_CARD,
        band: Int = SOFT_BAND,
        style: SoftStyle = SoftStyle.RAISED,
        glow: Int = Color.TRANSPARENT,
        depth: Float = 1f,
    ) = SoftDrawable(
        fill = fill,
        radiusPx = radius * context.resources.displayMetrics.density,
        bandPx = band * context.resources.displayMetrics.density,
        style = style,
        glowColor = glow,
        depth = depth,
    )

    /** 凹陷槽：搜索框、海报井、进度轨道。 */
    internal fun well(
        context: Context,
        fill: Int = well,
        radius: Int = RADIUS_CARD,
        band: Int = SOFT_BAND,
        depth: Float = 1f,
    ) = soft(context, fill, radius, band, SoftStyle.INSET, Color.TRANSPARENT, depth)

    /** 纯色圆角底，保留给进度条分段、角标压暗层这类不需要柔光的小色块。 */
    fun shape(
        context: Context,
        color: Int,
        border: Int = Color.TRANSPARENT,
        radius: Int = RADIUS_POSTER,
        stroke: Int = 1,
    ) = GradientDrawable().apply {
        val density = context.resources.displayMetrics.density
        setColor(color)
        cornerRadius = radius * density
        if (stroke > 0) setStroke((stroke * density).toInt(), border)
    }

    /**
     * 卡片底板：焦点 = 抬一档 + 更深的柔光 + 光晕。
     *
     * 卡片里是图片和文字，底板不能换成 [lit] 亮面——深色主题下亮面接近纯白，
     * 卡里的浅色剧名会直接消失。焦点因此由「抬起来 + 光晕 + 剧名转亮」表达。
     */
    fun card(context: Context, focused: Boolean) =
        if (focused) {
            soft(
                context, surfaceHigh, RADIUS_CARD, SOFT_BAND, SoftStyle.RAISED,
                glow = litGlow, depth = 1.5f,
            )
        } else {
            soft(context, surface, RADIUS_CARD, SOFT_BAND, SoftStyle.RAISED)
        }

    /**
     * 卡片焦点描边：盖在内容之上的一圈强调色细线（`View.foreground`，API 23 起可用）。
     *
     * 光晕在客厅距离上不够醒目，海报又占掉卡片大部分面积，因此再补一圈线：
     * 底板只抬一档，这一圈线负责让人一眼看出遥控器停在哪一部剧上。
     */
    fun focusRing(context: Context, radius: Int = RADIUS_CARD, width: Int = 2): Drawable =
        GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            val density = context.resources.displayMetrics.density
            cornerRadius = radius * density
            setStroke((width * density).toInt(), accent)
        }

    /** 卡片焦点：底板 + 描边 + 剧名转亮，三处一起变，远看也能分清。 */
    fun cardFocus(view: View, focused: Boolean, radius: Int = RADIUS_CARD) {
        view.background = card(view.context, focused)
        view.foreground = if (focused) focusRing(view.context, radius) else null
    }

    /** 焦点反馈：只缩放，不位移（位移会打乱行内对齐）。 */
    fun focus(view: View, focused: Boolean, scale: Float = FOCUS_SCALE, duration: Long = DURATION) {
        view.animate().cancel()
        view.animate()
            .scaleX(if (focused) scale else 1f)
            .scaleY(if (focused) scale else 1f)
            .setDuration(if (animate) duration else 0L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 播放器底部控制面板的三段渐变。 */
    fun panel(
        top: Int = Color.TRANSPARENT,
        middle: Int = 0xA6000000.toInt(),
        bottom: Int = 0xF0000000.toInt(),
    ) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(top, middle, bottom),
    )
}
