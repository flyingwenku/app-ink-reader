package com.fred.inkreader.epub

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Html
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * 极简 EPUB 解析：ZIP + OPF + XHTML，不引任何第三方库。
 *
 * 解析链路：
 *   META-INF/container.xml → OPF 路径
 *   → OPF 的 <manifest> 建立 id → href 映射
 *   → OPF 的 <spine> 决定阅读顺序
 *   → 各章 XHTML 清洗后交给 Html.fromHtml
 *
 * 已知限制：
 * - 不支持带 DRM 加密的 epub（解密不在此项目范围内）
 * - 非 UTF-8 编码的章节会乱码
 * - 复杂 CSS（class 选择器、表格、浮动）会被 Html.fromHtml 忽略
 */
class EpubBook(private val file: File) {

    var title: String = file.nameWithoutExtension
        private set

    private val hrefs = ArrayList<String>()
    private val titles = ArrayList<String?>()
    /** 目录标题：spine href → 标题，来自 NCX / nav（比正文 <title> 可靠） */
    private val tocLabels = HashMap<String, String>()
    private var ncxPath = ""
    private var navPath = ""
    private var zip: ZipFile? = null
    private var opfDir = ""

    val chapterCount: Int
        get() = hrefs.size

    fun open(): Boolean {
        return try {
            val z = ZipFile(file)
            val opfPath = findOpfPath(z)
            if (opfPath == null) {
                z.close()
                return false
            }
            opfDir = opfPath.substringBeforeLast('/', "")
            val opf = readText(z, opfPath)
            if (opf == null) {
                z.close()
                return false
            }
            parseOpf(opf)
            if (hrefs.isEmpty()) {
                z.close()
                return false
            }
            parseToc(z)
            repeat(hrefs.size) { titles.add(null) }
            zip = z
            true
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        try {
            zip?.close()
        } catch (ignored: Exception) {
        }
        zip = null
    }

    // ===== 解析 =====

    private fun findOpfPath(z: ZipFile): String? {
        val xml = readText(z, "META-INF/container.xml") ?: return null
        return Regex("""full-path="([^"]+)"""").find(xml)
            ?.groupValues?.get(1)
            ?.let { decode(it) }
    }

    private fun parseOpf(opf: String) {
        val manifest = HashMap<String, String>()
        Regex("""<item\s[^>]*>""").findAll(opf).forEach { m ->
            val tag = m.value
            val id = attr(tag, "id")
            val href = attr(tag, "href")
            if (id != null && href != null) {
                manifest[id] = decode(href)
                // EPUB2 目录是 toc.ncx，EPUB3 是 properties="nav" 的 xhtml
                if (attr(tag, "media-type") == "application/x-dtbncx+xml") ncxPath = decode(href)
                if (attr(tag, "properties")?.contains("nav") == true) navPath = decode(href)
            }
        }

        Regex("""(?s)<dc:title[^>]*>(.*?)</dc:title>""").find(opf)?.let { m ->
            val t = m.groupValues[1].trim()
            if (t.isNotEmpty()) title = t
        }

        Regex("""<itemref\s[^>]*idref="([^"]+)"""").findAll(opf).forEach { m ->
            manifest[m.groupValues[1]]?.let { hrefs.add(it) }
        }
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    /**
     * 解析标准目录标题：EPUB2 的 NCX navMap / EPUB3 的 nav 文档。
     *
     * 实测教训（calibre 转换的书）：
     * 1. NCX 的 `<text>` 可能整体是乱码（UTF-8 被当 GBK 写回），标签都残缺；
     *    而同一本书的 nav.xhtml 是干净的 → **NCX 有乱码就整体回退到 nav**
     * 2. 一章常被切成多个 spine 文件，NCX 只标第一个 →
     *    其余碎片**继承前一个文件的章节名**（否则目录一半是书名）
     */
    private fun parseToc(z: ZipFile) {
        if (ncxPath.isNotEmpty()) {
            val ncx = readText(z, resolve(opfDir, ncxPath))
            if (ncx != null) {
                val dir = ncxPath.substringBeforeLast('/', "")
                // navLabel + content 成对出现；正则按"text → 其后最近的 content"配对，
                // 嵌套 navPoint 也能正确配对
                Regex("""(?s)<text[^>]*>(.*?)</text>.*?<content\s+src="([^"]+)"""")
                    .findAll(ncx)
                    .forEach { m ->
                        val label = htmlText(m.groupValues[1])
                        val href = resolve(dir, decode(m.groupValues[2].substringBefore('#')))
                        if (label.isNotEmpty() && !tocLabels.containsKey(href)) {
                            tocLabels[href] = label
                        }
                    }
                if (hasMojibake(tocLabels.values)) tocLabels.clear()
            }
        }
        if (tocLabels.isEmpty() && navPath.isNotEmpty()) {
            val nav = readText(z, resolve(opfDir, navPath)) ?: return
            val dir = navPath.substringBeforeLast('/', "")
            val block = Regex("""(?s)<nav\b[^>]*epub:type="toc"[^>]*>(.*?)</nav>""").find(nav)
                ?.groupValues?.get(1)
                ?: Regex("""(?s)<nav\b[^>]*>(.*?)</nav>""").find(nav)?.groupValues?.get(1)
                ?: return
            Regex("""(?is)<a\s[^>]*href="([^"]+)"[^>]*>(.*?)</a>""").findAll(block).forEach { m ->
                val href = resolve(dir, decode(m.groupValues[1].substringBefore('#')))
                val label = htmlText(m.groupValues[2])
                if (label.isNotEmpty() && !tocLabels.containsKey(href)) {
                    tocLabels[href] = label
                }
            }
        }
        inheritLabels()
    }

    /** 乱码特征：GBK 双字节区（U+4E00 之外的 CJK 扩展块大量出现）+ 替换符 */
    private fun hasMojibake(labels: Collection<String>): Boolean {
        if (labels.isEmpty()) return false
        var bad = 0
        for (label in labels) {
            for (ch in label) {
                if (ch == '\uFFFD') return true
                val b = ch.code
                // UTF-8 中文按 GBK 误读后会落在 CJK 部件扩展/兼容区，常规中文不在这两块
                if (b in 0x3400..0x4DBF || b in 0xF900..0xFAFF || b in 0xE000..0xF8FF) bad++
            }
        }
        // 阈值放宽到 2：真实章名里出现私用区字符的几乎不存在
        return bad >= 2
    }

    /** 没有自己的目录条目的 spine 文件（章的切分碎片）继承前一个文件的章节名 */
    private fun inheritLabels() {
        var last: String? = null
        for (i in hrefs.indices) {
            val own = tocLabels[hrefs[i]]
            if (own != null) {
                last = own
            } else if (last != null) {
                tocLabels[hrefs[i]] = last
            }
        }
    }

    private fun htmlText(s: String): String =
        try {
            Html.fromHtml(s).toString().trim()
        } catch (e: Exception) {
            s.trim()
        }

    private fun decode(s: String): String =
        try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }

    private fun readText(z: ZipFile, path: String): String? {
        val entry = z.getEntry(path) ?: return null
        return try {
            z.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    /** 拼接并归一化相对路径（处理 ./ 与 ../） */
    private fun resolve(base: String, rel: String): String {
        val all = if (base.isEmpty()) rel else "$base/$rel"
        val out = ArrayList<String>()
        for (p in all.split('/')) {
            when (p) {
                "", "." -> {
                }
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(p)
            }
        }
        return out.joinToString("/")
    }

    // ===== 章节内容 =====

    private fun raw(index: Int): String? {
        val z = zip ?: return null
        return readText(z, resolve(opfDir, hrefs[index]))
    }

    /**
     * 生成可用于排版的一章内容。
     * 必须在后台线程调用：含解压、HTML 解析与图片解码。
     */
    fun chapterSpanned(index: Int, res: Resources, maxWidth: Int): Spanned {
        val source = raw(index) ?: return SpannableString("")
        val html = cleanHtml(source)
        val base = resolve(opfDir, hrefs[index].substringBeforeLast('/', ""))
        val getter = Html.ImageGetter { src -> loadImage(src, base, res, maxWidth) }
        val spanned = try {
            Html.fromHtml(html, getter, null)
        } catch (e: Exception) {
            fallbackSpanned(source)
        }
        return trimTrailing(spanned)
    }

    private fun fallbackSpanned(source: String): Spanned {
        // 极端情况（标签严重不规范）下退化为纯文本，保证至少有内容可读
        val plain = Regex("""(?s)<[^>]*>""").replace(cleanHtml(source), "")
        return SpannableString(plain)
    }

    private fun cleanHtml(xhtml: String): String {
        var s = xhtml
        val bodyStart = s.indexOf("<body", 0, true)
        if (bodyStart >= 0) {
            val gt = s.indexOf('>', bodyStart)
            val bodyEnd = s.indexOf("</body", 0, true)
            s = if (bodyEnd > gt) s.substring(gt + 1, bodyEnd) else s.substring(gt + 1)
        }
        s = Regex("(?is)<script.*?</script>").replace(s, "")
        s = Regex("(?is)<style.*?</style>").replace(s, "")
        s = Regex("(?is)<!--.*?-->").replace(s, "")
        // 首行缩进 2 字符：中文排版习惯（替代西文的首字下沉）
        s = Regex("""(?i)<p(\s[^>]*)?>""").replace(s, "<p$1>　　")
        return s
    }

    private fun trimTrailing(s: Spanned): Spanned {
        val b = SpannableStringBuilder(s)
        while (b.isNotEmpty() && (b[b.length - 1] == '\n' || b[b.length - 1] == ' ')) {
            b.delete(b.length - 1, b.length)
        }
        return SpannableString(b)
    }

    private fun loadImage(src: String, base: String, res: Resources, maxWidth: Int): Drawable? {
        val z = zip ?: return null
        val path = resolve(base, src.substringBefore('#').substringBefore('?'))
        val entry = z.getEntry(path) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            z.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > maxWidth * 2) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = z.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null

            val bitmap = if (decoded.width > maxWidth) {
                val scale = maxWidth.toFloat() / decoded.width
                val matrix = Matrix().apply { setScale(scale, scale) }
                val scaled = Bitmap.createBitmap(
                    decoded, 0, 0, decoded.width, decoded.height, matrix, true
                )
                decoded.recycle()
                scaled
            } else {
                decoded
            }
            bitmap.density = res.displayMetrics.densityDpi
            BitmapDrawable(res, bitmap).apply { setBounds(0, 0, bitmap.width, bitmap.height) }
        } catch (e: Exception) {
            null
        }
    }

    // ===== 章节标题（后台扫描，未扫到返回空串） =====

    fun scanTitle(index: Int) {
        if (index !in 0 until chapterCount) return
        if (titles[index] != null || tocLabels.containsKey(hrefs[index])) return
        val z = zip ?: return
        val entry = z.getEntry(resolve(opfDir, hrefs[index])) ?: return
        val head = try {
            z.getInputStream(entry).use { input ->
                val buf = ByteArray(4096)
                val n = input.read(buf)
                if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            return
        }
        val hit = Regex("""(?s)<title[^>]*>(.*?)</title>""").find(head)
            ?.groupValues?.get(1)
            ?: Regex("""(?s)<h[1-3][^>]*>(.*?)</h[1-3]>""").find(head)?.groupValues?.get(1)
        if (hit == null) return
        val plain = try {
            Html.fromHtml(hit).toString()
        } catch (e: Exception) {
            hit
        }.trim()
        if (plain.isNotEmpty()) titles[index] = plain
    }

    /** 章节标题：标准目录优先，正文 <title> 兜底（前者才可信） */
    fun chapterTitle(index: Int): String {
        tocLabels[hrefs[index]]?.let { return it }
        return titles[index] ?: ""
    }

    fun chapterTitles(): List<String> = (0 until chapterCount).map { chapterTitle(it) }
}
