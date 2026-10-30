// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.*
import android.widget.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TvSocialTest {
    @Test
    fun knownEmojiUsesImageAndUnknownLabelIsPreserved() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val text =
                CommentEmoji(controller.get()).render("你好[笑哭]【赞】[未知表情]", 32f)
                    as android.text.Spanned
            assertEquals(
                2,
                text.getSpans(0, text.length, android.text.style.ImageSpan::class.java).size,
            )
            assertTrue(text.toString().contains("[未知表情]"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun commentSignatureWorksOnAndroid6And8() {
        val headers =
            com.hongguotv.core
                .CommentSignature()
                .headers("aid=8662&device_id=123", 1700000000123, ByteArray(11) { it.toByte() })
        assertEquals("840420e100007c44bd0bfe74163888622f1454edf858fca6234c", headers["X-Gorgon"])
        assertEquals("AgMEBVbVr6bCGXEl3RbL1aAkpFGUXoV+4yYWolKDMC0DpDXW", headers["X-Ladon"])
        assertTrue(headers["X-Argus"]!!.length > 100)
    }

    private fun views(v: View): List<TextView> =
        (if (v is TextView) listOf(v) else emptyList()) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) }
            else emptyList()

    private fun allViews(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) }
            else emptyList()

    private fun flush(social: TvSocial) {
        val f = social.javaClass.getDeclaredField("worker").apply { isAccessible = true }
        (f.get(social) as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
        Thread.sleep(80)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun remoteCommentContinuationDeduplicatesAndRestoresFocus() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val anchor =
            Button(activity).apply {
                text = "评论"
                isFocusableInTouchMode = true
            }
        root.addView(anchor)
        activity.setContentView(root)
        val calls = mutableListOf<String>()
        val social =
            TvSocial(
                activity,
                { null },
                { "123456" to "234567" },
                { _, _, cursor ->
                    synchronized(calls) { calls.add(cursor) }
                    val rows = JSONArray()
                    for (id in if (cursor.isEmpty()) listOf("1", "2") else listOf("2", "3")) rows
                        .put(
                            JSONObject()
                                .put("id", id)
                                .put("author", "作者$id")
                                .put("content", "评论$id")
                                .put("created", 1700000000)
                                .put("likes", 2)
                        )
                    JSONObject()
                        .put("items", rows)
                        .put("total", 3)
                        .put("hasMore", cursor.isEmpty())
                        .put("nextCursor", if (cursor.isEmpty()) "next" else "")
                },
            )
        try {
            social.attach(root)
            social.showComments(anchor)
            flush(social)
            val d = ShadowDialog.getLatestDialog()
            val decor = d.window!!.decorView
            assertTrue(views(decor).any { it.text.contains("已加载 2 条") })
            views(decor).first { it.text == "加载更多评论" }.performClick()
            flush(social)
            assertEquals(listOf("", "next"), calls)
            assertTrue(views(decor).any { it.text.contains("已加载 3 条") })
            assertEquals(1, views(decor).count { it.text.contains("作者2") })
            views(decor).first { it.text == "关闭" }.performClick()
            ShadowLooper.idleMainLooper()
            assertFalse(d.isShowing)
            assertTrue(anchor.hasFocus())
        } finally {
            social.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun remoteSettingsPersistAndResetClearsOverlay() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val a = controller.get()
        val root = FrameLayout(a)
        val anchor = Button(a)
        root.addView(anchor)
        a.setContentView(root)
        val social = TvSocial(a, { null }, { null })
        try {
            social.attach(root)
            social.settings(anchor)
            val d = ShadowDialog.getLatestDialog()
            val all = allViews(d.window!!.decorView)
            val prefs = a.getSharedPreferences("social", 0)
            // 显示方式：隐藏后两条滑条退出焦点，切回全屏再恢复。
            all.filterIsInstance<TextView>().first { it.text == "隐藏弹幕" }.performClick()
            assertEquals("hidden", prefs.getString("mode", null))
            val scale = all.first { it.contentDescription?.startsWith("字号") == true }
            assertFalse(scale.isEnabled)
            all.filterIsInstance<TextView>().first { it.text == "全屏显示" }.performClick()
            assertEquals("full", prefs.getString("mode", null))
            assertTrue(scale.isEnabled)
            // 速度档位即时写盘。
            all.filterIsInstance<TextView>().first { it.text == "快速" }.performClick()
            assertEquals("fast", prefs.getString("speed", null))
            // 左右键逐档调字号 / 透明度，调完立即保存。
            val right = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            val left = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            assertTrue(scale.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, right))
            assertEquals(1.1f, prefs.getFloat("scale", 1f), .001f)
            val alpha = all.first { it.contentDescription?.startsWith("透明度") == true }
            assertTrue(alpha.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, left))
            assertEquals(.9f, prefs.getFloat("alpha", 1f), .001f)
            social.foreground(false)
            social.reset()
            d.dismiss()
        } finally {
            social.destroy()
            controller.pause().stop().destroy()
        }
    }
}
