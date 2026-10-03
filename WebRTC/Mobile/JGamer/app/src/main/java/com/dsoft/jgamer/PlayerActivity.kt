package com.dsoft.jgamer

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.hardware.input.InputManager
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.dsoft.jgamer.model.DeviceInfo
import com.dsoft.jgamer.model.GameEntry
import com.dsoft.jgamer.model.GameRepository
import com.dsoft.jgamer.model.GameSystem
import com.dsoft.jgamer.model.Prefs
import com.dsoft.jgamer.ui.GamepadOverlay
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * The emulator screen. Hosts a LibretroDroid [GLRetroView] plus a touch
 * [GamepadOverlay]. Handles save/load state slots, per-game SRAM persistence,
 * auto-save on exit and auto-resume on launch. Everything is guarded so a bad
 * core/ROM shows a message instead of crashing.
 */
class PlayerActivity : AppCompatActivity(), GamepadOverlay.PadListener {

    private lateinit var repo: GameRepository
    private lateinit var prefs: Prefs
    private lateinit var entry: GameEntry
    private lateinit var system: GameSystem

    private var retroView: GLRetroView? = null
    private lateinit var overlay: GamepadOverlay
    // Built once and never re-parented: detaching a GLSurfaceView kills its GL
    // thread + EGL context, which is what blanked the game on rotation.
    private lateinit var root: FrameLayout
    private lateinit var gameArea: FrameLayout
    private var coreFileName = ""
    // Touch pad shown? Resolved from Auto / On / Off (Auto = hide on TV or with a gamepad).
    private var padVisible = true
    private val handler = Handler(Looper.getMainLooper())

    // Nostalgic filters (libretro shaders).
    private val shaders = listOf(ShaderConfig.Default, ShaderConfig.CRT, ShaderConfig.LCD, ShaderConfig.Sharp)
    private val filterNames = listOf("Filter: Off", "Filter: CRT scanlines", "Filter: LCD grid", "Filter: Sharp")
    private var filterIndex = 0

    // Game-screen size presets (game-area layout weight). Bigger = larger screen.
    private val screenWeights = floatArrayOf(0.9f, 1.3f, 1.8f, 2.6f)

    // 5 Game Boy colour palettes (gambatte internal palettes; DMG games).
    private val gbPaletteNames = listOf("Classic Green (DMG)", "Pocket Grey", "GBC Blue", "GBC Orange", "Grayscale")
    private val gbPaletteValues = listOf("GB - DMG", "GB - Pocket", "GBC - Blue", "GBC - Orange", "GBC - Grayscale")

    private val stateDir by lazy { File(filesDir, "states/${entry.id}").apply { runCatching { mkdirs() } } }
    private val sramFile by lazy { File(File(filesDir, "sram").apply { runCatching { mkdirs() } }, "${entry.id}.srm") }
    private val autoStateFile by lazy { File(stateDir, "auto.state") }
    // Snapshot taken in onSaveInstanceState, restored if the activity is ever recreated.
    private val recreateStateFile by lazy { File(stateDir, "recreate.state") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = GameRepository.get(this)
        prefs = Prefs(this)

        val id = intent.getStringExtra(EXTRA_GAME_ID).orEmpty()
        val e = repo.byId(id)
        if (e == null) { toast(getString(R.string.game_not_found)); finish(); return }
        entry = e
        system = entry.system
        filterIndex = prefs.getFilterIndex(system.id).coerceIn(0, shaders.size - 1)

        setFullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        padVisible = DeviceInfo.showPad(this, prefs.effectivePadMode(system.id))
        buildUi()
        startEmulator()
        maybeHintHiddenPad()

        prefs.lastGameId = entry.id
        repo.markPlayed(entry.id, System.currentTimeMillis())

        // Recreated (palette / engine switch / system kill): restore the exact
        // snapshot, but only into the same core — states don't cross engines.
        val restore = savedInstanceState?.takeIf {
            it.getBoolean(STATE_SNAPSHOT) && it.getString(STATE_CORE) == coreFileName
        } != null && recreateStateFile.exists()
        if (restore) {
            scheduleAutoLoad(0, recreateStateFile)
        } else if (intent.getBooleanExtra(EXTRA_AUTO_LOAD, false) && autoStateFile.exists()) {
            scheduleAutoLoad(0, autoStateFile)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val ok = runCatching {
            val st = retroView?.serializeState()
            if (st != null && st.isNotEmpty()) { recreateStateFile.writeBytes(st); true } else false
        }.getOrDefault(false)
        outState.putBoolean(STATE_SNAPSHOT, ok)
        outState.putString(STATE_CORE, coreFileName)
    }

    private fun buildUi() {
        overlay = GamepadOverlay(this).apply {
            listener = this@PlayerActivity
            vibrate = prefs.vibrate
            configure(system, alpha = 1f,
                scale = prefs.getOverlayScale(system.id),
                positions = prefs.getOverlayPositions(system.id),
                joystick = prefs.getDpadJoystick(system.id),
                themeIndex = prefs.controlTheme)
        }
        gameArea = FrameLayout(this).apply { id = CONTAINER_ID; setBackgroundColor(Color.BLACK) }
        // Size from the real root once laid out (rotation, cutouts, nav bar);
        // display metrics are only the first guess.
        root = object : FrameLayout(this) {
            override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
                super.onSizeChanged(w, h, ow, oh)
                post { relayout(w, h) }
            }
        }.apply { setBackgroundColor(Color.BLACK) }
        root.addView(gameArea, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        val dm = resources.displayMetrics
        relayout(dm.widthPixels, dm.heightPixels)
    }

    private fun isLandscape() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Portrait: game on top, opaque control panel below.
     * Landscape: 3 columns — the game fills the FULL height in the centre and
     * the controls live in the left/right side panels, so nothing covers the
     * picture. Only LayoutParams change; the emulator view stays attached, so
     * rotating never restarts or blanks the game.
     */
    private fun relayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val landscape = isLandscape()
        overlay.landscapeMode = landscape
        overlay.visibility = if (padVisible) View.VISIBLE else View.GONE
        val gameLp = gameArea.layoutParams as FrameLayout.LayoutParams
        val padLp = overlay.layoutParams as FrameLayout.LayoutParams
        // Region the game gets; the display mode then sizes the picture inside it.
        var regionW = w; var regionH = h
        if (!padVisible) {
            // No touch pad: the game owns the whole screen (no panels, no split).
            gameLp.width = FrameLayout.LayoutParams.MATCH_PARENT
            gameLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            gameLp.gravity = Gravity.CENTER
            padLp.height = FrameLayout.LayoutParams.MATCH_PARENT; padLp.topMargin = 0
        } else if (landscape) {
            val gw = landscapeGameWidth(w, h)
            gameLp.width = gw; gameLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            gameLp.gravity = Gravity.CENTER
            padLp.height = FrameLayout.LayoutParams.MATCH_PARENT; padLp.topMargin = 0
            overlay.landscapeSide = (w - gw) / 2f
            regionW = gw
        } else {
            // Same split the old weighted LinearLayout gave: game weight vs pad weight 1.
            val weight = screenWeights[prefs.screenSize.coerceIn(0, screenWeights.size - 1)]
            val gh = (h * weight / (weight + 1f)).toInt()
            gameLp.width = FrameLayout.LayoutParams.MATCH_PARENT; gameLp.height = gh
            gameLp.gravity = Gravity.TOP
            padLp.height = h - gh; padLp.topMargin = gh
            overlay.landscapeSide = 0f
            regionH = gh
        }
        padLp.width = FrameLayout.LayoutParams.MATCH_PARENT; padLp.gravity = Gravity.TOP
        gameArea.layoutParams = gameLp
        overlay.layoutParams = padLp
        fitPicture(regionW, regionH)
    }

    private var fixedBuffer = false

    /**
     * Size the emulator view inside its region by the display mode:
     *  Fit     — fill the region; LibretroDroid letterboxes to the game aspect.
     *  Fill    — fill the region and render into a buffer at the game aspect,
     *            which the compositor stretches to the region (no bars).
     *  Integer — largest whole multiple of the native resolution (Fit if unknown).
     * Live: only LayoutParams / buffer size change, the core keeps running.
     */
    private fun fitPicture(rw: Int, rh: Int) {
        val rv = retroView ?: return
        val lp = rv.layoutParams as? FrameLayout.LayoutParams ?: return
        if (rw <= 0 || rh <= 0) return
        var vw = FrameLayout.LayoutParams.MATCH_PARENT
        var vh = FrameLayout.LayoutParams.MATCH_PARENT
        val mode = prefs.displayMode
        if (mode == Prefs.DISPLAY_INTEGER && system.nativeHeight > 0) {
            val nh = system.nativeHeight
            val nw = nh * system.aspect
            val k = minOf(rw / nw, rh.toFloat() / nh).toInt()
            if (k >= 1) { vw = (nw * k).toInt(); vh = nh * k }
        }
        if (lp.width != vw || lp.height != vh || lp.gravity != Gravity.CENTER) {
            lp.width = vw; lp.height = vh; lp.gravity = Gravity.CENTER
            rv.layoutParams = lp
        }
        runCatching {
            if (mode == Prefs.DISPLAY_FILL) {
                rv.holder.setFixedSize((rh * system.aspect).toInt().coerceAtLeast(1), rh)
                fixedBuffer = true
            } else if (fixedBuffer) {
                rv.holder.setSizeFromLayout()
                fixedBuffer = false
            }
        }
    }

    /** Re-resolve Auto / On / Off (setting changed, or a gamepad came / went). */
    private fun refreshPad() {
        val show = DeviceInfo.showPad(this, prefs.effectivePadMode(system.id))
        if (show == padVisible) return
        padVisible = show
        relayout(root.width, root.height)
        maybeHintHiddenPad()
    }

    private fun maybeHintHiddenPad() {
        if (padVisible || prefs.padHiddenHintShown) return
        prefs.padHiddenHintShown = true
        Toast.makeText(this, R.string.pad_hidden_hint, Toast.LENGTH_LONG).show()
    }

    // Auto mode follows controllers being plugged in / out.
    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshPad()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshPad()
        override fun onInputDeviceChanged(deviceId: Int) {}
    }

    /** Centre column width: full-height picture at the system's aspect, but
     *  always leave each side panel at least [MIN_SIDE] of the width. */
    private fun landscapeGameWidth(w: Int, h: Int): Int =
        minOf(h * system.aspect, w * (1f - 2f * MIN_SIDE)).toInt().coerceAtLeast(1)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setFullscreen()
        // First guess from the new metrics; root.onSizeChanged refines it.
        val dm = resources.displayMetrics
        relayout(dm.widthPixels, dm.heightPixels)
    }

    private fun startEmulator() {
        // Core selection order: a user-pinned engine wins; otherwise walk the
        // system's core list by [coreAttempt] so arcade ROMs can auto-fall back
        // through FBNeo → MAME variants until one boots the romset.
        val attempt = intent.getIntExtra(EXTRA_CORE_ATTEMPT, 0)
        coreFileName = prefs.getGameCore(entry.id)
            ?: system.cores.getOrElse(attempt) { system.cores.first() }.file
        val core = File(applicationInfo.nativeLibraryDir, coreFileName)
        val data = GLRetroViewData(this).apply {
            coreFilePath = if (core.exists()) core.absolutePath else coreFileName
            gameFilePath = entry.localPath
            systemDirectory = File(filesDir, "system").apply { runCatching { mkdirs() } }.absolutePath
            savesDirectory = File(filesDir, "saves").apply { runCatching { mkdirs() } }.absolutePath
            saveRAMState = runCatching { if (sramFile.exists()) sramFile.readBytes() else null }.getOrNull()
            shader = shaders[filterIndex]
            if (system == GameSystem.GB) {
                val pal = gbPaletteValues[prefs.gbPalette.coerceIn(0, gbPaletteValues.size - 1)]
                variables = arrayOf(
                    Variable("gambatte_gb_colorization", "internal"),
                    Variable("gambatte_gb_internal_palette", pal)
                )
            }
        }
        val view = runCatching { GLRetroView(this, data) }.getOrElse {
            Log.e(TAG, "GLRetroView init failed", it)
            toast(getString(R.string.core_failed))
            finish(); return
        }
        retroView = view
        // We route physical-controller input ourselves (custom port assignment +
        // remap), so keep the surface from consuming key/motion events.
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        lifecycle.addObserver(view)
        gameArea.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })
        root.post { relayout(root.width, root.height) }   // apply the display mode

        // Watch for load failures so we can offer another engine (arcade romsets
        // are picky about which core boots them).
        lifecycleScope.launch {
            runCatching { view.getGLRetroErrors().collect { code -> onCoreError(code) } }
        }
    }

    private var errorHandled = false
    private fun onCoreError(code: Int) {
        if (errorHandled) return
        val loadError = code == GLRetroView.ERROR_LOAD_GAME ||
            code == GLRetroView.ERROR_LOAD_LIBRARY ||
            code == GLRetroView.ERROR_GL_NOT_COMPATIBLE
        if (!loadError) return
        errorHandled = true
        runOnUiThread {
            val pinned = prefs.getGameCore(entry.id)
            val attempt = intent.getIntExtra(EXTRA_CORE_ATTEMPT, 0)
            // Auto-cycle engines when the user hasn't pinned one and more remain.
            if (pinned == null && attempt < system.cores.size - 1) {
                val next = system.cores[attempt + 1]
                toast(getString(R.string.trying_engine, next.label))
                startActivity(intent(this, entry.id, autoLoad = false, coreAttempt = attempt + 1))
                finish()
                return@runOnUiThread
            }
            // All engines tried (or a pinned engine failed): explain clearly.
            if (system.cores.size > 1) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.load_failed_title))
                    .setMessage(getString(R.string.load_failed_all,
                        system.cores.joinToString(", ") { it.label }))
                    .setPositiveButton(R.string.menu_switch_engine) { _, _ -> switchEngineDialog() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.load_failed_title))
                    .setMessage(getString(R.string.core_failed))
                    .setPositiveButton(R.string.menu_quit) { _, _ -> finish() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    // ---- Input from overlay --------------------------------------------------

    /** Port the on-screen pad drives (GBA has no shared-screen 2P, so always P1). */
    private fun touchPort(): Int = if (system == GameSystem.GBA) 0 else prefs.touchPlayer

    override fun onDpad(x: Int, y: Int) {
        runCatching { retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x.toFloat(), y.toFloat(), touchPort()) }
    }

    override fun onButton(keyCode: Int, pressed: Boolean) {
        val action = if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        runCatching { retroView?.sendKeyEvent(action, keyCode, touchPort()) }
    }

    override fun onMenu() = showMenu()

    override fun onQuickSave() { doSaveState(File(stateDir, "slot0.state")) }
    override fun onQuickLoad() { doLoadState(File(stateDir, "slot0.state")) }
    override fun onFastForward(active: Boolean) {
        runCatching { retroView?.frameSpeed = if (active) 2 else 1 }
    }
    override fun onFilterCycle() {
        filterIndex = (filterIndex + 1) % shaders.size
        runCatching { retroView?.shader = shaders[filterIndex] }
        prefs.setFilterIndex(system.id, filterIndex)
        toast(filterNames[filterIndex])
    }
    override fun onAnalog(x: Float, y: Float) {
        runCatching { retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y, touchPort()) }
    }
    override fun onLayoutChanged(token: String, xFraction: Float, yFraction: Float) {
        // Landscape uses a fixed computed split layout; don't let dragging there
        // overwrite the user's portrait positions.
        if (isLandscape()) return
        prefs.setOverlayPosition(system.id, token, xFraction, yFraction)
    }
    override fun onEditFinished() { toast(getString(R.string.layout_saved)) }

    // ---- Menu / save / load --------------------------------------------------

    private fun showMenu() {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun item(text: String, action: () -> Unit) { labels.add(text); actions.add(action) }

        item(getString(R.string.menu_resume)) {}
        item(getString(R.string.menu_save_state)) { slotDialog(save = true) }
        item(getString(R.string.menu_load_state)) { slotDialog(save = false) }
        item(getString(R.string.menu_reset)) { runCatching { retroView?.reset() } }
        if (system.cores.size > 1) item(getString(R.string.menu_switch_engine)) { switchEngineDialog() }
        if (system == GameSystem.GB) item(getString(R.string.menu_palette)) { paletteDialog() }
        item(getString(R.string.menu_controllers)) { controllersDialog() }
        item(getString(R.string.menu_filter)) { filterDialog() }
        item(getString(R.string.menu_theme)) { themeDialog() }
        item(getString(R.string.menu_screen_size)) { screenSizeDialog() }
        item(getString(R.string.menu_display, displayNames()[prefs.displayMode.coerceIn(0, 2)])) { displayDialog() }
        item(getString(R.string.menu_pad_mode, padModeNames()[prefs.effectivePadMode(system.id).coerceIn(0, 2)])) { padModeDialog() }
        item(getString(R.string.menu_controls_size)) { sizeDialog() }
        item(getString(if (prefs.getDpadJoystick(system.id)) R.string.menu_use_dpad else R.string.menu_use_joystick)) {
            val on = !prefs.getDpadJoystick(system.id)
            prefs.setDpadJoystick(system.id, on); overlay.setJoystick(on)
        }
        item(getString(if (overlay.editMode) R.string.menu_edit_done else R.string.menu_edit_layout)) {
            overlay.editMode = !overlay.editMode
        }
        item(getString(R.string.menu_reset_layout)) {
            prefs.resetOverlay(system.id)
            overlay.configure(system, 1f, 1f, emptyMap(), prefs.getDpadJoystick(system.id), prefs.controlTheme)
        }
        item(getString(R.string.menu_quit)) { finish() }

        AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    private fun paletteDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_palette)
            .setSingleChoiceItems(gbPaletteNames.toTypedArray(), prefs.gbPalette.coerceIn(0, gbPaletteNames.size - 1)) { d, which ->
                prefs.gbPalette = which
                d.dismiss()
                recreate()   // reload with the new palette variable (auto-save keeps progress)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Pick the screen filter directly (the 🎨 pad button only cycles through them). */
    private fun filterDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_filter)
            .setSingleChoiceItems(filterNames.map { it.removePrefix("Filter: ") }.toTypedArray(), filterIndex) { d, which ->
                filterIndex = which
                runCatching { retroView?.shader = shaders[filterIndex] }
                prefs.setFilterIndex(system.id, filterIndex)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun themeDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_theme)
            .setSingleChoiceItems(com.dsoft.jgamer.ui.ControlThemes.names, prefs.controlTheme.coerceIn(0, com.dsoft.jgamer.ui.ControlThemes.list.size - 1)) { d, which ->
                prefs.controlTheme = which
                overlay.setTheme(which)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun screenSizeDialog() {
        val names = arrayOf(
            getString(R.string.size_small), getString(R.string.size_medium),
            getString(R.string.size_large), getString(R.string.size_xl)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_screen_size)
            .setSingleChoiceItems(names, prefs.screenSize.coerceIn(0, names.size - 1)) { d, which ->
                prefs.screenSize = which
                d.dismiss()
                relayout(root.width, root.height)   // no restart needed
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun displayNames() = arrayOf(
        getString(R.string.display_fit), getString(R.string.display_fill), getString(R.string.display_integer))

    private fun padModeNames() = arrayOf(
        getString(R.string.pad_auto), getString(R.string.pad_on), getString(R.string.pad_off))

    private fun displayDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.opt_display)
            .setSingleChoiceItems(displayNames(), prefs.displayMode.coerceIn(0, 2)) { d, which ->
                prefs.displayMode = which
                d.dismiss()
                relayout(root.width, root.height)   // live, no restart
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Per-system override: first row = follow the global setting. */
    private fun padModeDialog() {
        val global = padModeNames()[prefs.padMode.coerceIn(0, 2)]
        val names = arrayOf(getString(R.string.pad_use_global, global)) + padModeNames()
        val checked = prefs.getPadOverride(system.id).let { if (it < 0) 0 else it + 1 }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pad_mode_for, system.displayName))
            .setSingleChoiceItems(names, checked) { d, which ->
                prefs.setPadOverride(system.id, which - 1)
                d.dismiss()
                refreshPad()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun switchEngineDialog() {
        val cores = system.cores
        val current = prefs.getGameCore(entry.id) ?: system.coreFile
        val checked = cores.indexOfFirst { it.file == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_switch_engine)
            .setSingleChoiceItems(cores.map { it.label }.toTypedArray(), checked) { d, which ->
                prefs.setGameCore(entry.id, cores[which].file)
                d.dismiss()
                errorHandled = false
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sizeDialog() {
        val seek = android.widget.SeekBar(this).apply {
            max = 120 // 60..180 %
            progress = ((prefs.getOverlayScale(system.id) * 100).toInt() - 60).coerceIn(0, max)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) {
                    // Qualify: bare `overlay` here would resolve to SeekBar.getOverlay().
                    this@PlayerActivity.overlay.updateScale((p + 60) / 100f)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_controls_size)
            .setView(seek)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.setOverlayScale(system.id, (seek.progress + 60) / 100f)
            }
            .show()
    }

    private fun slotDialog(save: Boolean) {
        val slots = arrayOf("Slot 1", "Slot 2", "Slot 3")
        AlertDialog.Builder(this)
            .setTitle(if (save) R.string.menu_save_state else R.string.menu_load_state)
            .setItems(slots) { _, which ->
                val f = File(stateDir, "slot${which + 1}.state")
                if (save) doSaveState(f) else doLoadState(f)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doSaveState(file: File): Boolean = runCatching {
        val bytes = retroView?.serializeState() ?: return false
        if (bytes.isEmpty()) return false
        file.writeBytes(bytes)
        toast(getString(R.string.state_saved))
        true
    }.getOrElse { toast(getString(R.string.state_failed)); false }

    private fun doLoadState(file: File): Boolean = runCatching {
        if (!file.exists()) { toast(getString(R.string.state_none)); return false }
        val ok = retroView?.unserializeState(file.readBytes()) ?: false
        toast(getString(if (ok) R.string.state_loaded else R.string.state_failed))
        ok
    }.getOrElse { toast(getString(R.string.state_failed)); false }

    /** Retry loading the auto-state until the core is ready (a few attempts). */
    private fun scheduleAutoLoad(attempt: Int, file: File) {
        if (attempt > 12) return
        handler.postDelayed({
            val ok = runCatching { retroView?.unserializeState(file.readBytes()) ?: false }.getOrDefault(false)
            if (!ok) scheduleAutoLoad(attempt + 1, file)
        }, 350)
    }

    // ---- Lifecycle -----------------------------------------------------------

    override fun onPause() {
        // Persist SRAM and (optionally) an auto save-state for resume-on-launch.
        runCatching {
            val sram = retroView?.serializeSRAM()
            if (sram != null && sram.isNotEmpty()) sramFile.writeBytes(sram)
        }
        if (prefs.autoSaveState) runCatching {
            val st = retroView?.serializeState()
            if (st != null && st.isNotEmpty()) autoStateFile.writeBytes(st)
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        setFullscreen()
        runCatching { (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputListener, handler) }
        refreshPad()
    }

    override fun onStop() {
        runCatching { (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputListener) }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setFullscreen()
    }

    // ---- Physical controllers (local multiplayer) ---------------------------

    private var captureIdentify = false                 // "press A to set P1" mode
    private var remapDevice = -1                         // device being remapped
    private val remapTargets = listOf(
        KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B",
        KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
        KeyEvent.KEYCODE_BUTTON_L1 to "L", KeyEvent.KEYCODE_BUTTON_R1 to "R",
        KeyEvent.KEYCODE_BUTTON_START to "START", KeyEvent.KEYCODE_BUTTON_SELECT to "SELECT"
    )
    private var remapIndex = -1                          // >=0 while remapping
    private val heldMeta = HashSet<Int>()                // Start / Select held (menu combo)

    private fun isPad(event: InputEvent?): Boolean {
        val src = event?.source ?: 0
        return src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private fun portForDevice(deviceId: Int): Int {
        if (system == GameSystem.GBA) return 0           // GBA link needs 2 screens; keep 1P
        val p1 = prefs.deviceP1
        return if (p1 < 0 || deviceId == p1) 0 else 1
    }

    private fun routePadKey(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        if (!isPad(event)) return false
        val devId = event.deviceId

        if (captureIdentify) {
            if (down) { prefs.deviceP1 = devId; captureIdentify = false; toast(getString(R.string.ctrl_p1_set)) }
            return true
        }
        if (remapIndex in remapTargets.indices) {
            if (down && (remapDevice < 0 || devId == remapDevice)) {
                remapDevice = devId
                prefs.setRemap(devId, keyCode, remapTargets[remapIndex].first)
                remapIndex++
                if (remapIndex in remapTargets.indices) toast(getString(R.string.ctrl_press_for, remapTargets[remapIndex].second))
                else { remapIndex = -1; toast(getString(R.string.ctrl_remap_done)) }
            }
            return true
        }

        val mapped = prefs.getRemap(devId)[keyCode] ?: keyCode
        if (mapped == KeyEvent.KEYCODE_BUTTON_MODE) { if (down) showMenu(); return true }
        if (!padVisible && (mapped == KeyEvent.KEYCODE_BUTTON_START || mapped == KeyEvent.KEYCODE_BUTTON_SELECT)) {
            if (down) heldMeta.add(mapped) else heldMeta.remove(mapped)
            if (down && heldMeta.size == 2) {
                // Release both in the core so the game doesn't see them stuck.
                heldMeta.forEach { runCatching { retroView?.sendKeyEvent(KeyEvent.ACTION_UP, it, portForDevice(devId)) } }
                heldMeta.clear()
                showMenu()
                return true
            }
        }
        if (!isForwardableButton(mapped)) return false
        runCatching { retroView?.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, mapped, portForDevice(devId)) }
        return true
    }

    private fun isForwardableButton(code: Int): Boolean =
        KeyEvent.isGamepadButton(code) ||
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isPad(event) && retroView != null) {
            val port = portForDevice(event.deviceId)
            runCatching {
                retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y), port)
                retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), port)
                retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ), port)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * TV remote (not a gamepad) while the touch pad is hidden: D-pad steers,
     * OK = A, so simple games are playable; Back / Menu still open the menu.
     */
    private fun routeRemoteKey(keyCode: Int, down: Boolean): Boolean {
        if (padVisible) return false
        val retro = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> keyCode
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> KeyEvent.KEYCODE_BUTTON_A
            else -> return false
        }
        runCatching { retroView?.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, retro, 0) }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && routePadKey(keyCode, event, true)) return true
        if (routeRemoteKey(keyCode, true)) return true
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (remapIndex >= 0) { remapIndex = -1; toast(getString(R.string.ctrl_remap_cancel)); return true }
            if (overlay.editMode) { overlay.editMode = false; toast(getString(R.string.layout_saved)); return true }
            showMenu(); return true
        }
        // TV remote / keyboard Menu key.
        if (keyCode == KeyEvent.KEYCODE_MENU) { showMenu(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && routePadKey(keyCode, event, false)) return true
        if (routeRemoteKey(keyCode, false)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun connectedPads(): List<Int> = DeviceInfo.connectedPads()

    private fun controllersDialog() {
        val pads = connectedPads()
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun item(t: String, a: () -> Unit) { labels.add(t); actions.add(a) }

        item(getString(if (prefs.touchPlayer == 0) R.string.ctrl_touch_p1 else R.string.ctrl_touch_p2)) {
            prefs.touchPlayer = if (prefs.touchPlayer == 0) 1 else 0
        }
        item(getString(R.string.ctrl_identify_p1)) {
            captureIdentify = true; toast(getString(R.string.ctrl_press_a_p1))
        }
        item(getString(R.string.ctrl_remap, "P1")) { startRemap(prefs.deviceP1.takeIf { it >= 0 } ?: pads.firstOrNull() ?: -1) }
        item(getString(R.string.ctrl_remap, "P2")) { startRemap(pads.firstOrNull { it != prefs.deviceP1 } ?: -1) }
        item(getString(R.string.ctrl_reset)) { prefs.deviceP1 = -1; prefs.resetControllers(); toast(getString(R.string.ctrl_reset_done)) }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_controllers) + "  (" + getString(R.string.ctrl_connected, pads.size) + ")")
            .setItems(labels.toTypedArray()) { _, w -> actions[w].invoke() }
            .show()
    }

    private fun startRemap(deviceId: Int) {
        if (deviceId < 0) { toast(getString(R.string.ctrl_no_pad)); return }
        remapDevice = deviceId
        prefs.clearRemap(deviceId)
        remapIndex = 0
        toast(getString(R.string.ctrl_press_for, remapTargets[0].second))
    }

    private fun setFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "PlayerActivity"
        /** Min width fraction of each landscape side panel. */
        private const val MIN_SIDE = 0.17f
        const val EXTRA_GAME_ID = "game_id"
        const val EXTRA_AUTO_LOAD = "auto_load"
        const val EXTRA_CORE_ATTEMPT = "core_attempt"
        private const val STATE_SNAPSHOT = "snapshot"
        private const val STATE_CORE = "core"
        private val CONTAINER_ID = View.generateViewId()

        fun intent(ctx: Context, gameId: String, autoLoad: Boolean = false, coreAttempt: Int = 0) =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_GAME_ID, gameId)
                .putExtra(EXTRA_AUTO_LOAD, autoLoad)
                .putExtra(EXTRA_CORE_ATTEMPT, coreAttempt)
    }
}
