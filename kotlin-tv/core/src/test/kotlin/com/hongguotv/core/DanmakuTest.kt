// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuTest {
    private fun danmaku(vararg pairs: Pair<Long, String>) =
        pairs.map { (at, text) -> Danmaku(at, text) }

    @Test
    fun `same text inside one window becomes one group`() {
        val groups =
            repeatGroups(
                danmaku(
                    0L to "前面高能",
                    1_000L to "前面高能",
                    25_000L to "前面高能",
                    2_000L to "另一条",
                )
            )
        assertEquals(3, groups.size)
        val first = groups.first()
        assertEquals("前面高能", first.text)
        assertEquals(2, first.count)
        assertEquals("前面高能 ×2", first.label)
        // 超出窗口的那一条另起一轮，不并进第一轮。
        assertEquals(listOf(0L, 1_000L), first.offsets)
        assertEquals(25_000L, groups.last().firstMs)
        // 只出现过一次的不挂数量标记。
        assertEquals("另一条", groups.first { it.text == "另一条" }.label)
    }

    @Test
    fun `blank text never becomes a group`() {
        assertTrue(repeatGroups(danmaku(0L to " ", 100L to "")).isEmpty())
    }

    @Test
    fun `highlights start at the second occurrence and do not overlap`() {
        val groups =
            repeatGroups(
                danmaku(
                    0L to "A",
                    1_000L to "A",
                    2_000L to "A",
                    5_000L to "B",
                    5_500L to "B",
                    6_000L to "B",
                    6_500L to "B",
                    7_000L to "B",
                )
            )
        val rounds = planHighlights(groups)
        assertEquals(2, rounds.size)
        assertEquals("A", rounds[0].group.text)
        assertEquals(1_000L, rounds[0].startMs)
        assertEquals("B", rounds[1].group.text)
        // 两轮首尾相接：同一时间只有一条中央弹幕。
        assertEquals(rounds[0].endMs, rounds[1].startMs)
        assertTrue(rounds[0].endMs - rounds[0].startMs <= DanmakuHighlight.MAX_DWELL_MS)
    }

    @Test
    fun `single occurrences never take the centre`() {
        assertTrue(planHighlights(repeatGroups(danmaku(0L to "一次"))).isEmpty())
    }

    @Test
    fun `style snaps to the stored step and clamps`() {
        assertEquals(.9f, DanmakuStyle.scale(.95f), 0f)
        assertEquals(.7f, DanmakuStyle.scale(.66f), 0f)
        assertEquals(DanmakuStyle.SCALE_MAX, DanmakuStyle.scale(9f), 0f)
        assertEquals(DanmakuStyle.SCALE_MIN, DanmakuStyle.scale(.1f), 0f)
        assertEquals(DanmakuStyle.SCALE_MAX, DanmakuStyle.scale(Float.NaN), 0f)
        assertEquals(.5f, DanmakuStyle.alpha(.52f), 0f)
        assertEquals("90%", DanmakuStyle.percent(.9f))
    }

    @Test
    fun `stored values fall back to the defaults`() {
        assertEquals(DanmakuMode.SINGLE, DanmakuMode.fromStored("single"))
        assertEquals(DanmakuMode.FULL, DanmakuMode.fromStored(null))
        assertEquals(DanmakuMode.FULL, DanmakuMode.fromStored("unknown"))
        assertTrue(DanmakuMode.FULL.showsStyle)
        assertFalse(DanmakuMode.HIDDEN.showsStyle)
        assertEquals(DanmakuSpeed.FAST, DanmakuSpeed.fromStored("fast"))
        assertEquals(DanmakuSpeed.NORMAL, DanmakuSpeed.fromStored(null))
    }
}
