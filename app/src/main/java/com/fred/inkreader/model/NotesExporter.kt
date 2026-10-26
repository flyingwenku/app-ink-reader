package com.fred.inkreader.model

/**
 * 导出为 md 纯文本。
 *
 * 格式（无 tag，按用户要求）：
 * ```
 * 摘要原文
 * > 笔记内容（没有笔记就没有这一行）
 *
 * ```
 */
object NotesExporter {

    fun toMarkdown(list: List<Highlight>): String = buildString {
        list.forEach { h ->
            append(h.text.trim()).append('\n')
            if (h.note.isNotBlank()) append("> ").append(h.note.trim()).append('\n')
            append('\n')
        }
    }
}
