package com.fred.inkreader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * 自绘分页 View：StaticLayout 切页 + canvas 平移翻页。
 *
 * 为什么自绘而不用 WebView：
 * - WebView 在旧手机上偏重（内存 + 冷启动），本项目面向旧手机
 * - 分页精确：能算出总页数，才能显示"12 / 345 页"
 * - 排版完全可控：行高、字距、纸色都由我们决定
 * - 后续加翻页动画只需改绘制部分
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
    }

    private var paged: Paged? = null
    private var page = 0

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
        invalidate()
        return true
    }

    fun prevPage(): Boolean {
        if (page <= 0) return false
        page--
        invalidate()
        return true
    }

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
        layout.draw(canvas)
        canvas.restore()
    }
}
