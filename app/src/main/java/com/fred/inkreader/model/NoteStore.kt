package com.fred.inkreader.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 笔记库：App 私有目录下的 JSON，每本书一个文件。
 *
 * 这是唯一事实源：下划线、编辑、删除都改这里；
 * .md 只是导出产物，用户手动触发时才生成。
 */
class NoteStore(context: Context) {

    private val dir = File(context.filesDir, "notes").apply { mkdirs() }

    private fun fileOf(bookId: String) = File(dir, safe(bookId) + ".json")

    /** 全书笔记，按 (章, 偏移) 有序 */
    fun all(bookId: String): MutableList<Highlight> {
        val f = fileOf(bookId)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("items") ?: return mutableListOf()
            val out = mutableListOf<Highlight>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Highlight(
                        o.getInt("c"), o.getInt("s"), o.getInt("e"),
                        o.getString("t"), o.optString("n", "")
                    )
                )
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** 某一章的笔记（绘制下划线用） */
    fun chapter(bookId: String, chapter: Int): List<Highlight> =
        all(bookId).filter { it.chapter == chapter }.sortedBy { it.start }

    fun upsert(bookId: String, h: Highlight) {
        val list = all(bookId)
        list.removeAll { it.chapter == h.chapter && it.start == h.start }
        list.add(h)
        list.sortWith(compareBy({ it.chapter }, { it.start }))
        save(bookId, list)
    }

    fun remove(bookId: String, h: Highlight) {
        val list = all(bookId)
        list.removeAll { it.chapter == h.chapter && it.start == h.start }
        save(bookId, list)
    }

    private fun save(bookId: String, list: List<Highlight>) {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(
                JSONObject().apply {
                    put("c", h.chapter)
                    put("s", h.start)
                    put("e", h.end)
                    put("t", h.text)
                    put("n", h.note)
                }
            )
        }
        fileOf(bookId).writeText(JSONObject().put("items", arr).toString())
    }

    /**
     * 文件名安全化：书名常含 / : * ? " 等非法字符，
     * 保留中英文、数字、点、横线、下划线。
     */
    fun safe(name: String): String {
        val s = name.replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_").trim('_')
        return if (s.isBlank()) "notes" else s.take(50)
    }
}
