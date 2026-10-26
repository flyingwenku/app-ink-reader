package com.fred.inkreader.model

/**
 * 导出为 md：章节分组 + 摘要作标题（Obsidian 里可直接折叠定位）。
 *
 * ```
 * ## 推荐序一
 * ### 摘要原文（作标题，一眼能扫）
 * 笔记正文（没写笔记就空着）
 *
 * ### 另一句摘要
 * ```
 *
 * 摘要用 `###` 而不是引用块：引用块 `>` 与正文混在一起分不清层级，
 * 用标题既区分了层级，又能在 Obsidian 大纲里直接跳转。
 */
object NotesExporter {

    fun toMarkdown(list: List<Highlight>, chapterTitle: (Int) -> String): String = buildString {
        var lastChapter = -1
        list.forEach { h ->
            if (h.chapter != lastChapter) {
                lastChapter = h.chapter
                if (isNotEmpty()) append('\n')
                append("## ").append(chapterTitle(h.chapter).trim()).append('\n')
            }
            append("### ").append(h.text.trim()).append('\n')
            if (h.note.isNotBlank()) append(h.note.trim()).append('\n')
            append('\n')
        }
    }
}
