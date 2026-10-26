package com.fred.inkreader.prefs

import android.content.Context

class ReaderPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /** 最近打开的书的文件路径 */
    var lastBook: String
        get() = prefs.getString(KEY_LAST, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST, value).apply()
        }

    /** 字号档位下标（0..3），默认第 2 档 */
    var fontSizeIndex: Int
        get() = prefs.getInt(KEY_FONT, 1)
        set(value) {
            prefs.edit().putInt(KEY_FONT, value).apply()
        }

    val fontSizeSp: Int
        get() = FONT_SIZES[fontSizeIndex.coerceIn(0, FONT_SIZES.size - 1)]

    /** 每本书的阅读位置："章:页" */
    fun position(bookId: String): Pair<Int, Int> {
        val raw = prefs.getString("$KEY_POS$bookId", "0:0") ?: "0:0"
        val parts = raw.split(":")
        val chapter = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val page = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return chapter to page
    }

    fun savePosition(bookId: String, chapter: Int, page: Int) {
        prefs.edit().putString("$KEY_POS$bookId", "$chapter:$page").apply()
    }

    companion object {
        private const val KEY_LAST = "last_book"
        private const val KEY_FONT = "font_index"
        private const val KEY_POS = "pos_"

        /** 4 档字号。MVP 只做字号，不做行距 / 字体选择 */
        val FONT_SIZES = intArrayOf(18, 20, 22, 24)
    }
}
