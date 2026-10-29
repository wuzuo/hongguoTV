// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color

/**
 * 界面主题：十四套新拟态材质（设置 → 界面主题）。
 *
 * 一套材质只写三个色值：底色、左上高光、右下投影。其余档位全部按同一套规则派生，
 * 因此新增主题只需要在枚举里加一行，几何与组件代码不用改。
 *
 * | 派生 | 算法 |
 * | --- | --- |
 * | [surface] 凸起面 | 底色 → 高光 45% |
 * | [surfaceHigh] 抬升面 | 底色 → 高光 72% |
 * | [well] 凹陷面 | 底色 → 投影 45% |
 * | [wellDeep] 深凹槽 | 底色 → 投影 72% |
 * | [text] / [textStrong] / [textDim] / [muted] | 文字基色 100 / 84 / 70 / 62% |
 *
 * 电视在暗环境里观看，默认 [DEEP_DARK]；偏好保存在独立的 SharedPreferences 中，
 * 不参与手机备份合并（配色不应跟着观看记录在设备之间搬家）。
 */
internal enum class TvTheme(
    /** 设置里显示的名字。 */
    val label: String,
    /** 落盘用的稳定字符串：改显示名不会丢用户选择。 */
    val storedValue: String,
    /** 背景与元素底色。 */
    val base: Int,
    /** 左上高光，也是「被抬起来」的那一档材质。 */
    val light: Int,
    /** 右下投影，也是「压进去」的那一档材质。 */
    val dark: Int,
    /** 焦点面：整块材质里最亮的一档。 */
    val lit: Int,
    /** 亮面上的字。 */
    val onLit: Int,
    /** 文字基色：浅色主题是深墨，深色主题是暖白。 */
    val ink: Int,
    /** 强调色：只做线与小色块（进度条、名次星标），不画选框。 */
    val accent: Int,
    /** 提示色：评分星标、榜单前三。 */
    val tips: Int,
    /** 浅色主题还是深色主题：只影响柔光强度与凹陷内阴影。 */
    val isDark: Boolean,
) {
    SOFT_GRAY(
        "经典浅灰", "soft-gray",
        base = 0xFFE0E5EC.toInt(), light = 0xFFFFFFFF.toInt(), dark = 0xFFA3B1C6.toInt(),
        lit = 0xFFFFFFFF.toInt(), onLit = 0xFF2B3444.toInt(), ink = 0xFF39435A.toInt(),
        accent = 0xFF5F6E8C.toInt(), tips = 0xFFC8871F.toInt(), isDark = false,
    ),
    CREAMY_MILK(
        "优雅奶白", "creamy-milk",
        base = 0xFFF0F3F4.toInt(), light = 0xFFFFFFFF.toInt(), dark = 0xFFD1D9E6.toInt(),
        lit = 0xFFFFFFFF.toInt(), onLit = 0xFF3B4250.toInt(), ink = 0xFF3B4250.toInt(),
        accent = 0xFF8F7355.toInt(), tips = 0xFFC08A2A.toInt(), isDark = false,
    ),
    DEEP_DARK(
        "深邃暗调", "deep-dark",
        base = 0xFF2E3238.toInt(), light = 0xFF383D44.toInt(), dark = 0xFF24272C.toInt(),
        lit = 0xFFF0EAE0.toInt(), onLit = 0xFF1A1612.toInt(), ink = 0xFFF4F3F1.toInt(),
        accent = 0xFFDFB77C.toInt(), tips = 0xFFFFC24B.toInt(), isDark = true,
    ),
    COOL_MINT(
        "高级柔冷", "cool-mint",
        base = 0xFFDEE4E7.toInt(), light = 0xFFFFFFFF.toInt(), dark = 0xFFB8C4C7.toInt(),
        lit = 0xFFFFFFFF.toInt(), onLit = 0xFF2F3A40.toInt(), ink = 0xFF37424A.toInt(),
        accent = 0xFF4E7F79.toInt(), tips = 0xFFC08A2A.toInt(), isDark = false,
    ),
    PORCELAIN(
        "瓷白", "porcelain",
        base = 0xFFE8EBE7.toInt(), light = 0xFFFBFCFA.toInt(), dark = 0xFFC2C7C1.toInt(),
        lit = 0xFFFBFCFA.toInt(), onLit = 0xFF262C29.toInt(), ink = 0xFF343B37.toInt(),
        accent = 0xFF6E7A72.toInt(), tips = 0xFFB8862B.toInt(), isDark = false,
    ),
    MOONSTONE(
        "月岩灰", "moonstone",
        base = 0xFFDDE2E5.toInt(), light = 0xFFF8FAFB.toInt(), dark = 0xFFB7C0C5.toInt(),
        lit = 0xFFF8FAFB.toInt(), onLit = 0xFF242B31.toInt(), ink = 0xFF333A3F.toInt(),
        accent = 0xFF5C6A73.toInt(), tips = 0xFFB8862B.toInt(), isDark = false,
    ),
    IVORY_SAND(
        "象牙沙", "ivory-sand",
        base = 0xFFE9E4DA.toInt(), light = 0xFFFCFAF4.toInt(), dark = 0xFFC6BFB2.toInt(),
        lit = 0xFFFCFAF4.toInt(), onLit = 0xFF2C2820.toInt(), ink = 0xFF3B362D.toInt(),
        accent = 0xFF8A7A5E.toInt(), tips = 0xFFB07C1F.toInt(), isDark = false,
    ),
    SILVER_SAGE(
        "银鼠尾草", "silver-sage",
        base = 0xFFDCE3DE.toInt(), light = 0xFFF7FBF8.toInt(), dark = 0xFFB3BDB7.toInt(),
        lit = 0xFFF7FBF8.toInt(), onLit = 0xFF232A25.toInt(), ink = 0xFF333B36.toInt(),
        accent = 0xFF5E7A68.toInt(), tips = 0xFFA87C22.toInt(), isDark = false,
    ),
    BLUE_HAZE(
        "蓝雾", "blue-haze",
        base = 0xFFDCE4EA.toInt(), light = 0xFFF7FBFE.toInt(), dark = 0xFFB2BEC8.toInt(),
        lit = 0xFFF7FBFE.toInt(), onLit = 0xFF20272E.toInt(), ink = 0xFF2F3944.toInt(),
        accent = 0xFF4F6B87.toInt(), tips = 0xFFB8862B.toInt(), isDark = false,
    ),
    CHAMPAGNE_SILK(
        "香槟丝绸", "champagne-silk",
        base = 0xFFE8E1D5.toInt(), light = 0xFFFDF9F1.toInt(), dark = 0xFFC5BAA9.toInt(),
        lit = 0xFFFDF9F1.toInt(), onLit = 0xFF2E2818.toInt(), ink = 0xFF3B352A.toInt(),
        accent = 0xFF8A7551.toInt(), tips = 0xFFB07C1F.toInt(), isDark = false,
    ),
    DUSTY_MAUVE(
        "灰雾玫瑰", "dusty-mauve",
        base = 0xFFE4DEE1.toInt(), light = 0xFFFAF7F9.toInt(), dark = 0xFFBCB2B8.toInt(),
        lit = 0xFFFAF7F9.toInt(), onLit = 0xFF2A2329.toInt(), ink = 0xFF3B3339.toInt(),
        accent = 0xFF7D6273.toInt(), tips = 0xFFB8862B.toInt(), isDark = false,
    ),
    PALE_OLIVE(
        "苍岩橄榄", "pale-olive",
        base = 0xFFDFE1D8.toInt(), light = 0xFFF8F9F1.toInt(), dark = 0xFFB9BDAE.toInt(),
        lit = 0xFFF8F9F1.toInt(), onLit = 0xFF262A20.toInt(), ink = 0xFF373A31.toInt(),
        accent = 0xFF6B7050.toInt(), tips = 0xFFA87C22.toInt(), isDark = false,
    ),
    GRAPHITE(
        "石墨", "graphite",
        base = 0xFF34383D.toInt(), light = 0xFF41464D.toInt(), dark = 0xFF25282C.toInt(),
        lit = 0xFFEDEFF2.toInt(), onLit = 0xFF14181C.toInt(), ink = 0xFFF4F3F1.toInt(),
        accent = 0xFF9FBFD8.toInt(), tips = 0xFFFFC24B.toInt(), isDark = true,
    ),
    MIDNIGHT_BLUE(
        "午夜蓝", "midnight-blue",
        base = 0xFF30363F.toInt(), light = 0xFF3D4550.toInt(), dark = 0xFF242930.toInt(),
        lit = 0xFFEFF3F8.toInt(), onLit = 0xFF14181D.toInt(), ink = 0xFFF2F4F7.toInt(),
        accent = 0xFF86C7E8.toInt(), tips = 0xFFFFC24B.toInt(), isDark = true,
    );

    /** 凸起面：按钮、卡片、设置项的常态。 */
    val surface: Int get() = mix(base, light, .45f)

    /** 抬升面：焦点容器、设置分组、抬一档的选中态。 */
    val surfaceHigh: Int get() = mix(base, light, .72f)

    /** 凹陷面：搜索框、海报井，以及按进去的选中态。 */
    val well: Int get() = mix(base, dark, .45f)

    /** 更深的凹槽：禁用态、进度条轨道。 */
    val wellDeep: Int get() = mix(base, dark, .72f)

    /** 右下投影。深色主题本身够暗，用 62%；浅色主题要压到 72% 才有厚度。 */
    val shadowDark: Int get() = alpha(dark, if (isDark) .62f else .72f)

    /** 左上高光。 */
    val shadowLight: Int get() = alpha(light, if (isDark) .62f else .72f)

    /** 凹陷槽的左上内阴影。 */
    val innerDark: Int get() = alpha(dark, if (isDark) .50f else .72f)

    /** 凹陷槽的右下内阴影：浅色主题上白色要够亮才看得出槽底。 */
    val innerLight: Int get() = alpha(light, if (isDark) .10f else .50f)

    /** 焦点面外的一圈光晕。 */
    val litGlow: Int get() = alpha(lit, if (isDark) .20f else .35f)

    /** 正文。 */
    val text: Int get() = ink

    /** 84%：区块标题。 */
    val textStrong: Int get() = alpha(ink, .84f)

    /** 70%：卡片标题、正文。 */
    val textDim: Int get() = alpha(ink, .70f)

    /** 62%：辅助说明。 */
    val muted: Int get() = alpha(ink, .62f)

    /** 分组分隔线。 */
    val separator: Int get() = alpha(ink, .08f)

    companion object {
        val fallback: TvTheme = DEEP_DARK

        fun of(value: String?): TvTheme =
            entries.firstOrNull { it.storedValue == value } ?: fallback
    }
}

/** 两色线性混合：[ratio] 为 0 取 [from]，为 1 取 [to]。 */
private fun mix(from: Int, to: Int, ratio: Float): Int =
    Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio).toInt(),
    )

/** 同一个色相、换一档不透明度。 */
private fun alpha(color: Int, ratio: Float): Int =
    (color and 0x00FFFFFF) or ((ratio * 255f).toInt().coerceIn(0, 255) shl 24)

/** 主题偏好的落盘：独立的小文件，不进 Library / 备份。 */
internal object TvThemeStore {
    private const val FILE = "native-theme-v1"
    private const val KEY = "theme"

    fun load(context: Context): TvTheme =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, null)
            .let(TvTheme::of)

    fun save(context: Context, theme: TvTheme) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, theme.storedValue)
            .apply()
    }
}
