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
            if (id != null && href != null) manifest[id] = decode(href)
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
        if (titles[index] != null) return
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

    /** 章节标题；尚未扫描到时返回空串（由调用方决定占位文案） */
    fun chapterTitle(index: Int): String = titles[index] ?: ""

    fun chapterTitles(): List<String> = (0 until chapterCount).map { chapterTitle(it) }
}
