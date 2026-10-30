// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuEmoji
import com.hongguotv.core.DanmakuHighlight
import com.hongguotv.core.DanmakuHighlightRound
import com.hongguotv.core.DanmakuMode
import com.hongguotv.core.DanmakuPart
import com.hongguotv.core.DanmakuRepeat
import com.hongguotv.core.DanmakuSpeed
import com.hongguotv.core.planHighlights
import com.hongguotv.core.repeatGroups

/**
 * 压在播放画面上的弹幕层。
 *
 * 数据就是匿名弹幕接口返回的那一份（见 `TimedComments`），这里只负责画：
 * 布局（哪一条落在哪一轨、什么时候进画）在 [relayout] 里一次性算好，
 * 每帧只按当前播放位置求 x，不再排序、不再测量，低端电视上也跟得上。
 *
 * 四种显示方式由 [DanmakuMode] 决定：只显一行 / 上方显示 / 全屏显示 / 隐藏。
 *
 * **不叠字的做法**：
 * 1. 同一轨道上，后一条要等前一条的尾部离开画面右边才能进画
 *    （`freeAt = start + (宽度 + 间距) / 速度`），因此后一条永远追不上前一条；
 * 2. 所有弹幕同速（[DanmakuSpeed] 只改整体速度），宽度不同的两条之间也不会追尾；
 * 3. 轨道高度按字体实际高度 + 描边 + 表情图边长算，大字号下相邻两轨也不会碰在一起；
 * 4. 排不下先换轨道，全排不下才往后延迟；延迟超过 [MAX_DELAY_MS] 就跳过这一条。
 *
 * **重复弹幕聚合**：整集数据先按内容聚合成一组一组（[repeatGroups]），同一组只画一次、
 * 文案带数量标记；数量最多的那几组按 [planHighlights] 排好的次序上屏幕中央突出显示，
 * 同一时间只有一条，中央那一条不再进普通轨道。
 *
 * 位置与播放状态由宿主通过 [positionMillis] / [playing] 提供，弹幕层自己不做时钟，
 * 暂停、跳转、倍速都不会和播放器脱节。
 *
 * 文本里的表情标签（`[笑哭]`）在 [relayout] 里就被 [DanmakuEmoji.parts] 切成
 * 「文字片段 + 表情片段」：文字照常按字体画，表情按 [DanmakuEmojiStore] 里的图片画。
 * 图片还没解好时先用字形顶着，解好后 [DanmakuEmojiStore.Listener] 会把这一帧重画一次。
 */
internal class DanmakuView(context: Context) : View(context), DanmakuEmojiStore.Listener {
    private companion object {
        /** 横穿一屏的基准时长（秒）：速度 = 屏宽 / 这个基准，再乘档位系数。 */
        const val BASE_SCROLL_SECONDS = 6f

        /** 全屏弹幕离顶边的安全边距，躲开电视过扫描。 */
        const val TOP_INSET_DP = 28

        /** 上方弹幕：整层只占屏幕高度的这一份，其余留白。 */
        const val TOP_MODE_SHARE = .25f

        /** 上方弹幕 / 只显一行离顶边留的安全边距。 */
        const val TOP_MODE_INSET_DP = 20

        /** 底部控制层之上收住，弹幕不压进度条。 */
        const val BOTTOM_INSET_DP = 112

        /** 单帧最多画多少条，给低端 GPU 一个上限。 */
        const val MAX_LINES_PER_FRAME = 160

        /** 同一轨道上前后两条之间至少留这么宽（按字号算）。 */
        const val LANE_GAP_TEXT = .9f

        /** 相邻轨道之间至少留这么高（按字号算）：描边与表情图都算在这一档里。 */
        const val LANE_GAP_VERTICAL = .35f

        /** 一条弹幕最多允许比自己的时间点晚多久出现；再晚就跳过，不做强行叠加。 */
        const val MAX_DELAY_MS = 4_000L

        /** 中央突出显示的字号倍率与底板留白（dp）。 */
        const val HIGHLIGHT_SCALE = 1.25f
        const val HIGHLIGHT_PAD_DP = 13

        /** 表情图相对字号的大小：比汉字略大一点，跟系统 emoji 的观感一致。 */
        const val EMOJI_SCALE = 1.2f

        /** 描边笔的原始不透明度（0xB3 = 70% 黑）。 */
        const val EDGE_ALPHA = 0xB3
    }

    /** 一条已经排好轨道的普通弹幕：起点（秒）、横穿时长（秒）、宽度、轨道、切好的片段。 */
    private class Line(
        val start: Float,
        val duration: Float,
        val width: Float,
        val lane: Int,
        val parts: List<DanmakuPart>,
    )

    private val fill =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
        }
    private val edge =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB3000000.toInt()
            style = Paint.Style.STROKE
            typeface = Typeface.DEFAULT_BOLD
            strokeJoin = Paint.Join.ROUND
        }
    private val highlightFill =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
        }
    private val highlightEdge =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC000000.toInt()
            style = Paint.Style.STROKE
            typeface = Typeface.DEFAULT_BOLD
            strokeJoin = Paint.Join.ROUND
        }

    /** 中央那一块的底板：弹幕密集时后面的字幕会从它下面穿过，给一层半透明底才读得清。 */
    private val plate =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99000000.toInt()
            style = Paint.Style.FILL
        }

    /** 画表情图用：只要 alpha（跟着弹幕透明度走），颜色和描边都不掺和。 */
    private val emojiPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 表情图 / 底板的目标矩形，每帧复用，绘制循环里不新建对象。 */
    private val emojiRect = RectF()
    private val plateRect = RectF()
    private val emoji = DanmakuEmojiStore.get(context)
    private val density = context.resources.displayMetrics.density

    private val lines = mutableListOf<Line>()

    /** 这一集拿到的全部弹幕（按时间排好）；换显示方式、换分辨率、换速度都从它重排。 */
    private var raw: List<Danmaku> = emptyList()

    /** 聚合出来的每一组（同一组只画一次，文案带数量标记），按时间排好。 */
    private var groups: List<DanmakuRepeat> = emptyList()

    /** 中央突出显示的次序（同一条只上一次）；数据补上来时重排一次，见 [planHighlights]。 */
    private var highlights: List<DanmakuHighlightRound> = emptyList()

    /** 切好的片段缓存：同一条文案（含数量标记）只切一次，避免每帧走正则。 */
    private val partsCache = mutableMapOf<String, List<DanmakuPart>>()

    /** 当前帧还要画的第一条：按时间排好序，播放推进时只往前走，不回头。 */
    private var cursor = 0

    /** 中央那一块当前在画第几轮；只往前挪，跳转时归零。 */
    private var highlightCursor = 0
    private var lastPosition = -1L
    private var laneCount = 0
    private var laneHeight = 0f
    private var laneTop = 0f

    /** 同轨道前后两条之间的水平间距（像素），按字号算。 */
    private var laneGap = 0f

    /** 按屏高算出来的「原始」字号（1 倍缩放）；缩放与透明度都在它上面叠加。 */
    private var baseTextSize = 0f

    /** 突出显示用的字号（普通字号 × [HIGHLIGHT_SCALE]，同样夹在上下限之内）。 */
    private var highlightSize = 0f

    var mode: DanmakuMode = DanmakuMode.FULL
        set(value) {
            if (field == value) return
            field = value
            // 轨道数量跟着档位变（上方显示只有最上面四分之一、只显一行只有一条），必须整批重排。
            relayout()
        }

    /**
     * 字号缩放（1f = 原始大小）。
     *
     * 字号变了轨道高度也变，必须整批重排：不然行距还是旧字号算出来的，
     * 放大之后两行字会叠在一起、缩小之后轨道之间会空一大截。
     */
    var textScale: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            applyTextMetrics()
            relayout()
        }

    /**
     * 文字不透明度（1f = 完全不透明）。
     *
     * 只改笔的 alpha，不动字号，因此不用重排轨道；表情图跟着一起淡。
     */
    var textAlpha: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            applyTextMetrics()
            invalidate()
        }

    /**
     * 移动速度档位。
     *
     * 速度只影响「横穿画面用多久」与同轨道能排多密，因此要整批重排：
     * 换档之后前后两条的间距按新速度重算，才不会有某一条追尾。
     */
    var speed: DanmakuSpeed = DanmakuSpeed.NORMAL
        set(value) {
            if (field == value) return
            field = value
            relayout()
        }

    /** 播放位置（毫秒），由宿主每帧提供。 */
    var positionMillis: () -> Long = { 0L }

    /** 是否正在播放：暂停时停在当前帧，不继续推进。 */
    var playing: () -> Boolean = { false }

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // 空态用 INVISIBLE 而不是 GONE：GONE 的视图不参与测量，width/height 永远是 0，
        // relayout() 里「宽高齐了才排轨道」的条件就永远不成立，弹幕层会把自己锁死在空态。
        visibility = INVISIBLE
    }

    /** 换集 / 拉到新的一批弹幕：重排轨道并回到第一条。 */
    fun setDanmaku(items: List<Danmaku>) {
        raw = items.sortedBy { it.offsetMs }
        relayout()
    }

    /** 清空这一集的弹幕（换集、切到隐藏档）。 */
    fun clear() {
        raw = emptyList()
        relayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        emoji.addListener(this)
    }

    override fun onDetachedFromWindow() {
        emoji.removeListener(this)
        super.onDetachedFromWindow()
    }

    /** 有一张表情图解好了：重画一帧，把顶着的字形换成图片。可能在解码线程上被叫到。 */
    override fun onEmojiDecoded() {
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    /**
     * 按当前档位、字号与速度把数据重排一次；每次重排都从头开始画。
     *
     * 数据是一窗一窗补上来的：每次都当作「手上这一份就是全部」重排，
     * 已经画过的位置不回补，只有后面的内容按新数据重排。
     */
    private fun relayout() {
        lines.clear()
        partsCache.clear()
        cursor = 0
        lastPosition = -1L
        groups = repeatGroups(raw)
        // 中央突出显示的次序：传上一份，正在展示的那一条不会被新数据挤掉。
        highlights = planHighlights(groups, highlights)
        highlightCursor = 0
        layoutLanes()
        val lanes = if (mode == DanmakuMode.HIDDEN) 0 else laneCount
        if (lanes > 0 && width > 0 && height > 0) schedule(lanes)
        // 这一集用到的表情图先全部排进解码队列：画到它们的时候图多半已经在缓存里。
        emoji.prefetch(groups.map { it.text })
        // 可见性只看「有没有数据」，不看「排没排成轨道」：轨道要等 onSizeChanged 之后才算得出来。
        visibility = if (mode == DanmakuMode.HIDDEN || raw.isEmpty()) INVISIBLE else VISIBLE
        invalidate()
    }

    /**
     * 把每一组排到轨道上：挑「最早能空出来」的那条轨道，并算出它什么时候进画。
     *
     * 同一轨道上前后两条同速追不上：后一条要等前一条的尾部离开画面右边才能进画。
     * 所有轨道都排不下就往后延迟；延迟超过 [MAX_DELAY_MS] 的跳过——
     * 宁可少一条，也不要在同一处硬叠两条。
     */
    private fun schedule(lanes: Int) {
        val viewWidth = width.toFloat()
        val pixelsPerSecond = speedPx(viewWidth / BASE_SCROLL_SECONDS)
        val laneFree = FloatArray(lanes)
        // 「只显一行」时唯一的轨道还要给中央那一块让路。
        val blocked =
            if (mode == DanmakuMode.SINGLE)
                highlights.map { it.startMs / 1000f to it.endMs / 1000f }
            else emptyList()
        // 已经排上中央的那几组不再进普通轨道，避免同一句同时出现两次。
        val planned = highlights.map { it.group.key }.toSet()
        groups
            .filterNot { it.key in planned }
            .forEach { group ->
                val parts = partsOf(group.label)
                val width = measure(parts, fill, fill.textSize * EMOJI_SCALE)
                val want = group.firstMs / 1000f
                val duration = (viewWidth + width) / pixelsPerSecond
                var lane = 0
                var start = Float.MAX_VALUE
                for (index in 0 until lanes) {
                    var candidate = maxOf(laneFree[index], want)
                    if (blocked.isNotEmpty()) candidate = avoid(candidate, duration, blocked)
                    if (candidate < start) {
                        start = candidate
                        lane = index
                    }
                }
                // 排不下：不强行叠加，直接跳过这一条。
                if ((start - want) * 1000f > MAX_DELAY_MS) return@forEach
                laneFree[lane] = start + (width + laneGap) / pixelsPerSecond
                lines += Line(start, duration, width, lane, parts)
            }
        lines.sortBy { it.start }
    }

    /** 把 [from] 到 `from + duration` 这段区间里所有「中央占着轨道」的时间段让过去。 */
    private fun avoid(from: Float, duration: Float, blocked: List<Pair<Float, Float>>): Float {
        var start = from
        blocked.forEach { (begin, end) ->
            if (start < end && start + duration > begin) start = end
        }
        return start
    }

    /** 速度档位换算成每秒多少像素：屏宽定的基准速度 × 档位系数。 */
    private fun speedPx(pixelsPerSecond: Float) = pixelsPerSecond * speed.factor

    /** 一条弹幕的片段（切成「文字 + 表情」），同一条文案只切一次。 */
    private fun partsOf(text: String): List<DanmakuPart> =
        partsCache.getOrPut(text) { DanmakuEmoji.parts(text) }

    /** 一段内容的宽度：文字按字体量，表情按图片的显示边长算。 */
    private fun measure(parts: List<DanmakuPart>, paint: Paint, emojiSize: Float): Float {
        var width = 0f
        parts.forEach { part -> width += if (part.asset == null) paint.measureText(part.text) else emojiSize }
        return width
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 字号按屏高给（1080p 电视约 18sp），再夹住上下限：720p 与 4K 都不会过大或过小。
        baseTextSize = (h * .034f).coerceIn(density * 18f, density * 34f)
        applyTextMetrics()
        relayout()
    }

    /**
     * 把「屏高定出的基准字号 + 用户缩放 / 透明度」落到几支笔上。
     *
     * 缩放后的字号仍然夹一次上下限（10dp–72dp）：2 倍字号铺到 4K 屏上会盖住大半画面，
     * 0.6 倍铺到 720p 上会小到读不出来。描边与表情图的不透明度跟着文字一起缩。
     */
    private fun applyTextMetrics() {
        val size = (baseTextSize * textScale).coerceIn(density * 10f, density * 72f)
        val alpha = textAlpha.coerceIn(0f, 1f)
        fill.textSize = size
        edge.textSize = size
        edge.strokeWidth = size * .14f
        // 中央那一块大一号，同样收在 72dp 之内。
        highlightSize = (size * HIGHLIGHT_SCALE).coerceAtMost(density * 72f)
        highlightFill.textSize = highlightSize
        highlightEdge.textSize = highlightSize
        highlightEdge.strokeWidth = highlightSize * .16f
        fill.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        edge.alpha = (EDGE_ALPHA * alpha).toInt().coerceIn(0, 255)
        highlightFill.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        highlightEdge.alpha = (0xCC * alpha).toInt().coerceIn(0, 255)
        emojiPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        // 底板跟着透明度淡一点：全黑底在暗画面上会把弹幕压成一块黑砖。
        plate.alpha = (0x99 * alpha).toInt().coerceIn(0, 255)
    }

    /**
     * 重新量一次可用轨道。
     *
     * 全屏铺满整幅画面，只让开顶边与底部控制层；上方显示只占最上面四分之一；
     * 只显一行只留一条轨道（轨道高度按突出字号算，中央那一块也放得下）。
     */
    private fun layoutLanes() {
        val metrics = fill.fontMetrics
        val textBox = (metrics.descent - metrics.ascent) + fill.strokeWidth
        val emojiBox = fill.textSize * EMOJI_SCALE
        laneGap = fill.textSize * LANE_GAP_TEXT
        laneHeight = maxOf(textBox, emojiBox) + fill.textSize * LANE_GAP_VERTICAL
        if (mode == DanmakuMode.HIDDEN || laneHeight <= 0f) {
            laneCount = 0
            return
        }
        val usable =
            when (mode) {
                DanmakuMode.SINGLE -> {
                    // 唯一那一行：顶边留出的边距要装得下中央那一块（它比普通弹幕大一档）。
                    laneTop =
                        maxOf(
                            TOP_MODE_INSET_DP * density,
                            highlightSize * .6f + HIGHLIGHT_PAD_DP * density * .5f,
                        )
                    laneHeight
                }
                DanmakuMode.TOP -> {
                    laneTop = TOP_MODE_INSET_DP * density
                    (height * TOP_MODE_SHARE - laneTop).coerceAtLeast(0f)
                }
                else -> {
                    laneTop = TOP_INSET_DP * density
                    (height - BOTTOM_INSET_DP * density).coerceAtLeast(laneTop + 1f) - laneTop
                }
            }
        laneCount = (usable / laneHeight).toInt().coerceIn(0, 24)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == DanmakuMode.HIDDEN || laneCount <= 0) return
        if (lines.isEmpty() && highlights.isEmpty()) return
        val viewWidth = width.toFloat()
        val now = positionMillis()
        val seconds = now / 1000f
        // 跳转（前进或后退）之后指针作废，从头找一次；正常播放只往前挪。
        if (now < lastPosition) {
            cursor = 0
            highlightCursor = 0
        }
        lastPosition = now
        while (cursor < lines.size && seconds - lines[cursor].start > lines[cursor].duration) cursor++
        var drawn = 0
        var index = cursor
        while (index < lines.size && drawn < MAX_LINES_PER_FRAME) {
            val line = lines[index]
            val elapsed = seconds - line.start
            if (elapsed < 0f) break
            if (elapsed <= line.duration) {
                val x = viewWidth - elapsed / line.duration * (viewWidth + line.width)
                val baseline = laneTop + (line.lane + 1) * laneHeight - fill.textSize * .28f
                drawParts(canvas, line.parts, x, baseline, fill, edge, fill.textSize * EMOJI_SCALE)
                drawn++
            }
            index++
        }
        drawHighlight(canvas, now)
        // 播放中才继续要下一帧：暂停时停在原地，退出播放时视图被移除，循环自然停下。
        if (playing() && visibility == VISIBLE && isAttachedToWindow) postInvalidateOnAnimation()
    }

    /**
     * 画中央突出显示的那一条。
     *
     * 同一时间只有一条：序号 [highlightCursor] 只往前走，当前这一轮没结束就不换下一条；
     * 数量标记跟着播放往上走，重复次数在停留期间继续增加时标记直接变大。
     */
    private fun drawHighlight(canvas: Canvas, nowMs: Long) {
        if (mode == DanmakuMode.HIDDEN || highlights.isEmpty()) return
        while (highlightCursor < highlights.size && nowMs >= highlights[highlightCursor].endMs)
            highlightCursor++
        val round = highlights.getOrNull(highlightCursor) ?: return
        if (nowMs < round.startMs) return
        val parts = partsOf(round.group.text)
        val counter = " ×${round.group.countUpTo(nowMs).coerceAtLeast(DanmakuHighlight.MIN_COUNT)}"
        val emojiSize = highlightSize * EMOJI_SCALE
        val bodyWidth = measure(parts, highlightFill, emojiSize)
        val counterWidth = highlightFill.measureText(counter)
        val total = bodyWidth + counterWidth
        val centerY =
            when (mode) {
                DanmakuMode.SINGLE -> laneTop + laneHeight / 2f
                DanmakuMode.TOP -> laneTop + (height * TOP_MODE_SHARE - laneTop) / 2f
                else -> height / 2f
            }
        val left = ((width - total) / 2f).coerceAtLeast(density * 8f)
        val padX = HIGHLIGHT_PAD_DP * density
        val padY = HIGHLIGHT_PAD_DP * density * .5f
        val box = highlightSize * .95f
        plateRect.set(left - padX, centerY - box / 2f - padY, left + total + padX, centerY + box / 2f + padY)
        canvas.drawRoundRect(plateRect, density * 10f, density * 10f, plate)
        val metrics = highlightFill.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        drawParts(canvas, parts, left, baseline, highlightFill, highlightEdge, emojiSize)
        // 数量标记接在正文后面：重画一次基线，宽高与正文同一档。
        canvas.drawText(counter, left + bodyWidth, baseline, highlightEdge)
        canvas.drawText(counter, left + bodyWidth, baseline, highlightFill)
    }

    /**
     * 画一段内容：文字片段按字体画（先描边后填充），表情片段按图片画。
     *
     * 表情图贴着文字行的中线居中。图片没解好时退回画字形，宽度早就按图片边长算过，
     * 换成图片之后这一条的位置和长度都不会跳。
     */
    private fun drawParts(
        canvas: Canvas,
        parts: List<DanmakuPart>,
        x: Float,
        baseline: Float,
        textPaint: Paint,
        strokePaint: Paint,
        emojiSize: Float,
    ) {
        val metrics = textPaint.fontMetrics
        val emojiTop = baseline + (metrics.ascent + metrics.descent) / 2f - emojiSize / 2f
        var cursorX = x
        parts.forEach { part ->
            val asset = part.asset
            if (asset == null) {
                canvas.drawText(part.text, cursorX, baseline, strokePaint)
                canvas.drawText(part.text, cursorX, baseline, textPaint)
                cursorX += textPaint.measureText(part.text)
            } else {
                val image: Bitmap? = emoji.bitmap(asset)
                if (image == null) {
                    canvas.drawText(part.text, cursorX, baseline, strokePaint)
                    canvas.drawText(part.text, cursorX, baseline, textPaint)
                } else {
                    emojiRect.set(cursorX, emojiTop, cursorX + emojiSize, emojiTop + emojiSize)
                    canvas.drawBitmap(image, null, emojiRect, emojiPaint)
                }
                cursorX += emojiSize
            }
        }
    }

    /** 播放位置可能被快进，指针要跟着重算；宿主在 seek 之后调一次。 */
    fun resetCursor() {
        cursor = 0
        highlightCursor = 0
        lastPosition = -1L
    }
}
