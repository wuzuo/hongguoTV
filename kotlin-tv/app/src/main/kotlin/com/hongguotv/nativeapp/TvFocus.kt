// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/**
 * 让输入框的上下键用于移动焦点而不是移动光标。
 *
 * 优先走 nextFocusUpId / nextFocusDownId（搜索框的焦点链本来就由筛选项和联想词写好），
 * 没有显式目标时退回系统 focusSearch；左右键仍留给光标定位。
 */
internal fun EditText.escapeVerticalFocus() {
    setOnKeyListener { view, keyCode, event ->
        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
        val direction =
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                else -> return@setOnKeyListener false
            }
        val target = verticalFocusTarget(view, direction) ?: return@setOnKeyListener false
        // 不用带方向的 requestFocus：它在 API 26 以下没有公开方法。
        target.requestFocus()
        true
    }
}

private fun verticalFocusTarget(view: View, direction: Int): View? {
    val explicit = if (direction == View.FOCUS_UP) view.nextFocusUpId else view.nextFocusDownId
    if (explicit != View.NO_ID && explicit != view.id) {
        val target = (view.rootView as? ViewGroup)?.findViewById<View>(explicit)
        if (target != null && target !== view && target.visibility == View.VISIBLE) return target
    }
    return view
        .focusSearch(direction)
        ?.takeIf { it !== view && it.isFocusable && it.visibility == View.VISIBLE }
}
