// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** 新拟态底板的两种形态：凸起（外投影 + 高光）、凹陷（内阴影）。 */
internal enum class SoftStyle { RAISED, INSET }

/**
 * 柔光（新拟态）绘制核心。
 *
 * Android 的 GradientDrawable 没有模糊，setShadowLayer 只对软件画布生效，
 * RenderEffect 又要 API 31，因此把「右下投影 + 左上高光」预渲染成半分辨率位图并做 LRU 缓存；
 * 每帧只画一张已经模糊好的图。
 *
 * 投影全部画在控件自己的内边距（柔光带）里，不靠父级「不裁剪」，
 * 因此滚动容器可以放心裁剪溢出内容。
 */
internal object Soft {
    /** 低内存设备在 onCreate 里调小；只影响柔光紧致度，不影响层次。 */
    var quality = 1f

    const val SCALE = .5f

    private const val MAX_ENTRIES = 48
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_BITMAP_BYTES = 3 * 1024 * 1024

    class Layers(val shadow: Bitmap?, val dark: Shader?, val light: Shader?) {
        val bytes: Int =
            (shadow?.allocationByteCount ?: 0) + if (dark != null || light != null) 512 else 0
    }

    private val cache = LinkedHashMap<String, Layers>(32, .75f, true)
    private var bytes = 0

    fun layers(key: String, build: () -> Layers): Layers {
        cache[key]?.let { return it }
        val made = build()
        cache[key] = made
        bytes += made.bytes
        val oldest = cache.entries.iterator()
        while (bytes > MAX_BYTES && cache.size > 1 && oldest.hasNext()) {
            val entry = oldest.next()
            bytes -= entry.value.bytes
            oldest.remove()
        }
        if (cache.size > MAX_ENTRIES) {
            val extra = cache.entries.iterator()
            while (cache.size > MAX_ENTRIES && extra.hasNext()) {
                val entry = extra.next()
                bytes -= entry.value.bytes
                extra.remove()
            }
        }
        return made
    }

    fun maxBitmapBytes() = MAX_BITMAP_BYTES

    /** 换主题或退到后台时释放缓存；下次绘制按需重建。 */
    fun trim() {
        cache.clear()
        bytes = 0
    }
}

/**
 * 一块新拟态底板。几何：bounds 四边各内缩 bandPx，剩下的圆角矩形就是面板；
 * 因此控件的 padding 必须与 bandPx 一致。
 */
internal class SoftDrawable(
    private val fill: Int,
    private val radiusPx: Float,
    private val bandPx: Float,
    private val style: SoftStyle,
    private val glowColor: Int = Color.TRANSPARENT,
    private val depth: Float = 1f,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plate = RectF()
    private val dst = RectF()
    private val clip = Path()
    private var key: String? = null
    private var cached: Soft.Layers? = null

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        bitmapPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        bitmapPaint.colorFilter = colorFilter
    }

    override fun onBoundsChange(bounds: Rect) {
        plate.set(
            bounds.left + bandPx,
            bounds.top + bandPx,
            bounds.right - bandPx,
            bounds.bottom - bandPx,
        )
        dst.set(bounds)
        key = null
        cached = null
    }

    override fun draw(canvas: Canvas) {
        val width = plate.width()
        val height = plate.height()
        if (width <= 0f || height <= 0f) return
        val radius = radiusPx.coerceAtMost(minOf(width, height) * .5f)
        when (style) {
            SoftStyle.RAISED -> {
                layers().shadow?.let { canvas.drawBitmap(it, null, dst, bitmapPaint) }
                fill(canvas, radius)
            }
            SoftStyle.INSET -> {
                fill(canvas, radius)
                inner(canvas, radius)
            }
        }
    }

    private fun fill(canvas: Canvas, radius: Float) {
        if (Color.alpha(fill) == 0) return
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(plate, radius, radius, paint)
    }

    private fun inner(canvas: Canvas, radius: Float) {
        val layer = layers()
        val dark = layer.dark ?: return
        val save = canvas.save()
        clip.reset()
        clip.addRoundRect(plate, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = dark
        canvas.drawRect(plate, paint)
        layer.light?.let {
            paint.shader = it
            canvas.drawRect(plate, paint)
        }
        paint.shader = null
        canvas.restoreToCount(save)
    }

    private fun layers(): Soft.Layers {
        cached?.let { return it }
        val id = key ?: buildString {
            append(bounds.left).append('+').append(bounds.top).append(':')
            append(bounds.width()).append('x').append(bounds.height())
            append("r").append(radiusPx.toInt())
            append("b").append(bandPx.toInt())
            append("s").append(style.ordinal)
            append("f").append(fill)
            append("g").append(glowColor)
            append("d").append((depth * 10).toInt())
            append("q").append((Soft.quality * 10).toInt())
            // 位图里烤着当前主题的投影 / 高光色，换主题必须换 key。
            append("t").append(TvStyle.themeId)
        }.also { key = it }
        val made = Soft.layers(id) { build() }
        cached = made
        return made
    }

    private fun build(): Soft.Layers = when (style) {
        SoftStyle.INSET -> Soft.Layers(null, innerGradient(false), innerGradient(true))
        else -> if (Color.alpha(fill) == 0 && Color.alpha(glowColor) == 0) {
            Soft.Layers(null, null, null)
        } else {
            Soft.Layers(shadowBitmap(), null, null)
        }
    }

    private fun innerGradient(light: Boolean): LinearGradient {
        val reach = bandPx * 2.6f * depth
        return if (light) {
            LinearGradient(
                plate.right, plate.bottom, plate.right - reach, plate.bottom - reach,
                TvStyle.innerLight, Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                plate.left, plate.top, plate.left + reach, plate.top + reach,
                TvStyle.innerDark, Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
        }
    }

    private fun shadowBitmap(): Bitmap? {
        val scale = Soft.SCALE
        val width = (bounds.width() * scale).toInt().coerceAtLeast(1)
        val height = (bounds.height() * scale).toInt().coerceAtLeast(1)
        if (width.toLong() * height * 4 > Soft.maxBitmapBytes()) return null
        val quality = Soft.quality
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val brush = Paint(Paint.ANTI_ALIAS_FLAG)
        val band = bandPx * scale
        val rect = RectF(band, band, width - band, height - band)
        val radius = radiusPx * scale
        if (rect.width() <= 0f || rect.height() <= 0f) return null
        if (Color.alpha(glowColor) > 0) {
            brush.color = glowColor
            brush.setShadowLayer(band * 1.35f * quality, 0f, 0f, glowColor)
            canvas.drawRoundRect(rect, radius, radius, brush)
        }
        brush.setShadowLayer(
            band * 1.05f * quality,
            band * .36f * depth * quality,
            band * .46f * depth * quality,
            TvStyle.shadowDark,
        )
        brush.color = TvStyle.shadowDark
        canvas.drawRoundRect(rect, radius, radius, brush)
        brush.setShadowLayer(
            band * .95f * quality,
            -band * .32f * depth * quality,
            -band * .40f * depth * quality,
            TvStyle.shadowLight,
        )
        brush.color = TvStyle.shadowLight
        canvas.drawRoundRect(rect, radius, radius, brush)
        return out
    }
}
