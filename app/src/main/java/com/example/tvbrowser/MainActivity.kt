package com.example.tvbrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.widget.VideoView
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.InputDevice
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    // ── Design Tokens ─────────────────────────────────────────────────────────
    companion object {
        const val BG_BASE          = "#07070C"
        const val BG_RAIL          = "#0B0B18"
        const val BG_SURFACE       = "#11111E"
        const val BG_SURFACE_FOCUS = "#1A1A2E"
        const val ACCENT_GLOW      = "#7C6FF0" // Indigo/Violet Focus Accent
        const val ACCENT_CYAN      = "#00E5FF"
        const val TEXT_PRIMARY     = "#FFFFFF"
        const val TEXT_MUTED       = "#7E7E8C"
        const val MAX_HISTORY      = 20
        
        const val SCREEN_DASHBOARD = 0
        const val SCREEN_MEDIA     = 1
    }

    // ── Data Models ───────────────────────────────────────────────────────────
    data class HistoryEntry(
        val title: String,
        val url: String,
        val relativeTimestamp: String,
        val faviconUrl: String? = null
    )

    data class UserBookmark(
        val title: String,
        val url: String,
        var isPinned: Boolean = true
    )

    data class PinnedMediaSite(
        val name: String,
        val url: String
    )

    data class MediaHistoryEntry(
        val title: String,
        val url: String,
        val domain: String,
        val relativeTimestamp: String,
        val year: String = "",
        val type: String = "video", // "video" or "audio"
        val artistName: String? = null,
        val imageUrl: String? = null
    )

    private lateinit var webViewContainer: FrameLayout
    private val activeWebView: WebView? get() = tabs.getOrNull(activeTabIndex)?.webView
    private lateinit var cursorView: ImageView

    private val tabs = mutableListOf<TabInfo>()
    private var activeTabIndex = -1

    private class TabInfo(
        val webView: WebView,
        var title: String = "New Tab",
        var url: String = "about:blank",
        var canGoBack: Boolean = false,
        var currentScrollY: Int = 0
    )

    // ── Virtual Cursor ────────────────────────────────────────────────────────
    private var isCursorActive = false
    private var cursorX = 0f
    private var cursorY = 0f
    private val cursorSpeed = 15f
    private var canGoBackState = false
    private var currentScrollY = 0
    private var isDragging = false
    private var dragStartMillis = 0L
    private var longPressTriggered = false

    // ── Layout Mode ───────────────────────────────────────────────────────────
    private var isDashboardActive = true
    private var currentScreenId = SCREEN_DASHBOARD
    private var isTabsOverlayVisible = false

    // ── Top-Level Views ───────────────────────────────────────────────────────
    private lateinit var rootContainer: FrameLayout
    private lateinit var homeDashboardLayout: LinearLayout
    private lateinit var browserSessionLayout: LinearLayout

    // ── Dashboard Views ───────────────────────────────────────────────────────
    private lateinit var omniInput: EditText
    private lateinit var continueRowContainer: LinearLayout
    private lateinit var bookmarkRowContainer: LinearLayout
    private lateinit var recentMediaBannerContainer: FrameLayout
    private lateinit var railTabsLabel: TextView
    private lateinit var railTabsBadge: TextView
    private lateinit var timeLabel: TextView
    private lateinit var wifiIconView: ImageView
    private lateinit var dashboardScrollView: ScrollView
    private lateinit var fixedTopSection: LinearLayout

    // ── Media Screen Views ────────────────────────────────────────────────────
    private lateinit var mediaScreenScrollView: ScrollView
    private lateinit var pinnedMediaRowContainer: LinearLayout
    private lateinit var mediaHistoryGridContainer: LinearLayout

    // ── Browser Views ─────────────────────────────────────────────────────────
    private lateinit var slimTopBar: LinearLayout
    private lateinit var slimUrlBar: EditText
    private lateinit var slimTabsButton: TextView
    private var slimBarVisible = true

    private lateinit var slimLoadingSpinner: ProgressBar
    private lateinit var slimRefreshStopBtn: ImageView
    private lateinit var slimAdBlockBtn: TextView
    private lateinit var btnOpenPlayer: TextView
    private lateinit var slimBgMediaIndicator: TextView
    private lateinit var urlClickTarget: TextView
    private lateinit var urlBarContainer: LinearLayout
    private var isFullScreenMode = false

    // ── Dashboard Active Tabs Row ─────────────────────────────────────────────
    private lateinit var tabsRowContainer: LinearLayout

    // ── Dashboard Data ────────────────────────────────────────────────────────
    private val historyEntries = mutableListOf<HistoryEntry>()
    private val bookmarks = mutableListOf<UserBookmark>()
    private val pinnedMediaSites = mutableListOf<PinnedMediaSite>()
    private val mediaHistoryList = mutableListOf<MediaHistoryEntry>()
    private val customBlockRules = HashMap<String, ArrayList<String>>() // domain -> custom rules
    private lateinit var prefs: SharedPreferences

    // ── State Variables ───────────────────────────────────────────────────────
    private var lastFocusedViewBeforeOverlay: View? = null
    private var detectedMediaUrl: String? = null
    private var detectedMediaTitle: String? = null
    private var isElementPickerActive = false

    // Background playback info
    private var isBackgroundPlaybackActive = false
    private var backgroundMediaUrl: String? = null
    private var backgroundMediaTitle: String? = null
    private var nativeVideoView: VideoView? = null
    private var nativePlayerLayout: FocusTrapFrameLayout? = null

    // Search Engine Map
    private val searchEngines = mapOf(
        "DuckDuckGo" to "https://duckduckgo.com/?q=%s",
        "Google" to "https://www.google.com/search?q=%s",
        "Bing" to "https://www.bing.com/search?q=%s",
        "Startpage" to "https://www.startpage.com/sp/search?query=%s",
        "Brave Search" to "https://search.brave.com/search?q=%s",
        "Ecosia" to "https://www.ecosia.org/search?q=%s",
        "Custom" to ""
    )

    // Favicon image cache
    private val faviconCache = mutableMapOf<String, Bitmap>()

    // Wi-Fi Status receiver
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (::wifiIconView.isInitialized) {
                updateWifiIcon()
            }
        }
    }

    // Time ticker receiver
    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (::timeLabel.isInitialized) {
                timeLabel.text = getFormattedTime()
            }
        }
    }

    // Theme Mode Configuration
    private var isDarkTheme = true
    private var themeMode = "auto"

    private fun updateThemeState() {
        val savedMode = prefs.getString("theme_mode", "auto") ?: "auto"
        themeMode = savedMode
        isDarkTheme = when (savedMode) {
            "dark" -> true
            "light" -> false
            else -> { // auto
                val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private fun resolveColor(hex: String): Int {
        return when (hex) {
            BG_BASE          -> if (isDarkTheme) android.graphics.Color.parseColor("#000000") else android.graphics.Color.parseColor("#F2F2F7")
            BG_RAIL          -> if (isDarkTheme) android.graphics.Color.parseColor("#121212") else android.graphics.Color.parseColor("#FFFFFF")
            BG_SURFACE       -> if (isDarkTheme) android.graphics.Color.parseColor("#1C1C1E") else android.graphics.Color.parseColor("#E5E5EA")
            BG_SURFACE_FOCUS -> if (isDarkTheme) android.graphics.Color.parseColor("#2C2C2E") else android.graphics.Color.parseColor("#FFFFFF")
            ACCENT_GLOW      -> if (isDarkTheme) android.graphics.Color.parseColor("#7C6FF0") else android.graphics.Color.parseColor("#5856D6")
            ACCENT_CYAN      -> if (isDarkTheme) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#007AFF")
            TEXT_PRIMARY     -> if (isDarkTheme) android.graphics.Color.parseColor("#FFFFFF") else android.graphics.Color.parseColor("#1C1C1E")
            TEXT_MUTED       -> if (isDarkTheme) android.graphics.Color.parseColor("#8E8E93") else android.graphics.Color.parseColor("#8E8E93")
            else             -> {
                try {
                    when (hex) {
                        "#00000000" -> android.graphics.Color.TRANSPARENT
                        "#E607070C" -> if (isDarkTheme) android.graphics.Color.parseColor("#E6000000") else android.graphics.Color.parseColor("#E6F2F2F7")
                        "#2A2A3E"   -> if (isDarkTheme) android.graphics.Color.parseColor("#2C2C2E") else android.graphics.Color.parseColor("#D1D1D6")
                        "#1A1A30"   -> if (isDarkTheme) android.graphics.Color.parseColor("#2C2C2E") else android.graphics.Color.parseColor("#D1D1D6")
                        "#1A1A2F"   -> if (isDarkTheme) android.graphics.Color.parseColor("#2C2C2E") else android.graphics.Color.parseColor("#D1D1D6")
                        "#3E3E5C"   -> if (isDarkTheme) android.graphics.Color.parseColor("#3A3A3C") else android.graphics.Color.parseColor("#C7C7CC")
                        "#1B2A1B"   -> if (isDarkTheme) android.graphics.Color.parseColor("#122A12") else android.graphics.Color.parseColor("#E8F5E9")
                        "#141424"   -> if (isDarkTheme) android.graphics.Color.parseColor("#0A0A0A") else android.graphics.Color.parseColor("#EAEAEF")
                        "#2D1414"   -> if (isDarkTheme) android.graphics.Color.parseColor("#2A0A0A") else android.graphics.Color.parseColor("#FEE2E2")
                        "#FF453A"   -> if (isDarkTheme) android.graphics.Color.parseColor("#FF453A") else android.graphics.Color.parseColor("#D32F2F")
                        else -> android.graphics.Color.parseColor(hex)
                    }
                } catch (e: Exception) {
                    android.graphics.Color.BLACK
                }
            }
        }
    }

    private fun View.applyPremiumShadow(dp: Float, elevationDp: Float, hasFocus: Boolean) {
        this.elevation = elevationDp * dp
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            if (!isDarkTheme) {
                this.outlineSpotShadowColor = android.graphics.Color.parseColor(if (hasFocus) "#801C1C1E" else "#351C1C1E")
                this.outlineAmbientShadowColor = android.graphics.Color.parseColor(if (hasFocus) "#451C1C1E" else "#201C1C1E")
            } else {
                this.outlineSpotShadowColor = android.graphics.Color.BLACK
                this.outlineAmbientShadowColor = android.graphics.Color.BLACK
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("tvbrowser_data", Context.MODE_PRIVATE)
        updateThemeState()

        loadHistory()
        loadBookmarks()
        loadPinnedMediaSites()
        loadMediaHistory()
        loadCustomBlockRules()

        rootContainer = object : FrameLayout(this) {
            override fun onViewAdded(child: View?) {
                super.onViewAdded(child)
                updateBackgroundFocusBlock()
            }
            override fun onViewRemoved(child: View?) {
                super.onViewRemoved(child)
                updateBackgroundFocusBlock()
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(resolveColor(BG_BASE))
        }

        buildHomeDashboard()
        buildBrowserSession()

        rootContainer.addView(homeDashboardLayout)
        rootContainer.addView(browserSessionLayout)

        setContentView(rootContainer)

        // Back handler: handles overlay dismiss first, then browser back, then dashboard return
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val overlayCount = rootContainer.childCount
                when {
                    overlayCount > 2 -> {
                        // Dynamically added dialog is active (since base has 2 static child views)
                        val topOverlay = rootContainer.getChildAt(overlayCount - 1)
                        if (topOverlay == nativePlayerLayout) {
                            stopNativePlayer()
                        }
                        rootContainer.removeView(topOverlay)
                        lastFocusedViewBeforeOverlay?.requestFocus()
                    }
                    isElementPickerActive  -> {
                        isElementPickerActive = false
                        Toast.makeText(this@MainActivity, "Element picker cancelled", Toast.LENGTH_SHORT).show()
                        activeWebView?.requestFocus()
                    }
                    !isDashboardActive && canGoBackState -> activeWebView?.goBack()
                    !isDashboardActive     -> showDashboard()
                    currentScreenId == SCREEN_MEDIA -> showScreen(SCREEN_DASHBOARD)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        // Restore tabs or create the default one
        val savedUrls = savedInstanceState?.getStringArrayList("tab_urls")
        val savedActiveIndex = savedInstanceState?.getInt("active_tab_index", -1) ?: -1
        val savedIsDashboard = savedInstanceState?.getBoolean("is_dashboard_active", true) ?: true
        val savedScreenId = savedInstanceState?.getInt("current_screen_id", SCREEN_DASHBOARD) ?: SCREEN_DASHBOARD

        if (savedUrls != null && savedUrls.isNotEmpty()) {
            for (url in savedUrls) {
                createNewTab(url)
            }
            if (savedActiveIndex in tabs.indices) {
                switchTab(savedActiveIndex)
            }
            if (savedIsDashboard) showDashboard() else showBrowser()
            showScreen(savedScreenId)
        } else {
            val intentUri = intent?.dataString
            createNewTab(intentUri)
            if (!intentUri.isNullOrEmpty()) showBrowser() else showDashboard()
        }

        // Reg filters
        registerReceiver(wifiReceiver, IntentFilter(WifiManager.RSSI_CHANGED_ACTION))
        registerReceiver(timeTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val urls = ArrayList(tabs.map { it.url })
        outState.putStringArrayList("tab_urls", urls)
        outState.putInt("active_tab_index", activeTabIndex)
        outState.putBoolean("is_dashboard_active", isDashboardActive)
        outState.putInt("current_screen_id", currentScreenId)
    }

    override fun onResume() {
        super.onResume()
        if (::wifiIconView.isInitialized) {
            updateWifiIcon()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiReceiver)
        unregisterReceiver(timeTickReceiver)
        for (tab in tabs) tab.webView.destroy()
        tabs.clear()
        stopNativePlayer()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildHomeDashboard
    // ─────────────────────────────────────────────────────────────────────────
    private fun buildHomeDashboard() {
        val dp = resources.displayMetrics.density

        homeDashboardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(resolveColor(BG_BASE))
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
        }

        // Left Nav Rail (Floating Sidebar)
        val railWidth = (130 * dp).toInt()
        val leftNavRail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(railWidth, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins((16 * dp).toInt(), (16 * dp).toInt(), 0, (16 * dp).toInt())
            }
            background = GradientDrawable().apply {
                setColor(resolveColor(BG_RAIL))
                cornerRadius = 16 * dp
                setStroke((1 * dp).toInt(), resolveColor("#2A2A3E"))
            }
            applyPremiumShadow(dp, 8f, false)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt())
            clipChildren = false
            clipToPadding = false
        }

        val btnHome      = makeRailButton(R.drawable.ic_home, "Home", isActive = true)
        val btnSearch    = makeRailButton(R.drawable.ic_search, "Search")
        val btnTabs      = makeRailButton(R.drawable.ic_tabs, "Tabs")
        val btnBookmarks = makeRailButton(R.drawable.ic_star_filled, "Bookmarks")
        val btnDownloads = makeRailButton(R.drawable.ic_download, "Downloads")
        val btnMedia     = makeRailButton(R.drawable.ic_clapperboard, "Media")
        val btnAdBlock   = makeRailButton(R.drawable.ic_shield, "AdBlock")
        val btnSettings  = makeRailButton(R.drawable.ic_settings, "Settings")

        val tabsBadgeHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }
        railTabsBadge = TextView(this).apply {
            text = tabs.size.toString()
            textSize = 9f
            setTextColor(resolveColor(BG_BASE))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            val sz = (17 * dp).toInt()
            layoutParams = FrameLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, (4 * dp).toInt(), (20 * dp).toInt(), 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(resolveColor(ACCENT_CYAN))
            }
        }
        railTabsLabel = railTabsBadge
        tabsBadgeHost.addView(btnTabs)
        tabsBadgeHost.addView(railTabsBadge)

        leftNavRail.addView(btnHome)
        leftNavRail.addView(btnSearch)
        leftNavRail.addView(tabsBadgeHost)
        leftNavRail.addView(btnBookmarks)
        leftNavRail.addView(btnDownloads)
        leftNavRail.addView(btnMedia)
        leftNavRail.addView(btnAdBlock)
        leftNavRail.addView(btnSettings)

        // Sidebar Navigation click routing
        btnHome.setOnClickListener {
            showScreen(SCREEN_DASHBOARD)
            dashboardScrollView.smoothScrollTo(0, 0)
        }
        btnSearch.setOnClickListener {
            showScreen(SCREEN_DASHBOARD)
            omniInput.requestFocus()
        }
        btnTabs.setOnClickListener {
            showScreen(SCREEN_DASHBOARD)
            dashboardScrollView.post {
                if (::tabsRowContainer.isInitialized) {
                    dashboardScrollView.smoothScrollTo(0, tabsRowContainer.top)
                    if (tabsRowContainer.childCount > 0) {
                        tabsRowContainer.getChildAt(0).requestFocus()
                    }
                }
            }
        }
        btnBookmarks.setOnClickListener {
            showScreen(SCREEN_DASHBOARD)
            dashboardScrollView.post { dashboardScrollView.smoothScrollTo(0, bookmarkRowContainer.top) }
        }
        btnDownloads.setOnClickListener { showDownloadsDialog() }
        btnMedia.setOnClickListener {
            showScreen(SCREEN_MEDIA)
        }
        btnAdBlock.setOnClickListener { showAdBlockerDashboard() }
        btnSettings.setOnClickListener { showSettingsDialog() }

        // Sidebar D-pad left/right listeners
        listOf(btnHome, btnSearch, btnTabs, btnBookmarks, btnDownloads, btnMedia, btnAdBlock, btnSettings).forEach { btn ->
            btn.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    if (currentScreenId == SCREEN_DASHBOARD) {
                        omniInput.requestFocus()
                    } else {
                        mediaScreenScrollView.requestFocus()
                    }
                    true
                } else false
            }
        }

        // Main content area
        val mainContentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            clipChildren = false
            clipToPadding = false
        }

        // Fixed top section (only on Dashboard)
        fixedTopSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), 0)
            setBackgroundColor(resolveColor(BG_BASE))
            applyPremiumShadow(dp, 4f, false)
            clipChildren = false
            clipToPadding = false
        }

        // 1. Top status header
        val topHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * dp).toInt()
            ).apply { bottomMargin = (14 * dp).toInt() }
            clipChildren = false
            clipToPadding = false
        }

        val logoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            clipChildren = false
            clipToPadding = false
        }
        logoLayout.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_globe)
            setColorFilter(Color.WHITE)
            val sz = (34 * dp).toInt()
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                rightMargin = (10 * dp).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(resolveColor(ACCENT_GLOW))
            }
        })
        logoLayout.addView(TextView(this).apply {
            val appVer = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
            } catch (e: Exception) {
                "1.0"
            }
            val titleBuilder = android.text.SpannableStringBuilder("TV Browser $appVer").apply {
                val start = "TV Browser".length
                setSpan(android.text.style.SuperscriptSpan(), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.RelativeSizeSpan(0.6f), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(resolveColor(ACCENT_CYAN)), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = titleBuilder
            textSize = 20f
            setTextColor(resolveColor(TEXT_PRIMARY))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        })
        topHeaderRow.addView(logoLayout)
        topHeaderRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })

        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            clipChildren = false
            clipToPadding = false
        }

        // About Info Button
        val aboutBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_info)
            setColorFilter(resolveColor(TEXT_PRIMARY))
            isFocusable = true
            isFocusableInTouchMode = true
            val btnSz = (42 * dp).toInt()
            setPadding((11 * dp).toInt(), (11 * dp).toInt(), (11 * dp).toInt(), (11 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
                rightMargin = (14 * dp).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(resolveColor(BG_SURFACE))
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1.0f).scaleY(if (hasFocus) 1.05f else 1.0f).setDuration(120).start()
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else BG_SURFACE))
                    if (hasFocus) setStroke((2 * dp).toInt(), resolveColor(ACCENT_GLOW))
                }
            }
            setOnClickListener {
                lastFocusedViewBeforeOverlay = this
                showAboutDialog()
            }
        }
        statusPanel.addView(aboutBtn)

        // Quick Theme Toggle Button
        val themeBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_theme)
            setColorFilter(resolveColor(TEXT_PRIMARY))
            isFocusable = true
            isFocusableInTouchMode = true
            val btnSz = (42 * dp).toInt()
            setPadding((11 * dp).toInt(), (11 * dp).toInt(), (11 * dp).toInt(), (11 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
                rightMargin = (14 * dp).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(resolveColor(BG_SURFACE))
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1.0f).scaleY(if (hasFocus) 1.05f else 1.0f).setDuration(120).start()
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else BG_SURFACE))
                    if (hasFocus) setStroke((2 * dp).toInt(), resolveColor(ACCENT_GLOW))
                }
            }
            setOnClickListener {
                val nextMode = when(prefs.getString("theme_mode", "auto")) {
                    "auto" -> "dark"
                    "dark" -> "light"
                    else -> "auto"
                }
                prefs.edit().putString("theme_mode", nextMode).apply()
                updateThemeState()
                recreate()
            }
        }
        statusPanel.addView(themeBtn)

        // WiFi Icon view
        wifiIconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply {
                rightMargin = (14 * dp).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_wifi_4)
            setColorFilter(resolveColor(TEXT_PRIMARY))
        }
        statusPanel.addView(wifiIconView)

        timeLabel = TextView(this).apply {
            text = getFormattedTime()
            textSize = 17f
            setTextColor(resolveColor(TEXT_PRIMARY))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 0, (16 * dp).toInt(), 0)
        }
        statusPanel.addView(timeLabel)

        val menuBtn = TextView(this).apply {
            text = "≡"
            textSize = 20f
            setTextColor(resolveColor(TEXT_PRIMARY))
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            val btnSz = (42 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(resolveColor(BG_SURFACE))
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1.0f).scaleY(if (hasFocus) 1.05f else 1.0f).setDuration(120).start()
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else BG_SURFACE))
                    if (hasFocus) setStroke((2 * dp).toInt(), resolveColor(ACCENT_GLOW))
                }
            }
            setOnClickListener { showSettingsDialog() }
        }
        statusPanel.addView(menuBtn)
        topHeaderRow.addView(statusPanel)
        fixedTopSection.addView(topHeaderRow)

        // 2. Search omnibox
        val omniboxRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (58 * dp).toInt()
            ).apply { bottomMargin = (16 * dp).toInt() }
            clipChildren = false
            clipToPadding = false
        }
        val omniboxContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            background = makePillBg(dp, false)
        }
        val searchIconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(resolveColor(TEXT_MUTED))
            val sz = (20 * dp).toInt()
            layoutParams = FrameLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                leftMargin = (20 * dp).toInt()
            }
        }
        omniInput = EditText(this).apply {
            hint = "Search the web or enter URL"
            setHintTextColor(resolveColor(TEXT_MUTED))
            setTextColor(resolveColor(TEXT_PRIMARY))
            textSize = 16f
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = false
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            background = null
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = (54 * dp).toInt()
                rightMargin = (16 * dp).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
            setOnFocusChangeListener { _, hasFocus ->
                isCursorVisible = hasFocus
                omniboxContainer.background = makePillBg(dp, hasFocus)
            }
            setOnEditorActionListener { _, actionId, event ->
                val isEnter = event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_UNSPECIFIED || isEnter
                ) {
                    if (event == null || event.action == KeyEvent.ACTION_DOWN) navigateFromOmnibox()
                    true
                } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    btnHome.requestFocus(); true
                } else false
            }
        }
        omniboxContainer.addView(searchIconView)
        omniboxContainer.addView(omniInput)
        omniboxRow.addView(omniboxContainer)

        val goArrowBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_forward)
            setColorFilter(resolveColor(TEXT_PRIMARY))
            isFocusable = true
            isFocusableInTouchMode = true
            val size = (44 * dp).toInt()
            setPadding((11 * dp).toInt(), (11 * dp).toInt(), (11 * dp).toInt(), (11 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = (12 * dp).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(resolveColor(BG_SURFACE))
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1.0f).scaleY(if (hasFocus) 1.05f else 1.0f).setDuration(120).start()
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else BG_SURFACE))
                    if (hasFocus) setStroke((2 * dp).toInt(), resolveColor(ACCENT_GLOW))
                }
            }
            setOnClickListener { navigateFromOmnibox() }
        }
        omniboxRow.addView(goArrowBtn)
        fixedTopSection.addView(omniboxRow)

        // 3. Quick Action Cards (4-up row)
        val actionCardsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (20 * dp).toInt() }
            clipChildren = false
            clipToPadding = false
        }
        // Voice Search uses ACCENT_GLOW (#7C6FF0); Scan QR swaps to ic_qr_code and #FF9800
        val ac1 = makeActionCard(R.drawable.ic_search, "Search the Web", "Find anything online", "#2196F3") { omniInput.requestFocus() }
        val ac2 = makeActionCard(R.drawable.ic_link, "Enter URL", "Go to any website", "#4CAF50") {
            omniInput.requestFocus(); omniInput.setText("https://"); omniInput.setSelection(omniInput.text.length)
        }
        val ac3 = makeActionCard(R.drawable.ic_mic, "Voice Search", "Search with your voice", ACCENT_GLOW) { startVoiceSearch() }
        val ac4 = makeActionCard(R.drawable.ic_qr_code, "Scan QR Code", "From your mobile", "#FF9800") { showQRCodeDialog() }
        actionCardsRow.addView(ac1)
        actionCardsRow.addView(ac2)
        actionCardsRow.addView(ac3)
        actionCardsRow.addView(ac4)
        fixedTopSection.addView(actionCardsRow)
        mainContentArea.addView(fixedTopSection)

        // Dashboard ScrollView
        dashboardScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), (24 * dp).toInt())
        }
        val shelfContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }

        // Shelf: Open Tabs
        shelfContainer.addView(makeShelfLabel("Open Tabs"))
        val rowTabs = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (24 * dp).toInt() }
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        tabsRowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            clipChildren = false
            clipToPadding = false
        }
        rowTabs.addView(tabsRowContainer)
        shelfContainer.addView(rowTabs)

        // Shelf A: Continue Browsing
        shelfContainer.addView(makeShelfLabel("Continue Browsing"))
        val rowContinue = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (24 * dp).toInt() }
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        continueRowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            clipChildren = false
            clipToPadding = false
        }
        rowContinue.addView(continueRowContainer)
        shelfContainer.addView(rowContinue)

        // Shelf B: Bookmarks
        shelfContainer.addView(makeShelfLabel("Bookmarks"))
        val rowBookmarks = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (24 * dp).toInt() }
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        bookmarkRowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            clipChildren = false
            clipToPadding = false
        }
        rowBookmarks.addView(bookmarkRowContainer)
        shelfContainer.addView(rowBookmarks)

        // Shelf C: Recent Media Banner Container
        shelfContainer.addView(makeShelfLabel("Recent Media"))
        recentMediaBannerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }
        shelfContainer.addView(recentMediaBannerContainer)

        dashboardScrollView.addView(shelfContainer)
        mainContentArea.addView(dashboardScrollView)

        // ─────────────────────────────────────────────────────────────────────
        // Media ScrollView (Dedicated Screen)
        // ─────────────────────────────────────────────────────────────────────
        mediaScreenScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            clipChildren = false
            clipToPadding = false
        }
        val mediaScreenContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }

        // Media Header
        val mediaHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()).apply {
                bottomMargin = (20 * dp).toInt()
            }
        }
        mediaHeader.addView(TextView(this).apply {
            text = "▶ Media Library"
            textSize = 22f
            setTextColor(resolveColor(TEXT_PRIMARY))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        mediaHeader.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
        mediaHeader.addView(makeModernButton("Back to Home").apply {
            setOnClickListener { showScreen(SCREEN_DASHBOARD) }
        })
        mediaScreenContainer.addView(mediaHeader)

        // Pinned Video Sites row
        mediaScreenContainer.addView(makeShelfLabel("Pinned Video Sites"))
        val rowPinnedMedia = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (24 * dp).toInt() }
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        pinnedMediaRowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            clipChildren = false
            clipToPadding = false
        }
        rowPinnedMedia.addView(pinnedMediaRowContainer)
        mediaScreenContainer.addView(rowPinnedMedia)

        // Played Media History grid list
        mediaScreenContainer.addView(makeShelfLabel("Played Media History"))
        mediaHistoryGridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, (8 * dp).toInt(), 0, (32 * dp).toInt())
            clipChildren = false
            clipToPadding = false
        }
        mediaScreenContainer.addView(mediaHistoryGridContainer)

        mediaScreenScrollView.addView(mediaScreenContainer)
        mainContentArea.addView(mediaScreenScrollView)

        homeDashboardLayout.addView(leftNavRail)
        homeDashboardLayout.addView(mainContentArea)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildBrowserSession
    // ─────────────────────────────────────────────────────────────────────────
    private fun buildBrowserSession() {
        val dp = resources.displayMetrics.density

        browserSessionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(resolveColor(BG_BASE))
            visibility = View.GONE
        }

        // Slim collapsible top bar
        slimTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(resolveColor(BG_BASE))
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnHomeSlim = makeSlimButton("⌂")
        btnHomeSlim.setOnClickListener { showDashboard() }
        slimTopBar.addView(btnHomeSlim, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = (8 * dp).toInt() })

        urlBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f).apply {
                rightMargin = (8 * dp).toInt()
            }
            background = makePillBg(dp, false)
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = false
            clipToPadding = false
            setOnFocusChangeListener { _, hasFocus ->
                background = makePillBg(dp, hasFocus)
                if (hasFocus) {
                    isCursorActive = false
                    cursorView.visibility = View.GONE
                }
            }
            setOnClickListener {
                slimUrlBar.setText(urlClickTarget.text)
                urlClickTarget.visibility = View.GONE
                slimUrlBar.visibility = View.VISIBLE
                slimUrlBar.isFocusable = true
                slimUrlBar.isFocusableInTouchMode = true
                slimUrlBar.requestFocus()
                slimUrlBar.selectAll()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(slimUrlBar, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        val searchIconSlim = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(resolveColor(TEXT_MUTED))
            val pad = (8 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        urlBarContainer.addView(searchIconSlim)

        slimLoadingSpinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply {
                rightMargin = (8 * dp).toInt()
            }
        }
        urlBarContainer.addView(slimLoadingSpinner)

        val textWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        urlClickTarget = TextView(this).apply {
            hint = "Search or enter address…"
            setHintTextColor(resolveColor(TEXT_MUTED))
            setTextColor(resolveColor(TEXT_PRIMARY))
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, (8 * dp).toInt(), 0)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        slimUrlBar = EditText(this).apply {
            hint = "Search or enter address…"
            setHintTextColor(resolveColor(TEXT_MUTED))
            setTextColor(resolveColor(TEXT_PRIMARY))
            textSize = 15f
            isFocusable = false
            isFocusableInTouchMode = false
            isCursorVisible = false
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            background = null
            setPadding(0, 0, (8 * dp).toInt(), 0)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setOnFocusChangeListener { _, hasFocus ->
                isCursorVisible = hasFocus
                if (!hasFocus) {
                    visibility = View.GONE
                    urlClickTarget.visibility = View.VISIBLE
                    isFocusable = false
                    isFocusableInTouchMode = false
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(windowToken, 0)
                }
            }
            setOnEditorActionListener { _, actionId, event ->
                val isEnter = event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_UNSPECIFIED || isEnter
                ) {
                    if (event == null || event.action == KeyEvent.ACTION_DOWN) navigateFromSlimBar()
                    true
                } else false
            }
        }

        textWrapper.addView(urlClickTarget)
        textWrapper.addView(slimUrlBar)
        urlBarContainer.addView(textWrapper)

        slimRefreshStopBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(resolveColor(TEXT_PRIMARY))
            isFocusable = true
            val btnSz = (36 * dp).toInt()
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 8 * dp
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = GradientDrawable().apply {
                    setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else "#00000000"))
                    cornerRadius = 8 * dp
                    if (hasFocus) setStroke((1 * dp).toInt(), resolveColor(ACCENT_GLOW))
                }
            }
            setTag("refresh")
            setOnClickListener {
                if (getTag() == "refresh") activeWebView?.reload() else activeWebView?.stopLoading()
            }
        }
        urlBarContainer.addView(slimRefreshStopBtn)
        slimTopBar.addView(urlBarContainer)

        // Ad Blocker Indicator & Dashboard trigger button
        val app = application as BrowserApp
        slimAdBlockBtn = TextView(this).apply {
            text = "0"
            textSize = 12f
            setTextColor(resolveColor(ACCENT_CYAN))
            gravity = Gravity.CENTER
            isFocusable = true
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            val shieldDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_shield)?.apply {
                val iconSize = (16 * dp).toInt()
                setBounds(0, 0, iconSize, iconSize)
                setColorFilter(resolveColor(ACCENT_CYAN), android.graphics.PorterDuff.Mode.SRC_IN)
            }
            setCompoundDrawables(shieldDrawable, null, null, null)
            compoundDrawablePadding = (4 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                rightMargin = (8 * dp).toInt()
            }
            background = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE))
                cornerRadius = 8 * dp
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = GradientDrawable().apply {
                    setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else BG_SURFACE))
                    cornerRadius = 8 * dp
                    if (hasFocus) setStroke((1 * dp).toInt(), resolveColor(ACCENT_GLOW))
                }
                val shieldColor = resolveColor(if (hasFocus) TEXT_PRIMARY else ACCENT_CYAN)
                val drawables = compoundDrawables
                drawables[0]?.setColorFilter(shieldColor, android.graphics.PorterDuff.Mode.SRC_IN)
                setTextColor(shieldColor)
            }
            setOnClickListener {
                showAdBlockerDashboard()
            }
        }
        slimTopBar.addView(slimAdBlockBtn)

        // "Open in Player" dynamic button
        btnOpenPlayer = TextView(this).apply {
            text = "▶ Open in Player"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = true
            visibility = View.GONE
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                rightMargin = (8 * dp).toInt()
            }
            background = GradientDrawable().apply {
                setColor(resolveColor(ACCENT_GLOW))
                cornerRadius = 8 * dp
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = GradientDrawable().apply {
                    setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else ACCENT_GLOW))
                    cornerRadius = 8 * dp
                    if (hasFocus) setStroke((1 * dp).toInt(), resolveColor(ACCENT_GLOW))
                }
            }
            setOnClickListener {
                detectedMediaUrl?.let { url ->
                    playMediaInNativePlayer(url, detectedMediaTitle ?: "Video Stream")
                }
            }
        }
        slimTopBar.addView(btnOpenPlayer)

        // "Background Media" restore indicator
        slimBgMediaIndicator = TextView(this).apply {
            text = "🎵 Background active"
            textSize = 12f
            setTextColor(Color.GREEN)
            gravity = Gravity.CENTER
            isFocusable = true
            visibility = View.GONE
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                rightMargin = (8 * dp).toInt()
            }
            background = GradientDrawable().apply {
                setColor(resolveColor("#1B2A1B"))
                cornerRadius = 8 * dp
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = GradientDrawable().apply {
                    setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else "#1B2A1B"))
                    cornerRadius = 8 * dp
                    if (hasFocus) setStroke((1 * dp).toInt(), resolveColor(ACCENT_GLOW))
                }
            }
            setOnClickListener {
                if (backgroundMediaUrl != null && backgroundMediaTitle != null) {
                    playMediaInNativePlayer(backgroundMediaUrl!!, backgroundMediaTitle!!)
                }
            }
        }
        slimTopBar.addView(slimBgMediaIndicator)

        slimTabsButton = makeSlimButton("⧉ TABS")
        slimTabsButton.setOnClickListener {
            showDashboard()
            dashboardScrollView.post {
                if (::tabsRowContainer.isInitialized) {
                    dashboardScrollView.smoothScrollTo(0, tabsRowContainer.top)
                    if (tabsRowContainer.childCount > 0) {
                        tabsRowContainer.getChildAt(0).requestFocus()
                    }
                }
            }
        }
        slimTopBar.addView(slimTabsButton)

        browserSessionLayout.addView(slimTopBar)

        webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        browserSessionLayout.addView(webViewContainer)

        // Virtual Cursor overlay (Apple Siri remote-like translucent dot)
        cursorView = ImageView(this).apply {
            val cursorSize = (24 * dp).toInt()
            layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(resolveColor(ACCENT_GLOW))
                setStroke((2 * dp).toInt(), android.graphics.Color.WHITE)
            }
            elevation = 8 * dp
            visibility = View.GONE
        }
        webViewContainer.addView(cursorView)

        // Periodic thread to poll ad blocking counters and update badge
        Thread {
            while (!isDestroyed) {
                runOnUiThread {
                    if (::slimAdBlockBtn.isInitialized) {
                        slimAdBlockBtn.text = "🛡 ${app.adBlockEngine.pageBlockCount.get()}"
                    }
                }
                Thread.sleep(1500)
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screen / State Management
    // ─────────────────────────────────────────────────────────────────────────
    private fun showDashboard() {
        isDashboardActive = true
        isCursorActive = false
        cursorView.visibility = View.GONE
        homeDashboardLayout.visibility = View.VISIBLE
        browserSessionLayout.visibility = View.GONE
        showScreen(SCREEN_DASHBOARD)
        omniInput.requestFocus()
    }

    private var wasCursorActiveBeforeOverlay = false

    private fun updateBackgroundFocusBlock() {
        if (!::homeDashboardLayout.isInitialized || !::browserSessionLayout.isInitialized) return
        val hasOverlay = rootContainer.childCount > 2
        if (hasOverlay) {
            if (rootContainer.childCount == 3) {
                wasCursorActiveBeforeOverlay = isCursorActive
                isCursorActive = false
                cursorView.visibility = View.GONE
            }
            homeDashboardLayout.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            browserSessionLayout.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            homeDashboardLayout.isFocusable = false
            browserSessionLayout.isFocusable = false
        } else {
            if (wasCursorActiveBeforeOverlay) {
                isCursorActive = true
                cursorView.visibility = View.VISIBLE
                wasCursorActiveBeforeOverlay = false
            }
            homeDashboardLayout.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            browserSessionLayout.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            homeDashboardLayout.isFocusable = false
            browserSessionLayout.isFocusable = false
        }
    }

    private fun showBrowser() {
        isDashboardActive = false
        homeDashboardLayout.visibility = View.GONE
        browserSessionLayout.visibility = View.VISIBLE
        activeWebView?.requestFocus()
        isCursorActive = true
        if (cursorX == 0f && cursorY == 0f) {
            webViewContainer.post {
                cursorX = webViewContainer.width / 2f
                cursorY = webViewContainer.height / 2f
                cursorView.translationX = cursorX
                cursorView.translationY = cursorY
            }
        }
        cursorView.visibility = View.VISIBLE
    }

    private fun showScreen(screenId: Int) {
        currentScreenId = screenId
        val dp = resources.displayMetrics.density
        if (screenId == SCREEN_DASHBOARD) {
            fixedTopSection.visibility = View.VISIBLE
            dashboardScrollView.visibility = View.VISIBLE
            mediaScreenScrollView.visibility = View.GONE
            refreshDashboard()
        } else if (screenId == SCREEN_MEDIA) {
            fixedTopSection.visibility = View.GONE
            dashboardScrollView.visibility = View.GONE
            mediaScreenScrollView.visibility = View.VISIBLE
            refreshPinnedMediaSites()
            refreshMediaHistoryList()
        }
    }

    private fun refreshDashboard() {
        refreshTabsShelf()

        // Combined tabs + history for Continue Browsing shelf
        val combined = mutableListOf<HistoryEntry>()
        tabs.forEach { tab ->
            if (tab.url.isNotBlank() && !tab.url.startsWith("about:") && !tab.url.startsWith("file:")) {
                combined.add(HistoryEntry(tab.title, tab.url, "Open now"))
            }
        }
        historyEntries.reversed().forEach { entry ->
            if (combined.none { it.url == entry.url }) {
                combined.add(entry)
            }
        }

        continueRowContainer.removeAllViews()
        combined.take(10).forEach { entry ->
            val card = makeSiteCard(
                title = entry.title,
                domain = Uri.parse(entry.url).host ?: entry.url,
                url = entry.url,
                timestamp = entry.relativeTimestamp,
                isStarred = null,
                isHistoryVariant = false,
                imageUrl = null
            ) {
                createNewTab(entry.url)
                showBrowser()
            }
            continueRowContainer.addView(card)
        }

        refreshBookmarkShelf()
        refreshRecentMediaBanner()
        updateTabsBadge()
    }

    private fun refreshBookmarkShelf() {
        bookmarkRowContainer.removeAllViews()
        bookmarks.forEach { bm ->
            val card = makeSiteCard(
                title = bm.title,
                domain = Uri.parse(bm.url).host ?: bm.url,
                url = bm.url,
                isStarred = bm.isPinned,
                isHistoryVariant = false,
                imageUrl = null,
                onStarToggle = {
                    bm.isPinned = !bm.isPinned
                    saveBookmarks()
                    refreshBookmarkShelf()
                }
            ) {
                createNewTab(bm.url)
                showBrowser()
            }
            bookmarkRowContainer.addView(card)
        }
        // dashed add bookmark button
        bookmarkRowContainer.addView(makeAddCard {
            showAddBookmarkPrompt()
        })
    }

    private fun refreshRecentMediaBanner() {
        recentMediaBannerContainer.removeAllViews()
        val dp = resources.displayMetrics.density

        if (mediaHistoryList.isEmpty()) {
            val emptyCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (72 * dp).toInt()
                ).apply { bottomMargin = (32 * dp).toInt() }
                background = GradientDrawable().apply {
                    setColor(resolveColor(BG_SURFACE))
                    cornerRadius = 14 * dp
                    setStroke((1 * dp).toInt(), resolveColor("#1A1A30"))
                }
                setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())

                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_clapperboard)
                    setColorFilter(resolveColor(TEXT_MUTED))
                    layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { rightMargin = (16 * dp).toInt() }
                })

                val textCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(TextView(this@MainActivity).apply {
                    text = "Your media history will appear here"
                    textSize = 13f; setTextColor(resolveColor(TEXT_PRIMARY))
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                })
                textCol.addView(TextView(this@MainActivity).apply {
                    text = "Videos you watch will be easy to find here"
                    textSize = 10f; setTextColor(resolveColor(TEXT_MUTED))
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
                addView(textCol)

                addView(TextView(this@MainActivity).apply {
                    text = "→"; textSize = 16f
                    setTextColor(resolveColor(TEXT_MUTED)); gravity = Gravity.CENTER
                })

                val normalBg = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 14 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
                val focusedBg = GradientDrawable().apply {
                    setColor(resolveColor(BG_SURFACE_FOCUS)); cornerRadius = 14 * dp
                    setStroke((3 * dp).toInt(), resolveColor(ACCENT_GLOW))
                }
                applyPremiumShadow(dp, 2f, false)
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f).setDuration(120).start()
                    v.applyPremiumShadow(dp, if (hasFocus) 12f else 2f, hasFocus)
                    v.translationZ = if (hasFocus) 6 * dp else 0f
                    background = if (hasFocus) focusedBg else normalBg
                }
                setOnClickListener { showScreen(SCREEN_MEDIA) }
            }
            recentMediaBannerContainer.addView(emptyCard)
        } else {
            val latest = mediaHistoryList.last()
            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (72 * dp).toInt()
                ).apply { bottomMargin = (32 * dp).toInt() }
                background = GradientDrawable().apply {
                    setColor(resolveColor(BG_SURFACE))
                    cornerRadius = 14 * dp
                    setStroke((1 * dp).toInt(), resolveColor("#1A1A30"))
                }
                setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())

                val thumbView = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { rightMargin = (16 * dp).toInt() }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (latest.imageUrl != null && latest.imageUrl.startsWith("http")) {
                        loadWebImage(latest.imageUrl, this)
                    } else {
                        setImageResource(if (latest.type == "audio") R.drawable.ic_music else R.drawable.ic_clapperboard)
                        setColorFilter(resolveColor(TEXT_MUTED))
                    }
                }
                addView(thumbView)

                val textCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(TextView(this@MainActivity).apply {
                    text = "Recently Played: ${latest.title}"
                    textSize = 13f; setTextColor(resolveColor(TEXT_PRIMARY))
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                textCol.addView(TextView(this@MainActivity).apply {
                    text = "${latest.domain} • ${latest.relativeTimestamp}"
                    textSize = 10f; setTextColor(resolveColor(TEXT_MUTED))
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
                addView(textCol)

                addView(TextView(this@MainActivity).apply {
                    text = "View Media Library →"; textSize = 12f
                    setTextColor(resolveColor(ACCENT_GLOW)); gravity = Gravity.CENTER
                })

                val normalBg = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 14 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
                val focusedBg = GradientDrawable().apply {
                    setColor(resolveColor(BG_SURFACE_FOCUS)); cornerRadius = 14 * dp
                    setStroke((3 * dp).toInt(), resolveColor(ACCENT_GLOW))
                }
                applyPremiumShadow(dp, 2f, false)
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f).setDuration(120).start()
                    v.applyPremiumShadow(dp, if (hasFocus) 12f else 2f, hasFocus)
                    v.translationZ = if (hasFocus) 6 * dp else 0f
                    background = if (hasFocus) focusedBg else normalBg
                }
                setOnClickListener { showScreen(SCREEN_MEDIA) }
            }
            recentMediaBannerContainer.addView(banner)
        }
    }

    private fun refreshPinnedMediaSites() {
        pinnedMediaRowContainer.removeAllViews()
        pinnedMediaSites.forEach { site ->
            val card = makeSiteCard(
                title = site.name,
                domain = Uri.parse(site.url).host ?: site.url,
                url = site.url,
                isStarred = null,
                isHistoryVariant = false,
                imageUrl = null
            ) {
                createNewTab(site.url)
                showBrowser()
            }
            // Long click to remove site from pinned row
            card.setOnLongClickListener {
                pinnedMediaSites.remove(site)
                savePinnedMediaSites()
                refreshPinnedMediaSites()
                Toast.makeText(this, "Pinned site removed", Toast.LENGTH_SHORT).show()
                true
            }
            pinnedMediaRowContainer.addView(card)
        }
        pinnedMediaRowContainer.addView(makeAddCard {
            showAddPinnedMediaSitePrompt()
        })
    }

    private fun refreshMediaHistoryList() {
        mediaHistoryGridContainer.removeAllViews()
        val dp = resources.displayMetrics.density
        var currentRow: LinearLayout? = null
        val cardsPerRow = 4
        
        mediaHistoryList.reversed().forEachIndexed { index, entry ->
            if (index % cardsPerRow == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (12 * dp).toInt() }
                }
                mediaHistoryGridContainer.addView(currentRow)
            }
            val card = makeSiteCard(
                title = entry.title,
                domain = entry.domain,
                url = entry.url,
                timestamp = entry.relativeTimestamp,
                isStarred = null,
                isHistoryVariant = true,
                imageUrl = entry.imageUrl
            ) {
                createNewTab(entry.url)
                showBrowser()
            }
            currentRow?.addView(card)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unified Card Creators
    // ─────────────────────────────────────────────────────────────────────────
    private fun makeSiteCard(
        title: String,
        domain: String,
        url: String,
        timestamp: String? = null,
        isStarred: Boolean? = null,
        isHistoryVariant: Boolean = false,
        imageUrl: String? = null,
        onStarToggle: (() -> Unit)? = null,
        onClick: () -> Unit
    ): LinearLayout {
        val dp = resources.displayMetrics.density
        val accentPalette = listOf("#F44336", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5", "#2196F3", "#00BCD4", "#4CAF50", "#FFC107")

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            val w = if (isHistoryVariant) (160 * dp).toInt() else (148 * dp).toInt()
            val h = if (isHistoryVariant) (180 * dp).toInt() else (140 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                rightMargin = (12 * dp).toInt()
            }
            background = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE))
                cornerRadius = 14 * dp
            }
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())

            // 1. Icon/Poster Container
            val mediaContainer = FrameLayout(this@MainActivity).apply {
                val hContainer = if (isHistoryVariant) (90 * dp).toInt() else (56 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hContainer).apply {
                    bottomMargin = (8 * dp).toInt()
                }
            }

            val imageView = ImageView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = if (isHistoryVariant) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            }

            if (imageUrl != null && imageUrl.startsWith("http")) {
                loadWebImage(imageUrl, imageView)
            } else {
                val vectorRes = getSiteIcon(url)
                if (vectorRes != 0) {
                    imageView.setImageResource(vectorRes)
                } else {
                    loadFavicon(url, imageView)
                }
            }
            mediaContainer.addView(imageView)

            if (isStarred != null) {
                val starBtn = ImageView(this@MainActivity).apply {
                    setImageResource(if (isStarred) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
                    setColorFilter(resolveColor(if (isStarred) "#FF9800" else "#7E7E8C"))
                    val sz = (24 * dp).toInt()
                    setPadding((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt())
                    layoutParams = FrameLayout.LayoutParams(sz, sz).apply {
                        gravity = Gravity.TOP or Gravity.START
                    }
                    setOnClickListener {
                        onStarToggle?.invoke()
                    }
                }
                mediaContainer.addView(starBtn)
            }
            addView(mediaContainer)

            // 2. Title
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 12f
                setTextColor(resolveColor(TEXT_PRIMARY))
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            // 3. Subtitle / Footer Row
            val footerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * dp).toInt() }
            }

            footerRow.addView(TextView(this@MainActivity).apply {
                text = domain
                textSize = 9f
                setTextColor(resolveColor(TEXT_MUTED))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            if (timestamp != null) {
                footerRow.addView(TextView(this@MainActivity).apply {
                    text = timestamp
                    textSize = 8f
                    setTextColor(resolveColor(TEXT_MUTED))
                    maxLines = 1
                })
            }
            addView(footerRow)

            val normalBg = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE))
                cornerRadius = 14 * dp
                setStroke((1 * dp).toInt(), resolveColor("#2A2A3E"))
            }
            val focusedBg = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE_FOCUS))
                cornerRadius = 14 * dp
                setStroke((3 * dp).toInt(), resolveColor(ACCENT_GLOW))
            }
            background = normalBg
            applyPremiumShadow(dp, 2f, false)

            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f).setDuration(120).start()
                v.applyPremiumShadow(dp, if (hasFocus) 12f else 2f, hasFocus)
                v.translationZ = if (hasFocus) 6 * dp else 0f
                background = if (hasFocus) focusedBg else normalBg
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeAddCard(onAddClick: () -> Unit): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams((148 * dp).toInt(), (140 * dp).toInt()).apply {
                rightMargin = (12 * dp).toInt()
            }
            val normalBg = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 14 * dp
                setStroke((1.5 * dp).toInt(), resolveColor("#3E3E5C"), 10f, 10f)
            }
            val focusedBg = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE_FOCUS))
                cornerRadius = 14 * dp
                setStroke((3 * dp).toInt(), resolveColor(ACCENT_GLOW))
            }
            background = normalBg

            addView(TextView(this@MainActivity).apply {
                text = "+"
                textSize = 28f
                setTextColor(resolveColor(TEXT_MUTED))
                gravity = Gravity.CENTER
            })

            addView(TextView(this@MainActivity).apply {
                text = "Add Item"
                textSize = 10f
                setTextColor(resolveColor(TEXT_MUTED))
                gravity = Gravity.CENTER
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })

            applyPremiumShadow(dp, 0f, false)

            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f).setDuration(120).start()
                v.applyPremiumShadow(dp, if (hasFocus) 12f else 0f, hasFocus)
                v.translationZ = if (hasFocus) 6 * dp else 0f
                background = if (hasFocus) focusedBg else normalBg
                (v as LinearLayout).let { ll ->
                    (ll.getChildAt(0) as TextView).setTextColor(resolveColor(if (hasFocus) TEXT_PRIMARY else TEXT_MUTED))
                    (ll.getChildAt(1) as TextView).setTextColor(resolveColor(if (hasFocus) TEXT_PRIMARY else TEXT_MUTED))
                }
            }
            setOnClickListener { onAddClick() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View Factory Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private fun makePillBg(dp: Float, focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(resolveColor(BG_SURFACE))
            cornerRadius = 28 * dp
            setStroke(
                (if (focused) 2 else 1) * dp.toInt(),
                resolveColor(if (focused) ACCENT_GLOW else "#2A2A3E")
            )
        }

    private fun makeRailButton(iconResId: Int, label: String, isActive: Boolean = false): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (56 * dp).toInt()
            ).apply { setMargins(0, (1 * dp).toInt(), 0, (1 * dp).toInt()) }

            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconResId)
                val iconSize = (20 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                setColorFilter(resolveColor(TEXT_MUTED))
            })
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 8.5f
                setTextColor(resolveColor(TEXT_MUTED))
                gravity = Gravity.CENTER
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })

            val normalBg = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 12 * dp
            }
            val focusedBg = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE_FOCUS))
                cornerRadius = 12 * dp
                setStroke((3 * dp).toInt(), resolveColor(ACCENT_GLOW))
            }
            background = normalBg

            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f).setDuration(120).start()
                v.translationZ = if (hasFocus) 6 * dp else 0f
                (v as? LinearLayout)?.let { ll ->
                    (ll.getChildAt(0) as? ImageView)?.setColorFilter(resolveColor(if (hasFocus) TEXT_PRIMARY else TEXT_MUTED))
                    (ll.getChildAt(1) as? TextView)?.setTextColor(resolveColor(if (hasFocus) TEXT_PRIMARY else TEXT_MUTED))
                }
                background = if (hasFocus) focusedBg else normalBg
            }
        }
    }

    private fun makeActionCard(
        icon: Any, title: String, subtitle: String,
        iconBgHex: String, onClick: () -> Unit
    ): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(0, (76 * dp).toInt(), 1f).apply {
                rightMargin = (8 * dp).toInt()
            }
            background = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE))
                cornerRadius = 14 * dp
            }
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())

            val iconSize = (42 * dp).toInt()
            val iconView = if (icon is Int) {
                ImageView(this@MainActivity).apply {
                    setImageResource(icon)
                    setColorFilter(Color.WHITE)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { rightMargin = (12 * dp).toInt() }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(resolveColor(iconBgHex))
                    }
                }
            } else {
                TextView(this@MainActivity).apply {
                    text = icon as String; textSize = 17f
                    setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { rightMargin = (12 * dp).toInt() }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(resolveColor(iconBgHex))
                    }
                }
            }
            addView(iconView)

            val textCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this@MainActivity).apply {
                text = title; textSize = 13f
                setTextColor(resolveColor(TEXT_PRIMARY))
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            })
            textCol.addView(TextView(this@MainActivity).apply {
                text = subtitle; textSize = 10f
                setTextColor(resolveColor(TEXT_MUTED))
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
            addView(textCol)

            val normalBg = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE))
                cornerRadius = 14 * dp
                setStroke((1 * dp).toInt(), resolveColor("#2A2A3E"))
            }
            val focusedBg = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE_FOCUS)); cornerRadius = 14 * dp
                setStroke((3 * dp).toInt(), resolveColor(ACCENT_GLOW))
            }
            background = normalBg
            applyPremiumShadow(dp, 2f, false)

            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f).setDuration(120).start()
                v.applyPremiumShadow(dp, if (hasFocus) 12f else 2f, hasFocus)
                v.translationZ = if (hasFocus) 6 * dp else 0f
                background = if (hasFocus) focusedBg else normalBg
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeSlimButton(label: String): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = label; textSize = 12f; setTextColor(resolveColor(TEXT_PRIMARY))
            gravity = Gravity.CENTER; isFocusable = true
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 10 * dp }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1.0f).scaleY(if (hasFocus) 1.05f else 1.0f).setDuration(120).start()
                v.background = GradientDrawable().apply {
                    setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else BG_SURFACE))
                    cornerRadius = 10 * dp
                    if (hasFocus) setStroke((3 * dp).toInt(), resolveColor(ACCENT_GLOW))
                }
                if (hasFocus) { isCursorActive = false; cursorView.visibility = View.GONE }
            }
        }
    }

    private fun makeModernButton(label: String): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = label; textSize = 14f; setTextColor(resolveColor(TEXT_PRIMARY))
            gravity = Gravity.CENTER; isFocusable = true; isFocusableInTouchMode = true
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 12 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f).setDuration(120).start()
                v.background = GradientDrawable().apply {
                    setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else BG_SURFACE))
                    cornerRadius = 12 * dp
                    setStroke(if (hasFocus) (3 * dp).toInt() else (1 * dp).toInt(), resolveColor(if (hasFocus) ACCENT_GLOW else "#2A2A3E"))
                }
            }
        }
    }

    private fun makeShelfLabel(text: String): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text; textSize = 15f
            setTextColor(resolveColor(TEXT_PRIMARY))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, (12 * dp).toInt(), 0, (8 * dp).toInt())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility functions for image fetching and time
    // ─────────────────────────────────────────────────────────────────────────
    private fun getFormattedTime(): String {
        val cal = Calendar.getInstance()
        val rawH = cal.get(Calendar.HOUR)
        val h = if (rawH == 0) 12 else rawH
        val m = String.format("%02d", cal.get(Calendar.MINUTE))
        val ampm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        return "$h:$m $ampm"
    }

    private fun updateWifiIcon() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cap = cm.getNetworkCapabilities(cm.activeNetwork)
        wifiIconView.setColorFilter(resolveColor(TEXT_PRIMARY))
        if (cap == null) {
            wifiIconView.setImageResource(R.drawable.ic_wifi_0)
            return
        }
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            wifiIconView.setImageResource(R.drawable.ic_wifi_4)
        } else if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            val level = WifiManager.calculateSignalLevel(info.rssi, 5)
            val resId = when (level) {
                1 -> R.drawable.ic_wifi_1
                2 -> R.drawable.ic_wifi_2
                3 -> R.drawable.ic_wifi_3
                4 -> R.drawable.ic_wifi_4
                else -> R.drawable.ic_wifi_0
            }
            wifiIconView.setImageResource(resId)
        } else {
            wifiIconView.setImageResource(R.drawable.ic_wifi_4)
        }
    }

    private fun loadWebImage(url: String, imageView: ImageView) {
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val bitmap = BitmapFactory.decodeStream(conn.inputStream)
                    runOnUiThread { imageView.setImageBitmap(bitmap) }
                }
            } catch (e: Exception) { Log.w("TVBrowser", "Image download failed: ${e.message}") }
        }.start()
    }

    private fun loadFavicon(url: String, imageView: ImageView, fallbackResId: Int = R.drawable.ic_globe) {
        val uri = Uri.parse(url)
        val host = uri.host ?: ""
        if (host.isEmpty()) {
            imageView.setImageResource(fallbackResId)
            return
        }
        val cached = faviconCache[host]
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        Thread {
            try {
                val conn = URL("https://www.google.com/s2/favicons?sz=64&domain=$host").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val bitmap = BitmapFactory.decodeStream(conn.inputStream)
                    if (bitmap != null) {
                        faviconCache[host] = bitmap
                        runOnUiThread { imageView.setImageBitmap(bitmap) }
                        return@Thread
                    }
                }
            } catch (e: Exception) {}
            runOnUiThread { imageView.setImageResource(fallbackResId) }
        }.start()
    }

    private fun getSiteIcon(url: String): Int {
        val lower = url.lowercase()
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> R.drawable.ic_youtube
            lower.contains("netflix.com") -> R.drawable.ic_netflix
            lower.contains("amazon.com") -> R.drawable.ic_amazon
            lower.contains("wikipedia.org") -> R.drawable.ic_wikipedia
            lower.contains("reddit.com") -> R.drawable.ic_reddit
            lower.contains("github.com") -> R.drawable.ic_github
            lower.contains("twitter.com") || lower.contains("t.co") || lower.contains("x.com") -> R.drawable.ic_twitter
            lower.contains("news.ycombinator.com") || lower.contains("hackernews") -> R.drawable.ic_hackernews
            lower.contains("bbc.com") || lower.contains("bbc.co.uk") -> R.drawable.ic_bbc
            lower.contains("vimeo.com") -> R.drawable.ic_vimeo
            lower.contains("dailymotion.com") -> R.drawable.ic_dailymotion
            lower.contains("peertube") || lower.contains("sepiia.org") -> R.drawable.ic_peertube
            lower.contains("duckduckgo.com") -> R.drawable.ic_duckduckgo
            else -> 0
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search Engine Settings & Navigation
    // ─────────────────────────────────────────────────────────────────────────
    private fun resolveUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.contains(" ") || (!trimmed.contains(".") && !trimmed.contains(":/"))) {
            val query = java.net.URLEncoder.encode(trimmed, "UTF-8")
            val engineName = prefs.getString("search_engine_name", "DuckDuckGo") ?: "DuckDuckGo"
            val template = if (engineName == "Custom") {
                prefs.getString("search_engine_custom_url", "https://duckduckgo.com/?q=%s") ?: "https://duckduckgo.com/?q=%s"
            } else {
                searchEngines[engineName] ?: "https://duckduckgo.com/?q=%s"
            }
            return if (template.contains("%s")) template.replace("%s", query) else "$template$query"
        }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private fun navigateFromOmnibox() {
        val url = resolveUrl(omniInput.text.toString())
        activeWebView?.loadUrl(url)
        showBrowser()
    }

    private fun navigateFromSlimBar() {
        val url = resolveUrl(slimUrlBar.text.toString())
        activeWebView?.loadUrl(url)
        slimUrlBar.clearFocus()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Native Pop-out Player (ExoPlayer VideoView implementation)
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleMediaDetected(src: String, title: String) {
        // Intercepted media source URL
        detectedMediaUrl = src
        detectedMediaTitle = title
        if (::btnOpenPlayer.isInitialized) {
            btnOpenPlayer.visibility = View.VISIBLE
        }
    }

    private fun playMediaInNativePlayer(url: String, title: String) {
        val dp = resources.displayMetrics.density

        // Pause webview playing media items
        activeWebView?.evaluateJavascript("document.querySelectorAll('video, audio').forEach(function(el) { el.pause(); });", null)
        stopNativePlayer()

        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
        }

        val videoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        nativeVideoView = videoView
        overlay.addView(videoView)

        val playStartTime = SystemClock.uptimeMillis()
        var loggedToHistory = false

        // Custom overlay TV controls
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#80000000"))
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
        }

        val titleTv = TextView(this).apply {
            text = title; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (14 * dp).toInt())
        }
        controls.addView(titleTv)

        val seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (16 * dp).toInt()
            }
        }
        controls.addView(seekBar)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnPlay = makeModernButton("Pause")
        val btnRew = makeModernButton("Rew -10s")
        val btnFwd = makeModernButton("Fwd +10s")
        val btnVolUp = makeModernButton("Vol +")
        val btnVolDn = makeModernButton("Vol -")
        val btnMin = makeModernButton("Minimize")
        val btnClose = makeModernButton("Close")

        btnPlay.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause(); btnPlay.text = "Play"
            } else {
                videoView.start(); btnPlay.text = "Pause"
            }
        }
        btnRew.setOnClickListener { videoView.seekTo((videoView.currentPosition - 10000).coerceAtLeast(0)) }
        btnFwd.setOnClickListener { videoView.seekTo((videoView.currentPosition + 10000).coerceAtMost(videoView.duration)) }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        btnVolUp.setOnClickListener { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
        btnVolDn.setOnClickListener { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }

        btnMin.setOnClickListener {
            isBackgroundPlaybackActive = true
            backgroundMediaUrl = url
            backgroundMediaTitle = title
            rootContainer.removeView(overlay)
            nativePlayerLayout = null
            showBackgroundPlayerIndicator()
            Toast.makeText(this@MainActivity, "Playing in background", Toast.LENGTH_SHORT).show()
            lastFocusedViewBeforeOverlay?.requestFocus()
        }

        btnClose.setOnClickListener {
            stopNativePlayer()
            rootContainer.removeView(overlay)
            nativePlayerLayout = null
            lastFocusedViewBeforeOverlay?.requestFocus()
        }

        btnRow.addView(btnPlay)
        btnRow.addView(btnRew)
        btnRow.addView(btnFwd)
        btnRow.addView(btnVolDn)
        btnRow.addView(btnVolUp)
        btnRow.addView(btnMin)
        btnRow.addView(btnClose)
        controls.addView(btnRow)
        overlay.addView(controls)

        nativePlayerLayout = overlay
        lastFocusedViewBeforeOverlay = currentFocus
        rootContainer.addView(overlay)

        videoView.setOnPreparedListener {
            seekBar.max = videoView.duration
            videoView.start()
            btnPlay.requestFocus()

            val progressUpdater = object : Runnable {
                override fun run() {
                    if (videoView.isPlaying) {
                        seekBar.progress = videoView.currentPosition
                        if (!loggedToHistory && (SystemClock.uptimeMillis() - playStartTime > 5000)) {
                            loggedToHistory = true
                            logPlayedMediaToHistory(url, title)
                        }
                    }
                    if (nativePlayerLayout == overlay) {
                        seekBar.postDelayed(this, 1000)
                    }
                }
            }
            seekBar.post(progressUpdater)
        }

        videoView.setOnCompletionListener {
            stopNativePlayer()
            rootContainer.removeView(overlay)
            nativePlayerLayout = null
            lastFocusedViewBeforeOverlay?.requestFocus()
        }

        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this@MainActivity, "Error playing stream natively", Toast.LENGTH_SHORT).show()
            rootContainer.removeView(overlay)
            nativePlayerLayout = null
            lastFocusedViewBeforeOverlay?.requestFocus()
            true
        }

        videoView.setVideoPath(url)
    }

    private fun stopNativePlayer() {
        try {
            nativeVideoView?.stopPlayback()
            nativeVideoView = null
        } catch (e: Exception) {}
        isBackgroundPlaybackActive = false
        backgroundMediaUrl = null
        backgroundMediaTitle = null
        hideBackgroundPlayerIndicator()
    }

    private fun showBackgroundPlayerIndicator() {
        if (::slimBgMediaIndicator.isInitialized) {
            slimBgMediaIndicator.visibility = View.VISIBLE
        }
    }

    private fun hideBackgroundPlayerIndicator() {
        if (::slimBgMediaIndicator.isInitialized) {
            slimBgMediaIndicator.visibility = View.GONE
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pinned Media & played history metadata lookup
    // ─────────────────────────────────────────────────────────────────────────
    private fun logPlayedMediaToHistory(url: String, title: String) {
        val domain = Uri.parse(url).host ?: ""
        val isAudio = url.lowercase().contains(".mp3")
        
        enrichMediaMetadata(title, isAudio) { enrichedTitle, year, artist, imageUrl ->
            runOnUiThread {
                mediaHistoryList.removeAll { it.url == url }
                mediaHistoryList.add(MediaHistoryEntry(
                    title = enrichedTitle,
                    url = url,
                    domain = domain,
                    relativeTimestamp = "Just now",
                    year = year,
                    type = if (isAudio) "audio" else "video",
                    artistName = artist,
                    imageUrl = imageUrl
                ))
                if (mediaHistoryList.size > 50) mediaHistoryList.removeAt(0)
                saveMediaHistory()
                if (currentScreenId == SCREEN_MEDIA) {
                    refreshMediaHistoryList()
                }
                refreshRecentMediaBanner()
            }
        }
    }

    private fun enrichMediaMetadata(title: String, isAudio: Boolean, onComplete: (enrichedTitle: String, year: String, artist: String?, imageUrl: String?) -> Unit) {
        Thread {
            try {
                val cleanTitle = title.replace(Regex("(?i)watch|live|stream|ntvstream|online|free|hulu|netflix|hbo|showtime|prime|disney|hd|sd|4k|1080p|720p"), "")
                    .replace(Regex("[|\\-\\_]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (isAudio) {
                    // Query MusicBrainz API for audio tracks
                    val serviceUrl = URL("https://musicbrainz.org/ws/2/release/?query=${java.net.URLEncoder.encode(cleanTitle, "UTF-8")}&fmt=json")
                    val conn = serviceUrl.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "TVBrowser/1.0 (contact@example.com)")
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    if (conn.responseCode == 200) {
                        val resp = conn.inputStream.bufferedReader().readText()
                        val obj = JSONObject(resp)
                        val releases = obj.optJSONArray("releases")
                        if (releases != null && releases.length() > 0) {
                            val first = releases.getJSONObject(0)
                            val mbid = first.optString("id", "")
                            val rTitle = first.optString("title", title)
                            val date = first.optString("date", "")
                            val year = if (date.length >= 4) date.substring(0, 4) else ""
                            val artistCredit = first.optJSONArray("artist-credit")
                            val artistName = if (artistCredit != null && artistCredit.length() > 0) {
                                artistCredit.getJSONObject(0).optJSONObject("artist")?.optString("name")
                            } else null
                            val imgUrl = if (mbid.isNotEmpty()) "https://coverartarchive.org/release/$mbid/front-250" else null
                            onComplete(rTitle, year, artistName, imgUrl)
                            return@Thread
                        }
                    }
                } else {
                    // Query TVMaze API for TV shows/movies
                    val serviceUrl = URL("https://api.tvmaze.com/singlesearch/shows?q=${java.net.URLEncoder.encode(cleanTitle, "UTF-8")}")
                    val conn = serviceUrl.openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    if (conn.responseCode == 200) {
                        val resp = conn.inputStream.bufferedReader().readText()
                        val obj = JSONObject(resp)
                        val showName = obj.optString("name", title)
                        val premiered = obj.optString("premiered", "")
                        val year = if (premiered.length >= 4) premiered.substring(0, 4) else ""
                        val imgObj = obj.optJSONObject("image")
                        val imgUrl = imgObj?.optString("medium") ?: imgObj?.optString("original")
                        onComplete(showName, year, null, imgUrl)
                        return@Thread
                    }
                }
            } catch (e: Exception) { Log.w("TVBrowser", "Enrichment exception: ${e.message}") }
            onComplete(title, "", null, null)
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Focus Trap Overlays & Dialogs (B7)
    // ─────────────────────────────────────────────────────────────────────────
    private class FocusTrapFrameLayout(context: Context) : FrameLayout(context) {
        var onBackPressed: (() -> Unit)? = null
        init {
            isClickable = true
        }
        override fun focusSearch(focused: View?, direction: Int): View? {
            val next = super.focusSearch(focused, direction)
            if (next == null || !isDescendantOf(next)) {
                return focused
            }
            return next
        }
        private fun isDescendantOf(view: View): Boolean {
            var p = view.parent
            while (p != null) {
                if (p == this) return true
                p = p.parent
            }
            return false
        }
        override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                onBackPressed?.let {
                    it.invoke()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun makeSettingRow(iconResId: Int, title: String, value: String, onClick: () -> Unit): LinearLayout {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE))
                cornerRadius = 12 * dp
                setStroke((1 * dp).toInt(), resolveColor("#2A2A3E"))
            }
        }

        val iconView = ImageView(this).apply {
            setImageResource(iconResId)
            setColorFilter(resolveColor(TEXT_PRIMARY))
            layoutParams = LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply {
                rightMargin = (14 * dp).toInt()
            }
        }
        row.addView(iconView)

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(resolveColor(TEXT_PRIMARY))
            typeface = Typeface.DEFAULT_BOLD
        }
        textContainer.addView(titleView)

        val valueView = TextView(this).apply {
            tag = "value_view"
            text = value
            textSize = 12f
            setTextColor(resolveColor(TEXT_MUTED))
            setPadding(0, (2 * dp).toInt(), 0, 0)
        }
        textContainer.addView(valueView)

        row.addView(textContainer)

        val arrowView = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_forward)
            setColorFilter(resolveColor(TEXT_MUTED))
            layoutParams = LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply {
                leftMargin = (8 * dp).toInt()
            }
        }
        row.addView(arrowView)

        row.setOnClickListener { onClick() }

        row.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.04f else 1f)
                .scaleY(if (hasFocus) 1.04f else 1f)
                .setDuration(120)
                .start()
            
            row.background = GradientDrawable().apply {
                setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else BG_SURFACE))
                cornerRadius = 12 * dp
                setStroke(if (hasFocus) (3 * dp).toInt() else (1 * dp).toInt(), resolveColor(if (hasFocus) ACCENT_GLOW else "#2A2A3E"))
            }
            row.applyPremiumShadow(dp, if (hasFocus) 8f else 0f, hasFocus)
            row.translationZ = if (hasFocus) 4 * dp else 0f

            iconView.setColorFilter(resolveColor(if (hasFocus) ACCENT_GLOW else TEXT_PRIMARY))
            arrowView.setColorFilter(resolveColor(if (hasFocus) ACCENT_GLOW else TEXT_MUTED))
            if (hasFocus) {
                isCursorActive = false
                cursorView.visibility = View.GONE
            }
        }

        return row
    }

    private fun updateRowValue(row: LinearLayout, newValue: String) {
        val textContainer = row.getChildAt(1) as? LinearLayout
        val valueView = textContainer?.findViewWithTag<TextView>("value_view")
        valueView?.text = newValue
    }

    private fun showSettingsDialog() {
        val dp = resources.displayMetrics.density
        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#E607070C"))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams((380 * dp).toInt(), FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END
            }
            background = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE))
                cornerRadii = floatArrayOf(
                    20 * dp, 20 * dp, // top-left
                    0f, 0f,           // top-right
                    0f, 0f,           // bottom-right
                    20 * dp, 20 * dp  // bottom-left
                )
                setStroke((1 * dp).toInt(), resolveColor("#2A2A3E"))
            }
            setPadding((24 * dp).toInt(), (32 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt())
            clipChildren = false
            clipToPadding = false
            applyPremiumShadow(dp, 16f, false)
        }

        fun dismissSettings() {
            card.animate()
                .translationX(400 * dp)
                .setDuration(250)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    rootContainer.removeView(overlay)
                    lastFocusedViewBeforeOverlay?.requestFocus()
                }
                .start()
        }

        overlay.onBackPressed = {
            dismissSettings()
        }
        overlay.setOnClickListener {
            dismissSettings()
        }
        card.setOnClickListener {
            // consume click events to prevent closure
        }

        val titleText = TextView(this).apply {
            text = "Browser Settings"
            textSize = 22f
            setTextColor(resolveColor(TEXT_PRIMARY))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (24 * dp).toInt())
        }
        card.addView(titleText)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }

        val scrollContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            clipChildren = false
            clipToPadding = false
        }

        // 1. Search Engine Row
        val currentEngine = prefs.getString("search_engine_name", "DuckDuckGo") ?: "DuckDuckGo"
        var rowSearchEngine: LinearLayout? = null
        rowSearchEngine = makeSettingRow(R.drawable.ic_search, "Default Search Engine", currentEngine) {
            showSearchEnginePickerDialog { selectedEngine ->
                rowSearchEngine?.let { updateRowValue(it, selectedEngine) }
            }
        }
        scrollContainer.addView(rowSearchEngine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * dp).toInt() })

        // 2. Theme Row
        val currentThemeMode = prefs.getString("theme_mode", "auto") ?: "auto"
        val displayThemeMode = when (currentThemeMode) {
            "dark" -> "Dark"
            "light" -> "Light"
            else -> "Auto"
        }
        val rowTheme = makeSettingRow(R.drawable.ic_theme, "Theme Mode", displayThemeMode) {
            val nextMode = when (prefs.getString("theme_mode", "auto")) {
                "auto" -> "dark"
                "dark" -> "light"
                else -> "auto"
            }
            prefs.edit().putString("theme_mode", nextMode).apply()
            updateThemeState()
            recreate()
        }
        scrollContainer.addView(rowTheme, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * dp).toInt() })

        // 3. Clear History Row
        val rowClearHistory = makeSettingRow(R.drawable.ic_trash, "Clear History", "Delete all saved browsing history") {
            historyEntries.clear()
            saveHistory()
            refreshDashboard()
            Toast.makeText(this@MainActivity, "Browsing history cleared", Toast.LENGTH_SHORT).show()
        }
        scrollContainer.addView(rowClearHistory, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * dp).toInt() })

        // 4. Clear Cache Row
        val rowClearCache = makeSettingRow(R.drawable.ic_trash, "Clear Cache & Cookies", "Clear temporary files and cookies") {
            WebView(this@MainActivity).clearCache(true)
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            Toast.makeText(this@MainActivity, "Cache and cookies cleared", Toast.LENGTH_SHORT).show()
        }
        scrollContainer.addView(rowClearCache, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * dp).toInt() })

        // 5. Close Settings Row
        val rowClose = makeSettingRow(R.drawable.ic_close, "Close Settings", "Return to browser dashboard") {
            dismissSettings()
        }
        scrollContainer.addView(rowClose, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        scrollView.addView(scrollContainer)
        card.addView(scrollView)
        overlay.addView(card)

        lastFocusedViewBeforeOverlay = currentFocus
        rootContainer.addView(overlay)

        card.translationX = 400 * dp
        card.animate()
            .translationX(0f)
            .setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        rowSearchEngine.post { rowSearchEngine.requestFocus() }
    }

    private fun showSearchEnginePickerDialog(onSelected: (String) -> Unit) {
        val dp = resources.displayMetrics.density
        val caller = currentFocus
        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#E607070C"))
            clipChildren = false
            clipToPadding = false
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams((400 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            clipChildren = false
            clipToPadding = false
            applyPremiumShadow(dp, 16f, false)
        }

        fun dismissDialog() {
            rootContainer.removeView(overlay)
            caller?.requestFocus()
        }

        overlay.onBackPressed = { dismissDialog() }
        overlay.setOnClickListener { dismissDialog() }
        card.setOnClickListener { /* consume click events */ }

        card.addView(TextView(this).apply {
            text = "Select Search Engine"; textSize = 18f
            setTextColor(resolveColor(TEXT_PRIMARY)); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (16 * dp).toInt())
        })

        val currentEngine = prefs.getString("search_engine_name", "DuckDuckGo") ?: "DuckDuckGo"

        searchEngines.keys.forEach { engine ->
            val isCurrent = (engine == currentEngine)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(resolveColor(BG_SURFACE))
                    cornerRadius = 12 * dp
                    setStroke((1 * dp).toInt(), resolveColor("#2A2A3E"))
                }
            }

            val searchIcon = ImageView(this).apply {
                setImageResource(R.drawable.ic_search)
                setColorFilter(resolveColor(if (isCurrent) ACCENT_GLOW else TEXT_MUTED))
                layoutParams = LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply {
                    rightMargin = (12 * dp).toInt()
                }
            }
            row.addView(searchIcon)

            val nameText = TextView(this).apply {
                text = engine; textSize = 14f
                setTextColor(resolveColor(TEXT_PRIMARY))
                typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameText)

            val indicator = FrameLayout(this).apply {
                val sz = (16 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (isCurrent) {
                        setColor(resolveColor(ACCENT_GLOW))
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke((1.5 * dp).toInt(), resolveColor("#4E4E62"))
                    }
                }
            }
            row.addView(indicator)

            row.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(120).start()
                v.background = GradientDrawable().apply {
                    setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else BG_SURFACE))
                    cornerRadius = 12 * dp
                    setStroke(if (hasFocus) (3 * dp).toInt() else (1 * dp).toInt(), resolveColor(if (hasFocus) ACCENT_GLOW else "#2A2A3E"))
                }
                v.applyPremiumShadow(dp, if (hasFocus) 6f else 0f, hasFocus)
                if (hasFocus) {
                    isCursorActive = false
                    cursorView.visibility = View.GONE
                }
            }

            row.setOnClickListener {
                if (engine == "Custom") {
                    showCustomSearchEngineTemplatePrompt { customUrl ->
                        prefs.edit().putString("search_engine_name", "Custom")
                            .putString("search_engine_custom_url", customUrl).apply()
                        onSelected("Custom")
                        dismissDialog()
                    }
                } else {
                    prefs.edit().putString("search_engine_name", engine).apply()
                    onSelected(engine)
                    dismissDialog()
                }
            }

            card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8 * dp).toInt() })
        }

        val btnCancel = makeModernButton("Cancel").apply { setOnClickListener { dismissDialog() } }
        card.addView(btnCancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() })

        overlay.addView(card)
        rootContainer.addView(overlay)

        var focusedInitial = false
        val childCount = card.childCount
        for (i in 0 until childCount) {
            val child = card.getChildAt(i)
            if (child is LinearLayout) {
                val tv = child.getChildAt(1) as? TextView
                if (tv?.text == currentEngine) {
                    child.post { child.requestFocus() }
                    focusedInitial = true
                    break
                }
            }
        }
        if (!focusedInitial) {
            btnCancel.post { btnCancel.requestFocus() }
        }
    }

    private fun showCustomSearchEngineTemplatePrompt(onSaved: (String) -> Unit) {
        val dp = resources.displayMetrics.density
        val caller = currentFocus
        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#E607070C"))
            clipChildren = false
            clipToPadding = false
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams((440 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
            setPadding((28 * dp).toInt(), (28 * dp).toInt(), (28 * dp).toInt(), (28 * dp).toInt())
            clipChildren = false
            clipToPadding = false
            applyPremiumShadow(dp, 16f, false)
        }

        fun dismissPrompt() {
            rootContainer.removeView(overlay)
            caller?.requestFocus()
        }

        overlay.onBackPressed = { dismissPrompt() }
        overlay.setOnClickListener { dismissPrompt() }
        card.setOnClickListener { /* consume click events */ }

        card.addView(TextView(this).apply {
            text = "Enter Custom Search URL"; textSize = 18f
            setTextColor(resolveColor(TEXT_PRIMARY)); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * dp).toInt())
        })
        card.addView(TextView(this).apply {
            text = "Use %s as query placeholder, e.g. https://startpage.com/sp/search?q=%s"; textSize = 11f
            setTextColor(resolveColor(TEXT_MUTED)); setPadding(0, 0, 0, (16 * dp).toInt())
        })

        val urlInputContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * dp).toInt()).apply { bottomMargin = (20 * dp).toInt() }
            background = makePillBg(dp, false)
        }
        val urlInput = EditText(this).apply {
            setText(prefs.getString("search_engine_custom_url", "https://"))
            setTextColor(resolveColor(TEXT_PRIMARY)); textSize = 15f
            isFocusable = true; isFocusableInTouchMode = true; setSingleLine(true)
            background = null; setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            setOnFocusChangeListener { _, hasFocus -> urlInputContainer.background = makePillBg(dp, hasFocus) }
        }
        urlInputContainer.addView(urlInput)
        card.addView(urlInputContainer)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val cancelBtn = makeModernButton("Cancel").apply { setOnClickListener { dismissPrompt() } }
        val saveBtn = makeModernButton("Save").apply {
            setOnClickListener {
                val inputUrl = urlInput.text.toString().trim()
                if (inputUrl.isNotEmpty() && inputUrl.contains("%s")) {
                    onSaved(inputUrl)
                    dismissPrompt()
                } else {
                    Toast.makeText(this@MainActivity, "URL must contain %s placeholder", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = (12 * dp).toInt() })
        btnRow.addView(saveBtn)
        card.addView(btnRow)

        overlay.addView(card)
        rootContainer.addView(overlay)
        urlInput.post { urlInput.requestFocus() }
    }

    private fun showDownloadsDialog() {
        val dp = resources.displayMetrics.density
        val caller = currentFocus
        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#E607070C"))
            clipChildren = false
            clipToPadding = false
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams((440 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
            clipChildren = false
            clipToPadding = false
            applyPremiumShadow(dp, 16f, false)
        }

        fun dismissDownloads() {
            rootContainer.removeView(overlay)
            caller?.requestFocus()
        }

        overlay.onBackPressed = { dismissDownloads() }
        overlay.setOnClickListener { dismissDownloads() }
        card.setOnClickListener { /* consume click events */ }

        card.addView(TextView(this).apply {
            text = "Downloads"; textSize = 22f
            setTextColor(resolveColor(TEXT_PRIMARY)); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (20 * dp).toInt())
        })
        card.addView(TextView(this).apply {
            text = "No files downloaded yet."; textSize = 14f
            setTextColor(resolveColor(TEXT_MUTED)); setPadding(0, 0, 0, (24 * dp).toInt())
        })
        val btnClose = makeModernButton("Close").apply {
            setOnClickListener { dismissDownloads() }
        }
        card.addView(btnClose, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        
        overlay.addView(card)
        rootContainer.addView(overlay)
        btnClose.post { btnClose.requestFocus() }
    }

    private fun showQRCodeDialog() {
        val dp = resources.displayMetrics.density
        val caller = currentFocus
        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#E607070C"))
            clipChildren = false
            clipToPadding = false
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams((440 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
            clipChildren = false
            clipToPadding = false
            applyPremiumShadow(dp, 16f, false)
        }

        fun dismissQR() {
            rootContainer.removeView(overlay)
            caller?.requestFocus()
        }

        overlay.onBackPressed = { dismissQR() }
        overlay.setOnClickListener { dismissQR() }
        card.setOnClickListener { /* consume click events */ }

        card.addView(TextView(this).apply {
            text = "Scan QR Code"; textSize = 22f
            setTextColor(resolveColor(TEXT_PRIMARY)); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, 0, 0, (20 * dp).toInt())
        })
        
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_qr_code)
            setColorFilter(resolveColor(ACCENT_CYAN))
            val qrSz = (100 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(qrSz, qrSz).apply {
                bottomMargin = (20 * dp).toInt()
            }
        })

        card.addView(TextView(this).apply {
            text = "Scan this code with your phone camera to control your TV browser or send tabs and links directly."
            textSize = 14f; setTextColor(resolveColor(TEXT_MUTED))
            gravity = Gravity.CENTER; setPadding(0, 0, 0, (24 * dp).toInt())
        })
        val btnClose = makeModernButton("Close").apply {
            setOnClickListener { dismissQR() }
        }
        card.addView(btnClose, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        
        overlay.addView(card)
        rootContainer.addView(overlay)
        btnClose.post { btnClose.requestFocus() }
    }

    private fun startVoiceSearch() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to search...")
        }
        try {
            startActivityForResult(intent, 1001)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice search is not supported on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val results = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                val query = results[0]
                omniInput.setText(query)
                navigateFromOmnibox()
            }
        }
    }

    private fun showAddBookmarkPrompt() {
        val dp = resources.displayMetrics.density
        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#E607070C"))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams((440 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
        }
        card.addView(TextView(this).apply { text = "Add Bookmark"; textSize = 22f; setTextColor(resolveColor(TEXT_PRIMARY)); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, (16 * dp).toInt()) })
        val urlInputContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * dp).toInt()).apply { bottomMargin = (20 * dp).toInt() }
            background = makePillBg(dp, false)
        }
        val urlInput = EditText(this).apply {
            hint = "https://example.com"; setHintTextColor(resolveColor(TEXT_MUTED))
            setTextColor(resolveColor(TEXT_PRIMARY)); textSize = 15f
            isFocusable = true; isFocusableInTouchMode = true; setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE; background = null
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            setOnFocusChangeListener { _, hasFocus -> urlInputContainer.background = makePillBg(dp, hasFocus) }
        }
        urlInputContainer.addView(urlInput)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val cancelBtn = makeModernButton("Cancel")
        cancelBtn.setOnClickListener { rootContainer.removeView(overlay) }
        val saveBtn = makeModernButton("Save")
        saveBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                val domain = Uri.parse(url).host ?: url
                bookmarks.add(UserBookmark(title = domain, url = url))
                saveBookmarks(); refreshBookmarkShelf()
            }
            rootContainer.removeView(overlay)
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = (12 * dp).toInt() })
        btnRow.addView(saveBtn)
        card.addView(urlInputContainer); card.addView(btnRow)
        overlay.addView(card)
        
        lastFocusedViewBeforeOverlay = currentFocus
        rootContainer.addView(overlay)
        urlInput.post { urlInput.requestFocus() }
    }

    private fun showAddPinnedMediaSitePrompt() {
        val dp = resources.displayMetrics.density
        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#E607070C"))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams((440 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
        }
        card.addView(TextView(this).apply { text = "Pin Media Site"; textSize = 22f; setTextColor(resolveColor(TEXT_PRIMARY)); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, (8 * dp).toInt()) })
        card.addView(TextView(this).apply { text = "Pin a new video/audio site to your media screen grid."; textSize = 12f; setTextColor(resolveColor(TEXT_MUTED)); setPadding(0, 0, 0, (16 * dp).toInt()) })

        // Input 1: Site Name
        val nameInputContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * dp).toInt()).apply { bottomMargin = (12 * dp).toInt() }
            background = makePillBg(dp, false)
        }
        val nameInput = EditText(this).apply {
            hint = "Site Name"; setHintTextColor(resolveColor(TEXT_MUTED))
            setTextColor(resolveColor(TEXT_PRIMARY)); textSize = 14f
            isFocusable = true; setSingleLine(true); background = null
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            setOnFocusChangeListener { _, hasFocus -> nameInputContainer.background = makePillBg(dp, hasFocus) }
        }
        nameInputContainer.addView(nameInput)
        card.addView(nameInputContainer)

        // Input 2: Site URL
        val urlInputContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * dp).toInt()).apply { bottomMargin = (20 * dp).toInt() }
            background = makePillBg(dp, false)
        }
        val urlInput = EditText(this).apply {
            hint = "https://example.com"; setHintTextColor(resolveColor(TEXT_MUTED))
            setTextColor(resolveColor(TEXT_PRIMARY)); textSize = 14f
            isFocusable = true; setSingleLine(true); background = null
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            setOnFocusChangeListener { _, hasFocus -> urlInputContainer.background = makePillBg(dp, hasFocus) }
        }
        urlInputContainer.addView(urlInput)
        card.addView(urlInputContainer)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val cancelBtn = makeModernButton("Cancel").apply { setOnClickListener { rootContainer.removeView(overlay) } }
        val saveBtn = makeModernButton("Pin Site").apply {
            setOnClickListener {
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    pinnedMediaSites.add(PinnedMediaSite(name, url))
                    savePinnedMediaSites()
                    refreshPinnedMediaSites()
                }
                rootContainer.removeView(overlay)
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = (12 * dp).toInt() })
        btnRow.addView(saveBtn)
        card.addView(btnRow)

        overlay.addView(card)
        rootContainer.addView(overlay)
        nameInput.post { nameInput.requestFocus() }
    }

    private fun showEditBookmarkDialog(bm: UserBookmark) {
        val dp = resources.displayMetrics.density
        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#E607070C"))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams((440 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
        }
        card.addView(TextView(this).apply { text = "Edit Bookmark"; textSize = 22f; setTextColor(resolveColor(TEXT_PRIMARY)); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, (16 * dp).toInt()) })

        // Input 1: Title
        val titleInputContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * dp).toInt()).apply { bottomMargin = (12 * dp).toInt() }
            background = makePillBg(dp, false)
        }
        val titleInput = EditText(this).apply {
            setText(bm.title)
            setTextColor(resolveColor(TEXT_PRIMARY)); textSize = 14f
            isFocusable = true; setSingleLine(true); background = null
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            setOnFocusChangeListener { _, hasFocus -> titleInputContainer.background = makePillBg(dp, hasFocus) }
        }
        titleInputContainer.addView(titleInput)
        card.addView(titleInputContainer)

        // Input 2: URL
        val urlInputContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * dp).toInt()).apply { bottomMargin = (20 * dp).toInt() }
            background = makePillBg(dp, false)
        }
        val urlInput = EditText(this).apply {
            setText(bm.url)
            setTextColor(resolveColor(TEXT_PRIMARY)); textSize = 14f
            isFocusable = true; setSingleLine(true); background = null
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            setOnFocusChangeListener { _, hasFocus -> urlInputContainer.background = makePillBg(dp, hasFocus) }
        }
        urlInputContainer.addView(urlInput)
        card.addView(urlInputContainer)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val cancelBtn = makeModernButton("Cancel").apply { setOnClickListener { rootContainer.removeView(overlay) } }
        val deleteBtn = makeModernButton("Delete").apply {
            setOnClickListener {
                bookmarks.remove(bm)
                saveBookmarks(); refreshBookmarkShelf()
                rootContainer.removeView(overlay)
            }
        }
        val saveBtn = makeModernButton("Save").apply {
            setOnClickListener {
                val t = titleInput.text.toString().trim()
                val u = urlInput.text.toString().trim()
                if (t.isNotEmpty() && u.isNotEmpty()) {
                    val idx = bookmarks.indexOf(bm)
                    if (idx != -1) {
                        bookmarks[idx] = UserBookmark(title = t, url = u, isPinned = bm.isPinned)
                        saveBookmarks(); refreshBookmarkShelf()
                    }
                }
                rootContainer.removeView(overlay)
            }
        }
        btnRow.addView(deleteBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = (12 * dp).toInt() })
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = (12 * dp).toInt() })
        btnRow.addView(saveBtn)
        card.addView(btnRow)

        overlay.addView(card)
        rootContainer.addView(overlay)
        titleInput.post { titleInput.requestFocus() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ad-Blocking stats & element picker sub-overlays (B8)
    // ─────────────────────────────────────────────────────────────────────────
    private fun showAdBlockerDashboard() {
        val dp = resources.displayMetrics.density
        val app = application as BrowserApp
        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#E607070C"))
            clipChildren = false
            clipToPadding = false
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams((440 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
            clipChildren = false
            clipToPadding = false
            applyPremiumShadow(dp, 16f, false)
        }

        card.addView(TextView(this).apply {
            text = "Ad-Blocking Dashboard"; textSize = 20f
            setTextColor(resolveColor(TEXT_PRIMARY)); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (16 * dp).toInt())
        })

        // Stats
        card.addView(TextView(this).apply {
            text = "Current Page Blocked: ${app.adBlockEngine.pageBlockCount.get()} requests\nLifetime Blocked: ${app.adBlockEngine.lifetimeBlockCount.get()} requests"
            textSize = 14f; setTextColor(resolveColor(TEXT_PRIMARY))
            setPadding(0, 0, 0, (20 * dp).toInt())
        })

        val btnPicker = makeModernButton("Block Element on Page")
        btnPicker.setOnClickListener {
            rootContainer.removeView(overlay)
            startElementPicker()
        }
        card.addView(btnPicker, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * dp).toInt() })

        val btnManage = makeModernButton("Manage Filters & Blocklist")
        btnManage.setOnClickListener {
            rootContainer.removeView(overlay)
            showBlocklistManagement()
        }
        card.addView(btnManage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (16 * dp).toInt() })

        val btnClose = makeModernButton("Close")
        btnClose.setOnClickListener {
            rootContainer.removeView(overlay)
            lastFocusedViewBeforeOverlay?.requestFocus()
        }
        card.addView(btnClose, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        overlay.addView(card)
        lastFocusedViewBeforeOverlay = currentFocus
        rootContainer.addView(overlay)
        btnClose.post { btnClose.requestFocus() }
        overlay.onBackPressed = {
            rootContainer.removeView(overlay)
            lastFocusedViewBeforeOverlay?.requestFocus()
        }
    }

    private fun showBlocklistManagement() {
        val dp = resources.displayMetrics.density
        val app = application as BrowserApp
        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#E607070C"))
            clipChildren = false
            clipToPadding = false
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams((480 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            clipChildren = false
            clipToPadding = false
            applyPremiumShadow(dp, 16f, false)
        }

        card.addView(TextView(this).apply {
            text = "Manage Subscribed Filters"; textSize = 18f
            setTextColor(resolveColor(TEXT_PRIMARY)); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        // Content ScrollView to avoid vertical overflow on TV screen
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val scrollWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (300 * dp).toInt()).apply {
                bottomMargin = (12 * dp).toInt()
            }
        }
        val mainScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            clipToPadding = false
            clipChildren = false
        }
        mainScroll.addView(scrollContent)
        scrollWrapper.addView(mainScroll)
        card.addView(scrollWrapper)

        // Toggle list filters (Redesigned as custom premium settings rows)
        app.adBlockEngine.filterLists.forEach { filterList ->
            val isEnabled = prefs.getBoolean("adblock_list_${filterList.name}", true)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(resolveColor(BG_SURFACE))
                    cornerRadius = 12 * dp
                    setStroke((1 * dp).toInt(), resolveColor("#2A2A3E"))
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f).setDuration(120).start()
                    v.background = GradientDrawable().apply {
                        setColor(resolveColor(if (hasFocus) BG_SURFACE_FOCUS else BG_SURFACE))
                        cornerRadius = 12 * dp
                        setStroke(if (hasFocus) (3 * dp).toInt() else (1 * dp).toInt(), resolveColor(if (hasFocus) ACCENT_GLOW else "#2A2A3E"))
                    }
                }
                setOnClickListener {
                    val nextState = !isEnabled
                    prefs.edit().putBoolean("adblock_list_${filterList.name}", nextState).apply()
                    Toast.makeText(this@MainActivity, "${filterList.name} set to ${if (nextState) "ENABLED" else "DISABLED"}. Loading...", Toast.LENGTH_SHORT).show()
                    Thread { app.adBlockEngine.loadRules(this@MainActivity) }.start()
                    rootContainer.removeView(overlay)
                    showBlocklistManagement()
                }
            }

            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_shield)
                val iconSize = (18 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { rightMargin = (12 * dp).toInt() }
                setColorFilter(resolveColor(if (isEnabled) ACCENT_CYAN else TEXT_MUTED))
            })

            row.addView(TextView(this).apply {
                text = filterList.name
                textSize = 14f
                setTextColor(resolveColor(if (isEnabled) TEXT_PRIMARY else TEXT_MUTED))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            row.addView(View(this).apply {
                val sz = (16 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (isEnabled) {
                        setColor(resolveColor(ACCENT_CYAN))
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke((2 * dp).toInt(), resolveColor(TEXT_MUTED))
                    }
                }
            })

            scrollContent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8 * dp).toInt() })
        }

        scrollContent.addView(TextView(this).apply {
            text = "Custom Domain Rules (Picker)"; textSize = 16f
            setTextColor(resolveColor(TEXT_PRIMARY)); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (14 * dp).toInt(), 0, (8 * dp).toInt())
        })

        // Inline custom domain rules container (no nested ScrollView!)
        val rulesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scrollContent.addView(rulesContainer)

        var hasCustomRules = false
        customBlockRules.forEach { (domain, selectors) ->
            selectors.forEach { selector ->
                hasCustomRules = true
                val ruleRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                    clipChildren = false
                    clipToPadding = false
                }
                ruleRow.addView(TextView(this).apply {
                    text = "$domain -> $selector"; textSize = 11f; setTextColor(resolveColor(TEXT_PRIMARY))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val editBtn = makeModernButton("Edit").apply {
                    textSize = 10f
                    setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                    setOnClickListener {
                        rootContainer.removeView(overlay)
                        showEditSelectorDialog(domain, selector)
                    }
                }
                val delBtn = makeModernButton("Delete").apply {
                    textSize = 10f
                    setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                    setOnClickListener {
                        selectors.remove(selector)
                        if (selectors.isEmpty()) {
                            customBlockRules.remove(domain)
                        }
                        saveCustomBlockRules()
                        rootContainer.removeView(overlay)
                        showBlocklistManagement()
                    }
                }
                ruleRow.addView(editBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = (8 * dp).toInt() })
                ruleRow.addView(delBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                rulesContainer.addView(ruleRow)
            }
        }
        if (!hasCustomRules) {
            rulesContainer.addView(TextView(this).apply { text = "No custom element block rules yet."; textSize = 12f; setTextColor(resolveColor(TEXT_MUTED)) })
        }

        val btnClose = makeModernButton("Close").apply {
            setOnClickListener {
                rootContainer.removeView(overlay)
                lastFocusedViewBeforeOverlay?.requestFocus()
            }
        }
        card.addView(btnClose, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        overlay.addView(card)
        rootContainer.addView(overlay)
        btnClose.post { btnClose.requestFocus() }
        overlay.onBackPressed = {
            rootContainer.removeView(overlay)
            lastFocusedViewBeforeOverlay?.requestFocus()
        }
    }

    private fun showEditSelectorDialog(domain: String, oldSelector: String) {
        val dp = resources.displayMetrics.density
        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#E607070C"))
            clipChildren = false
            clipToPadding = false
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams((440 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
            setPadding((28 * dp).toInt(), (28 * dp).toInt(), (28 * dp).toInt(), (28 * dp).toInt())
            clipChildren = false
            clipToPadding = false
            applyPremiumShadow(dp, 16f, false)
        }

        card.addView(TextView(this).apply {
            text = "Edit CSS Selector"; textSize = 18f
            setTextColor(resolveColor(TEXT_PRIMARY)); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        card.addView(TextView(this).apply {
            text = "Domain: $domain"; textSize = 12f
            setTextColor(resolveColor(TEXT_MUTED)); setPadding(0, 0, 0, (16 * dp).toInt())
        })

        val inputContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * dp).toInt()).apply { bottomMargin = (20 * dp).toInt() }
            background = makePillBg(dp, false)
        }
        val input = EditText(this).apply {
            setText(oldSelector)
            setTextColor(resolveColor(TEXT_PRIMARY)); textSize = 15f
            isFocusable = true; isFocusableInTouchMode = true; setSingleLine(true)
            background = null; setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            setOnFocusChangeListener { _, hasFocus -> inputContainer.background = makePillBg(dp, hasFocus) }
        }
        inputContainer.addView(input)
        card.addView(inputContainer)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
        }
        val cancelBtn = makeModernButton("Cancel").apply {
            setOnClickListener {
                rootContainer.removeView(overlay)
                showBlocklistManagement()
            }
        }
        val saveBtn = makeModernButton("Save").apply {
            setOnClickListener {
                val newSelector = input.text.toString().trim()
                if (newSelector.isNotEmpty()) {
                    val list = customBlockRules[domain]
                    if (list != null) {
                        val index = list.indexOf(oldSelector)
                        if (index != -1) {
                            list[index] = newSelector
                            saveCustomBlockRules()
                        }
                    }
                    rootContainer.removeView(overlay)
                    showBlocklistManagement()
                } else {
                    Toast.makeText(this@MainActivity, "Selector cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = (12 * dp).toInt() })
        btnRow.addView(saveBtn)
        card.addView(btnRow)

        overlay.addView(card)
        rootContainer.addView(overlay)
        input.post { input.requestFocus() }
        overlay.onBackPressed = {
            rootContainer.removeView(overlay)
            showBlocklistManagement()
        }
    }

    private fun showAboutDialog() {
        val dp = resources.displayMetrics.density
        val overlay = FocusTrapFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveColor("#E607070C"))
            clipChildren = false
            clipToPadding = false
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams((400 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            background = GradientDrawable().apply { setColor(resolveColor(BG_SURFACE)); cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), resolveColor("#2A2A3E")) }
            setPadding((28 * dp).toInt(), (28 * dp).toInt(), (28 * dp).toInt(), (28 * dp).toInt())
            clipChildren = false
            clipToPadding = false
            applyPremiumShadow(dp, 16f, false)
        }

        // Title and Logo
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (16 * dp).toInt())
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_globe)
            setColorFilter(Color.WHITE)
            val sz = (36 * dp).toInt()
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { rightMargin = (12 * dp).toInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(resolveColor(ACCENT_GLOW))
            }
        })
        header.addView(TextView(this).apply {
            text = "TV Browser"
            textSize = 20f
            setTextColor(resolveColor(TEXT_PRIMARY))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        card.addView(header)

        // Version Info
        val appVer = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
        card.addView(TextView(this).apply {
            text = "Version $appVer"
            textSize = 13f
            setTextColor(resolveColor(ACCENT_CYAN))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (14 * dp).toInt())
        })

        // Description
        card.addView(TextView(this).apply {
            text = "A premium, private, and customizable web browser designed specifically for Android TV. Fully optimized for simple D-pad controller navigation."
            textSize = 14f
            setTextColor(resolveColor(TEXT_MUTED))
            setPadding(0, 0, 0, (20 * dp).toInt())
        })

        // Features list
        val features = listOf(
            "Built-in High-Performance AdBlocker",
            "Left Navigation Rail for quick switching",
            "Custom Search Engines & User Agents",
            "True OLED Dark Mode & customizable themes"
        )
        features.forEach { feature ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, (8 * dp).toInt())
            }
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_shield)
                setColorFilter(resolveColor(ACCENT_CYAN))
                layoutParams = LinearLayout.LayoutParams((14 * dp).toInt(), (14 * dp).toInt()).apply { rightMargin = (8 * dp).toInt() }
            })
            row.addView(TextView(this).apply {
                text = feature
                textSize = 12f
                setTextColor(resolveColor(TEXT_PRIMARY))
            })
            card.addView(row)
        }

        // Spacing before close
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (16 * dp).toInt())
        })

        val btnClose = makeModernButton("Close").apply {
            setOnClickListener {
                rootContainer.removeView(overlay)
                lastFocusedViewBeforeOverlay?.requestFocus()
            }
        }
        card.addView(btnClose, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        overlay.addView(card)
        rootContainer.addView(overlay)
        btnClose.post { btnClose.requestFocus() }
        overlay.onBackPressed = {
            rootContainer.removeView(overlay)
            lastFocusedViewBeforeOverlay?.requestFocus()
        }
    }

    private fun startElementPicker() {
        if (activeWebView == null) {
            Toast.makeText(this, "No active web page to pick elements from", Toast.LENGTH_SHORT).show()
            return
        }
        isElementPickerActive = true
        isCursorActive = true
        cursorView.visibility = View.VISIBLE
        
        // Inject script helper
        activeWebView?.evaluateJavascript(ELEMENT_PICKER_JS, null)
        Toast.makeText(this, "Highlight element and press SELECT (OK) to block", Toast.LENGTH_LONG).show()
    }

    private fun handleElementSelected(selector: String) {
        val webView = activeWebView ?: return
        val url = webView.url ?: ""
        val host = Uri.parse(url).host ?: "unknown"
        val normalizedHost = if (host.startsWith("www.")) host.substring(4) else host

        val list = customBlockRules[normalizedHost] ?: ArrayList()
        list.add(selector)
        customBlockRules[normalizedHost] = list
        saveCustomBlockRules()

        // Apply display: none style immediately
        val hideScript = "(function() { var style = document.createElement('style'); style.textContent = '$selector { display: none !important; }'; document.documentElement.appendChild(style); })();"
        webView.evaluateJavascript(hideScript, null)

        Toast.makeText(this, "Element blocked successfully!", Toast.LENGTH_SHORT).show()
        isElementPickerActive = false
        activeWebView?.requestFocus()
    }

    private val ELEMENT_PICKER_JS = """
        (function() {
            if (window.elementPickerActive) return;
            window.elementPickerActive = true;
            
            var lastEl = null;
            var lastOutline = "";
            
            window.highlightElementAt = function(x, y) {
                var el = document.elementFromPoint(x, y);
                if (el === lastEl) return;
                if (lastEl) {
                    lastEl.style.outline = lastOutline;
                }
                if (el && el !== document.body && el !== document.documentElement) {
                    lastEl = el;
                    lastOutline = el.style.outline;
                    el.style.outline = "2px solid #FF9800";
                } else {
                    lastEl = null;
                }
            };
            
            window.selectElementAt = function(x, y) {
                var el = document.elementFromPoint(x, y);
                if (el && el !== document.body && el !== document.documentElement) {
                    var path = [];
                    while (el && el.nodeType === Node.ELEMENT_NODE) {
                        var selector = el.nodeName.toLowerCase();
                        if (el.id) {
                            selector += '#' + el.id;
                            path.unshift(selector);
                            break;
                        } else {
                            var sibling = el;
                            var nth = 1;
                            while (sibling = sibling.previousElementSibling) {
                                if (sibling.nodeName.toLowerCase() == el.nodeName.toLowerCase()) nth++;
                            }
                            selector += ":nth-of-type(" + nth + ")";
                        }
                        path.unshift(selector);
                        el = el.parentNode;
                    }
                    var fullSelector = path.join(" > ");
                    window.MediaObserver.onElementSelected(fullSelector);
                    if (lastEl) {
                        lastEl.style.outline = lastOutline;
                    }
                    window.elementPickerActive = false;
                }
            };
        })();
    """.trimIndent()

    private val MEDIA_OBSERVER_JS = """
        (function() {
            function checkMedia() {
                var mediaElements = document.querySelectorAll('video, audio');
                for (var i = 0; i < mediaElements.length; i++) {
                    var el = mediaElements[i];
                    if (!el.paused && !el.ended && el.readyState >= 2) {
                        var src = el.currentSrc || el.src;
                        if (src && src.startsWith('http')) {
                            window.MediaObserver.onMediaDetected(src, document.title || "Media Stream");
                            return;
                        }
                    }
                }
            }
            if (!window.mediaObserverInterval) {
                window.mediaObserverInterval = setInterval(checkMedia, 2000);
            }
        })();
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────────────────
    private fun saveHistory() {
        val arr = JSONArray()
        historyEntries.takeLast(MAX_HISTORY).forEach { e ->
            arr.put(JSONObject().apply { put("title", e.title); put("url", e.url); put("ts", e.relativeTimestamp) })
        }
        prefs.edit().putString("history_json", arr.toString()).apply()
    }

    private fun loadHistory() {
        val json = prefs.getString("history_json", null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                historyEntries.add(HistoryEntry(o.optString("title", "Page"), o.optString("url", ""), o.optString("ts", "")))
            }
        } catch (e: Exception) { Log.w("TVBrowser", "History parse error", e) }
    }

    private fun saveBookmarks() {
        val arr = JSONArray()
        bookmarks.forEach { bm ->
            arr.put(JSONObject().apply { put("title", bm.title); put("url", bm.url); put("pinned", bm.isPinned) })
        }
        prefs.edit().putString("bookmarks_json", arr.toString()).apply()
    }

    private fun loadBookmarks() {
        val json = prefs.getString("bookmarks_json", null)
        bookmarks.clear()
        if (json == null) {
            bookmarks.add(UserBookmark("DuckDuckGo", "https://duckduckgo.com", true))
            bookmarks.add(UserBookmark("Twitter", "https://twitter.com", true))
            bookmarks.add(UserBookmark("Hacker News", "https://news.ycombinator.com", true))
            bookmarks.add(UserBookmark("BBC", "https://www.bbc.com", true))
        } else {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    bookmarks.add(UserBookmark(o.optString("title", "Bookmark"), o.optString("url", ""), o.optBoolean("pinned", true)))
                }
            } catch (e: Exception) { Log.w("TVBrowser", "Bookmark parse error", e) }
        }
    }

    private fun loadPinnedMediaSites() {
        val json = prefs.getString("pinned_media_sites_json", null)
        pinnedMediaSites.clear()
        if (json == null) {
            pinnedMediaSites.add(PinnedMediaSite("YouTube", "https://www.youtube.com"))
            pinnedMediaSites.add(PinnedMediaSite("Vimeo", "https://vimeo.com"))
            pinnedMediaSites.add(PinnedMediaSite("Dailymotion", "https://www.dailymotion.com"))
            pinnedMediaSites.add(PinnedMediaSite("PeerTube", "https://sepiia.org"))
        } else {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    pinnedMediaSites.add(PinnedMediaSite(o.getString("name"), o.getString("url")))
                }
            } catch (e: Exception) { Log.w("TVBrowser", "Pinned sites parse error", e) }
        }
    }

    private fun savePinnedMediaSites() {
        val arr = JSONArray()
        pinnedMediaSites.forEach { site ->
            arr.put(JSONObject().apply { put("name", site.name); put("url", site.url) })
        }
        prefs.edit().putString("pinned_media_sites_json", arr.toString()).apply()
    }

    private fun loadMediaHistory() {
        val json = prefs.getString("media_history_json", null) ?: return
        mediaHistoryList.clear()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                mediaHistoryList.add(MediaHistoryEntry(
                    o.optString("title", "Media"),
                    o.optString("url", ""),
                    o.optString("domain", ""),
                    o.optString("ts", ""),
                    o.optString("year", ""),
                    o.optString("type", "video"),
                    o.optString("artist", null).takeIf { it?.isNotEmpty() ?: false },
                    o.optString("image", null).takeIf { it?.isNotEmpty() ?: false }
                ))
            }
        } catch (e: Exception) { Log.w("TVBrowser", "Media history parse error", e) }
    }

    private fun saveMediaHistory() {
        val arr = JSONArray()
        mediaHistoryList.forEach { entry ->
            arr.put(JSONObject().apply {
                put("title", entry.title)
                put("url", entry.url)
                put("domain", entry.domain)
                put("ts", entry.relativeTimestamp)
                put("year", entry.year)
                put("type", entry.type)
                put("artist", entry.artistName ?: "")
                put("image", entry.imageUrl ?: "")
            })
        }
        prefs.edit().putString("media_history_json", arr.toString()).apply()
    }

    private fun loadCustomBlockRules() {
        val json = prefs.getString("custom_block_rules_json", null) ?: return
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { key ->
                val arr = obj.getJSONArray(key)
                val list = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                customBlockRules[key] = list
            }
        } catch (e: Exception) { Log.w("TVBrowser", "Custom rules parse error", e) }
    }

    private fun saveCustomBlockRules() {
        val obj = JSONObject()
        customBlockRules.forEach { (domain, list) ->
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            obj.put(domain, arr)
        }
        prefs.edit().putString("custom_block_rules_json", obj.toString()).commit()
    }

    private fun handleSlimBarCollapse(scrollY: Int) {
        if (!::slimTopBar.isInitialized) return
        val barH = slimTopBar.height.toFloat()
        if (scrollY > 150 && slimBarVisible) {
            slimBarVisible = false
            slimTopBar.animate().translationY(-barH).setDuration(200).start()
        } else if (scrollY < 50 && !slimBarVisible) {
            slimBarVisible = true
            slimTopBar.animate().translationY(0f).setDuration(200).start()
        }
    }

    private val IFRAME_SANDBOX_FIX_JS = """
        (function() {
            function fixIframes() {
                document.querySelectorAll('iframe').forEach(function(el) {
                    if (el.hasAttribute('sandbox')) {
                        el.removeAttribute('sandbox');
                        el.removeAttribute('referrerpolicy');
                        el.setAttribute('allow',
                            'autoplay; fullscreen; encrypted-media; picture-in-picture; ' +
                            'accelerometer; clipboard-write; gyroscope');
                        el.setAttribute('allowfullscreen', '');
                        el.setAttribute('allowpaymentrequest', '');
                        var src = el.src;
                        el.src = '';
                        el.src = src;
                    }
                });
            }
            fixIframes();
            var obs = new MutationObserver(function() { fixIframes(); });
            if (document.documentElement) obs.observe(document.documentElement, { childList: true, subtree: true });
        })();
    """.trimIndent()

    private fun createWebView(): WebView {
        return WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }

            // JavaScript Interface for media detection and selector element block rules callbacks
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onMediaDetected(src: String, title: String) {
                    runOnUiThread { handleMediaDetected(src, title) }
                }

                @android.webkit.JavascriptInterface
                fun onElementSelected(selector: String) {
                    runOnUiThread { handleElementSelected(selector) }
                }
            }, "MediaObserver")

            val app = application as BrowserApp
            webViewClient = AdBlockingWebViewClient(
                engine = app.adBlockEngine,
                onPageStartedCallback = { url ->
                    runOnUiThread {
                        onTabUrlChanged(this@apply, url)
                        onTabLoadingStateChanged(this@apply, true)
                    }
                },
                onPageFinishedCallback = { url ->
                    runOnUiThread {
                        onTabUrlChanged(this@apply, url)
                        onTabLoadingStateChanged(this@apply, false)
                    }
                },
                onMediaIntercepted = { url ->
                    runOnUiThread {
                        handleMediaDetected(url, this@apply.title ?: "Video Stream")
                    }
                }
            )

            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    runOnUiThread { onTabTitleChanged(this@apply, title ?: "New Tab") }
                }

                private var customView: View? = null
                private var customViewCallback: CustomViewCallback? = null

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    runOnUiThread {
                        if (customView != null) {
                            callback?.onCustomViewHidden()
                            return@runOnUiThread
                        }
                        customView = view
                        customViewCallback = callback
                        isFullScreenMode = true
                        slimTopBar.visibility = View.GONE
                        webViewContainer.visibility = View.GONE
                        cursorView.visibility = View.GONE
                        
                        rootContainer.addView(customView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    }
                }

                override fun onHideCustomView() {
                    runOnUiThread {
                        if (customView == null) return@runOnUiThread
                        rootContainer.removeView(customView)
                        customView = null
                        customViewCallback?.onCustomViewHidden()
                        isFullScreenMode = false
                        slimTopBar.visibility = View.VISIBLE
                        webViewContainer.visibility = View.VISIBLE
                        cursorView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun onTabUrlChanged(webView: WebView, url: String) {
        val tab = tabs.firstOrNull { it.webView == webView } ?: return
        tab.url = url
        if (webView == activeWebView) {
            urlClickTarget.text = url; slimUrlBar.setText(url)
            canGoBackState = webView.canGoBack()
        }
    }

    private fun onTabLoadingStateChanged(webView: WebView, isLoading: Boolean) {
        if (webView == activeWebView) {
            slimLoadingSpinner.visibility = if (isLoading) View.VISIBLE else View.GONE
            slimRefreshStopBtn.setImageResource(if (isLoading) R.drawable.ic_close else R.drawable.ic_refresh)
            slimRefreshStopBtn.setTag(if (isLoading) "close" else "refresh")
            canGoBackState = webView.canGoBack()
        }

        if (isLoading) {
            if (webView == activeWebView) {
                // Reset page media capture states on page reload/redirect
                detectedMediaUrl = null
                detectedMediaTitle = null
                btnOpenPlayer.visibility = View.GONE
            }
        } else {
            webView.evaluateJavascript(IFRAME_SANDBOX_FIX_JS, null)
            webView.evaluateJavascript(MEDIA_OBSERVER_JS, null)
            // Inject custom selectors rules to hide elements on domain load
            injectCustomHideRules(webView, webView.url ?: "")

            val tab = tabs.firstOrNull { it.webView == webView } ?: return
            if (tab.url.isNotBlank() && !tab.url.startsWith("about:") && !tab.url.startsWith("file:")) {
                historyEntries.removeAll { it.url == tab.url }
                historyEntries.add(HistoryEntry(title = tab.title.ifBlank { tab.url }, url = tab.url, relativeTimestamp = "Just now"))
                if (historyEntries.size > MAX_HISTORY) historyEntries.removeAt(0)
                saveHistory()
            }
        }
    }

    private fun injectCustomHideRules(webView: WebView, url: String) {
        val host = Uri.parse(url).host ?: return
        val normalizedHost = if (host.startsWith("www.")) host.substring(4) else host
        val rules = customBlockRules[normalizedHost] ?: return
        if (rules.isEmpty()) return
        val selectors = rules.joinToString(", ") { "\"$it\"" }
        val js = """
            (function() {
                var selectors = [$selectors];
                var css = selectors.join(' { display: none !important; }\n') + ' { display: none !important; }';
                var style = document.createElement('style');
                style.type = 'text/css';
                style.appendChild(document.createTextNode(css));
                document.documentElement.appendChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun onTabTitleChanged(webView: WebView, title: String) {
        tabs.firstOrNull { it.webView == webView }?.title = title
    }

    private fun onTabScrollChanged(webView: WebView, scrollY: Int) {
        tabs.firstOrNull { it.webView == webView }?.currentScrollY = scrollY
        if (webView == activeWebView) { currentScrollY = scrollY; handleSlimBarCollapse(scrollY) }
    }

    private fun createNewTab(url: String? = null) {
        val webView = createWebView()
        val tabInfo = TabInfo(webView)
        tabs.add(tabInfo)
        activeTabIndex = tabs.size - 1
        webViewContainer.addView(webView, 0) // underneath cursor
        val resolved = resolveUrl(if (!url.isNullOrEmpty()) url else "https://duckduckgo.com")
        webView.loadUrl(resolved)
        switchTab(activeTabIndex)
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        for (i in tabs.indices) {
            tabs[i].webView.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        val tab = tabs[index]
        urlClickTarget.text = tab.url
        slimUrlBar.setText(tab.url)
        canGoBackState = tab.webView.canGoBack()
        currentScrollY = tab.currentScrollY
        val app = application as BrowserApp
        app.adBlockEngine.pageBlockCount.set(0) // Reset page ad count
        updateTabsBadge()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        webViewContainer.removeView(tab.webView)
        tab.webView.destroy()
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            createNewTab()
        } else {
            val next = if (activeTabIndex >= tabs.size) tabs.size - 1 else activeTabIndex
            switchTab(next)
        }
    }

    private fun showTabsOverlay() {
        showDashboard()
        dashboardScrollView.post {
            if (::tabsRowContainer.isInitialized) {
                dashboardScrollView.smoothScrollTo(0, tabsRowContainer.top)
                if (tabsRowContainer.childCount > 0) {
                    tabsRowContainer.getChildAt(0).requestFocus()
                }
            }
        }
    }

    private fun hideTabsOverlay() {
        // empty stub
    }

    private fun makeTabCard(
        index: Int,
        title: String,
        url: String,
        isActive: Boolean,
        onClose: () -> Unit,
        onClick: () -> Unit
    ): View {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams((148 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = (16 * dp).toInt()
            }
            clipChildren = false
            clipToPadding = false
        }

        val contentCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (125 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE))
                cornerRadius = 14 * dp
                if (isActive) {
                    setStroke((2 * dp).toInt(), resolveColor(ACCENT_CYAN))
                } else {
                    setStroke((1 * dp).toInt(), resolveColor("#2A2A3E"))
                }
            }
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())

            val imgView = ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt()).apply {
                    bottomMargin = (8 * dp).toInt()
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            val vectorRes = getSiteIcon(url)
            if (vectorRes != 0) {
                imgView.setImageResource(vectorRes)
            } else {
                loadFavicon(url, imgView)
            }
            addView(imgView)

            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 12f
                setTextColor(resolveColor(TEXT_PRIMARY))
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            })

            val domain = if (url.isNotBlank() && !url.startsWith("about:") && !url.startsWith("file:")) {
                Uri.parse(url).host ?: url
            } else "New Tab"
            addView(TextView(this@MainActivity).apply {
                text = if (isActive) "Active Now" else domain
                textSize = 9f
                setTextColor(resolveColor(if (isActive) ACCENT_CYAN else TEXT_MUTED))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })

            val normalBg = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE))
                cornerRadius = 14 * dp
                setStroke((1 * dp).toInt(), resolveColor(if (isActive) ACCENT_CYAN else "#2A2A3E"))
            }
            val focusedBg = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE_FOCUS))
                cornerRadius = 14 * dp
                setStroke((2 * dp).toInt(), resolveColor(ACCENT_GLOW))
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(120).start()
                background = if (hasFocus) focusedBg else normalBg
            }
            setOnClickListener { onClick() }
        }

        val closeBtn = TextView(this).apply {
            text = "Close Tab"
            textSize = 11f
            setTextColor(resolveColor(TEXT_MUTED))
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (28 * dp).toInt()).apply {
                topMargin = (8 * dp).toInt()
            }
            background = GradientDrawable().apply {
                setColor(resolveColor("#141424"))
                cornerRadius = 8 * dp
                setStroke((1 * dp).toInt(), resolveColor("#2A2A3E"))
            }

            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(120).start()
                if (hasFocus) {
                    setTextColor(resolveColor("#FF453A"))
                    background = GradientDrawable().apply {
                        setColor(resolveColor("#2D1414"))
                        cornerRadius = 8 * dp
                        setStroke((1.5 * dp).toInt(), resolveColor("#FF453A"))
                    }
                } else {
                    setTextColor(resolveColor(TEXT_MUTED))
                    background = GradientDrawable().apply {
                        setColor(resolveColor("#141424"))
                        cornerRadius = 8 * dp
                        setStroke((1 * dp).toInt(), resolveColor("#2A2A3E"))
                    }
                }
            }
            setOnClickListener { onClose() }
        }

        container.addView(contentCard)
        container.addView(closeBtn)
        return container
    }

    private fun makeNewTabCard(): View {
        val dp = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((148 * dp).toInt(), (125 * dp).toInt()).apply {
                rightMargin = (16 * dp).toInt()
            }
            background = GradientDrawable().apply {
                setColor(resolveColor(BG_SURFACE))
                cornerRadius = 14 * dp
                setStroke((1 * dp).toInt(), resolveColor("#2A2A3E"))
            }
            isFocusable = true
            isFocusableInTouchMode = true

            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.scaleX = 1.05f; view.scaleY = 1.05f
                    view.background = GradientDrawable().apply {
                        setColor(resolveColor(BG_SURFACE_FOCUS))
                        cornerRadius = 14 * dp
                        setStroke((2 * dp).toInt(), resolveColor(ACCENT_GLOW))
                    }
                } else {
                    view.scaleX = 1f; view.scaleY = 1f
                    view.background = GradientDrawable().apply {
                        setColor(resolveColor(BG_SURFACE))
                        cornerRadius = 14 * dp
                        setStroke((1 * dp).toInt(), resolveColor("#2A2A3E"))
                    }
                }
            }

            setOnClickListener {
                createNewTab()
                showBrowser()
            }
        }

        card.addView(TextView(this).apply {
            text = "+"
            textSize = 28f
            setTextColor(resolveColor(ACCENT_GLOW))
            gravity = Gravity.CENTER
        })

        card.addView(TextView(this).apply {
            text = "New Tab"
            textSize = 12f
            setTextColor(resolveColor(TEXT_PRIMARY))
            gravity = Gravity.CENTER
            setPadding(0, (4 * dp).toInt(), 0, 0)
        })

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams((148 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            clipChildren = false
            clipToPadding = false
        }
        container.addView(card)
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (28 * dp).toInt()).apply {
                topMargin = (8 * dp).toInt()
            }
        })

        return container
    }

    private fun refreshTabsShelf() {
        if (!::tabsRowContainer.isInitialized) return
        tabsRowContainer.removeAllViews()

        tabs.forEachIndexed { index, tab ->
            val isActive = (index == activeTabIndex)
            val card = makeTabCard(
                index = index,
                title = tab.title.ifBlank { "New Tab" },
                url = tab.url,
                isActive = isActive,
                onClose = {
                    closeTab(index)
                    refreshDashboard()
                },
                onClick = {
                    switchTab(index)
                    showBrowser()
                }
            )
            tabsRowContainer.addView(card)
        }

        val newTabCard = makeNewTabCard()
        tabsRowContainer.addView(newTabCard)
    }

    private fun updateTabsBadge() {
        if (::railTabsBadge.isInitialized) {
            railTabsBadge.text = tabs.size.toString()
            railTabsBadge.visibility = if (tabs.size > 0) View.VISIBLE else View.GONE
        }
        if (::slimTabsButton.isInitialized) {
            slimTabsButton.text = "⧉ TABS (${tabs.size})"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Key Event Dispatch (Virtual Cursor & D-pad management)
    // ─────────────────────────────────────────────────────────────────────────
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (slimUrlBar.hasFocus() || omniInput.hasFocus() || isTabsOverlayVisible || rootContainer.childCount > 2) {
            return super.dispatchKeyEvent(event)
        }

        // Toggle mouse/link mode using Play/Pause, Menu, or long press Center/OK keys
        val keyCode = event.keyCode
        if (!isDashboardActive) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.repeatCount == 0) {
                        longPressTriggered = false
                    } else if (event.repeatCount == 10) {
                        longPressTriggered = true
                        toggleCursorMode()
                        isDragging = false
                        return true
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MENU) {
                    toggleCursorMode()
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (longPressTriggered) {
                        longPressTriggered = false
                        isDragging = false
                        return true
                    }
                }
            }
        }

        // If Element picker is active, steer the cursor and highlight elements
        if (isElementPickerActive && !isDashboardActive) {
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val dp = resources.displayMetrics.density
                    val webX = cursorX / dp
                    val webY = cursorY / dp
                    activeWebView?.evaluateJavascript("window.selectElementAt($webX, $webY)", null)
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                var dx = 0f; var dy = 0f
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP    -> dy = -cursorSpeed
                    KeyEvent.KEYCODE_DPAD_DOWN  -> dy = cursorSpeed
                    KeyEvent.KEYCODE_DPAD_LEFT  -> dx = -cursorSpeed
                    KeyEvent.KEYCODE_DPAD_RIGHT -> dx = cursorSpeed
                    else -> return super.dispatchKeyEvent(event)
                }
                moveCursor(dx, dy)
                // Highlight active element under picker cursor
                val dp = resources.displayMetrics.density
                val webX = cursorX / dp
                val webY = cursorY / dp
                activeWebView?.evaluateJavascript("window.highlightElementAt($webX, $webY)", null)
                return true
            }
            return true
        }

        if (isCursorActive && !isDashboardActive) {
            val keyCode = event.keyCode
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) { scrollPage(-300); return true }
                if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) { scrollPage(300); return true }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !isDragging) {
                    isDragging = true; dragStartMillis = SystemClock.uptimeMillis()
                    dispatchTouch(MotionEvent.ACTION_DOWN, cursorX, cursorY, dragStartMillis)
                } else if (event.action == KeyEvent.ACTION_UP && isDragging) {
                    isDragging = false; dispatchTouch(MotionEvent.ACTION_UP, cursorX, cursorY, dragStartMillis)
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                val speed = cursorSpeed * (if (event.repeatCount > 0) 1.5f else 1.0f)
                var dx = 0f; var dy = 0f
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP    -> dy = -speed
                    KeyEvent.KEYCODE_DPAD_DOWN  -> dy = speed
                    KeyEvent.KEYCODE_DPAD_LEFT  -> dx = -speed
                    KeyEvent.KEYCODE_DPAD_RIGHT -> dx = speed
                    else -> return super.dispatchKeyEvent(event)
                }
                moveCursor(dx, dy)
                if (isDragging) dispatchTouch(MotionEvent.ACTION_MOVE, cursorX, cursorY, dragStartMillis)
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val containerW = webViewContainer.width.toFloat()
        val containerH = webViewContainer.height.toFloat()
        cursorX = (cursorX + dx).coerceIn(0f, containerW)
        val newY = cursorY + dy
        when {
            newY < 0 -> {
                cursorY = 0f
                if (!isDragging) {
                    if (!slimBarVisible) { slimBarVisible = true; slimTopBar.animate().translationY(0f).setDuration(200).start() }
                    urlBarContainer.requestFocus()
                }
            }
            newY > containerH -> { cursorY = containerH; if (!isDragging) scrollPage(100) }
            else -> cursorY = newY
        }
        cursorView.translationX = cursorX
        cursorView.translationY = cursorY
    }

    private fun scrollPage(dy: Int) {
        val js = "(function(){" +
                "var dy = $dy;" +
                "var x = $cursorX / window.devicePixelRatio;" +
                "var y = $cursorY / window.devicePixelRatio;" +
                "var el = document.elementFromPoint(x, y);" +
                "var scrolled = false;" +
                "while (el) {" +
                "  if (el.scrollHeight > el.clientHeight) {" +
                "    var style = window.getComputedStyle(el);" +
                "    if (style.overflowY === 'auto' || style.overflowY === 'scroll') {" +
                "      var prev = el.scrollTop;" +
                "      el.scrollBy(0, dy);" +
                "      if (el.scrollTop !== prev) {" +
                "        scrolled = true;" +
                "        break;" +
                "      }" +
                "    }" +
                "  }" +
                "  el = el.parentElement;" +
                "}" +
                "if (!scrolled) {" +
                "  window.scrollBy(0, dy);" +
                "}" +
                "})()"
        activeWebView?.evaluateJavascript(js, null)
    }

    private fun dispatchTouch(action: Int, x: Float, y: Float, downTime: Long) {
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        activeWebView?.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun toggleCursorMode() {
        if (isDashboardActive) return
        isCursorActive = !isCursorActive
        if (isCursorActive) {
            if (cursorX == 0f && cursorY == 0f) {
                cursorX = webViewContainer.width / 2f
                cursorY = webViewContainer.height / 2f
            }
            cursorView.translationX = cursorX
            cursorView.translationY = cursorY
            cursorView.visibility = View.VISIBLE
            activeWebView?.clearFocus()
            Toast.makeText(this, "Mouse mode active (Virtual Cursor)", Toast.LENGTH_SHORT).show()
        } else {
            cursorView.visibility = View.GONE
            activeWebView?.requestFocus()
            Toast.makeText(this, "Link navigation mode active (D-pad focus)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intent?.dataString
        if (!uri.isNullOrEmpty()) {
            activeWebView?.loadUrl(uri)
            slimUrlBar.setText(uri)
            showBrowser()
        }
    }
}
