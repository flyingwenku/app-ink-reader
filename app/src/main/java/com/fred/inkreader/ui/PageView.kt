package com.fred.inkreader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.fred.inkreader.model.Highlight
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 自绘分页 View：StaticLayout 切页 + canvas 平移翻页 + 划线笔记。
 *
 * 为什么自绘而不用 WebView：
 * - WebView 在旧手机上偏重（内存 + 冷启动），本项目面向旧手机
 * - 分页精确：能算出总页数，才能显示"12 / 345 页"
 * - 排版完全可控：行高、字距、纸色都由我们决定
 * - 选词/划线反而更直接：整章只有一个 StaticLayout，
 *   触摸坐标 → getLineForVertical → getOffsetForHorizontal 即可反查字符偏移
 *
 * 与项目二的 SevenSegmentView 一脉相承：自绘方案已在前一个项目验证过。
 */
class PageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 一次分页的结果，不可变，可在后台线程生成 */
    class Paged(val layout: StaticLayout, private val offsets: IntArray) {
        val pageCount: Int
            get() = offsets.size

        fun offsetOf(page: Int): Int = offsets[page]

        /** 当前页的结束偏移（不含） */
        fun endOf(page: Int): Int =
            if (page + 1 < offsets.size) offsets[page + 1] else layout.text.length
    }

    // ===== 回调（由 Activity 决定语义）=====

    /** 普通点击：参数是 x / width，交给 Activity 判断左翻页 / 右翻页 / 工具条 */
    var onPageTap: ((Float) -> Unit)? = null

    /** 点到已有划线 */
    var onHighlightTap: ((Highlight) -> Unit)? = null

    /** 选区确定：长按选句完成，或把手拖完松手 */
    var onSelectionReady: ((Int, Int) -> Unit)? = null

    /** 当前章的划线，用于绘制下划线；赋值即重绘 */
    var highlights: List<Highlight> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private var paged: Paged? = null
    private var page = 0

    // 选区状态：selStart < 0 表示无选区
    private var selStart = -1
    private var selEnd = -1
    private var dragHandle = 0          // 0=无 1=起点把手 2=终点把手
    private var longPressFired = false
    private var downX = 0f
    private var downY = 0f

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density: Float get() = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val selPaint = Paint().apply {
        color = Color.parseColor("#8A8A8A")
        alpha = 70                       // 淡灰块，符合纸面观感
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B2B2B")
    }
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B2B2B")
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private val longPressRun = Runnable {
        longPressFired = true
        val off = offsetAt(downX, downY)
        if (off >= 0) {
            val text = paged?.layout?.text ?: return@Runnable
            val range = sentenceBounds(text, off)
            selStart = range.first
            selEnd = range.last + 1
            invalidate()
            onSelectionReady?.invoke(selStart, selEnd)
        }
    }

    companion object {
        private const val LINE_SPACING = 1.7f      // 行高：借鉴墨水屏观感
        private const val LETTER_SPACING = 0.02f   // 字间距（API 21 可用）
        private const val INK = "#2B2B2B"          // 刻意不用纯黑

        /**
         * 计算分页，必须在后台线程调用（长章节耗时明显）。
         *
         * @param contentW 内容区宽度（已扣除左右留白）
         * @param contentH 内容区高度
         */
        fun paginate(
            text: Spanned,
            contentW: Int,
            contentH: Int,
            sizeSp: Float,
            density: Float
        ): Paged {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = sizeSp * density
                color = Color.parseColor(INK)
                letterSpacing = LETTER_SPACING
            }

            // minSdk 21：只能用老构造器（StaticLayout.Builder 是 API 23+）
            val layout = StaticLayout(
                text, paint, contentW,
                Layout.Alignment.ALIGN_NORMAL,
                LINE_SPACING, 0f, true
            )

            val offsets = ArrayList<Int>()
            offsets.add(0)
            var line = 0
            while (line < layout.lineCount) {
                var end = line
                while (end + 1 < layout.lineCount &&
                    layout.getLineBottom(end + 1) - layout.getLineTop(line) <= contentH
                ) {
                    end++
                }
                val next = end + 1
                if (next < layout.lineCount) offsets.add(layout.getLineStart(next))
                line = next
            }
            return Paged(layout, offsets.toIntArray())
        }
    }

    fun apply(paged: Paged, target: Int) {
        this.paged = paged
        page = target.coerceIn(0, max(0, paged.pageCount - 1))
        clearSelection()
        invalidate()
    }

    val pageCount: Int
        get() = paged?.pageCount ?: 0

    val currentPage: Int
        get() = page

    /** 返回 false 表示本章已翻到末尾（由 Activity 决定是否切章） */
    fun nextPage(): Boolean {
        val p = paged ?: return false
        if (page + 1 >= p.pageCount) return false
        page++
        clearSelection()
        invalidate()
        return true
    }

    fun prevPage(): Boolean {
        if (page <= 0) return false
        page--
        clearSelection()
        invalidate()
        return true
    }

    /** 取选区 / 划线原文 */
    fun textAt(start: Int, end: Int): String {
        val text = paged?.layout?.text ?: return ""
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        return text.subSequence(s, e).toString()
    }

    fun clearSelection() {
        selStart = -1
        selEnd = -1
        dragHandle = 0
    }

    /** 当前选区（半开区间概念：first..last 含端点），无选区为 null */
    val selection: IntRange?
        get() = if (selEnd > selStart) selStart until selEnd else null

    /** 只显示选区，不触发笔记流程（用于"跳回原文"时定位） */
    fun selectRange(start: Int, end: Int) {
        selStart = start
        selEnd = max(start + 1, end)
        dragHandle = 0
        invalidate()
    }

    /** 字符偏移 → 页码 */
    fun pageOf(offset: Int): Int {
        val p = paged ?: return 0
        var result = 0
        for (i in 0 until p.pageCount) {
            if (p.offsetOf(i) <= offset) result = i else break
        }
        return result
    }

    /** 跳到指定字符偏移所在页 */
    fun gotoOffset(offset: Int) {
        val p = paged ?: return
        page = pageOf(offset).coerceIn(0, p.pageCount - 1)
        invalidate()
    }

    // ===== 绘制 =====

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = paged ?: return
        val layout = p.layout
        val top = layout.getLineTop(layout.getLineForOffset(p.offsetOf(page)))

        canvas.save()
        // 先裁剪内容区（防上下页内容溢出到留白处），再平移到当前页
        canvas.clipRect(
            paddingLeft,
            paddingTop,
            width - paddingRight,
            height - paddingBottom
        )
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat() - top)

        drawUnderlines(canvas, layout)
        if (selEnd > selStart) drawSelection(canvas, layout)
        layout.draw(canvas)
        if (selEnd > selStart) drawHandles(canvas, layout)

        canvas.restore()
    }

    /** 已有笔记：在文字下方画下划线（逐行画，跨行也连续） */
    private fun drawUnderlines(canvas: Canvas, layout: Layout) {
        val p = paged ?: return
        val pageStart = p.offsetOf(page)
        val pageEnd = p.endOf(page)
        val y = underlinePaint.strokeWidth
        highlights.forEach { h ->
            if (h.end <= pageStart || h.start >= pageEnd) return@forEach
            val s = max(h.start, pageStart)
            val e = min(h.end, pageEnd - 1)
            var line = layout.getLineForOffset(s)
            val last = layout.getLineForOffset(e)
            while (line <= last && line < layout.lineCount) {
                val lineEnd = layout.getLineEnd(line)
                val xs = layout.getPrimaryHorizontal(max(s, layout.getLineStart(line)))
                val xe = if (e >= lineEnd - 1) layout.getLineWidth(line)
                else layout.getPrimaryHorizontal(e)
                val ly = layout.getLineBottom(line).toFloat() - y
                if (xe > xs) canvas.drawLine(xs, ly, xe, ly, underlinePaint)
                line++
            }
        }
    }

    /** 选区：淡灰块（画在文字之下） */
    private fun drawSelection(canvas: Canvas, layout: Layout) {
        val path = Path()
        layout.getSelectionPath(selStart, selEnd, path)
        canvas.drawPath(path, selPaint)
    }

    /** 选区两端把手（画在文字之上，方便抓） */
    private fun drawHandles(canvas: Canvas, layout: Layout) {
        val r = 8f * density
        drawHandle(canvas, layout, selStart, r, true)
        drawHandle(canvas, layout, selEnd, r, false)
    }

    private fun drawHandle(canvas: Canvas, layout: Layout, off: Int, r: Float, isStart: Boolean) {
        val line = layout.getLineForOffset(off)
        val x = layout.getPrimaryHorizontal(off)
        val y = if (isStart) layout.getLineTop(line).toFloat() else layout.getLineBottom(line).toFloat()
        canvas.drawCircle(x, y, r, handlePaint)
    }

    // ===== 触摸：长按选句 + 把手微调 + 点击命中 =====

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                longPressFired = false
                dragHandle = handleAt(event.x, event.y)
                if (dragHandle == 0) {
                    handler.removeCallbacks(longPressRun)
                    handler.postDelayed(longPressRun, 400)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    handler.removeCallbacks(longPressRun)   // 移动了就不是长按
                }
                if (dragHandle != 0) {
                    val off = offsetAt(event.x, event.y)
                    if (off >= 0) {
                        if (dragHandle == 1) {
                            if (off < selEnd) selStart = off
                        } else if (off > selStart) {
                            selEnd = off
                        }
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRun)
                val moved = abs(event.x - downX) > slop || abs(event.y - downY) > slop
                when {
                    dragHandle != 0 -> {
                        // 把手拖完松手：选区变了就再弹一次笔记框
                        dragHandle = 0
                        if (selEnd > selStart) onSelectionReady?.invoke(selStart, selEnd)
                    }
                    longPressFired -> { /* 长按已处理，保持选区供把手调整 */ }
                    !moved -> {
                        val off = offsetAt(event.x, event.y)
                        val hit = if (off >= 0) highlightAt(off) else null
                        if (hit != null) {
                            clearSelection()
                            invalidate()
                            onHighlightTap?.invoke(hit)
                        } else if (selEnd > selStart) {
                            // 选区还在时点击 = 取消选区，不翻页（避免误触）
                            clearSelection()
                            invalidate()
                        } else {
                            onPageTap?.invoke(event.x / width)
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRun)
                dragHandle = 0
                return true
            }
        }
        return true
    }

    /** 命中哪个把手：0=无 1=起点 2=终点（触摸半径放大，好抓） */
    private fun handleAt(x: Float, y: Float): Int {
        if (selEnd <= selStart) return 0
        val p = paged ?: return 0
        val layout = p.layout
        val top = layout.getLineTop(layout.getLineForOffset(p.offsetOf(page)))
        val lx = x - paddingLeft
        val ly = y - paddingTop + top
        val r = 22f * density
        fun near(off: Int, isStart: Boolean): Boolean {
            val line = layout.getLineForOffset(off)
            val hx = layout.getPrimaryHorizontal(off)
            val hy =
                if (isStart) layout.getLineTop(line).toFloat() else layout.getLineBottom(line).toFloat()
            return abs(lx - hx) <= r && abs(ly - hy) <= r
        }
        if (near(selStart, true)) return 1
        if (near(selEnd, false)) return 2
        return 0
    }

    /** 触摸坐标 → 章内字符偏移（限制在当前页内） */
    private fun offsetAt(x: Float, y: Float): Int {
        val p = paged ?: return -1
        val layout = p.layout
        val pageStart = p.offsetOf(page)
        val pageEnd = max(pageStart + 1, p.endOf(page))
        val top = layout.getLineTop(layout.getLineForOffset(pageStart))
        val ly = (y - paddingTop + top).toInt()
        val line = layout.getLineForVertical(ly).coerceIn(0, layout.lineCount - 1)
        val off = layout.getOffsetForHorizontal(line, x - paddingLeft)
        return off.coerceIn(pageStart, pageEnd - 1)
    }

    private fun highlightAt(off: Int): Highlight? =
        highlights.firstOrNull { it.start <= off && off < it.end }

    /**
     * 以句为选区（中文 ICU 分词不可靠，选整句最可预期）：
     * 从落点向两侧扫到句末标点或换行为止，并带上句末标点。
     */
    private fun sentenceBounds(text: CharSequence, off: Int): IntRange {
        val bounds = "。！？；…\n”』」"
        var s = off
        while (s > 0 && text[s - 1] !in bounds) s--
        var e = off
        while (e < text.length && text[e] !in bounds) e++
        if (e < text.length && text[e] in bounds) e++      // 含句末标点
        if (e <= s) e = min(text.length, s + 1)

        // 吃掉行首空白（首行缩进的全角空格不该出现在摘要里）
        val ws = " \t\n　"
        while (s < e && text[s] in ws) s++

        // 限制在当前页内
        val p = paged
        if (p != null) {
            val pageStart = p.offsetOf(page)
            val pageEnd = max(pageStart + 1, p.endOf(page))
            s = max(s, pageStart)
            e = min(e, pageEnd)
        }
        return s until max(s + 1, e)
    }
}
