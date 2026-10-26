package com.fred.inkreader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.fred.inkreader.epub.EpubBook
import com.fred.inkreader.prefs.ReaderPrefs
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * 入口页：书架（多本书）+ 打开本地 epub。
 *
 * 打开方式用 Storage Access Framework（ACTION_OPEN_DOCUMENT），
 * 因此**不需要任何存储权限**——与项目二「不要定位权限」同一思路。
 *
 * 书架即私有目录 books/ 下的全部 epub（导入过的都在），按导入时间倒序。
 */
class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: ReaderPrefs
    private lateinit var bookList: ListView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = ReaderPrefs(this)

        bookList = findViewById(R.id.bookList)
        tvEmpty = findViewById(R.id.tvEmpty)
        findViewById<Button>(R.id.btnOpen).setOnClickListener { pickBook() }
        findViewById<TextView>(R.id.tvVersion).text =
            getString(R.string.settings_version, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        val books = shelfFiles()
        bookList.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            books.map { it.nameWithoutExtension }
        )
        bookList.setOnItemClickListener { _, _, position, _ -> open(books[position]) }
        bookList.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
        tvEmpty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 书架：导入目录下所有 epub，最近导入的在前 */
    private fun shelfFiles(): List<File> =
        File(filesDir, "books")
            .listFiles { f -> f.isFile && f.name.endsWith(".epub", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    @Suppress("DEPRECATION")
    private fun pickBook() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // 各文件管理器对 application/epub+zip 的认法不一致，用 */* 再按后缀与内容验证
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_OPEN)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_file_manager, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        executor.execute {
            val file = importBook(uri)
            handler.post {
                if (file == null) {
                    Toast.makeText(this, R.string.not_epub, Toast.LENGTH_SHORT).show()
                } else {
                    open(file)
                }
            }
        }
    }

    /**
     * 复制到私有目录后再用 ZipFile 打开。
     *
     * 为什么必须复制：EPUB 是 ZIP，需要随机访问；
     * 而从 content URI 只能拿到顺序流，每次翻页重扫一遍会非常慢。
     */
    private fun importBook(uri: Uri): File? {
        val name = (displayName(uri) ?: "book.epub").let {
            if (it.endsWith(".epub", true)) it else "$it.epub"
        }
        val dir = File(filesDir, "books")
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, name.replace(Regex("[/\\\\:*?\"<>|]"), "_"))
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { ins -> FileOutputStream(target).use { out -> ins.copyTo(out) } }

            // 立即验证：不是 epub 或受 DRM 保护就直接丢弃，避免脏文件堆积
            val probe = EpubBook(target)
            val ok = probe.open()
            probe.close()
            if (!ok) {
                target.delete()
                null
            } else {
                target
            }
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    private fun displayName(uri: Uri): String? =
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }

    private fun open(file: File) {
        prefs.lastBook = file.absolutePath
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_PATH, file.absolutePath)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    companion object {
        private const val REQ_OPEN = 1001
    }
}
