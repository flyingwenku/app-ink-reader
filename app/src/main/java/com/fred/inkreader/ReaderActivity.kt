package com.fred.inkreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.style.ImageSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fred.inkreader.epub.EpubBook
import com.fred.inkreader.model.Highlight
import com.fred.inkreader.model.NoteStore
import com.fred.inkreader.model.NotesExporter
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
    private lateinit var notePanel: LinearLayout
    private lateinit var noteList: ListView
    private lateinit var noteBar: LinearLayout
    private lateinit var tvSelInfo: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private var cutoutTop = 0
    private lateinit var prefs: ReaderPrefs
    private lateinit var noteStore: NoteStore

    private var executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var scanExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var book: EpubBook
    private var bookId = ""
    private var chapterIndex = 0
    private var loading = false
    private var loadSeq = 0

    // 顶部开孔区处理：自检窗口是否铺满全屏，铺不满则退回纸色状态栏
    private var selfChecked = false
    private var paperStatusBarMode = false
    private var topPadded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        // 阅读时保持常亮（无需任何权限）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = ReaderPrefs(this)
        noteStore = NoteStore(this)

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
        notePanel = findViewById(R.id.notePanel)
        noteList = findViewById(R.id.noteList)
        noteBar = findViewById(R.id.noteBar)
        tvSelInfo = findViewById(R.id.tvSelInfo)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)

        tvBook.text = book.title

        findViewById<Button>(R.id.btnToc).setOnClickListener { showToc() }
        findViewById<Button>(R.id.btnTocClose).setOnClickListener { hideToc() }
        findViewById<Button>(R.id.btnNotes).setOnClickListener { showNotes() }
        findViewById<Button>(R.id.btnNoteClose).setOnClickListener { hideNotes() }
        findViewById<Button>(R.id.btnFontMinus).setOnClickListener { changeFont(-1) }
        findViewById<Button>(R.id.btnFontPlus).setOnClickListener { changeFont(1) }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportNotes() }

        findViewById<Button>(R.id.btnSelWrite).setOnClickListener {
            val r = pageView.selection ?: return@setOnClickListener
            showNoteDialog(null, r.first, r.last + 1)
        }
        findViewById<Button>(R.id.btnSelCancel).setOnClickListener { dismissSelection() }

        // 触摸逻辑全在 PageView 内（长按选句 / 把手 / 划线命中），这里只接结果
        pageView.onPageTap = { ratio ->
            if (!loading) when {
                ratio < 1f / 3f -> goPrev()
                ratio > 2f / 3f -> goNext()
                else -> toggleToolbar()
            }
        }
        // 长按需先让用户调整选区，所以先浮出选区条，不直接弹输入框
        pageView.onSelectionReady = { _, _ -> refreshNoteBar() }
        // 点空白取消选区：选区条自己消失，不必手动点取消
        pageView.onSelectionCleared = { noteBar.visibility = View.GONE }
        pageView.onHighlightTap = { h -> showNoteDialog(h, h.start, h.end) }

        val (chapter, page) = prefs.position(bookId)
        loadChapter(chapter, page)

        // 章节标题后台扫描（读每章前 4KB），不阻塞阅读。
        // 用独立线程，避免与分页排队互相阻塞。
        scanExecutor.execute {
            for (i in 0 until book.chapterCount) book.scanTitle(i)
        }
    }

    // ===== 加载与分页 =====

    /** @param jump 非空时：加载后直接定位到这条笔记所在页，并把该段高亮出来 */
    private fun loadChapter(
        index: Int,
        page: Int,
        jump: Highlight? = null,
        skipDir: Int = 1,
        skips: Int = 0
    ) {
        applySafePadding()
        val contentW = pageView.width - pageView.paddingLeft - pageView.paddingRight
        val contentH = pageView.height - pageView.paddingTop - pageView.paddingBottom
        if (contentW <= 0 || contentH <= 0) {
            pageView.post { loadChapter(index, page, jump, skipDir, skips) }
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
            // 空章节判定：封面/CSS 装帧页解析后既无文本也无图片
            // （很多书的前几页是 SVG 封面或纯样式页，Html.fromHtml 出来是空的）
            val hasImage = spanned.getSpans(0, spanned.length, ImageSpan::class.java).isNotEmpty()
            val blank = spanned.toString().trim().isEmpty() && !hasImage

            handler.post {
                // 连点时丢弃过期结果，避免页面错乱
                if (seq != loadSeq) return@post
                // 空章节自动沿方向跳过（上限全书章数，防全书皆空的死循环）
                if (blank && skips < book.chapterCount) {
                    val next = index + skipDir
                    if (next in 0 until book.chapterCount) {
                        loadChapter(
                            next,
                            if (skipDir > 0) 0 else Int.MAX_VALUE,
                            null, skipDir, skips + 1
                        )
                        return@post
                    }
                }
                loading = false
                chapterIndex = index
                pageView.apply(result, page)
                pageView.highlights = noteStore.chapter(bookId, index)
                if (jump != null) {
                    // 跳回原文：页码由字符偏移反算，改过字号也不会错位
                    pageView.gotoOffset(jump.start)
                    pageView.selectRange(jump.start, jump.end)
                }
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
            loadChapter(chapterIndex - 1, Int.MAX_VALUE, skipDir = -1)
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

    /** 点屏幕中部：标题条 / 工具条 / 状态条一起出现或一起消失（正文全屏） */
    private fun toggleToolbar() {
        val show = toolbar.visibility != View.VISIBLE
        toolbar.visibility = if (show) View.VISIBLE else View.GONE
        topBar.visibility = toolbar.visibility
        bottomBar.visibility = toolbar.visibility
    }

    private fun hideChrome() {
        toolbar.visibility = View.GONE
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
    }

    private fun showToc() {
        hideChrome()
        tocList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, tocItems())
        tocList.setOnItemClickListener { _, _, position, _ ->
            hideToc()
            loadChapter(position, 0)
        }
        tocList.setSelection(chapterIndex)
        tocPanel.visibility = View.VISIBLE
    }

    private fun tocItems(): List<String> {
        val out = ArrayList<String>()
        (0 until book.chapterCount).forEach { i ->
            val t = book.chapterTitle(i)
            val label = if (t.isEmpty()) getString(R.string.chapter_fallback, i + 1) else t
            // 章的切分碎片继承同名 -> 连续同名只显示第一条
            if (out.isEmpty() || out.last() != label) out.add(label)
        }
        return out
    }

    private fun hideToc() {
        tocPanel.visibility = View.GONE
    }

    // ===== 笔记 =====

    /**
     * 笔记框：existing 为空 = 新建；否则为查看 / 编辑已有条目。
     * 笔记可以留空 —— 点确认即表示"只划线，不写笔记"。
     */
    private fun showNoteDialog(existing: Highlight?, start: Int, end: Int) {
        val excerpt = pageView.textAt(start, end)
        val preview = TextView(this).apply {
            text = getString(R.string.note_excerpt, excerpt)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ReaderActivity, R.color.ink_secondary))
            setPadding(0, 0, 0, 12)
        }
        // 输入框不设 hint：实测"可留空"这类提示是多余的，占版面还干扰
        val input = EditText(this).apply {
            setText(existing?.note ?: "")
            setSingleLine(false)
            maxLines = 6
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(preview)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.note_title_new else R.string.note_title_edit)
            .setView(box)
            .setNegativeButton(R.string.note_cancel) { _, _ -> dismissSelection() }
            .setPositiveButton(R.string.note_save, null)
            .apply {
                if (existing != null) {
                    setNeutralButton(R.string.note_delete) { _, _ -> deleteNote(existing) }
                }
            }
            .create()

        // 自己接管确认键：允许笔记留空直接确认
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveNote(start, end, excerpt, input.text.toString())
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveNote(start: Int, end: Int, excerpt: String, note: String) {
        noteStore.upsert(
            bookId,
            Highlight(chapterIndex, start, end, excerpt, note.trim())
        )
        refreshHighlights()
        dismissSelection()
        Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
    }

    private fun deleteNote(h: Highlight) {
        noteStore.remove(bookId, h)
        refreshHighlights()
        dismissSelection()
        Toast.makeText(this, R.string.note_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun refreshHighlights() {
        pageView.highlights = noteStore.chapter(bookId, chapterIndex)
    }

    // ===== 选区条（长按后先调选区，再写笔记）=====

    /** 长按或把手拖完都会走到这里：浮出选区条，让用户先调整再写 */
    private fun refreshNoteBar() {
        val r = pageView.selection
        if (r == null) {
            noteBar.visibility = View.GONE
            return
        }
        val text = pageView.textAt(r.first, r.last + 1)
        tvSelInfo.text = getString(R.string.selected_chars, text.length)
        noteBar.visibility = View.VISIBLE
    }

    private fun dismissSelection() {
        pageView.clearSelection()
        pageView.invalidate()
        noteBar.visibility = View.GONE
    }

    // ===== 笔记面板（App 内直接看，导出降为低频）=====

    private fun showNotes() {
        hideChrome()
        val list = noteStore.all(bookId)
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val rows = ArrayList<NoteRow>()
        var lastChapter = -1
        list.forEach { h ->
            if (h.chapter != lastChapter) {
                lastChapter = h.chapter
                rows.add(NoteRow(titleOf(h.chapter), null))
            }
            rows.add(NoteRow(null, h))
        }
        noteList.adapter = NoteAdapter(rows)
        noteList.setOnItemClickListener { _, _, position, _ ->
            val h = rows[position].item ?: return@setOnItemClickListener   // 章节行不跳转
            hideNotes()
            loadChapter(h.chapter, 0, jump = h, skipDir = 0)
        }
        notePanel.visibility = View.VISIBLE
    }

    private fun hideNotes() {
        notePanel.visibility = View.GONE
    }

    /** 笔记面板的行：section 非空 = 章节标题行；item 非空 = 笔记条目行 */
    private class NoteRow(val section: String?, val item: Highlight?)

    /** md 式展示：章节(大字) > 摘要(中字) > 笔记正文(小字)，完整展开不截断 */
    private inner class NoteAdapter(private val rows: List<NoteRow>) : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): NoteRow = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_note, parent, false)
            val tvSection = v.findViewById<TextView>(R.id.tvNoteSection)
            val tvExcerpt = v.findViewById<TextView>(R.id.tvNoteExcerpt)
            val tvBody = v.findViewById<TextView>(R.id.tvNoteBody)
            val row = rows[position]
            if (row.section != null) {
                tvSection.visibility = View.VISIBLE
                tvSection.text = row.section
            } else {
                tvSection.visibility = View.GONE
            }
            val h = row.item
            if (h != null) {
                tvExcerpt.visibility = View.VISIBLE
                tvExcerpt.text = h.text.trim()
                if (h.note.isBlank()) {
                    tvBody.visibility = View.GONE
                } else {
                    tvBody.visibility = View.VISIBLE
                    tvBody.text = h.note
                }
            } else {
                tvExcerpt.visibility = View.GONE
                tvBody.visibility = View.GONE
            }
            return v
        }
    }

    // ===== 导出（手动触发）=====

    /** 选位置 + 默认文件名「书名.md」；同名即覆盖（写入模式 wt = 截断重写） */
    private fun exportNotes() {
        val list = noteStore.all(bookId)
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/markdown"
            putExtra(Intent.EXTRA_TITLE, noteStore.safe(book.title) + ".md")
        }
        try {
            startActivityForResult(intent, REQ_EXPORT)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val md = NotesExporter.toMarkdown(noteStore.all(bookId)) { titleOf(it) }
            contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(md.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 信息栏 =====

    private fun updateInfo() {
        val total = max(1, pageView.pageCount)
        val battery = batteryLevel()
        val info = if (battery >= 0) {
            getString(R.string.page_info, pageView.currentPage + 1, total, battery)
        } else {
            getString(R.string.page_info_plain, pageView.currentPage + 1, total)
        }
        tvInfo.text = info
    }

    private fun systemDimen(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else -1
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

    /**
     * 顶部黑条的真凶（2026-10-26 实测定位）：**挖孔 / 刘海屏的切割模式**。
     *
     * v1.2.2 诊断输出：`D top=111 sb=111 nav=2 decY=111`
     * - sb=111px 远超常规状态栏（24dp≈66px），是"状态栏 + 开孔规避区"的高度
     * - decY=111 说明整个窗口被系统压在开孔区之下，而状态栏又被我们隐藏了
     *   → 上方 111px 没有任何窗口绘制，系统用黑边填充（挖孔屏的经典黑边）
     *
     * 解法（API 28+）：`layoutInDisplayCutoutMode = SHORT_EDGES`，让窗口延伸进开孔区，
     * 顶部由我们自己接管（微信读书即此做法）。
     * 若 ROM 仍不给（自检窗口位置），退回"显示纸色状态栏"——两条路都保证顶部不发黑。
     */
    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        if (paperStatusBarMode) {
            applyPaperStatusBar()
            return
        }
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // API 30+ 的 WindowInsetsController 在 minSdk 21 下用不了，只能用这套 flag。
        // LAYOUT_* 三个标志让内容延伸到状态栏 / 导航栏底下，缺了它们窗口不重排
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        // 首次布局后自检：窗口到底铺没铺到 y=0
        if (!selfChecked) {
            selfChecked = true
            window.decorView.post {
                val loc = IntArray(2)
                window.decorView.getLocationOnScreen(loc)
                if (loc[1] > 0) {
                    // ROM 仍把窗口压在开孔区之下 → 退路：显示状态栏并染成纸色
                    paperStatusBarMode = true
                    applyPaperStatusBar()
                } else {
                    // 铺满成功 → 内容自己让出开孔高度，避免标题顶进摄像头
                    padTopForCutout()
                }
                updateInfo()
            }
        }
    }

    /** 窗口铺满时，让章节标题避开开孔区（该区域为纸色，不会发黑） */
    private fun padTopForCutout() {
        if (topPadded) return
        topPadded = true
        val sb = systemDimen("status_bar_height")
        if (sb > 0) {
            tvChapter.setPadding(
                tvChapter.paddingLeft,
                tvChapter.paddingTop + sb,
                tvChapter.paddingRight,
                tvChapter.paddingBottom
            )
            // 正文也要让开开孔区：记录后重排
            cutoutTop = sb
            repaginate()
        }
    }

    /**
     * 正文安全区：顶部让出开孔 + 一行，底部也让出一行。
     *
     * 为什么是"一整行"而不是刚好贴着开孔：开孔旁边只留几像素会让第一行字
     * 贴着摄像头边缘，视觉上很难受；直接空一行最干净，也符合纸面留白。
     */
    private fun applySafePadding() {
        val d = resources.displayMetrics.density
        val side = (18 * d).toInt()
        val lineH = (prefs.fontSizeSp * 1.7f * d).toInt()
        val top = maxOf(lineH, cutoutTop + (4 * d).toInt())
        pageView.setPadding(side, top, side, lineH)
    }

    /** 安全区变化后按当前位置重排（分页结果依赖可用高度） */
    private fun repaginate() {
        applySafePadding()
        if (pageView.pageCount > 0) loadChapter(chapterIndex, pageView.currentPage)
    }

    /** 退路：顶部显示状态栏并染成纸色（是"纸色 + 时钟电量"，不是黑条） */
    @Suppress("DEPRECATION")
    private fun applyPaperStatusBar() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = ContextCompat.getColor(this, R.color.paper)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
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
        private const val REQ_EXPORT = 1001
    }
}
