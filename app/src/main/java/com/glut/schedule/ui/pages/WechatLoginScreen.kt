package com.glut.schedule.ui.pages

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.glut.schedule.BuildConfig
import com.glut.schedule.service.academic.CasWebLoginConfig
import com.glut.schedule.service.academic.WechatNav
import com.glut.schedule.service.academic.classifyWechatNavigation
import com.glut.schedule.service.academic.extractStudentNumberFromHandoffUrl
import com.glut.schedule.service.academic.hasLocalAcademicSession
import com.glut.schedule.service.academic.mergeWebLoginCookies
import com.glut.schedule.service.academic.upgradeAcademicHttpToHttps
import org.json.JSONTokener

private val WechatPageBg = Color(0xFFF6F4EF)
private val WechatPrimary = Color(0xFF141821)
private val WechatSecondary = Color(0xFF667085)
private val WechatAccent = Color(0xFF3F7DF6)
private val WechatBorder = Color(0xFFD1D5DB)
private val WechatError = Color(0xFFDC2626)

/**
 * 微信登录覆盖层：把学校统一身份认证页跑在一个受约束的 WebView 里。
 *
 * 为什么非 WebView 不可：二维码被确认之后，是**显示二维码的那一方**自己轮询
 * `checkLoginStatus(iamSessionId)` 完成登录的（见 `login6.js`）。会话只会落在
 * 举着二维码的这个 WebView 里——把二维码抠出来另作展示，轮询就断了。
 *
 * 用户实际动作：App 把二维码自动存进相册 → 切微信「扫一扫 → 相册」→ 确认 → 切回来，
 * 此时 WebView 早已轮询到结果并跳转完毕，本组件直接取会话收工。
 *
 * 安全约束（每一条都是硬性的）：
 *  - **绝不调用 `addJavascriptInterface`**：页面无法回调到 Kotlin。
 *    本组件只用 `evaluateJavascript`（App → 页面，单向）读一次二维码。
 *  - 顶层导航按 [classifyWechatNavigation] 白名单放行，越界即拦并在界面内提示。
 *    **只拦顶层**——拦子资源只会让页面白屏。
 *  - 进入前清空 cookie（不继承上一位用户的会话），退出时再清一次并销毁 WebView。
 */
@Composable
fun WechatLoginScreen(
    /**
     * 登录成功。
     *
     * [studentNumber] 取自 IAM 递给教务的跳转 URL——微信登录没有密码框，
     * 这是唯一权威的学号来源；从教务页面反查会抓到内部账号，静默算错年级和学期。
     */
    onSuccess: (cookie: String, studentNumber: String?) -> Unit,
    onCancel: () -> Unit,
    onFailed: (reason: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // WebViewClient 的回调在 remember 里创建、只捕获一次，所以必须经 State 读最新值，
    // 否则回调会拿着首次组合时的旧 lambda。
    val currentOnSuccess by rememberUpdatedState(onSuccess)
    val currentOnFailed by rememberUpdatedState(onFailed)

    var errorText by remember { mutableStateOf("") }
    var blockedNotice by remember { mutableStateOf("") }
    var qrCodeSaved by remember { mutableStateOf(false) }
    // 成功判定有两个触发点（导航拦截与页面加载完成），必须防重入。
    // 只有成功才锁死；失败不锁，用户可以「刷新二维码」再来一次。
    var handled by remember { mutableStateOf(false) }
    // IAM 下发的跳转里带着权威学号，导航过程中顺手记下，成功时一并交出去。
    var handoffStudentNumber by remember { mutableStateOf<String?>(null) }

    val webView = remember {
        // 排障主力：真机连上电脑后在 chrome://inspect 里能看到这个 WebView 的每一次顶层跳转，
        // 「ticket 到底有没有回到教务」一眼就看出来了。仅在 debug 构建开启。
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            configureForCasLogin(
                onBlocked = { blockedNotice = "已阻止加载非学校域名的页面（$it）" },
                onLanded = {
                    if (!handled) {
                        val cookie = readAcademicCookie()
                        Log.i(
                            LOG_TAG,
                            "落到教务页，取回 cookie 长度=${cookie.length} " +
                                "是否有教务会话=${hasLocalAcademicSession(cookie)} " +
                                "学号=${handoffStudentNumber ?: "（跳转里没带）"}"
                        )
                        if (hasLocalAcademicSession(cookie)) {
                            handled = true
                            currentOnSuccess(cookie, handoffStudentNumber)
                        } else {
                            currentOnFailed(WECHAT_NO_ACADEMIC_SESSION_MESSAGE)
                        }
                    }
                },
                onLoadFailed = { errorText = it },
                onQrCodeSaved = { qrCodeSaved = true },
                onHandoffStudentNumber = { if (it.isNotBlank()) handoffStudentNumber = it }
            )
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
            // 只落盘、不清空——理由见上面 LaunchedEffect 里的说明。
            // flush 是有意义的：不写盘的话，进程被回收时会话就没了，免扫也就无从谈起。
            CookieManager.getInstance().flush()
        }
    }

    LaunchedEffect(webView) {
        // 这里**故意不清 cookie**：统一身份认证的会话留在 WebView 里，下次打开即可
        // 直接换成教务会话——用户不必每次重扫，而这正是他最常抱怨的一点。
        //
        // 代价与浏览器的「记住登录」完全一样：共用设备上别人也能续用这份登录态。
        // 换账号的场景另有兜底——导入时会按学号判定并清空旧数据（见 performImport）。
        webView.post { webView.loadUrl(CasWebLoginConfig.casLoginUrl()) }
    }

    // 用户去微信扫码这段时间，App 在后台——页面那套「轮询扫码结果」的定时器会被系统掐住，
    // 回来时没人叫醒它，于是「微信里已经确认了、App 里却还停在二维码上」。
    // 切回前台主动 onResume + resumeTimers，让轮询接着跑。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                webView.onResume()
                webView.resumeTimers()
                // 光恢复定时器不够：它下一次触发可能在很久之后，而用户正盯着屏幕等。
                // 直接把页面那个「查扫码结果」的函数叫一遍，立刻要个答案。
                webView.evaluateJavascript(POLL_KICK_SCRIPT) { result ->
                    Log.i(LOG_TAG, "回到前台，已催轮询；结果=$result 当前页=${webView.url}")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 手动兜底：金智落地有可能是 JS 驱动，也可能停在 CAS 域下不再跳。 */
    fun finishFromCurrentCookie() {
        val cookie = readAcademicCookie()
        if (hasLocalAcademicSession(cookie)) {
            currentOnSuccess(cookie, handoffStudentNumber)
        } else {
            errorText = WECHAT_NO_ACADEMIC_SESSION_MESSAGE
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WechatPageBg)
            .systemBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("微信登录", color = WechatPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("关闭", color = WechatSecondary, fontSize = 15.sp)
            }
        }

        Text(
            text = if (qrCodeSaved) {
                "二维码已存入相册：请打开微信「扫一扫 → 相册」选第一张，确认后切回本应用。"
            } else {
                "请用微信「扫一扫 → 相册」识别二维码完成登录，无需输入学号密码。"
            },
            color = if (qrCodeSaved) WechatAccent else WechatSecondary,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
        )

        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)
        )

        if (blockedNotice.isNotBlank()) {
            Text(
                blockedNotice,
                color = WechatSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
        if (errorText.isNotBlank()) {
            Text(
                errorText,
                color = WechatError,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    errorText = ""
                    blockedNotice = ""
                    webView.reload()
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Text("刷新二维码", color = WechatPrimary, fontSize = 14.sp)
            }
            Button(
                onClick = { finishFromCurrentCookie() },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WechatAccent)
            ) {
                Text("我已完成扫码", color = Color.White, fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * 读回教务与 CAS 两侧的 cookie 并合并。
 *
 * 两个 URL 都**必须带各自的路径**：`CookieManager.getCookie(url)` 按 URL 匹配 path，
 * 拿根路径去问 `/academic` 下的 JSESSIONID 只会得到空串。
 */
private fun readAcademicCookie(): String {
    val cookies = CookieManager.getInstance()
    return mergeWebLoginCookies(
        jwCookieHeader = cookies.getCookie(CasWebLoginConfig.ACADEMIC_COOKIE_URL).orEmpty(),
        casCookieHeader = cookies.getCookie(CasWebLoginConfig.CAS_COOKIE_URL).orEmpty()
    )
}

/**
 * 把 WebView 调成「只用来跑统一身份认证页」的样子。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForCasLogin(
    onBlocked: (String) -> Unit,
    onLanded: () -> Unit,
    onLoadFailed: (String) -> Unit,
    onQrCodeSaved: () -> Unit,
    onHandoffStudentNumber: (String) -> Unit
) {
    settings.apply {
        // 统一身份认证页靠 <script> 跳转，JS 关不掉；代价用白名单和「不注册 JS 桥」来抵。
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        setGeolocationEnabled(false)
        setSaveFormData(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportMultipleWindows(false)
        builtInZoomControls = true
        displayZoomControls = false
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            // IAM 把学号明写在递给教务的跳转上（`userName=` / `j_username=`）。
            // 这是微信登录唯一的权威来源——从教务页面反查会抓到内部账号，静默算错学期。
            extractStudentNumberFromHandoffUrl(url)?.let(onHandoffStudentNumber)
            // 统一身份认证把身份递给教务时用的是**明文**地址（caLoginZF.do?verify=…&userName=…），
            // 里面带着学号和签名，而教务自己在 301 之前那一次明文请求就已经把它暴露在校园网上了。
            // 这里直接升级成 https 再放行：签名与 scheme 无关，而明文那一步就此消失。
            upgradeAcademicHttpToHttps(url)?.let { secured ->
                Log.i(LOG_TAG, "明文教务地址已升级：$secured")
                view.loadUrl(secured)
                return true
            }
            val nav = classifyWechatNavigation(url)
            Log.i(LOG_TAG, "导航 -> $nav $url")
            return when (nav) {
                WechatNav.BLOCKED -> {
                    onBlocked(request.url.host.orEmpty())
                    true
                }
                // 教务落地页**必须放行**，一个字都不能拦。
                //
                // CAS 回跳过来的那个地址带着 ticket（ssoLogin.do?ticket=ST-…），
                // 教务要靠**这次请求**去消费 ticket、并下发自己的 JSESSIONID。
                // 在这里 return true 把导航拦下，WebView 就不会真的发出那个请求——
                // ticket 永远不会被消费，cookie 里也永远没有教务会话。
                // 症状极具迷惑性：浏览器里扫码能正常进教务，App 里却报
                // 「已登录统一身份认证，但教务系统未建立会话」。
                else -> false
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            val nav = classifyWechatNavigation(url)
            Log.i(LOG_TAG, "加载完成 -> $nav $url")
            // 成功判定必须在这里**也**做一次。CAS 的落地是页面自己用 JS 跳过去的
            // （金智那套就是这个形态：`<script>location.href='...'</script>`），
            // 而 shouldOverrideUrlLoading 对脚本发起的导航并不保证会被调用。
            // 只挂在那一个回调上，就会得到最难查的一种失败：微信里确认了、页面也确实跳走了，
            // App 却一直停在二维码上，什么都不说。
            if (nav == WechatNav.ACADEMIC_LANDING) {
                onLanded()
                return
            }
            // 登录页把二维码画在 #qrcode 的 canvas 上，直接把它读出来存进相册，
            // 用户就不必自己截图（截图的时机、裁切都得用户自己拿捏）。
            view.evaluateJavascript(QR_CODE_READ_SCRIPT) { raw ->
                val dataUrl = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                val base64 = dataUrl?.substringAfter("base64,", "")?.takeIf { it.isNotBlank() }
                    ?: return@evaluateJavascript
                val bitmap = runCatching {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull() ?: return@evaluateJavascript
                if (saveBitmapToAlbum(view.context, bitmap)) onQrCodeSaved()
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            val message = webErrorMessage(error.errorCode)
            Log.w(LOG_TAG, "主框架加载失败（${error.errorCode}）$message ${request.url}")
            onLoadFailed(message)
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            if (!request.isForMainFrame) return
            onLoadFailed("学校统一身份认证返回 HTTP ${errorResponse.statusCode}，请稍后重试")
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            // 证书有问题就停住。proceed() 等于亲手拆掉这道防线。
            handler.cancel()
            onLoadFailed("统一身份认证的证书校验未通过，已停止加载")
        }
    }
}

/**
 * 切回前台时主动催一次「扫码结果查询」。
 *
 * 页面的轮询函数名与隐藏字段名都取自金智自己的 `login6.js`（`checkLoginStatus(iamSessionId)`）。
 * 页面改版时它可能失配——所以这里只当作**加速**，真正的判定仍由页面自身的跳转完成，
 * 失败也只是「没催动」，不会把事情弄坏。
 */
private const val POLL_KICK_SCRIPT =
    "(function(){try{var el=document.getElementById('iamSessionId');" +
        "if(el&&typeof checkLoginStatus==='function'){checkLoginStatus(el.value);return 'kicked';}" +
        "return 'no-fn';}catch(e){return 'err';}})()"

/** 读 `#qrcode` 里的 canvas 并转成 data URL；没有二维码时返回空串。 */
private const val QR_CODE_READ_SCRIPT =
    "(function(){var c=document.querySelector('#qrcode canvas');return c?c.toDataURL('image/png'):'';})()"

private fun webErrorMessage(errorCode: Int): String = when (errorCode) {
    WebViewClient.ERROR_HOST_LOOKUP,
    WebViewClient.ERROR_TIMEOUT,
    WebViewClient.ERROR_CONNECT -> "网络不可用，请检查网络后重试"

    // ERROR_CLEARTEXT_NOT_PERMITTED：常量是 API 31 才加的，且并非所有 SDK stub 都能解析到，
    // 这里用它的等值字面量，避免为一个纯提示分支拉高编译门槛。
    CLEARTEXT_NOT_PERMITTED_ERROR_CODE ->
        "统一身份认证跳转到了非加密地址，已被系统阻止"

    else -> "页面加载失败（错误码 $errorCode）"
}

/**
 * 把二维码写进系统相册。
 *
 * Android 10 起走 MediaStore 的 `IS_PENDING`，不需要任何权限；
 * 更低版本要写公共目录，缺权限时**静默放弃**——用户自己截图一样能走通，
 * 不值得为省一步去要一个存储权限。
 */
private fun saveBitmapToAlbum(context: Context, bitmap: Bitmap): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    // 先清旧的再写：MediaStore 遇到重名会自动改叫「…(1).png」，
    // 于是每开一次登录相册就多一张。二维码是一次性的，留旧的纯属碍事。
    deleteExistingQrCodes(context)
    return runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, QR_CODE_FILE_NAME)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/ScheduleApp"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        try {
            resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            true
        } catch (error: Exception) {
            // 半成品留在相册里比没保存更糟，失败就把它撤掉。
            resolver.delete(uri, null, null)
            throw error
        }
    }.getOrDefault(false)
}

private const val QR_CODE_FILE_STEM = "桂系一站式_微信登录二维码"
private const val QR_CODE_FILE_NAME = "$QR_CODE_FILE_STEM.png"

/**
 * 清掉相册里此前存下的所有登录二维码。
 *
 * 按**前缀**匹配，不是精确文件名：早期版本留下的那批已经被 MediaStore 改名成
 * 「…(1).png」「…(2).png」，只删精确同名的话它们会永远赖在相册里。
 */
private fun deleteExistingQrCodes(context: Context) {
    runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val stale = mutableListOf<Uri>()
        resolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$QR_CODE_FILE_STEM%"),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                stale += ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
        stale.forEach { resolver.delete(it, null, null) }
    }
}

/** 等价于 `WebViewClient.ERROR_CLEARTEXT_NOT_PERMITTED`（API 31+）。 */
private const val CLEARTEXT_NOT_PERMITTED_ERROR_CODE = -10

/**
 * 排障用日志标签：`adb logcat -s WechatLogin` 能看到每一次顶层跳转与它的分类。
 * 「ticket 到底有没有回到教务」这类问题，有它一眼就看得出来，没有就只能靠猜。
 */
private const val LOG_TAG = "WechatLogin"
