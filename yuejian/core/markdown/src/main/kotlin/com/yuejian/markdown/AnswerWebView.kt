package com.yuejian.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val ASSET_BASE = "file:///android_asset/markdown/"
private const val PAGE_URL = ASSET_BASE + "answer.html"
private val EMPTY_BYTES = ByteArray(0)
private const val MIN_HEIGHT_DP = 24

/**
 * 回答渲染宿主：持有 WebView 池与每个消息的内容高度。
 *
 * 约定：
 * - 纵向滚动一律交给外层 Compose 列表，WebView 自身不可纵向滚动；
 *   只有代码块 / 表格 / 长公式内部的横向滚动在 WebView 内处理。
 * - 池内的 WebView 只加载一次本地页面，之后用 JS 注入更新内容，
 *   不重建 WebView、不整页刷新、不重复加载 KaTeX 与字体。
 * - 高度测量通过 evaluateJavascript 拉取（而非暴露 JS 桥），
 *   页面无法主动调用任何原生能力。
 */
class AnswerRenderHost internal constructor(context: Context, private val maxPooled: Int) {
    private val appContext = context.applicationContext
    private val idle = ArrayDeque<RichWebView>()
    private val heights = mutableStateMapOf<String, Int>()
    var onLinkClick: (String) -> Unit = {}
    var onAnswerDoubleTap: () -> Unit = {}
    var onQuoteRequest: (messageId: String, text: String) -> Unit = { _, _ -> }
    var onCardRequest: (messageId: String, text: String) -> Unit = { _, _ -> }
    var onSelectionChange: (active: Boolean) -> Unit = {}
    var textZoom: Int = 100

    /** 全局同一时刻只会出现一个文本选择 ActionMode；计数避免新旧模式回调顺序带来的误判。 */
    var selectionActive: Boolean = false
        private set
    private var selectionCount = 0

    internal fun beginSelection() {
        selectionCount++
        if (selectionCount == 1) {
            selectionActive = true
            onSelectionChange(true)
        }
    }

    internal fun endSelection() {
        selectionCount = (selectionCount - 1).coerceAtLeast(0)
        if (selectionCount == 0) {
            selectionActive = false
            onSelectionChange(false)
        }
    }

    internal fun heightFor(key: String): Int? = heights[key]

    internal fun acquire(): RichWebView {
        val pooled = synchronized(idle) { if (idle.isEmpty()) null else idle.removeFirst() }
        val view = pooled ?: RichWebView(appContext)
        if (view.parent is ViewGroup) (view.parent as ViewGroup).removeView(view)
        view.host = this
        configure(view)
        if (!view.pageLoaded) {
            view.pageLoaded = true
            view.loadUrl(PAGE_URL)
        }
        return view
    }

    internal fun release(view: RichWebView) {
        view.boundKey = null
        view.boundMessageId = null
        view.quoteEnabled = false
        view.onTextScaleGesture = null
        view.onTextScaleGestureEnd = null
        view.lastMarkdown = null
        // 池化复用前清掉旧选区，防止把引用挂到另一条消息上
        view.clearSelection()
        synchronized(idle) {
            if (idle.size < maxPooled) {
                idle.addLast(view)
            } else {
                view.destroy()
            }
        }
    }

    internal fun reportHeight(key: String, px: Int) {
        if (px > 0) heights[key] = px
    }

    fun dispose() {
        synchronized(idle) {
            while (idle.isNotEmpty()) idle.removeFirst().destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: RichWebView) {
        if (view.configured) return
        view.configured = true
        view.settings.apply {
            // KaTeX 需要 JS；页面只加载本地 assets，且所有外部请求都被拦截
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            loadsImagesAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = false
            loadWithOverviewMode = false
            textZoom = this@AnswerRenderHost.textZoom
        }
        view.webViewClient = AnswerWebViewClient(view)
        view.webChromeClient = null
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.isFocusableInTouchMode = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.setBackgroundColor(Color.TRANSPARENT)
    }
}

@Composable
fun rememberAnswerRenderHost(
    onLinkClick: (String) -> Unit,
    onAnswerDoubleTap: () -> Unit = {},
    onQuoteRequest: (messageId: String, text: String) -> Unit = { _, _ -> },
    onSelectionChange: (active: Boolean) -> Unit = {},
    onCardRequest: (messageId: String, text: String) -> Unit = { _, _ -> },
    maxPooled: Int = 4
): AnswerRenderHost {
    val context = LocalContext.current
    val density = LocalDensity.current
    val host = remember(context, maxPooled) { AnswerRenderHost(context, maxPooled) }
    val zoom = (density.fontScale * 100f).roundToInt().coerceIn(50, 300)
    val currentLink by rememberUpdatedState(onLinkClick)
    val currentDoubleTap by rememberUpdatedState(onAnswerDoubleTap)
    val currentQuote by rememberUpdatedState(onQuoteRequest)
    val currentSelection by rememberUpdatedState(onSelectionChange)
    val currentCard by rememberUpdatedState(onCardRequest)
    SideEffect {
        host.textZoom = zoom
        host.onLinkClick = currentLink
        host.onAnswerDoubleTap = currentDoubleTap
        host.onQuoteRequest = currentQuote
        host.onSelectionChange = currentSelection
        host.onCardRequest = currentCard
    }
    DisposableEffect(host) { onDispose { host.dispose() } }
    return host
}

/**
 * 单条回答的富文本渲染区。高度由 JS 实测得到，未测出时使用粗略估算避免首帧塌陷。
 *
 * @param key 绑定标识：内容或状态变化时变化，用于判断是否需要重新注入
 * @param messageId 所属消息 ID：选区追问回调的来源标识
 * @param heightKey 高度缓存标识：同一条回答保持稳定，避免状态切换时高度闪跳
 * @param quoteEnabled 是否允许“追问这段”（仅限已结束的 AI 回答）
 */
@Composable
fun AnswerRichText(
    markdown: String,
    key: String,
    messageId: String,
    heightKey: String = key,
    style: AnswerStyle,
    host: AnswerRenderHost,
    quoteEnabled: Boolean = false,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
    onTextScaleGesture: ((Float) -> Unit)? = null,
    onTextScaleGestureEnd: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    val measured = host.heightFor(key = heightKey)
    val estimate = remember(heightKey, markdown.length, style.baseSizePx) { estimateHeightPx(markdown, style.baseSizePx) }
    val heightDp = maxOf(MIN_HEIGHT_DP.dp, density.run { (measured ?: estimate).toDp() })
    AndroidView(
        factory = { host.acquire() },
        update = { view ->
            view.onTextScaleGesture = onTextScaleGesture
            view.onTextScaleGestureEnd = onTextScaleGestureEnd
            view.bind(key, heightKey, messageId, quoteEnabled, markdown, style, host, density, textScale)
        },
        onRelease = { view -> host.release(view) },
        modifier = modifier.fillMaxWidth().height(heightDp)
    )
}

/** 首帧估算：按“每行约 F 个中文字符”粗略折算，仅用于避免 0 高度闪烁。 */
internal fun estimateHeightPx(markdown: String, fontSizePx: Float): Int {
    val perLine = maxOf(8f, fontSizePx)
    var rows = 0
    var index = 0
    while (index < markdown.length) {
        val nxt = markdown.indexOf('\n', index)
        val end = if (nxt < 0) markdown.length else nxt
        val len = (end - index).coerceAtLeast(1)
        rows += ceil(len.toFloat() / perLine).toInt().coerceAtLeast(1)
        if (nxt < 0) break
        index = nxt + 1
    }
    return ceil((rows.coerceAtLeast(1)) * fontSizePx * 1.75f + 8f).toInt()
}

internal class RichWebView(context: Context) : WebView(context) {
    companion object {
        /** 文本选择菜单里的“追问这段”。 */
        private const val MENU_QUOTE = 0x2f17
        /** 文本选择菜单里的“保存卡片”（摘录成精选卡片）。 */
        private const val MENU_CARD = 0x2f18
    }

    var host: AnswerRenderHost? = null
    var configured = false
    var pageLoaded = false
    var pageReady = false
    var boundKey: String? = null
    var boundMessageId: String? = null
    var quoteEnabled: Boolean = false
    var lastMarkdown: String? = null
    var lastStyleKey: String? = null
    var lastZoom: Int = 0

    var onTextScaleGesture: ((Float) -> Unit)? = null
    var onTextScaleGestureEnd: (() -> Unit)? = null
    private var consumingPinch = false
    private var pinchChanged = false
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = onTextScaleGesture != null
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor.isFinite() && factor > 0f) {
                    pinchChanged = true
                    onTextScaleGesture?.invoke(factor)
                }
                return true
            }
        }).apply {
            isQuickScaleEnabled = false
            isStylusScaleEnabled = false
        }
    private var measureKey: String? = null

    private var pendingHtml: String? = null
    private var pendingCss: String? = null
    private var pendingKey: String? = null
    private var renderSeq = 0L
    private var densityScale = 1f
    private var consumingDoubleTap = false
    private val doubleTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // 文本选择/拖动手柄期间不识别专注双击，清除选择后恢复
            if (host?.selectionActive == true) return false
            consumingDoubleTap = true
            host?.onAnswerDoubleTap?.invoke()
            return true
        }
    })

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(wrapSelectionMenu(callback))

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(wrapSelectionMenu(callback), type)

    /** 在系统文本选择菜单里追加“追问这段”；其余菜单行为原样保留。 */
    private fun wrapSelectionMenu(callback: ActionMode.Callback?): ActionMode.Callback? {
        if (callback == null) return null
        return object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                val created = callback.onCreateActionMode(mode, menu)
                host?.beginSelection()
                if (quoteEnabled && menu != null) {
                    if (menu.findItem(MENU_QUOTE) == null) menu.add(Menu.NONE, MENU_QUOTE, 100, "追问这段")
                    if (menu.findItem(MENU_CARD) == null) menu.add(Menu.NONE, MENU_CARD, 101, "保存卡片")
                }
                return created
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean =
                callback.onPrepareActionMode(mode, menu)

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                // 注意：不能在提取完成前 finish/清选区——JS 取文是异步的，
                // 提前收尾会让选区被系统清掉、提取为空（表现为“点了没反应”）
                if (item?.itemId == MENU_QUOTE) { requestQuote(mode); return true }
                if (item?.itemId == MENU_CARD) { requestCard(mode); return true }
                return callback.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode?) {
                host?.endSelection()
                callback.onDestroyActionMode(mode)
            }
        }
    }

    /** 白名单提取：仅本页 YuejianSelection()，结果须通过消息与绑定代次校验后才回传。 */
    private fun requestQuote(mode: ActionMode?) {
        val host = host ?: return
        if (!quoteEnabled) return
        val messageId = boundMessageId ?: return
        val seq = renderSeq
        evaluateJavascript("YuejianSelection()") { raw ->
            val stale = boundMessageId != messageId || renderSeq != seq || !quoteEnabled
            val cleaned = raw?.trim().orEmpty()
            val bytes = if (cleaned.isEmpty() || cleaned == "null") null else try {
                Base64.decode(cleaned.trim('"'), Base64.DEFAULT)
            } catch (_: Exception) { null }
            val text = bytes?.let { String(it, Charsets.UTF_8).trim() }.orEmpty()
            if (!stale && text.isNotEmpty() && text.length <= 16000) host.onQuoteRequest(messageId, text)
            else if (!stale) host.onQuoteRequest(messageId, "") // 空文本=提取失败，交上层提示
            clearSelection()
            mode?.finish()
        }
    }

    /** 白名单提取：仅本页 YuejianSelection()，校验消息与绑定代次后回传（摘录成卡片用）。 */
    private fun requestCard(mode: ActionMode?) {
        val host = host ?: return
        if (!quoteEnabled) return
        val messageId = boundMessageId ?: return
        val seq = renderSeq
        evaluateJavascript("YuejianSelection()") { raw ->
            val stale = boundMessageId != messageId || renderSeq != seq || !quoteEnabled
            val cleaned = raw?.trim().orEmpty()
            val bytes = if (cleaned.isEmpty() || cleaned == "null") null else try {
                Base64.decode(cleaned.trim('"'), Base64.DEFAULT)
            } catch (_: Exception) { null }
            val text = bytes?.let { String(it, Charsets.UTF_8).trim() }.orEmpty()
            if (!stale && text.isNotEmpty() && text.length <= 60000) host.onCardRequest(messageId, text)
            else if (!stale) host.onCardRequest(messageId, "") // 空文本=提取失败，交上层提示
            clearSelection()
            mode?.finish()
        }
    }

    internal fun clearSelection() {
        if (pageReady) evaluateJavascript("try{window.getSelection().removeAllRanges()}catch(e){}") { }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onTextScaleGesture != null) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { consumingPinch = false; pinchChanged = false }
            if (event.pointerCount >= 2 && !consumingPinch) {
                consumingPinch = true
                consumingDoubleTap = false
                clearSelection()
                parent?.requestDisallowInterceptTouchEvent(true)
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.onTouchEvent(cancel)
                cancel.recycle()
            }
            scaleDetector.onTouchEvent(event)
            if (consumingPinch) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    if (pinchChanged) onTextScaleGestureEnd?.invoke()
                    consumingPinch = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
        }
        doubleTapDetector.onTouchEvent(event)
        if (consumingDoubleTap) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) consumingDoubleTap = false
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) {
            val key = measureKey
            if (key != null) scheduleMeasure(key, renderSeq, 100)
        }
    }

    fun bind(key: String, heightKey: String, messageId: String, quoteEnabled: Boolean, markdown: String, style: AnswerStyle, host: AnswerRenderHost, density: Density, textScale: Float = 1f) {
        this.host = host
        this.measureKey = heightKey
        this.boundMessageId = messageId
        this.quoteEnabled = quoteEnabled
        this.densityScale = density.density
        val zoom = if (onTextScaleGesture == null) {
            (density.fontScale * 100f).roundToInt().coerceIn(50, 300)
        } else {
            (density.fontScale * textScale.coerceIn(.8f, 2f) * 100f).roundToInt().coerceIn(40, 600)
        }
        val zoomChanged = zoom != lastZoom
        val webStyle = style.copy(baseSizePx = style.baseSizePx / density.density / density.fontScale)
        val changed = boundKey != key || lastMarkdown != markdown || lastStyleKey != webStyle.styleKey || zoomChanged
        if (!changed) return
        if (zoomChanged) {
            lastZoom = zoom
            settings.textZoom = zoom
        }
        if (boundKey == key && lastMarkdown == markdown && lastStyleKey == webStyle.styleKey) {
            // Pinch changes only WebView textZoom: retain DOM/selection and avoid parsing Markdown again.
            measureKey?.let { scheduleMeasure(it, renderSeq, 32) }
        } else render(key, markdown, webStyle)
    }

    private fun render(key: String, markdown: String, style: AnswerStyle) {
        boundKey = key
        lastMarkdown = markdown
        lastStyleKey = style.styleKey
        renderSeq++
        val html = YuejianMarkdown.render(markdown)
        pendingHtml = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        pendingCss = Base64.encodeToString(style.toCss().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        pendingKey = key
        if (pageReady) flushPending()
    }

    internal fun flushPending() {
        val htmlB64 = pendingHtml ?: return
        val cssB64 = pendingCss ?: return
        val key = pendingKey ?: return
        pendingHtml = null
        pendingCss = null
        pendingKey = null
        val seq = renderSeq
        // base64 内容只含 [A-Za-z0-9+/=]，不存在字符串转义问题
        evaluateJavascript("YuejianRender('$htmlB64','$cssB64')") { result ->
            applyHeight(seq, parseHeight(result))
        }
        // KaTeX 字体异步就绪后行高可能变化，补测一次
        val height = measureKey
        if (height != null) scheduleMeasure(height, seq, 240)
    }

    private fun scheduleMeasure(heightKey: String, seq: Long, delayMs: Long) {
        postDelayed({
            if (renderSeq == seq) {
                evaluateJavascript("YuejianMeasure()") { result -> applyHeight(seq, parseHeight(result)) }
            }
        }, delayMs)
    }

    private fun applyHeight(seq: Long, px: Int) {
        val key = measureKey ?: return
        if (px <= 0 || renderSeq != seq) return
        host?.reportHeight(key, (px * densityScale).roundToInt())
    }
}

private class AnswerWebViewClient(private val view: RichWebView) : WebViewClient() {
    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString().orEmpty()
        view.host?.handleLink(url)
        return true
    }

    @Suppress("OverridingDeprecatedMember")
    override fun shouldOverrideUrlLoading(v: WebView, url: String?): Boolean {
        view.host?.handleLink(url.orEmpty())
        return true
    }

    override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString().orEmpty()
        return if (isLocal(url)) null else blocked()
    }

    @Suppress("OverridingDeprecatedMember")
    override fun shouldInterceptRequest(v: WebView, url: String?): WebResourceResponse? {
        return if (isLocal(url.orEmpty())) null else blocked()
    }

    override fun onPageFinished(v: WebView, url: String) {
        if (url.startsWith(ASSET_BASE)) {
            view.pageReady = true
            view.flushPending()
        }
    }

    override fun onReceivedError(
        v: WebView,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        // 本地页面加载异常不做任何提示，也不让 WebView 显示错误页
    }

    private fun AnswerRenderHost.handleLink(url: String) {
        if (url.isBlank() || url == PAGE_URL) return
        if (isLocal(url)) return
        // 链接由应用接管，不交给 WebView 自行跳转
        onLinkClick(url)
    }

    private fun isLocal(url: String) = url.startsWith(ASSET_BASE) || url == "about:blank"

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(EMPTY_BYTES))
}

private fun parseHeight(raw: String?): Int {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == "null") return 0
    val cleaned = trimmed.trim('"')
    return cleaned.toLongOrNull()?.toInt()?.coerceAtLeast(0) ?: 0
}
