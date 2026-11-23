package com.glut.schedule.service.campus

import com.glut.schedule.service.network.awaitResponse
import com.glut.schedule.service.network.readBytesLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.IOException
import java.security.MessageDigest

private const val OFFICIAL_HOST = "www.glut.edu.cn"
private val schoolImagePath = Regex(
    """/(?:__local|_mediafile)/(?:[\w-]+/)+[\w-]+\.(?:jpe?g|png|gif|webp)""",
    RegexOption.IGNORE_CASE
)

internal fun isOfficialSchoolOverviewImage(url: HttpUrl): Boolean =
    url.isHttps && url.host == OFFICIAL_HOST && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
        schoolImagePath.matches(url.encodedPath)

internal class OfficialSchoolOverviewGateway(client: OkHttpClient, private val now: () -> Long = System::currentTimeMillis) {
    // 所有请求只访问预设的官网栏目。拒绝跳转，避免响应把客户端带到其他地址。
    private val officialClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun fetch(topic: SchoolOverviewTopic, force: Boolean): String = coroutineScope {
        val sources = when (topic) {
            SchoolOverviewTopic.LEADERS -> listOf(leadersPage)
            SchoolOverviewTopic.HISTORY -> listOf(historyPage)
            SchoolOverviewTopic.SPIRIT -> listOf(spiritPage, marksPage)
            SchoolOverviewTopic.SONG -> listOf(marksPage)
        }
        val pages = sources.map { source -> async { readPage(source, force) } }.awaitAll()
        val data = withContext(Dispatchers.Default) {
            when (topic) {
                SchoolOverviewTopic.LEADERS -> parseLeaders(pages[0])
                SchoolOverviewTopic.HISTORY -> parseHistory(pages[0])
                SchoolOverviewTopic.SPIRIT -> parseSpirit(pages[0], pages[1])
                SchoolOverviewTopic.SONG -> parseSong(pages[0])
            }
        }
        JSONObject()
            .put("topic", topic.key)
            .put("fetchedAt", now())
            .put("sources", JSONArray(sources.map { JSONObject().put("label", it.label).put("url", it.url.toString()) }))
            .put("data", data)
            .toString()
    }

    private suspend fun readPage(source: Source, force: Boolean): Document {
        val request = Request.Builder().url(source.url).header("Accept", "text/html")
            .apply { if (force) cacheControl(CacheControl.FORCE_NETWORK) }.build()
        return officialClient.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw IOException("学校官网请求失败（${response.code}）")
            val body = response.body
            val bytes = body.readBytesLimited(MAX_PAGE_BYTES)
            Jsoup.parse(bytes.inputStream(), body.contentType()?.charset()?.name(), source.url.toString())
        }
    }

    private fun parseLeaders(page: Document): JSONObject {
        val entries = page.selectFirst("ul.list_box_leader")?.children()?.filter { it.normalName() == "li" }.orEmpty()
        if (entries.isEmpty()) throw IOException("官网领导名单缺失")

        val ids = mutableSetOf<String>()
        val people = entries.map { entry ->
            val heading = entry.selectFirst("h5")
            val name = heading?.selectFirst("font")?.cleanText().orEmpty()
            val role = heading?.selectFirst("span")?.cleanText().orEmpty()
            val duties = entry.paragraphs().joinToString("\n\n")
            if (name.isEmpty() || role.isEmpty() || duties.isEmpty()) throw IOException("官网领导资料不完整")
            // 沿用旧快照按姓名生成的标识，保留客户端的展开状态。
            val id = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it) }
            if (!ids.add(id)) throw IOException("官网领导资料重复")
            JSONObject().put("id", id).put("name", name).put("role", role)
                .put("duties", duties).put("photoUrl", entry.requiredPhoto(leadersPage.url))
        }
        return JSONObject().put("people", JSONArray(people))
    }

    private fun parseHistory(page: Document): JSONObject {
        val article = page.article()
        val paragraphs = article.paragraphs()
        if (paragraphs.firstOrNull() != "桂林理工大学历史沿革") throw IOException("官网历史沿革标题缺失")

        val periods = mutableListOf<Period>()
        for (paragraph in paragraphs.drop(1)) {
            val heading = periodHeading.matchEntire(paragraph)
            if (heading != null) {
                val (title, range) = heading.destructured
                periods += Period(title.trim(), range)
                continue
            }
            val period = periods.lastOrNull() ?: throw IOException("官网历史阶段缺失")
            // 原文每段对应一条事件，只提取段首日期，并保留同年事件和段内的后续日期。
            val dated = datedEvent.matchEntire(paragraph)
            period.events.put(JSONObject()
                .put("date", dated?.groupValues?.get(1).orEmpty())
                .put("text", dated?.groupValues?.get(2) ?: paragraph))
        }
        if (periods.isEmpty() || periods.any { it.events.length() == 0 }) throw IOException("官网历史沿革资料不完整")

        val data = JSONObject().put("periods", JSONArray(periods.map { period ->
            JSONObject().put("id", period.range.take(4)).put("title", period.title)
                .put("range", period.range).put("events", period.events)
        }))
        // 沿革图缺失或地址不受信时，仍保留完整的校史文字。
        article.selectFirst("img")?.officialPhoto(historyPage.url)?.let { data.put("diagramUrl", it) }
        return data
    }

    private fun parseSpirit(page: Document, marks: Document): JSONObject {
        val article = page.article()
        val paragraphs = article.paragraphs()
        val phrases = paragraphs.firstOrNull().orEmpty().split(' ').filter(String::isNotEmpty)
        val description = paragraphs.drop(1).joinToString("\n\n")
        val marksLines = marks.article().paragraphs()
        val mottoIndex = marksLines.indexOfFirst(mottoStart::containsMatchIn)
        val songIndex = marksLines.indexAfter(mottoIndex, songTitle)
        if (mottoIndex < 0 || songIndex < 0) throw IOException("官网校训栏目不完整")

        val motto = marksLines[mottoIndex].replace(mottoStart, "")
        // 校歌标题是校训正文的下界，保留校训的涵义与出处两个小节。
        val mottoDescription = marksLines.subList(mottoIndex + 1, songIndex)
            .filterNot(mottoHeading::matches).joinToString("\n\n")
        if (phrases.isEmpty() || description.isEmpty() || motto.isEmpty() || mottoDescription.isEmpty()) {
            throw IOException("官网桂工精神或校训资料不完整")
        }
        return JSONObject().put("photoUrl", article.requiredPhoto(spiritPage.url))
            .put("phrases", JSONArray(phrases)).put("description", description)
            .put("motto", motto).put("mottoDescription", mottoDescription)
    }

    private fun parseSong(page: Document): JSONObject {
        val nodes = page.article().select("p")
        val lines = nodes.map { it.cleanText() }
        val title = lines.indexOfFirst(songTitle::matches)
        val motto = lines.indexOfFirst(mottoStart::containsMatchIn)
        val firstMotto = lines.indexAfter(motto, mottoMeaning)
        val secondMotto = lines.indexAfter(firstMotto, mottoOrigin)
        val notes = lines.indexAfter(title, songNote)
        val meaning = lines.indexAfter(notes, songMeaning)
        if (motto < 0 || firstMotto <= motto || secondMotto <= firstMotto || title <= secondMotto ||
            notes <= title || meaning <= notes) {
            throw IOException("官网校歌栏目不完整")
        }

        // 歌词需保留段内换行，播放器或附件之后的文字只属于各自栏目。
        val lyrics = nodes.subList(title + 1, notes).takeWhile { !it.isMediaParagraph() }
            .flatMap { it.sourceText(keepBreaks = true).lines().map(String::cleanSourceText).filter(String::isNotEmpty) }
        val noteEntries = lines.subList(notes + 1, meaning).filter(String::isNotEmpty).map { line ->
            val note = noteEntry.matchEntire(line) ?: throw IOException("官网校歌注释格式无效")
            val (term, text) = note.destructured
            JSONObject().put("term", term.trim()).put("text", text)
        }
        // 学校宣传片标题会改名，也可能缺省，兼容媒体或附件作为大意下界。
        val meaningEnd = (meaning + 1 until nodes.size).firstOrNull { index ->
            promotionTitle.matches(lines[index]) || attachmentStart.containsMatchIn(lines[index]) || nodes[index].isMediaParagraph()
        } ?: nodes.size
        val meaningLines = lines.subList(meaning + 1, meaningEnd).filter(String::isNotEmpty)
        val mottoSections = listOf(
            lines[firstMotto] to lines.subList(firstMotto + 1, secondMotto).filter(String::isNotEmpty),
            lines[secondMotto] to lines.subList(secondMotto + 1, title).filter(String::isNotEmpty)
        )
        if (lyrics.isEmpty() || noteEntries.isEmpty() || meaningLines.isEmpty() || mottoSections.any { it.second.isEmpty() }) {
            throw IOException("官网校歌资料不完整")
        }
        return JSONObject().put("title", lines[title]).put("lyrics", JSONArray(lyrics))
            .put("notes", JSONArray(noteEntries)).put("meaning", JSONArray(meaningLines))
            .put("mottoSections", JSONArray(mottoSections.map { (heading, paragraphs) ->
                JSONObject().put("title", heading).put("paragraphs", JSONArray(paragraphs))
            }))
    }

    private fun Element.article(): Element = getElementById("vsb_content") ?: throw IOException("官网正文缺失")

    private fun Element.paragraphs(): List<String> = select("p").map { it.cleanText() }.filter(String::isNotEmpty)

    private fun Element.requiredPhoto(pageUrl: HttpUrl): String = selectFirst("img")?.officialPhoto(pageUrl)
        ?: throw IOException("官网图片地址缺失或无效")

    private fun Element.officialPhoto(pageUrl: HttpUrl): String? {
        // 正文的 orisrc 指向官方原图，优先于页面缩略图。
        val src = attr("orisrc").ifBlank { attr("src") }
        return pageUrl.resolve(src)?.takeIf(::isOfficialSchoolOverviewImage)?.toString()
    }

    private fun Element.isMediaParagraph(): Boolean = selectFirst("script, audio, video, iframe") != null ||
        select("a").any { mediaLink.containsMatchIn(it.attr("href")) || mediaLabel.containsMatchIn(it.cleanText()) }

    private fun Element.cleanText(): String = sourceText().cleanSourceText()

    private fun Element.sourceText(keepBreaks: Boolean = false): String = buildString {
        // 相邻内联节点沿用原文字符，避免给中文标点和内联强调人为插入空格。
        traverse { node, _ ->
            if (node is TextNode) append(node.wholeText)
            else if (keepBreaks && node.nodeName() == "br") append('\n')
        }
    }

    private fun List<String>.indexAfter(start: Int, pattern: Regex): Int =
        (start + 1 until size).firstOrNull { pattern.matches(this[it]) } ?: -1

    private data class Source(val label: String, val url: HttpUrl)
    private data class Period(val title: String, val range: String, val events: JSONArray = JSONArray())

    private companion object {
        const val MAX_PAGE_BYTES = 512 * 1024

        val leadersPage = Source("现任领导", "https://$OFFICIAL_HOST/xxgk/xrld.htm".toHttpUrl())
        val historyPage = Source("历史沿革", "https://$OFFICIAL_HOST/xxgk/lsyg.htm".toHttpUrl())
        val spiritPage = Source("桂工精神", "https://$OFFICIAL_HOST/xxgk/ggjs.htm".toHttpUrl())
        val marksPage = Source("学校标识", "https://$OFFICIAL_HOST/xxgk/xxbs.htm".toHttpUrl())

        val periodHeading = Regex("""(.+?)[（(]\s*(\d{4}(?:年?\s*[—–－~-]\s*\d{4}年?|年以来))\s*[）)]""")
        val datedEvent = Regex("""(\d{4}年(?:\d{1,2}月(?:\d{1,2}日)?)?)[，、,\s]+(.+)""")
        val songTitle = Regex("桂林理工大学之歌|校歌")
        val mottoStart = Regex("""^校训\s*[：:]\s*""")
        val mottoHeading = Regex("校训.+释义")
        val mottoMeaning = Regex("一、涵义阐释")
        val mottoOrigin = Regex("二、出处、典故")
        val songNote = Regex("""[【\[]?注释[】\]]?""")
        val songMeaning = Regex("""[【\[]?歌词大意[】\]]?""")
        val noteEntry = Regex("""([^：:]+)[：:]\s*(.+)""")
        val promotionTitle = Regex("桂林理工大学[^。！？]{0,40}宣传片(?:《[^》]*》)?")
        val attachmentStart = Regex("""^附件\s*[【\[：:]""")
        val mediaLink = Regex("""\.(?:mp4|mp3)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
        val mediaLabel = Regex("""\.(?:mp4|mp3)$""", RegexOption.IGNORE_CASE)
    }
}

private val invisibleCharacters = Regex("[\u200b-\u200d\ufeff]")
private val separators = Regex("""[\s\p{Z}]+""")

private fun String.cleanSourceText(): String = replace(invisibleCharacters, "").replace(separators, " ").trim()
