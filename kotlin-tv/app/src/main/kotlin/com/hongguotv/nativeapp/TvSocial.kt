// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.media3.common.Player
import com.hongguotv.core.*
import java.util.concurrent.Executors
import org.json.JSONObject

/** Owns only anonymous social reads. Playback and account writes are independent. */
class TvSocial(
    private val activity: Activity,
    private val player: () -> Player?,
    private val identity: () -> Pair<String, String>?,
    private val readComments: (String, String, String) -> JSONObject = AppCommentPages()::read,
    private val readReviews: (String, String) -> JSONObject =
        PublicComments(okhttp3.OkHttpClient())::read,
) {
    private val emoji = CommentEmoji(activity)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val timed = TimedComments()
    private val prefs = activity.getSharedPreferences("social", 0)
    private var active = false
    private var key: Pair<String, String>? = null
    private var scope: RequestScope? = null
    private var commentScope: RequestScope? = null
    private var generation = 0
    private var dialog: Dialog? = null
    private var overlay: DanmakuView? = null
    private var loading = false
    private var failedAt = 0L
    private val pages = linkedMapOf<Long, JSONObject>()

    init {
        migratePreferences()
    }

    /**
     * 旧版本把弹幕设置存成「开关 + 字号 + 行数 + 速度毫秒」，现在换成
     * 「显示方式 + 速度档位 + 字号缩放 + 透明度」。只迁移一次，迁完把旧键删掉。
     */
    private fun migratePreferences() {
        if (!prefs.contains("enabled") && !prefs.contains("font")) return
        val edit = prefs.edit()
        if (!prefs.contains("mode")) {
            val mode =
                if (prefs.getBoolean("enabled", true)) DanmakuMode.FULL else DanmakuMode.HIDDEN
            edit.putString("mode", mode.storedValue)
            edit.putFloat("scale", DanmakuStyle.scale(prefs.getInt("font", 24) / 24f))
        }
        edit.remove("enabled").remove("font").remove("lanes").remove("travel").apply()
    }

    private fun mode() = DanmakuMode.fromStored(prefs.getString("mode", null))
    private fun speed() = DanmakuSpeed.fromStored(prefs.getString("speed", null))
    private fun scale() = DanmakuStyle.scale(prefs.getFloat("scale", 1f))
    private fun alpha() = DanmakuStyle.alpha(prefs.getFloat("alpha", 1f))

    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()

    private val tick =
        object : Runnable {
            override fun run() {
                if (!active) return
                val next = identity()
                if (next != key) {
                    reset()
                    key = next
                }
                val p = player()
                overlay?.invalidate()
                if (
                    p != null &&
                        next != null &&
                        mode().showsStyle &&
                        !loading &&
                        p.duration in 1..86400000 &&
                        p.currentPosition < p.duration &&
                        (failedAt == 0L ||
                            android.os.SystemClock.elapsedRealtime() - failedAt > 15000)
                ) {
                    val start = p.currentPosition / 30000 * 30000
                    val desired =
                        listOf(
                            start,
                            if (start > 0 && p.currentPosition - start < 15000) start - 30000
                            else start,
                            if (p.currentPosition - start > 20000 && start + 30000 < p.duration)
                                start + 30000
                            else start,
                        )
                    desired
                        .firstOrNull { !pages.containsKey(it) }
                        ?.let { loadTimed(next, it, p.duration) }
                }
                main.postDelayed(this, 500)
            }
        }

    fun attach(root: android.widget.FrameLayout) {
        overlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        overlay =
            DanmakuView(activity).also { view ->
                view.positionMillis = { player()?.currentPosition ?: 0L }
                view.playing = { player()?.isPlaying == true }
                view.mode = mode()
                view.speed = speed()
                view.textScale = scale()
                view.textAlpha = alpha()
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
            }
        publish()
    }

    fun foreground(value: Boolean) {
        active = value
        main.removeCallbacks(tick)
        if (value) main.post(tick)
        else {
            reset()
            overlay?.invalidate()
        }
    }

    fun reset() {
        generation++
        scope?.cancel()
        scope = null
        commentScope?.cancel()
        commentScope = null
        loading = false
        failedAt = 0
        pages.clear()
        overlay?.clear()
        dialog?.dismiss()
        dialog = null
        key = null
    }

    fun destroy() {
        foreground(false)
        worker.shutdownNow()
    }

    private fun loadTimed(ids: Pair<String, String>, start: Long, duration: Long) {
        loading = true
        val ticket = generation
        val request = RequestScope()
        scope = request
        worker.submit {
            try {
                val page = request.run { timed.read(ids.first, ids.second, start, duration) }
                main.post {
                    if (ticket != generation || !active) return@post
                    loading = false
                    pages[start] = page
                    while (pages.size > 6) pages.remove(
                        pages.keys.maxBy { kotlin.math.abs(it - start) }
                    )
                    publish()
                }
            } catch (_: Exception) {
                main.post {
                    if (ticket == generation) {
                        loading = false
                        failedAt = android.os.SystemClock.elapsedRealtime()
                    }
                }
            }
        }
    }

    /**
     * 把已经拿到的几页弹幕合成一份交给弹幕层。
     *
     * 窗口是 30 秒一段、最多留 6 页，相邻窗口在边界上会重复同一条，因此按 `id` 去重；
     * 时间点缺失或文本为空的条目直接跳过。
     */
    private fun publish() {
        val seen = mutableSetOf<String>()
        val items = mutableListOf<Danmaku>()
        pages.values.forEach { page ->
            val rows = page.optJSONArray("items") ?: return@forEach
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optString("id")
                if (id.isBlank() || !seen.add(id)) continue
                val text = row.optString("text").trim()
                val at = row.optLong("timeMS", -1)
                if (text.isEmpty() || at < 0) continue
                items += Danmaku(at, text)
            }
        }
        overlay?.setDanmaku(items)
    }

    private fun label(text: String, size: Float = 18f, color: Int = TvStyle.text) =
        TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }

    /** 弹窗内按钮：与全站同一套三台阶（凸起 / 按进去 / 打亮）。 */
    private fun button(text: String, action: () -> Unit) =
        label(text).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            minimumHeight = dp(TvStyle.BUTTON_COMPACT + TvStyle.SOFT_BAND * 2)
            setPadding(
                dp(14 + TvStyle.SOFT_BAND),
                dp(TvStyle.SOFT_BAND),
                dp(14 + TvStyle.SOFT_BAND),
                dp(TvStyle.SOFT_BAND),
            )
            background = TvStyle.soft(
                activity,
                TvStyle.surface,
                TvStyle.RADIUS_PILL,
                TvStyle.SOFT_BAND,
                SoftStyle.RAISED,
            )
            setOnFocusChangeListener { view, focus ->
                background =
                    when {
                        focus ->
                            TvStyle.soft(
                                activity,
                                TvStyle.lit,
                                TvStyle.RADIUS_PILL,
                                TvStyle.SOFT_BAND,
                                SoftStyle.RAISED,
                                glow = TvStyle.litGlow,
                                depth = 1.2f,
                            )
                        else ->
                            TvStyle.soft(
                                activity,
                                TvStyle.surface,
                                TvStyle.RADIUS_PILL,
                                TvStyle.SOFT_BAND,
                                SoftStyle.RAISED,
                            )
                    }
                setTextColor(if (focus) TvStyle.onLit else TvStyle.text)
                TvStyle.focus(view, focus)
            }
            setOnClickListener { action() }
        }

    /**
     * 弹幕设置面板：显示方式与速度各一行单选，字号与透明度各一条滑条。
     *
     * 与参考实现一致，四项都即时生效（改完立刻能看到画面变化），不需要「保存」。
     * 隐藏档下字号 / 透明度没有意义，两条滑条置灰并退出焦点。
     */
    fun settings(anchor: View) {
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(26), dp(22), dp(26), dp(22))
                background =
                    TvStyle.soft(
                        activity,
                        TvStyle.surfaceHigh,
                        TvStyle.RADIUS_DIALOG,
                        TvStyle.SOFT_BAND_LARGE,
                        SoftStyle.RAISED,
                        depth = 1.1f,
                    )
            }
        val scaleSlider =
            DanmakuSlider.scale(activity, scale()) { value ->
                prefs.edit().putFloat("scale", value).apply()
                overlay?.textScale = value
            }
        val alphaSlider =
            DanmakuSlider.alpha(activity, alpha()) { value ->
                prefs.edit().putFloat("alpha", value).apply()
                overlay?.textAlpha = value
            }
        fun syncSliders() {
            val on = mode().showsStyle
            scaleSlider.setEnabled(on)
            alphaSlider.setEnabled(on)
        }
        val modeRow =
            choices(
                DanmakuMode.entries.map { it.label },
                { DanmakuMode.entries.indexOf(mode()) },
            ) { index ->
                val value = DanmakuMode.entries[index]
                prefs.edit().putString("mode", value.storedValue).apply()
                overlay?.mode = value
                syncSliders()
            }
        val speedRow =
            choices(
                DanmakuSpeed.entries.map { it.label },
                { DanmakuSpeed.entries.indexOf(speed()) },
            ) { index ->
                val value = DanmakuSpeed.entries[index]
                prefs.edit().putString("speed", value.storedValue).apply()
                overlay?.speed = value
            }
        syncSliders()

        body.addView(label("弹幕设置", 22f, TvStyle.textStrong))
        body.addView(section("显示方式"))
        body.addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        body.addView(section("速度"))
        body.addView(speedRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        body.addView(
            scaleSlider,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(TvStyle.GAP_BLOCK) },
        )
        body.addView(
            alphaSlider,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(TvStyle.GAP_INLINE) },
        )
        body.addView(
            button("重新读取弹幕") {
                reset()
                d.dismiss()
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(TvStyle.GAP_BLOCK) },
        )
        body.addView(
            label("真实时间轴弹幕；表情标签显示为图片，无内容时不使用普通评论代替。", 13f, TvStyle.muted)
                .apply { setPadding(dp(12), dp(TvStyle.GAP_INLINE), dp(12), dp(9)) }
        )
        body.addView(
            button("关闭") { d.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(TvStyle.GAP_INLINE) },
        )
        d.setContentView(body)
        d.setOnDismissListener { if (anchor.isAttachedToWindow) anchor.requestFocus() }
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.show()
        d.window?.setLayout(dp(620), -2)
        modeRow.getChildAt(0).requestFocus()
    }

    /** 面板里的小标题：与上方控件拉开一段距离，和下面的按钮贴在一起。 */
    private fun section(text: String) =
        label(text, 14f, TvStyle.muted).apply {
            setPadding(dp(12), dp(TvStyle.GAP_BLOCK), dp(12), dp(2))
        }

    /**
     * 一行单选按钮：选中按进去、焦点打亮，和全站按钮同一套三台阶。
     *
     * [selected] 每次点击后重新取一遍，避免把状态复制到每个按钮上各存一份。
     */
    private fun choices(
        labels: List<String>,
        selected: () -> Int,
        choose: (Int) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val views = mutableListOf<TextView>()
        fun paint(view: TextView) {
            val focused = view.hasFocus()
            val chosen = view.isSelected
            view.background =
                when {
                    focused ->
                        TvStyle.soft(
                            activity,
                            TvStyle.lit,
                            TvStyle.RADIUS_PILL,
                            TvStyle.SOFT_BAND,
                            SoftStyle.RAISED,
                            glow = TvStyle.litGlow,
                            depth = 1.2f,
                        )
                    chosen ->
                        TvStyle.well(
                            activity,
                            TvStyle.well,
                            TvStyle.RADIUS_PILL,
                            TvStyle.SOFT_BAND,
                            depth = 1.15f,
                        )
                    else ->
                        TvStyle.soft(
                            activity,
                            TvStyle.surface,
                            TvStyle.RADIUS_PILL,
                            TvStyle.SOFT_BAND,
                            SoftStyle.RAISED,
                        )
                }
            view.setTextColor(
                when {
                    focused -> TvStyle.onLit
                    chosen -> TvStyle.accent
                    else -> TvStyle.text
                }
            )
        }
        lateinit var sync: () -> Unit
        sync = {
            val index = selected()
            views.forEachIndexed { i, view ->
                view.isSelected = i == index
                paint(view)
            }
        }
        labels.forEachIndexed { index, text ->
            val view =
                TextView(activity).apply {
                    id = View.generateViewId()
                    this.text = text
                    textSize = 14f
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    maxLines = 1
                    isFocusable = true
                    isFocusableInTouchMode = true
                    minimumHeight = dp(TvStyle.BUTTON_COMPACT)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnFocusChangeListener { v, focused ->
                        paint(v as TextView)
                        TvStyle.focus(v, focused)
                    }
                    setOnClickListener {
                        choose(index)
                        sync()
                    }
                }
            views += view
            row.addView(
                view,
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(TvStyle.GAP_INLINE) },
            )
        }
        sync()
        return row
    }

    fun showComments(anchor: View, target: Pair<String, String>? = null) {
        val ids = target ?: identity() ?: return
        dialog?.dismiss()
        commentScope?.cancel()
        key = ids
        val ticket = ++generation
        // The timed read belongs to the old generation as well.
        scope?.cancel()
        loading = false
        val d = Dialog(activity)
        dialog = d
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var cursor = ""
        var more = true
        var busy = false
        var reviewMode = false
        var disposed = false
        var pageGeneration = 0
        val seen = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(20), dp(22), dp(20))
                background =
                    TvStyle.soft(
                        activity,
                        TvStyle.surfaceHigh,
                        TvStyle.RADIUS_DIALOG,
                        TvStyle.SOFT_BAND_LARGE,
                        SoftStyle.RAISED,
                        depth = 1.1f,
                    )
            }
        body.addView(label("评论与剧评", 22f, TvStyle.textStrong))
        val tabs = LinearLayout(activity)
        body.addView(
            tabs,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(TvStyle.GAP_INLINE) },
        )
        val status = label("匿名读取 · 点赞数量只读", 14f, TvStyle.muted)
        body.addView(status)
        val scroll = ScrollView(activity)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        lateinit var load: TextView
        lateinit var read: () -> Unit
        fun addRow(row: JSONObject) {
            val created = row.optLong("created")
            val date =
                if (created > 0)
                    java.text
                        .SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                        .format(java.util.Date(created * 1000))
                else ""
            val author = row.optString("author").ifBlank { "红果用户" }
            val content = row.optString("content")
            val card =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setPadding(
                        dp(8 + TvStyle.SOFT_BAND),
                        dp(6 + TvStyle.SOFT_BAND),
                        dp(8 + TvStyle.SOFT_BAND),
                        dp(8 + TvStyle.SOFT_BAND),
                    )
                    background = TvStyle.card(activity, false)
                    contentDescription = "$author，$content"
                    setOnFocusChangeListener { view, focused ->
                        background = TvStyle.card(activity, focused)
                        TvStyle.focus(view, focused, 1.02f)
                    }
                }
            card.addView(
                label("$author  ·  赞 ${row.optLong("likes")}  ·  $date", 13f).apply {
                    setTextColor(TvStyle.muted)
                    setPadding(0, 0, 0, dp(8))
                }
            )
            card.addView(
                label("", 20f).apply {
                    text = emoji.render(content, textSize * 1.2f)
                    setPadding(0, 0, 0, 0)
                    setLineSpacing(dp(6).toFloat(), 1f)
                }
            )
            card.setOnClickListener {
                val expanded =
                    android.app.AlertDialog.Builder(activity)
                        .setTitle(author)
                        .setMessage(emoji.render(content, dp(24).toFloat()))
                        .setPositiveButton("关闭", null)
                        .create()
                expanded.setOnDismissListener { if (card.isAttachedToWindow) card.requestFocus() }
                expanded.show()
                decorateDialog(expanded)
            }
            list.addView(
                card,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(TvStyle.GAP_INLINE) },
            )
        }
        read = {
            if (!busy && more && !disposed) {
                busy = true
                load.text = "正在读取…"
                val sent = cursor
                val mode = reviewMode
                val batch = pageGeneration
                val request = RequestScope()
                commentScope = request
                worker.submit {
                    try {
                        val page =
                            request.run {
                                if (mode)
                                    readReviews(ids.first, ids.second).getJSONObject("reviews")
                                else readComments(ids.first, ids.second, sent)
                            }
                        main.post {
                            if (
                                disposed ||
                                    ticket != generation ||
                                    batch != pageGeneration ||
                                    mode != reviewMode
                            )
                                return@post
                            busy = false
                            val previousScroll = scroll.scrollY
                            val rows = page.getJSONArray("items")
                            for (i in 0 until rows.length()) {
                                val row = rows.getJSONObject(i)
                                if (seen.add(row.getString("id"))) addRow(row)
                            }
                            scroll.post { if (!disposed) scroll.scrollTo(0, previousScroll) }
                            more = !mode && page.getBoolean("hasMore")
                            if (more) {
                                val next = page.getString("nextCursor")
                                if (!cursors.add(next)) {
                                    more = false
                                    status.text = "游标重复，停止自动加载；可重新打开重试。"
                                }
                                cursor = next
                            }
                            status.text =
                                if (mode) "官网节选 ${seen.size} 条 / 共 ${page.optLong("total")} 条"
                                else "已加载 ${seen.size} 条 / 共 ${page.optLong("total")} 条"
                            load.text = if (more) "加载更多评论" else if (mode) "官网剧评节选已读完" else "已到末尾"
                        }
                    } catch (_: Exception) {
                        main.post {
                            if (
                                !disposed &&
                                    ticket == generation &&
                                    batch == pageGeneration &&
                                    mode == reviewMode
                            ) {
                                busy = false
                                load.text = "加载失败，按确认重试"
                                status.text = "保留已有评论，重试使用原游标"
                            }
                        }
                    }
                }
            }
        }
        fun select(mode: Boolean) {
            pageGeneration++
            commentScope?.cancel()
            reviewMode = mode
            busy = false
            cursor = ""
            more = true
            seen.clear()
            cursors.clear()
            list.removeAllViews()
            read()
        }
        tabs.addView(button("本集评论") { select(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(
            button("整剧剧评") { select(true) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(TvStyle.GAP_INLINE) },
        )
        load = button("加载更多评论") { read() }
        body.addView(
            load,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(TvStyle.GAP_INLINE) },
        )
        body.addView(label("上下浏览，到底继续加载。发送、回复未接入。", 13f, TvStyle.muted))
        body.addView(
            button("关闭") { d.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(TvStyle.GAP_INLINE) },
        )
        scroll.setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, _: Int ->
            if (!reviewMode && y > 0 && y + scroll.height >= list.height - dp(80)) read()
        }
        d.setContentView(body)
        d.setOnDismissListener {
            disposed = true
            commentScope?.cancel()
            if (dialog === d) dialog = null
            if (anchor.isAttachedToWindow) anchor.requestFocus()
        }
        d.show()
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.END)
            setDimAmount(0f)
            setLayout(
                minOf(dp(500), (activity.resources.displayMetrics.widthPixels * .65).toInt()),
                -1,
            )
        }
        tabs.getChildAt(0).requestFocus()
        read()
    }
}
