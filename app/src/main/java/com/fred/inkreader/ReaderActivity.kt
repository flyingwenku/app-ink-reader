package com.fred.inkreader

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.fred.inkreader.epub.EpubBook
import com.fred.inkreader.prefs.ReaderPrefs
import com.fred.inkreader.ui.PageView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * 阅读页：纸感 + 沉浸 + 翻页。
 *
 * 交互（刻意不放大按钮，保持纸面纯净）：
 * - 点屏幕左 1/3：上一页
 * - 点屏幕右 1/3：下一页
 * - 点屏幕中间：唤出 / 收起工具条（目录、字号）
 * - 音量上下键：翻页
 *
 * 旋转 180° 时 Activity 不重建（已声明 configChanges），阅读位置不丢。
 */
class ReaderActivity : Activity() {

    private lateinit var pageView: PageView
    private lateinit var tvChapter: TextView
    private lateinit var tvBook: TextView
    private lateinit var tvInfo: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var tocPanel: LinearLayout
    private lateinit var tocList: ListView
    private lateinit var prefs: ReaderPrefs

    private var executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var scanExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var book: EpubBook
    private var bookId = ""
    private var chapterIndex = 0
    private var loading = false
    private var loadSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        // 阅读时保持常亮（无需任何权限）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = ReaderPrefs(this)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        val file = File(path)
        bookId = file.name
        book = EpubBook(file)
        if (!book.open()) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        pageView = findViewById(R.id.pageView)
        tvChapter = findViewById(R.id.tvChapter)
        tvBook = findViewById(R.id.tvBook)
        tvInfo = findViewById(R.id.tvInfo)
        toolbar = findViewById(R.id.toolbar)
        tocPanel = findViewById(R.id.tocPanel)
        tocList = findViewById(R.id.tocList)

        tvBook.text = book.title

        findViewById<Button>(R.id.btnToc).setOnClickListener { showToc() }
        findViewById<Button>(R.id.btnTocClose).setOnClickListener { hideToc() }
        findViewById<Button>(R.id.btnFontMinus).setOnClickListener { changeFont(-1) }
        findViewById<Button>(R.id.btnFontPlus).setOnClickListener { changeFont(1) }

        pageView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP && !loading) {
                when {
                    event.x < v.width / 3f -> goPrev()
                    event.x > v.width * 2f / 3f -> goNext()
                    else -> toggleToolbar()
                }
            }
            true
        }

        val (chapter, page) = prefs.position(bookId)
        loadChapter(chapter, page)

        // 章节标题后台扫描（读每章前 4KB），不阻塞阅读。
        // 用独立线程，避免与分页排队互相阻塞。
        scanExecutor.execute {
            for (i in 0 until book.chapterCount) book.scanTitle(i)
        }
    }

    // ===== 加载与分页 =====

    private fun loadChapter(index: Int, page: Int) {
        val contentW = pageView.width - pageView.paddingLeft - pageView.paddingRight
        val contentH = pageView.height - pageView.paddingTop - pageView.paddingBottom
        if (contentW <= 0 || contentH <= 0) {
            pageView.post { loadChapter(index, page) }
            return
        }

        loading = true
        val seq = ++loadSeq
        tvChapter.text = titleOf(index)
        tvInfo.text = getString(R.string.loading)

        executor.execute {
            // 后台：解压 + HTML 解析 + 图片解码
            val spanned = book.chapterSpanned(index, resources, contentW)
            // 后台：StaticLayout 排版 + 分页（长章节耗时明显，必须离主线程）
            val result = PageView.paginate(
                spanned, contentW, contentH,
                prefs.fontSizeSp.toFloat(),
                resources.displayMetrics.density
            )
            handler.post {
                // 连点时丢弃过期结果，避免页面错乱
                if (seq != loadSeq) return@post
                loading = false
                chapterIndex = index
                pageView.apply(result, page)
                tvChapter.text = titleOf(index)
                savePosition()
                updateInfo()
            }
        }
    }

    private fun titleOf(index: Int): String {
        val t = book.chapterTitle(index)
        return if (t.isEmpty()) getString(R.string.chapter_fallback, index + 1) else t
    }

    // ===== 翻页 =====

    private fun goNext() {
        if (loading) return
        if (pageView.nextPage()) {
            savePosition()
            updateInfo()
            return
        }
        if (chapterIndex + 1 < book.chapterCount) {
            loadChapter(chapterIndex + 1, 0)
        } else {
            Toast.makeText(this, R.string.last_page, Toast.LENGTH_SHORT).show()
        }
    }

    private fun goPrev() {
        if (loading) return
        if (pageView.prevPage()) {
            savePosition()
            updateInfo()
            return
        }
        if (chapterIndex > 0) {
            loadChapter(chapterIndex - 1, Int.MAX_VALUE)
        } else {
            Toast.makeText(this, R.string.first_page, Toast.LENGTH_SHORT).show()
        }
    }

    private fun changeFont(delta: Int) {
        if (loading) return
        val total = pageView.pageCount
        val ratio = if (total > 0) pageView.currentPage.toFloat() / total else 0f
        prefs.fontSizeIndex =
            (prefs.fontSizeIndex + delta).coerceIn(0, ReaderPrefs.FONT_SIZES.size - 1)
        // 按阅读进度比例还原位置，改字号后不至于跳回开头
        loadChapter(chapterIndex, (total * ratio).toInt())
    }

    // ===== 工具条与目录 =====

    private fun toggleToolbar() {
        toolbar.visibility = if (toolbar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun showToc() {
        toolbar.visibility = View.GONE
        tocList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, tocItems())
        tocList.setOnItemClickListener { _, _, position, _ ->
            hideToc()
            loadChapter(position, 0)
        }
        tocList.setSelection(chapterIndex)
        tocPanel.visibility = View.VISIBLE
    }

    private fun tocItems(): List<String> =
        (0 until book.chapterCount).map { i ->
            val t = book.chapterTitle(i)
            if (t.isEmpty()) getString(R.string.chapter_fallback, i + 1) else t
        }

    private fun hideToc() {
        tocPanel.visibility = View.GONE
    }

    // ===== 信息栏 =====

    private fun updateInfo() {
        val total = max(1, pageView.pageCount)
        val battery = batteryLevel()
        tvInfo.text = if (battery >= 0) {
            getString(R.string.page_info, pageView.currentPage + 1, total, battery)
        } else {
            getString(R.string.page_info_plain, pageView.currentPage + 1, total)
        }
    }

    /** 电量来自电池广播（sticky 广播，registerReceiver(null, ...) 即可同步拿到） */
    private fun batteryLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level < 0 || scale <= 0) -1 else (level * 100f / scale).toInt()
    }

    private fun savePosition() {
        prefs.savePosition(bookId, chapterIndex, pageView.currentPage)
    }

    // ===== 生命周期 =====

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        // API 30+ 的 WindowInsetsController 在 minSdk 21 下用不了，只能用这套 flag
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                goPrev()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                goNext()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (tocPanel.visibility == View.VISIBLE) {
            hideToc()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        scanExecutor.shutdownNow()
        if (::book.isInitialized) book.close()
    }

    companion object {
        const val EXTRA_PATH = "book_path"
    }
}
