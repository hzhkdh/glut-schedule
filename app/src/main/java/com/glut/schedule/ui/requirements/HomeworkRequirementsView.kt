package com.glut.schedule.ui.requirements

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.ui.viewinterop.AndroidView
import com.glut.schedule.service.chaoxing.homeworkDetailDocument
import kotlin.math.abs
import kotlin.math.ceil

/** 只负责排版：高度跟随正文与图片，纵向拖动由外层列表处理。 */
@Composable
fun HomeworkRequirementsView(html: String, modifier: Modifier = Modifier) {
    // 换内容时重新从最小高度测量，避免短要求继承旧正文的高度。
    key(html) {
        var heightPx by remember { mutableIntStateOf(1) }
        val height = with(LocalDensity.current) { heightPx.toDp() }
        AndroidView(
            factory = { context -> RequirementsWebView(context) { heightPx = it }.apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.isForMainFrame && request.hasGesture() && request.url.scheme == "https") {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        }
                        return true
                    }
                }
                loadDataWithBaseURL("https://mooc1.chaoxing.com/", homeworkDetailDocument(html), "text/html", "UTF-8", null)
            } },
            onRelease = { it.stopLoading(); it.destroy() },
            modifier = modifier.height(height)
        )
    }
}

/** 不执行页面脚本，直接在原生绘制后读取排版范围，异步图片也会触发重测。 */
private class RequirementsWebView(context: Context, val reportHeight: (Int) -> Unit) : WebView(context) {
    private var reported = 0
    private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    @Suppress("DEPRECATION")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 滚动范围至少等于视口高度，不能用来测量缩短后的正文。
        // contentHeight 是 CSS 像素，scale 转成实际像素；向上取整防止裁掉末行。
        val content = ceil(contentHeight * scale.toDouble()).toInt().coerceAtLeast(1)
        if (content != reported) {
            reported = content
            // 避免在绘制中直接触发 Compose 布局。
            post { if (isAttachedToWindow) reportHeight(content) }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && w != oldw) reported = 0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downY = event.y
            dragging = false
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.y - downY) > slop) {
            if (!dragging) {
                // 拖动取消 WebView 的点击/长按，普通点击仍可打开附件。
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.onTouchEvent(cancel)
                cancel.recycle()
                dragging = true
            }
        }
        val handled = if (dragging) false else super.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(false)
        return handled
    }
}
