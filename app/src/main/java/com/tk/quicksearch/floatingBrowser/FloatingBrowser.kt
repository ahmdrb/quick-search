package com.tk.quicksearch.floatingBrowser

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.tk.quicksearch.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Entry point for the floating browser. The small permission activity is used so Android can show
 * its explicit overlay permission screen before the overlay service is started.
 */
object FloatingBrowserLauncher {
    const val PACKAGE_NAME = "com.tk.quicksearch.floatingbrowser"
    const val EXTRA_URL = "floating_browser_url"

    fun open(context: Context, url: String) {
        val intent = Intent(context, FloatingBrowserPermissionActivity::class.java).apply {
            putExtra(EXTRA_URL, normalizeAddress(url))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, R.string.common_error_unable_to_open, Toast.LENGTH_SHORT).show()
            }
    }

    internal fun start(context: Context, url: String) {
        val intent = Intent(context, FloatingBrowserService::class.java).apply {
            putExtra(EXTRA_URL, normalizeAddress(url))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    internal fun normalizeAddress(value: String): String {
        val input = value.trim()
        if (input.isBlank()) return "https://www.google.com"
        if (input.startsWith("https://", ignoreCase = true) ||
            input.startsWith("http://", ignoreCase = true)
        ) {
            return input
        }
        if (!input.contains(' ') && input.contains('.')) return "https://$input"
        return "https://www.google.com/search?q=${Uri.encode(input)}"
    }
}

class FloatingBrowserPermissionActivity : Activity() {
    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 4107
    }

    private var requestedUrl: String = "https://www.google.com"

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        requestedUrl = intent.getStringExtra(FloatingBrowserLauncher.EXTRA_URL)
            ?: "https://www.google.com"
        continueWhenAllowed()
    }

    private fun continueWhenAllowed() {
        if (Settings.canDrawOverlays(this)) {
            runCatching { FloatingBrowserLauncher.start(this, requestedUrl) }
                .onFailure {
                    Toast.makeText(this, R.string.common_error_unable_to_open, Toast.LENGTH_SHORT).show()
                }
            finish()
            return
        }

        val permissionIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(permissionIntent, REQUEST_OVERLAY_PERMISSION)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.common_error_unable_to_open, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Deprecated("Uses the system overlay permission activity result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OVERLAY_PERMISSION) return
        if (Settings.canDrawOverlays(this)) {
            runCatching { FloatingBrowserLauncher.start(this, requestedUrl) }
                .onFailure {
                    Toast.makeText(this, R.string.common_error_unable_to_open, Toast.LENGTH_SHORT).show()
                }
        }
        finish()
    }
}

/**
 * User-started browser window. It owns a WebView in a TYPE_APPLICATION_OVERLAY window so browsing
 * can continue above the app the user was previously viewing.
 */
class FloatingBrowserService : Service() {
    private lateinit var windowManager: WindowManager
    private var panel: FrameLayout? = null
    private var minimizedBubble: TextView? = null
    private var browser: WebView? = null
    private var address: EditText? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var minimized = false
    private var dragStart: FloatArray? = null
    private var resizeStart: FloatArray? = null
    private var bubbleStart: FloatArray? = null
    private var bubbleMoved = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(FloatingBrowserLauncher.EXTRA_URL)
            ?: "https://www.google.com"
        if (!Settings.canDrawOverlays(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        runCatching {
            if (panel == null) {
                showPanel(url)
            } else {
                if (minimized) restorePanel()
                loadUrl(url)
            }
        }.onFailure {
            Toast.makeText(this, R.string.common_error_unable_to_open, Toast.LENGTH_SHORT).show()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeWindows()
        super.onDestroy()
    }

    private fun showPanel(initialUrl: String) {
        val screen = screenBounds()
        val width = min(screen.width(), max(dp(280), (screen.width() * 0.92f).roundToInt()))
        val height = min(screen.height(), max(dp(240), (screen.height() * 0.72f).roundToInt()))
        val params = WindowManager.LayoutParams(
            width,
            height,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(64)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        panelParams = params

        val webView = WebView(this).apply {
            setBackgroundColor(panelColor())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (!url.isNullOrBlank()) address?.setText(url)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") return false
                    if (scheme == "mailto" || scheme == "tel") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                    return true
                }
            }
            webChromeClient = WebChromeClient()
        }
        browser = webView

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            clipToOutline = true
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(panelColor())
        }

        val moveHandle = button("⠿", getString(R.string.browser_floating_name), dp(38)) { }
        moveHandle.setOnTouchListener { _, event -> dragPanel(event) }
        toolbar.addView(moveHandle)

        val addressField = EditText(this).apply {
            singleLine = true
            textSize = 14f
            setTextColor(primaryTextColor())
            setHintTextColor(secondaryTextColor())
            hint = "Search or enter address"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(8), 0, dp(8), 0)
            background = inputBackground()
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    loadUrl(text?.toString().orEmpty())
                    true
                } else false
            }
        }
        address = addressField
        toolbar.addView(addressField, LinearLayout.LayoutParams(0, dp(48), 1f))

        toolbar.addView(button("‹", getString(R.string.desc_navigate_back), dp(36)) {
            if (webView.canGoBack()) webView.goBack()
        })
        toolbar.addView(button("›", getString(R.string.action_open), dp(36)) {
            if (webView.canGoForward()) webView.goForward()
        })
        toolbar.addView(button("↻", getString(R.string.action_open), dp(36)) { webView.reload() })
        toolbar.addView(button("−", getString(R.string.browser_floating_name), dp(36)) { minimizePanel() })
        toolbar.addView(button("×", getString(R.string.browser_floating_name), dp(36)) { stopSelf() })

        content.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(56),
        ))
        content.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val root = FrameLayout(this).apply {
            background = panelBackground()
            clipToOutline = true
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        val resizeHandle = TextView(this).apply {
            text = "◢"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(secondaryTextColor())
            contentDescription = getString(R.string.browser_floating_name)
            setOnTouchListener { _, event -> resizePanel(event) }
        }
        root.addView(resizeHandle, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END))

        panel = root
        windowManager.addView(root, params)
        loadUrl(initialUrl)
    }

    private fun button(label: String, description: String, width: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(primaryTextColor())
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(width, dp(48))
            val value = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)) {
                background = runCatching { getDrawable(value.resourceId) }.getOrNull()
            }
        }

    private fun loadUrl(value: String) {
        val normalized = FloatingBrowserLauncher.normalizeAddress(value)
        address?.setText(normalized)
        browser?.loadUrl(normalized)
    }

    private fun minimizePanel() {
        val currentPanel = panel ?: return
        val params = panelParams ?: return
        runCatching { windowManager.removeView(currentPanel) }
        val bubble = TextView(this).apply {
            text = "🌐"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(primaryTextColor())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(panelColor())
                setStroke(dp(1), secondaryTextColor())
            }
            elevation = dp(8).toFloat()
            contentDescription = getString(R.string.browser_floating_name)
            setOnTouchListener { view, event -> dragBubble(view, event) }
        }
        val bubbleParams = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x.coerceAtLeast(0)
            y = params.y.coerceAtLeast(0)
        }
        windowManager.addView(bubble, bubbleParams)
        minimizedBubble = bubble
        minimized = true
    }

    private fun restorePanel() {
        val bubble = minimizedBubble ?: return
        runCatching { windowManager.removeView(bubble) }
        minimizedBubble = null
        val currentPanel = panel ?: return
        val params = panelParams ?: return
        windowManager.addView(currentPanel, params)
        minimized = false
    }

    private fun dragPanel(event: MotionEvent): Boolean {
        val params = panelParams ?: return true
        val view = panel ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStart = floatArrayOf(event.rawX, event.rawY, params.x.toFloat(), params.y.toFloat())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val start = dragStart ?: return true
                params.x = (start[2] + event.rawX - start[0]).roundToInt()
                params.y = (start[3] + event.rawY - start[1]).roundToInt()
                clampPanel(params)
                runCatching { windowManager.updateViewLayout(view, params) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragStart = null
                return true
            }
        }
        return true
    }

    private fun resizePanel(event: MotionEvent): Boolean {
        val params = panelParams ?: return true
        val view = panel ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeStart = floatArrayOf(event.rawX, event.rawY, params.width.toFloat(), params.height.toFloat())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val start = resizeStart ?: return true
                val screen = screenBounds()
                params.width = (start[2] + event.rawX - start[0]).roundToInt()
                    .coerceIn(dp(280).coerceAtMost(screen.width()), screen.width())
                params.height = (start[3] + event.rawY - start[1]).roundToInt()
                    .coerceIn(dp(240).coerceAtMost(screen.height()), screen.height())
                clampPanel(params)
                runCatching { windowManager.updateViewLayout(view, params) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resizeStart = null
                return true
            }
        }
        return true
    }

    private fun dragBubble(view: View, event: MotionEvent): Boolean {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                bubbleStart = floatArrayOf(event.rawX, event.rawY, params.x.toFloat(), params.y.toFloat())
                bubbleMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val start = bubbleStart ?: return true
                val dx = event.rawX - start[0]
                val dy = event.rawY - start[1]
                if (abs(dx) > ViewConfiguration.get(this).scaledTouchSlop ||
                    abs(dy) > ViewConfiguration.get(this).scaledTouchSlop
                ) bubbleMoved = true
                if (bubbleMoved) {
                    val screen = screenBounds()
                    params.x = (start[2] + dx).roundToInt().coerceIn(0, max(0, screen.width() - view.width))
                    params.y = (start[3] + dy).roundToInt().coerceIn(0, max(0, screen.height() - view.height))
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!bubbleMoved) restorePanel()
                bubbleStart = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                bubbleStart = null
                return true
            }
        }
        return true
    }

    private fun clampPanel(params: WindowManager.LayoutParams) {
        val screen = screenBounds()
        params.x = params.x.coerceIn(0, max(0, screen.width() - params.width))
        params.y = params.y.coerceIn(0, max(0, screen.height() - params.height))
    }

    private fun removeWindows() {
        minimizedBubble?.let { runCatching { windowManager.removeView(it) } }
        minimizedBubble = null
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        browser?.let {
            runCatching {
                it.stopLoading()
                it.loadUrl("about:blank")
                it.destroy()
            }
        }
        browser = null
        address = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun screenBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.bounds
        }
        val metrics = resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun windowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun panelColor(): Int = colorForAttribute(android.R.attr.colorBackground, Color.DKGRAY)

    private fun primaryTextColor(): Int = colorForAttribute(android.R.attr.textColorPrimary, Color.WHITE)

    private fun secondaryTextColor(): Int = colorForAttribute(android.R.attr.textColorSecondary, Color.LTGRAY)

    private fun colorForAttribute(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attribute, value, true)) return fallback
        if (value.resourceId != 0) {
            return runCatching { ContextCompat.getColor(this, value.resourceId) }.getOrDefault(value.data)
        }
        return value.data
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(panelColor())
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), secondaryTextColor())
    }

    private fun inputBackground() = GradientDrawable().apply {
        setColor(panelColor())
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), secondaryTextColor())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.browser_floating_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.browser_floating_name))
            .setContentText(getString(R.string.browser_floating_name))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL = "floating_browser"
        private const val NOTIFICATION_ID = 4108
    }
}
