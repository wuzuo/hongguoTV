// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TvSearchFocusTest {
    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 960, 540)
    }

    @Test
    fun downKeyLeavesSingleLineInputThroughExplicitFocusChain() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val input =
            EditText(activity).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
                nextFocusDownId = View.generateViewId()
            }
        input.escapeVerticalFocus()
        val below = TextView(activity).apply {
            id = input.nextFocusDownId
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val container = LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        container.addView(input, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(below, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        activity.setContentView(container)
        container.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
        )
        container.layout(0, 0, 960, 540)
        input.requestFocus()
        layout(container)
        assertTrue(input.hasFocus())
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(input.dispatchKeyEvent(event))
        assertTrue(below.hasFocus())
        assertFalse(input.hasFocus())
        controller.pause().stop().destroy()
    }
}
