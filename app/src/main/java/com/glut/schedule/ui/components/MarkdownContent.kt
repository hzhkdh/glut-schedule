package com.glut.schedule.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown

@Composable
fun MarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier
) {
    Markdown(
        content = MarkdownPolicy.sanitize(markdown),
        modifier = modifier
    )
}

object MarkdownPolicy {
    private val imagePattern = Regex("""!\[([^]]*)]\(([^)]+)\)""")
    private val linkPattern = Regex("""\[([^]]+)]\(([^)\s]+)(?:\s+[\"'][^\"']*[\"'])?\)""")
    private val htmlTagPattern = Regex("""<[^>]+>""")

    fun sanitize(markdown: String): String {
        var safe = imagePattern.replace(markdown) { match -> match.groupValues[1] }
        safe = linkPattern.replace(safe) { match ->
            val label = match.groupValues[1]
            val url = match.groupValues[2]
            if (isSafeHttpUrl(url)) "[$label]($url)" else label
        }
        return htmlTagPattern.replace(safe, "")
    }

    fun toPlainText(markdown: String): String {
        return sanitize(markdown)
            .replace(Regex("""```[^\n]*"""), "")
            .replace("```", "")
            .replace(linkPattern) { it.groupValues[1] }
            .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
            .replace(Regex("""(?m)^\s*(?:[-+*]|\d+[.)])\s+"""), "")
            .replace(Regex("""(?m)^\s*>\s?"""), "")
            .replace(Regex("""(?m)^\s*(?:-{3,}|\*{3,}|_{3,})\s*$"""), "")
            .replace(Regex("""(\*\*|__|~~|`|\*|_)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun isSafeHttpUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
}
