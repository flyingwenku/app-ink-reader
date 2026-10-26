package com.fred.inkreader.model

/**
 * 一条划线 / 笔记。
 *
 * 定位主键是 (chapter, start, end) —— 章内字符偏移，
 * 不依赖页码：字号、屏宽变了页码会变，但偏移不会。
 *
 * note 为空 = 只有划线（纯摘要），没有写笔记。
 */
data class Highlight(
    val chapter: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val note: String = ""
)
