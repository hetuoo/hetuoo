package com.hetuoo.phosphor

import android.app.Activity
import android.app.ActivityManager
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var sfx: Sfx
    private lateinit var launcherApps: LauncherApps
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var overlay: CrtOverlay
    private lateinit var tabBar: TabBar
    private lateinit var pages: List<View>
    private var tab = HOME
    private var menu: View? = null

    private lateinit var clock: ClockView
    private lateinit var favList: ListView
    private lateinit var favEmpty: TextView
    private lateinit var prompt: TextView
    private lateinit var appList: ListView
    private lateinit var appCount: TextView
    private lateinit var search: EditText
    private lateinit var homeRadio: TextView
    private lateinit var radioName: TextView
    private lateinit var radioStatus: TextView
    private lateinit var radioVu: VuView
    private lateinit var bandBar: TabBar
    private lateinit var bandHead: TextView
    private lateinit var stationList: ListView
    private var stations: List<Station> = emptyList()
    private var band = 0
    private val fetched = HashSet<Int>()
    private lateinit var scope: ScopeView
    private lateinit var gPower: GaugeView
    private lateinit var gMem: GaugeView
    private lateinit var gDisk: GaugeView
    private lateinit var sysInfo: TextView
    private lateinit var conf: LinearLayout

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var favs: List<AppEntry> = emptyList()
    private var loadGen = 0
    private var quiet = false
    private var resumed = false
    private var cursorOn = true

    private val favAdapter = AppAdapter({ favs }, pins = false)
    private val appAdapter = AppAdapter({ shown }, pins = true)

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Term.init(this, prefs.theme)
        if (Icons.cacheColor != Term.fg) {
            Icons.cache.clear()
            Icons.cacheColor = Term.fg
        }
        sfx = Sfx(this).apply {
            enabled = prefs.sound
            tabSound = prefs.tabSound
        }
        launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        buildUi()
        setContentView(root)
        setupWindow()

        launcherApps.registerCallback(appsChanged)
        loadApps()
        Radio.listener = ::showRadioState
        showRadioState()
        showBand(prefs.radioBand.coerceIn(0, Radio.BANDS.size - 1))

        val startTab = if (intent?.getBooleanExtra(RadioService.EXTRA_SHOW_RADIO, false) == true) RADIO else pendingTab
        pendingTab = HOME
        if (startTab != HOME) setTab(startTab, sound = false) else sfx.bootWhenReady()
    }

    override fun onStart() {
        super.onStart()
        overlay.powerOn()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        overlay.running = true
        scope.running = tab == STAT
        radioVu.running = tab == RADIO && Radio.state == Radio.ON_AIR
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ticker, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(ticker, f)
        clock.invalidate()
        ui.post(blink)
        if (tab == STAT) ui.post(statTick)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        overlay.running = false
        scope.running = false
        radioVu.running = false
        unregisterReceiver(ticker)
        ui.removeCallbacks(blink)
        ui.removeCallbacks(statTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        loadGen++
        if (Radio.listener == ::showRadioState) Radio.listener = null
        launcherApps.unregisterCallback(appsChanged)
        sfx.release()
    }

    /** The Home button while we're already showing: snap back to the home screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeMenu()
        val target = if (intent.getBooleanExtra(RadioService.EXTRA_SHOW_RADIO, false)) RADIO else HOME
        if (tab != target) setTab(target)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            menu != null -> {
                sfx.back()
                closeMenu()
            }
            tab != HOME -> setTab(HOME)
        }
        // A launcher never finishes on Back.
    }

    // ---------------------------------------------------------------- window

    private fun setupWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (Build.VERSION.SDK_INT >= 28) {
            // Let the picture run up behind a camera cutout; the content still keeps clear of it.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applyBars()
        // The CRT overlay covers the whole glass; the content keeps clear of bars and keyboard.
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or
                        WindowInsets.Type.displayCutout()
                )
                content.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                content.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
    }

    /** Hides (or restores) the system status bar. A swipe from the top edge peeks at it. */
    private fun applyBars() {
        val hide = prefs.hideStatusBar
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (hide) {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.statusBars())
            } else {
                c.show(WindowInsets.Type.statusBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                if (hide) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0
        }
    }

    // ---------------------------------------------------------------- UI construction

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Term.bg) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
        }
        tabBar = TabBar(this, arrayOf("HOME", "APPS", "RADIO", "STAT", "CONF"), 25f).apply {
            onSelect = { setTab(it) }
        }
        content.addView(tabBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        pages = listOf(buildHome(), buildApps(), buildRadio(), buildStat(), buildConf())
        val holder = FrameLayout(this)
        pages.forEachIndexed { i, p ->
            p.visibility = if (i == HOME) View.VISIBLE else View.GONE
            holder.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        content.addView(holder, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        overlay = CrtOverlay(this).apply {
            scanlines = prefs.scanlines
            flicker = prefs.flicker
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun heading(s: String) = Term.text(this, s, 22f, Term.shade(0.6f)).apply {
        setShadowLayer(0f, 0f, 0f, 0)
        setPadding(px(18f), px(10f), px(18f), px(4f))
    }

    private fun termList() = ListView(this).apply {
        divider = null
        selector = ColorDrawable(Color.TRANSPARENT)
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        cacheColorHint = Color.TRANSPARENT
    }

    private fun buildHome(): View = column().apply {
        clock = ClockView(context).apply {
            onZone = { zone ->
                sfx.select()
                haptic()
                when (zone) {
                    ClockView.TIME -> openClock()
                    ClockView.DATE -> open(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(
                        CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(),
                        System.currentTimeMillis())))
                    else -> open(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
                }
            }
        }
        addView(clock, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        homeRadio = Term.text(context, "", 22f).apply {
            setPadding(px(18f), px(8f), px(18f), px(8f))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            setOnClickListener { setTab(RADIO) }
        }
        addView(homeRadio, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(heading("// FAVORITES"))

        favList = termList().apply {
            adapter = favAdapter
            setOnItemClickListener { _, _, pos, _ -> launch(favs[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> favMenu(favs[pos]); true }
        }
        favEmpty = Term.text(context, "NOTHING PINNED YET.\nLONG-PRESS A PROGRAM UNDER [APPS]\nTO PIN IT HERE.", 22f, Term.shade(0.6f)).apply {
            setPadding(px(18f), px(12f), px(18f), 0)
            visibility = View.GONE
        }
        val box = FrameLayout(context)
        box.addView(favList, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        box.addView(favEmpty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(box, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        addView(Term.text(context, "^ SWIPE UP FOR ALL PROGRAMS ^", 20f, Term.shade(0.5f)).apply {
            setShadowLayer(0f, 0f, 0f, 0)
            gravity = Gravity.CENTER
            setPadding(0, px(6f), 0, px(8f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        prompt = Term.text(context, "> SEARCH_", 27f).apply {
            background = Term.border()
            setPadding(px(14f), px(12f), px(14f), px(12f))
            setOnClickListener { openSearch() }
        }
        addView(prompt, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(px(14f), 0, px(14f), px(14f))
        })
    }

    private fun buildApps(): View = column().apply {
        appCount = heading("// LOADING PROGRAMS...")
        addView(appCount)

        appList = termList().apply {
            adapter = appAdapter
            setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> appMenu(shown[pos]); true }
        }
        addView(appList, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        search = EditText(context).apply {
            background = null
            typeface = Term.face
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f)
            setTextColor(Term.fg)
            setHintTextColor(Term.shade(0.45f))
            setShadowLayer(Term.dp(5f), 0f, 0f, Term.glow())
            hint = "SEARCH PROGRAMS"
            isSingleLine = true
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            if (Build.VERSION.SDK_INT >= 29) {
                textCursorDrawable = GradientDrawable().apply {
                    setColor(Term.fg)
                    setSize(px(11f), px(24f))
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!quiet) sfx.tick()
                    applyFilter()
                }
            })
            setOnEditorActionListener { _, _, _ ->
                shown.firstOrNull()?.let { launch(it) }
                true
            }
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Term.border()
            setPadding(px(14f), px(12f), px(14f), px(12f))
            addView(Term.text(context, "> ", 27f))
            addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(px(14f), px(6f), px(14f), px(14f))
        })
    }

    private fun buildRadio(): View = column().apply {
        addView(heading("// RECEIVER"))
        radioName = Term.text(context, "", 27f).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        radioStatus = Term.text(context, "", 22f, Term.shade(0.6f)).apply {
            setShadowLayer(0f, 0f, 0f, 0)
            setPadding(0, px(4f), 0, px(10f))
        }
        radioVu = VuView(context)
        val panel = column().apply {
            background = Term.border()
            setPadding(px(14f), px(12f), px(14f), px(14f))
            addView(radioName)
            addView(radioStatus)
            addView(radioVu)
            // Tap the receiver to stop, or to retune the last station.
            setOnClickListener {
                haptic()
                val s = Radio.current
                when {
                    Radio.state == Radio.TUNING || Radio.state == Radio.ON_AIR -> {
                        sfx.back()
                        RadioService.stop(this@MainActivity)
                    }
                    s != null -> tune(s)
                }
            }
        }
        addView(panel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(px(14f), px(2f), px(14f), px(6f))
        })

        bandBar = TabBar(context, Radio.BANDS, 24f, 50f).apply {
            onSelect = {
                if (it != band) {
                    sfx.tick()
                    haptic()
                    showBand(it)
                }
            }
        }
        addView(bandBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bandHead = heading("")
        addView(bandHead)

        stationList = termList().apply {
            adapter = stationAdapter
            setOnItemClickListener { _, _, pos, _ ->
                val s = stations[pos]
                val live = Radio.state == Radio.TUNING || Radio.state == Radio.ON_AIR
                if (live && Radio.current?.url == s.url) {
                    sfx.back()
                    RadioService.stop(this@MainActivity)
                } else {
                    tune(s)
                }
            }
        }
        addView(stationList, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun buildStat(): View {
        val col = column()
        col.addView(heading("// SIGNAL"))
        scope = ScopeView(this)
        col.addView(scope)
        col.addView(heading("// SYSTEMS"))
        gPower = GaugeView(this, "POWER")
        gMem = GaugeView(this, "MEMORY")
        gDisk = GaugeView(this, "STORAGE")
        gPower.setOnClickListener {
            sfx.select()
            open(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        }
        gDisk.setOnClickListener {
            sfx.select()
            open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        }
        col.addView(gPower)
        col.addView(gMem)
        col.addView(gDisk)
        col.addView(heading("// UNIT"))
        sysInfo = Term.text(this, "", 24f).apply {
            setPadding(px(18f), px(4f), px(18f), px(18f))
            setLineSpacing(px(4f).toFloat(), 1f)
        }
        col.addView(sysInfo)
        return ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(col)
        }
    }

    private fun buildConf(): View {
        conf = column()
        fillConf()
        return ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(conf)
        }
    }

    private fun fillConf() {
        conf.removeAllViews()
        fun row(label: String, note: String, act: () -> Unit) {
            conf.addView(RowView(this).apply {
                bind(label, note)
                setOnClickListener {
                    sfx.tick()
                    haptic()
                    act()
                }
            })
        }
        fun onOff(b: Boolean) = if (b) "[ON]" else "[OFF]"

        conf.addView(heading("// DISPLAY"))
        row("PHOSPHOR", "[${Term.THEMES[prefs.theme].first}]") {
            prefs.theme = (prefs.theme + 1) % Term.THEMES.size
            pendingTab = CONF
            recreate()
        }
        row("SCANLINES", onOff(prefs.scanlines)) {
            prefs.scanlines = !prefs.scanlines
            overlay.scanlines = prefs.scanlines
            overlay.invalidate()
            fillConf()
        }
        row("FLICKER", onOff(prefs.flicker)) {
            prefs.flicker = !prefs.flicker
            overlay.flicker = prefs.flicker
            overlay.invalidate()
            fillConf()
        }
        row("STATUS BAR", if (prefs.hideStatusBar) "[HIDDEN]" else "[SHOWN]") {
            prefs.hideStatusBar = !prefs.hideStatusBar
            applyBars()
            fillConf()
        }
        row("PROGRAM ICONS", onOff(prefs.icons)) {
            prefs.icons = !prefs.icons
            favAdapter.notifyDataSetChanged()
            appAdapter.notifyDataSetChanged()
            fillConf()
        }
        conf.addView(heading("// FEEDBACK"))
        row("SOUND", onOff(prefs.sound)) {
            prefs.sound = !prefs.sound
            sfx.enabled = prefs.sound
            sfx.tab()
            fillConf()
        }
        row("SWITCH SOUND", "[${Sfx.TAB_SOUNDS[prefs.tabSound.coerceIn(0, Sfx.TAB_SOUNDS.size - 1)]}]") {
            prefs.tabSound = (prefs.tabSound + 1) % Sfx.TAB_SOUNDS.size
            sfx.tabSound = prefs.tabSound
            sfx.tab()
            fillConf()
        }
        row("HAPTICS", onOff(prefs.haptics)) {
            prefs.haptics = !prefs.haptics
            fillConf()
        }
        conf.addView(heading("// SYSTEM"))
        row("SET AS HOME SCREEN", ">") { open(Intent(Settings.ACTION_HOME_SETTINGS)) }
        row("DEVICE SETTINGS", ">") { open(Intent(Settings.ACTION_SETTINGS)) }
        conf.addView(heading("PHOSPHOR TERMINAL V1.1"))
    }

    // ---------------------------------------------------------------- navigation

    private fun setTab(i: Int, sound: Boolean = true) {
        if (i == tab || i !in pages.indices) return
        val dir = if (i > tab) 1f else -1f
        if (tab == APPS) {
            hideKeyboard()
            quiet = true
            search.setText("")
            quiet = false
            search.clearFocus()
            appList.setSelection(0)
        }
        pages[tab].visibility = View.GONE
        pages[i].apply {
            visibility = View.VISIBLE
            alpha = 0f
            translationX = dir * Term.dp(30f)
            animate().alpha(1f).translationX(0f).setDuration(150).start()
        }
        tab = i
        tabBar.selected = i
        overlay.glitch()
        if (sound) {
            sfx.tab()
            haptic()
        }
        scope.running = resumed && i == STAT
        radioVu.running = resumed && i == RADIO && Radio.state == Radio.ON_AIR
        ui.removeCallbacks(statTick)
        if (i == STAT) ui.post(statTick)
    }

    private fun openSearch() {
        setTab(APPS)
        search.requestFocus()
        search.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0)
        }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun haptic() {
        if (prefs.haptics) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || menu != null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val far = Term.dp(70f)
                if (abs(dx) > far && abs(dx) > abs(dy) * 1.5f) {
                    setTab(tab + if (dx < 0) 1 else -1)
                } else if (abs(dy) > far && abs(dy) > abs(dx) * 1.5f) {
                    when {
                        tab == HOME && dy < 0 && !favList.canScrollVertically(1) -> setTab(APPS)
                        tab == HOME && dy > 0 && !favList.canScrollVertically(-1) -> openNotifications()
                        tab == APPS && dy > 0 && !appList.canScrollVertically(-1) -> setTab(HOME)
                    }
                }
                return false
            }
        })
    }

    /** Watches every touch for swipes without stealing it from the lists underneath. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun openNotifications() {
        try {
            val bar = getSystemService("statusbar") ?: return
            bar.javaClass.getMethod("expandNotificationsPanel").invoke(bar)
        } catch (e: Exception) {
            // Not available on this device; the status bar still pulls down by hand.
        }
    }

    // ---------------------------------------------------------------- apps

    private val appsChanged = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String?, u: UserHandle?) = loadApps()
        override fun onPackageAdded(p: String?, u: UserHandle?) = loadApps()
        override fun onPackageChanged(p: String?, u: UserHandle?) = loadApps()
        override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = loadApps()
        override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = loadApps()
    }

    private fun loadApps() {
        val gen = ++loadGen
        val fg = Term.fg
        val size = px(40f)
        thread(name = "apps") {
            val infos = try {
                launcherApps.getActivityList(null, Process.myUserHandle())
            } catch (e: Exception) {
                emptyList()
            }.filter { it.componentName.packageName != packageName }

            val pairs = infos.map { it to AppEntry(it.componentName, it.label?.toString() ?: it.componentName.packageName) }
                .sortedBy { it.second.lower }
            val cached = synchronized(Icons.cache) { HashMap(Icons.cache) }
            pairs.forEach { (_, e) -> e.icon = cached[e.key] }

            runOnUiThread {
                if (gen != loadGen) return@runOnUiThread
                all = pairs.map { it.second }
                seedFavs()
                refreshFavs()
                applyFilter()
            }

            var pending = 0
            for ((info, e) in pairs) {
                if (gen != loadGen) return@thread
                if (e.icon != null) continue
                try {
                    val bmp = Icons.render(info.getIcon(0), size, fg)
                    e.icon = bmp
                    synchronized(Icons.cache) { Icons.cache[e.key] = bmp }
                } catch (err: Exception) {
                    // Leave the slot empty for an icon that won't draw.
                }
                if (++pending % 12 == 0) runOnUiThread(::redrawLists)
            }
            runOnUiThread(::redrawLists)
        }
    }

    private fun redrawLists() {
        favAdapter.notifyDataSetChanged()
        appAdapter.notifyDataSetChanged()
    }

    /** First run: pin whatever handles calls, texts, the web and the camera on this phone. */
    private fun seedFavs() {
        if (prefs.favsSeeded || all.isEmpty()) return
        val probes = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        val keys = ArrayList<String>()
        for (probe in probes) {
            val pkg = try {
                packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            } catch (e: Exception) {
                null
            } ?: continue
            val hit = all.firstOrNull { it.component.packageName == pkg } ?: continue
            if (hit.key !in keys) keys.add(hit.key)
        }
        prefs.favs = keys
        prefs.favsSeeded = true
    }

    private fun refreshFavs() {
        val byKey = all.associateBy { it.key }
        favs = prefs.favs.mapNotNull { byKey[it] }
        favAdapter.notifyDataSetChanged()
        favEmpty.visibility = if (favs.isEmpty() && all.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        shown = if (q.isEmpty()) all else {
            // Names that start with the query first, then names that merely contain it.
            val (starts, rest) = all.filter { it.lower.contains(q) }.partition { it.lower.startsWith(q) }
            starts + rest
        }
        appAdapter.notifyDataSetChanged()
        appCount.text = when {
            all.isEmpty() -> "// LOADING PROGRAMS..."
            q.isEmpty() -> "// ${all.size} PROGRAMS"
            else -> "// ${shown.size} OF ${all.size} MATCH"
        }
    }

    private fun launch(e: AppEntry) {
        sfx.select()
        haptic()
        try {
            launcherApps.startMainActivity(e.component, Process.myUserHandle(), null, null)
        } catch (err: Exception) {
            loadApps()
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // No handler on this device.
        }
    }

    private fun setPinned(e: AppEntry, pinned: Boolean) {
        prefs.favs = if (pinned) prefs.favs + e.key else prefs.favs - e.key
        refreshFavs()
        appAdapter.notifyDataSetChanged()
    }

    private fun moveFav(e: AppEntry, by: Int) {
        val keys = prefs.favs.toMutableList()
        val from = keys.indexOf(e.key)
        val to = from + by
        if (from < 0 || to !in keys.indices) return
        keys.add(to, keys.removeAt(from))
        prefs.favs = keys
        refreshFavs()
    }

    private fun appInfo(e: AppEntry) {
        try {
            launcherApps.startAppDetailsActivity(e.component, Process.myUserHandle(), null, null)
        } catch (err: Exception) {
            // Ignore.
        }
    }

    private fun appMenu(e: AppEntry) {
        val pinned = e.key in prefs.favs
        showMenu(e.label, listOf(
            (if (pinned) "UNPIN FROM HOME" else "PIN TO HOME") to { setPinned(e, !pinned) },
            "PROGRAM INFO" to { appInfo(e) },
            "UNINSTALL" to { open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.component.packageName}"))) },
        ))
    }

    private fun favMenu(e: AppEntry) {
        showMenu(e.label, listOf(
            "MOVE UP" to { moveFav(e, -1) },
            "MOVE DOWN" to { moveFav(e, 1) },
            "UNPIN FROM HOME" to { setPinned(e, false) },
            "PROGRAM INFO" to { appInfo(e) },
        ))
    }

    // ---------------------------------------------------------------- radio

    private fun openClock() {
        try {
            startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // No app answers the standard request; fall back to the usual clock apps by name.
        }
        for (pkg in listOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage")) {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            open(i)
            return
        }
    }

    private fun tune(s: Station) {
        sfx.select()
        haptic()
        // Android 13+ only shows the playback notification (with its Stop button) if allowed.
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotify &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.askedNotify = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        try {
            RadioService.play(this, s)
        } catch (e: Exception) {
            Radio.set(Radio.FAILED, s)
        }
    }

    private fun showRadioState() {
        val s = Radio.current
        val live = Radio.state == Radio.TUNING || Radio.state == Radio.ON_AIR
        radioName.text = s?.name ?: "NO STATION"
        radioStatus.text = when (Radio.state) {
            Radio.TUNING -> "TUNING..."
            Radio.ON_AIR -> "ON AIR - TAP TO STOP"
            Radio.FAILED -> "NO SIGNAL - TAP TO RETRY"
            else -> if (s == null) "PICK A STATION BELOW" else "STOPPED - TAP TO PLAY"
        }
        radioVu.running = resumed && tab == RADIO && Radio.state == Radio.ON_AIR
        homeRadio.visibility = if (live) View.VISIBLE else View.GONE
        homeRadio.text = (if (Radio.state == Radio.ON_AIR) "ON AIR: " else "TUNING: ") + (s?.name ?: "")
        stationAdapter.notifyDataSetChanged()
    }

    /** Shows a decade's stations: presets and the last saved list at once, then a fresh scan. */
    private fun showBand(i: Int) {
        band = i
        prefs.radioBand = i
        bandBar.selected = i
        setStations(i, Radio.decode(prefs.stations(i)))
        stationList.setSelection(0)
        if (i in fetched) return
        bandHead.text = "// ${Radio.BANDS[i]} - SCANNING..."
        thread(name = "radio") {
            val found = Radio.fetch(i)
            runOnUiThread {
                if (found != null && found.isNotEmpty()) {
                    fetched.add(i)
                    prefs.saveStations(i, Radio.encode(found))
                }
                if (band == i && !isDestroyed) {
                    setStations(i, found ?: Radio.decode(prefs.stations(i)))
                    if (found == null) bandHead.text = "// ${Radio.BANDS[i]} - ${stations.size} STATIONS (OFFLINE)"
                }
            }
        }
    }

    private fun setStations(i: Int, directory: List<Station>) {
        val presetUrls = Radio.presets[i].map { it.url }.toSet()
        stations = Radio.presets[i] + directory.filter { it.url !in presetUrls }
        bandHead.text = "// ${Radio.BANDS[i]} - ${stations.size} STATIONS"
        stationAdapter.notifyDataSetChanged()
    }

    private inner class StationAdapter : BaseAdapter() {
        override fun getCount() = stations.size
        override fun getItem(position: Int) = stations[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? RowView ?: RowView(this@MainActivity)
            val s = stations[position]
            val note = if (Radio.current?.url == s.url) when (Radio.state) {
                Radio.ON_AIR -> "[ON AIR]"
                Radio.TUNING -> "[TUNING]"
                Radio.FAILED -> "[NO SIGNAL]"
                else -> s.note
            } else s.note
            row.bind(s.name, note)
            return row
        }
    }

    private val stationAdapter = StationAdapter()

    // ---------------------------------------------------------------- in-terminal menu

    private fun showMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        closeMenu()
        hideKeyboard()
        sfx.tick()
        haptic()
        val box = column().apply {
            background = Term.border(2f, Term.fg, Term.bg)
            isClickable = true
            setPadding(px(4f), px(8f), px(4f), px(8f))
            addView(heading("// $title"))
            for ((label, act) in items) {
                addView(RowView(context).apply {
                    bind(label)
                    setOnClickListener {
                        sfx.tick()
                        closeMenu()
                        act()
                    }
                })
            }
            addView(RowView(context).apply {
                bind("CANCEL")
                setOnClickListener {
                    sfx.back()
                    closeMenu()
                }
            })
        }
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xD0000000.toInt())
            setOnClickListener {
                sfx.back()
                closeMenu()
            }
            addView(box, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
                setMargins(px(28f), 0, px(28f), 0)
            })
        }
        // Under the CRT overlay, so the scanlines run across the menu too.
        root.addView(scrim, root.indexOfChild(overlay), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        menu = scrim
    }

    private fun closeMenu() {
        menu?.let { (it.parent as? ViewGroup)?.removeView(it) }
        menu = null
    }

    // ---------------------------------------------------------------- live readouts

    private val ticker = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                clock.battery = if (level < 0 || scale <= 0) -1 else level * 100 / scale
                clock.charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            } else {
                clock.invalidate()
            }
        }
    }

    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            prompt.text = if (cursorOn) "> SEARCH_" else "> SEARCH"
            ui.postDelayed(this, 550)
        }
    }

    private val statTick = object : Runnable {
        override fun run() {
            if (!resumed || tab != STAT) return
            updateStat()
            ui.postDelayed(this, 1000)
        }
    }

    private fun updateStat() {
        val pct = clock.battery
        gPower.set(pct / 100f, if (pct < 0) "--" else "$pct%" + if (clock.charging) " CHARGING" else "")

        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val usedMem = mi.totalMem - mi.availMem
        gMem.set(usedMem.toFloat() / mi.totalMem.coerceAtLeast(1), "${gb(usedMem)} / ${gb(mi.totalMem)} GB")

        try {
            val fs = StatFs(Environment.getDataDirectory().path)
            val usedDisk = fs.totalBytes - fs.availableBytes
            gDisk.set(usedDisk.toFloat() / fs.totalBytes.coerceAtLeast(1), "${gb(usedDisk)} / ${gb(fs.totalBytes)} GB")
        } catch (e: Exception) {
            gDisk.set(0f, "--")
        }

        val s = SystemClock.elapsedRealtime() / 1000
        val up = "%dD %02d:%02d:%02d".format(s / 86400, s % 86400 / 3600, s % 3600 / 60, s % 60)
        sysInfo.text = listOf(
            "MODEL     ${Build.MANUFACTURER} ${Build.MODEL}".uppercase(),
            "SYSTEM    ANDROID ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "UPTIME    $up",
            "PROGRAMS  ${all.size}",
            "PINNED    ${favs.size}",
        ).joinToString("\n")
    }

    private fun gb(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0)

    private fun px(dp: Float) = Term.dp(dp).toInt()

    // ---------------------------------------------------------------- list adapter

    private inner class AppAdapter(val items: () -> List<AppEntry>, val pins: Boolean) : BaseAdapter() {
        override fun getCount() = items().size
        override fun getItem(position: Int) = items()[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? RowView ?: RowView(this@MainActivity)
            val e = items()[position]
            val note = if (pins && e.key in pinnedKeys()) "[PIN]" else ""
            row.bind(e.label, note, e.icon, prefs.icons)
            return row
        }

        private var keys: Set<String> = emptySet()
        private fun pinnedKeys() = keys
        override fun notifyDataSetChanged() {
            keys = favs.mapTo(HashSet()) { it.key }
            super.notifyDataSetChanged()
        }
    }

    companion object {
        const val HOME = 0
        const val APPS = 1
        const val RADIO = 2
        const val STAT = 3
        const val CONF = 4

        /** Which screen to reopen on after the activity rebuilds itself for a colour change. */
        var pendingTab = HOME
    }
}
