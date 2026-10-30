// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 一条弹幕：播放时间轴上的毫秒位置 + 文本。
 *
 * 数据来自本仓库已有的匿名弹幕接口（见 [TimedComments]）：文本在
 * `comment.common.content.text`，时间点在 `comment.expand.offset_time`。
 * 表情不是字形而是 `[笑哭]` 这类方括号标签，渲染前由 [DanmakuEmoji] 换成图片。
 */
data class Danmaku(val offsetMs: Long, val text: String)

/**
 * 弹幕在播放画面上的显示方式。存进本机设置，换集与重开应用都保持。
 *
 * 顺序就是设置面板里的排列顺序：
 * 只显一行 → 上方显示 → 全屏显示 → 隐藏弹幕。
 */
enum class DanmakuMode(val storedValue: String, val label: String) {
    /** 只显一行：整屏只有一条轨道，多密也不会上下叠字。 */
    SINGLE("single", "只显一行"),

    /** 上方显示：只在画面上方区域滚动。 */
    TOP("top", "上方显示"),

    /** 全屏显示：整幅画面从上到下都能滚。 */
    FULL("full", "全屏显示"),

    /** 隐藏弹幕：整层不画，也不发请求。 */
    HIDDEN("hidden", "隐藏弹幕");

    /**
     * 这一档下字号 / 透明度 / 速度还有没有意义。
     *
     * 隐藏档整层不画，留着这几项只会让人以为功能坏了；判定放在这里，
     * 设置面板与播放器菜单才能同时收起、同时放出。
     */
    val showsStyle get() = this != HIDDEN

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue == value } ?: FULL
    }
}

/**
 * 弹幕移动速度档位。
 *
 * [factor] 是「标准档」的倍数：所有弹幕同速，换档只改变整体横穿画面的时间，
 * 不会让快的追上慢的。
 */
enum class DanmakuSpeed(val storedValue: String, val label: String, val factor: Float) {
    SLOW("slow", "慢速", .75f),
    NORMAL("normal", "标准", 1f),
    FAST("fast", "快速", 1.5f);

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue == value } ?: NORMAL
    }
}

/**
 * 一轮「重复弹幕」聚合：同样的文字在 [DANMAKU_REPEAT_WINDOW_MS] 之内出现了 [count] 次。
 *
 * 同一条内容只显示一次，文案带数量标记（`这个片段太精彩了 ×25`）；只出现过一次的
 * 就是原文，不挂「×1」。
 */
data class DanmakuRepeat(val text: String, val offsets: List<Long>) {
    val count get() = offsets.size
    val firstMs get() = offsets.first()
    val lastMs get() = offsets.last()

    /** 这一组的身份：同一句在后续窗口里还会聚合成新的一轮，key 必须带起始时间。 */
    val key get() = "$text@$firstMs"

    /** 显示文案：重复过的带数量标记。 */
    val label get() = if (count >= 2) "$text ×$count" else text

    /** 播放到 [nowMs] 为止已经出现过几条：中央那一块的数量标记跟着它往上走。 */
    fun countUpTo(nowMs: Long): Int {
        var low = 0
        var high = offsets.size
        while (low < high) {
            val mid = (low + high) / 2
            if (offsets[mid] <= nowMs) low = mid + 1 else high = mid
        }
        return low
    }

    /** 够格进中央的时刻：第 [DanmakuHighlight.MIN_COUNT] 条出现的时候。 */
    val eligibleMs get() = offsets[minOf(DanmakuHighlight.MIN_COUNT, count) - 1]
}

/** 一轮聚合的时间窗口：从这一轮的第一条开始算，窗口内出现的同内容都算进这一轮。 */
const val DANMAKU_REPEAT_WINDOW_MS = 20_000L

/**
 * 把整集弹幕按内容聚合：同一句文字在窗口内出现多次合成一组，按时间排序。
 *
 * 纯函数，与画面无关：弹幕层拿它决定「哪些内容只画一次、数量是多少」。
 */
fun repeatGroups(items: List<Danmaku>, windowMs: Long = DANMAKU_REPEAT_WINDOW_MS): List<DanmakuRepeat> {
    val groups = mutableListOf<DanmakuRepeat>()
    items.sortedBy { it.offsetMs }.groupBy { it.text.trim() }.forEach { (text, list) ->
        if (text.isEmpty()) return@forEach
        var bucket = mutableListOf<Long>()
        var windowStart = Long.MIN_VALUE
        list.forEach { item ->
            // 第一条开一个窗口；窗口内继续累计；超出窗口就是新的一轮。
            if (bucket.isNotEmpty() && item.offsetMs > windowStart + windowMs) {
                groups += DanmakuRepeat(text, bucket)
                bucket = mutableListOf()
            }
            if (bucket.isEmpty()) windowStart = item.offsetMs
            bucket += item.offsetMs
        }
        if (bucket.isNotEmpty()) groups += DanmakuRepeat(text, bucket)
    }
    return groups.sortedWith(compareBy({ it.firstMs }, { it.text }))
}

/**
 * 中央突出显示的参数。
 *
 * 同一时间只有一条弹幕获得中央突出显示；它要稳（数量涨了只更新标记、不切走），
 * 并且停留时间有上限，不会长期霸占画面中央。
 */
object DanmakuHighlight {
    /** 至少重复这么多次才算「重复弹幕」，才有资格进中央。 */
    const val MIN_COUNT = 2

    /** 一条中央弹幕至少停留多久。 */
    const val MIN_DWELL_MS = 5_000L

    /** 停留期间每多出现一条重复，延长多久。 */
    const val EXTEND_MS = 1_200L

    /** 停留上限：再热也不会一直占着中央。 */
    const val MAX_DWELL_MS = 12_000L

    /** 一轮聚合结束后还能被选上中央的尾巴：超过这个时间就不再补展示。 */
    const val TAIL_MS = 6_000L
}

/** 中央突出显示的一轮：[group] 从 [startMs] 展示到 [endMs]（播放时间轴上的毫秒）。 */
data class DanmakuHighlightRound(val group: DanmakuRepeat, val startMs: Long, val endMs: Long)

/**
 * 排一遍中央突出显示的次序（纯函数，可单测）。
 *
 * - 一轮结束后再选下一条，一轮之内不换；
 * - 选「到那一刻为止累计数量最多」的那一组，数量相同选更早出现的；
 * - 每轮停留 = 基础停留 + 期间每多一条重复延长一点，封顶 [DanmakuHighlight.MAX_DWELL_MS]；
 * - [previous] 是上一份数据排出来的次序：数据是一窗一窗补上来的，正在中央展示的那条
 *   只要还在候选里就接着排它，补数据不会把中央那一条换掉。
 */
fun planHighlights(
    groups: List<DanmakuRepeat>,
    previous: List<DanmakuHighlightRound> = emptyList(),
): List<DanmakuHighlightRound> {
    val pending = groups.filter { it.count >= DanmakuHighlight.MIN_COUNT }
    if (pending.isEmpty()) return emptyList()
    val used = mutableSetOf<String>()
    val rounds = mutableListOf<DanmakuHighlightRound>()
    var cursor = 0L
    while (true) {
        // 已经过去太久的那些（快进之后才拿到的旧聚合）不再补展示。
        pending
            .filter { it.key !in used && cursor > it.lastMs + DanmakuHighlight.TAIL_MS }
            .forEach { used += it.key }
        val showing = previous.firstOrNull { cursor >= it.startMs && cursor < it.endMs }?.group?.key
        val carried =
            showing?.let { key ->
                pending.firstOrNull { it.key == key && it.key !in used && it.eligibleMs <= cursor }
            }
        val ready = pending.filter { it.key !in used && it.eligibleMs <= cursor }
        // 数量优先、相同数量先到先得。
        val best =
            carried
                ?: ready
                    .filter { it.countUpTo(cursor) >= DanmakuHighlight.MIN_COUNT }
                    .maxWithOrNull(compareBy({ it.countUpTo(cursor) }, { -it.firstMs }))
        if (best == null) {
            val next =
                pending
                    .filter { it.key !in used }
                    .map { maxOf(it.eligibleMs, it.firstMs) }
                    .filter { it > cursor }
                    .minOrNull() ?: break
            cursor = next
            continue
        }
        val end = roundEnd(best, cursor)
        rounds += DanmakuHighlightRound(best, cursor, end)
        used += best.key
        cursor = end
    }
    return rounds
}

/** 一轮的结束时刻：基础停留 + 这期间每多一条重复延长一点，封顶在最大停留时长。 */
private fun roundEnd(group: DanmakuRepeat, startMs: Long): Long {
    var end = startMs + DanmakuHighlight.MIN_DWELL_MS
    while (true) {
        val counted = group.countUpTo(end)
        val wanted =
            startMs +
                DanmakuHighlight.MIN_DWELL_MS +
                (counted - DanmakuHighlight.MIN_COUNT) * DanmakuHighlight.EXTEND_MS
        val next = minOf(wanted, startMs + DanmakuHighlight.MAX_DWELL_MS)
        if (next <= end) return end
        end = next
    }
}

/**
 * 弹幕文字的外观偏好：字号缩放与不透明度。
 *
 * 两项都是连续量（面板上用左右键逐档调），取值一律经过 [scale] / [alpha] 归一：
 * 先吸附到 0.1 的整数倍，再夹进上下限，最后抹掉浮点误差。归一放在 core 里，
 * 面板上的读数、写盘的值、下次读回来的值才是同一个数。
 */
object DanmakuStyle {
    const val SCALE_MIN = .6f
    const val SCALE_MAX = 2f
    const val SCALE_STEP = .1f
    const val ALPHA_MIN = .2f
    const val ALPHA_MAX = 1f
    const val ALPHA_STEP = .1f

    fun scale(value: Float) = snap(value, SCALE_MIN, SCALE_MAX, SCALE_STEP)
    fun alpha(value: Float) = snap(value, ALPHA_MIN, ALPHA_MAX, ALPHA_STEP)

    /** 面板上的读数：`0.9f → "90%"`。 */
    fun percent(value: Float) = "${Math.round(value * 100)}%"

    private fun snap(value: Float, min: Float, max: Float, step: Float): Float {
        // 非法值（NaN / 无穷）落回上限：读坏数据时宁可回到「100%」这一档。
        if (!value.isFinite()) return max
        val snapped = Math.round((value - min) / step) * step + min
        return (Math.round(snapped * 100f) / 100f).coerceIn(min, max)
    }
}
