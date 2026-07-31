package com.dsoft.jgamer

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
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
        buildUi()
        startEmulator()

        prefs.lastGameId = entry.id
        repo.markPlayed(entry.id, System.currentTimeMillis())

        if (intent.getBooleanExtra(EXTRA_AUTO_LOAD, false) && autoStateFile.exists()) {
            scheduleAutoLoad(0)
        }
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
        applyLayout()
    }

    private fun isLandscape() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Portrait: game on top, opaque control panel below (unchanged).
     * Landscape: game CENTRED with side + bottom margins, and a transparent
     * overlay on top whose controls sit in the left/right columns so the picture
     * stays in the middle and nothing is cramped. Reuses the existing overlay and
     * emulator view, so rotating never restarts the game.
     */
    private fun applyLayout() {
        val landscape = isLandscape()
        overlay.landscapeMode = landscape

        // Detach the reusable views from any previous root.
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        val rv = retroView
        (rv?.parent as? ViewGroup)?.removeView(rv)

        val gameArea = FrameLayout(this).apply { id = CONTAINER_ID; setBackgroundColor(Color.BLACK) }

        if (landscape) {
            val dm = resources.displayMetrics
            val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = (dm.widthPixels * 0.24f).toInt()
                rightMargin = leftMargin
                topMargin = (dm.heightPixels * 0.03f).toInt()
                bottomMargin = (dm.heightPixels * 0.14f).toInt()
            }
            root.addView(gameArea, lp)
            root.addView(overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setContentView(root)
        } else {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
            }
            val gameWeight = screenWeights[prefs.screenSize.coerceIn(0, screenWeights.size - 1)]
            root.addView(gameArea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, gameWeight))
            root.addView(overlay, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            setContentView(root)
        }

        // Re-attach the existing emulator view into the fresh container.
        if (rv != null) {
            gameArea.addView(rv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER })
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setFullscreen()
        applyLayout()   // re-arrange controls for the new orientation, keep the game running
    }

    private fun startEmulator() {
        // Core selection order: a user-pinned engine wins; otherwise walk the
        // system's core list by [coreAttempt] so arcade ROMs can auto-fall back
        // through FBNeo → MAME variants until one boots the romset.
        val attempt = intent.getIntExtra(EXTRA_CORE_ATTEMPT, 0)
        val coreFileName = prefs.getGameCore(entry.id)
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
        val container = findViewById<FrameLayout>(CONTAINER_ID)
        container.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

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
                toast(getString(R.string.core_failed))
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
        item(getString(R.string.menu_theme)) { themeDialog() }
        item(getString(R.string.menu_screen_size)) { screenSizeDialog() }
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
                recreate()
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
    private fun scheduleAutoLoad(attempt: Int) {
        if (attempt > 12) return
        handler.postDelayed({
            val ok = runCatching { retroView?.unserializeState(autoStateFile.readBytes()) ?: false }.getOrDefault(false)
            if (!ok) scheduleAutoLoad(attempt + 1)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && routePadKey(keyCode, event, true)) return true
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (remapIndex >= 0) { remapIndex = -1; toast(getString(R.string.ctrl_remap_cancel)); return true }
            if (overlay.editMode) { overlay.editMode = false; toast(getString(R.string.layout_saved)); return true }
            showMenu(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && routePadKey(keyCode, event, false)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun connectedPads(): List<Int> = runCatching {
        InputDevice.getDeviceIds().filter {
            val d = InputDevice.getDevice(it)
            val s = d?.sources ?: 0
            s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        }
    }.getOrDefault(emptyList())

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
        const val EXTRA_GAME_ID = "game_id"
        const val EXTRA_AUTO_LOAD = "auto_load"
        const val EXTRA_CORE_ATTEMPT = "core_attempt"
        private val CONTAINER_ID = View.generateViewId()

        fun intent(ctx: Context, gameId: String, autoLoad: Boolean = false, coreAttempt: Int = 0) =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_GAME_ID, gameId)
                .putExtra(EXTRA_AUTO_LOAD, autoLoad)
                .putExtra(EXTRA_CORE_ATTEMPT, coreAttempt)
    }
}
