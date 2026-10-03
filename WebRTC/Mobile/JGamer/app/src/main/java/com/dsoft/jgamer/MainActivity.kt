package com.dsoft.jgamer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dsoft.jgamer.model.DeviceInfo
import com.dsoft.jgamer.model.GameEntry
import com.dsoft.jgamer.model.GameRepository
import com.dsoft.jgamer.model.GameSystem
import com.dsoft.jgamer.model.Prefs
import com.dsoft.jgamer.model.RomFolders
import com.dsoft.jgamer.ui.DeleteRomDialog
import com.dsoft.jgamer.ui.GameListAdapter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home: tabs for Recent / NES / SNES / GB / GBA / Genesis / Game Gear / Arcade / PICO-8, a game list, and import. If
 * "resume last game on launch" is enabled, jumps straight into the last game.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var repo: GameRepository
    private lateinit var prefs: Prefs
    private lateinit var adapter: GameListAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var tabs: TabLayout
    private lateinit var fab: FloatingActionButton

    // tab 0 = Recent; 1..N = systems
    private val systemTabs = listOf(null, GameSystem.NES, GameSystem.SNES, GameSystem.GB, GameSystem.GBA, GameSystem.GENESIS, GameSystem.GG, GameSystem.ARCADE, GameSystem.PICO8)
    private var currentTab = 1
    private val isTv by lazy { DeviceInfo.isTv(this) }
    // What the list currently shows (submitList diffs async, so don't read the adapter).
    private var shown: List<GameEntry> = emptyList()

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) importAll(uris) }

    // Picks the ROM folder for the current system tab.
    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onFolderPicked(uri) }

    // Systems with a folder scan in flight (one at a time per system).
    private val scanning = HashSet<GameSystem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = GameRepository.get(this)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        adapter = GameListAdapter(
            onClick = { play(it) },
            onLongClick = { itemMenu(it) },
            onDelete = { DeleteRomDialog.show(this, it) { refresh() } }
        )
        recycler = findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        emptyView = findViewById(R.id.emptyView)

        tabs = findViewById(R.id.tabs)
        listOf(R.string.tab_recent, R.string.tab_nes, R.string.tab_snes, R.string.tab_gb, R.string.tab_gba, R.string.tab_genesis, R.string.tab_gg, R.string.tab_arcade, R.string.tab_pico8).forEach {
            tabs.addTab(tabs.newTab().setText(it))
        }
        tabs.getTabAt(currentTab)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { currentTab = tab.position; refresh(scan = true) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        fab = findViewById(R.id.fabImport)
        fab.setOnClickListener { pick() }

        maybeResumeLast(savedInstanceState)
    }

    private fun maybeResumeLast(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        val id = prefs.lastGameId ?: return
        if (!prefs.resumeOnLaunch) return
        if (repo.byId(id) == null) return
        startActivity(PlayerActivity.intent(this, id, autoLoad = true))
    }

    override fun onResume() { super.onResume(); refresh(scan = true) }

    /** Rebuild the list; with [scan], also sync the tab's ROM folder in the background. */
    private fun refresh(scan: Boolean = false) {
        val list = if (currentTab == 0) repo.recent() else repo.bySystem(systemTabs[currentTab]!!)
        adapter.submitList(list)
        shown = list
        val empty = list.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.setText(if (currentTab == 0) R.string.empty_recent else R.string.empty_library)
        // Recent tab is history-only: no add button.
        fab.visibility = if (currentTab == 0) View.GONE else View.VISIBLE
        invalidateOptionsMenu()
        // TV remote: give the list a focused row so OK / D-pad work straight away.
        if (isTv && !empty && (currentFocus == null || currentFocus === recycler)) {
            recycler.post { recycler.getChildAt(0)?.requestFocus() }
        }
        if (scan) systemTabs[currentTab]?.let { scanFolder(it, quiet = true) }
    }

    // ---- Per-system ROM folder -------------------------------------------------

    private fun pickFolder() {
        runCatching { folderLauncher.launch(RomFolders.get(this, systemTabs[currentTab] ?: return)) }
            .onFailure { toast(getString(R.string.import_failed)) }
    }

    private fun onFolderPicked(uri: Uri) {
        val system = systemTabs[currentTab] ?: return
        if (!RomFolders.set(this, system, uri)) { toast(getString(R.string.folder_grant_failed)); return }
        toast(getString(R.string.folder_set, system.displayName, RomFolders.label(uri)))
        scanFolder(system, quiet = false)
    }

    /** Import new files from [system]'s folder (copy happens off the main thread). */
    private fun scanFolder(system: GameSystem, quiet: Boolean) {
        val tree = RomFolders.get(this, system) ?: run {
            if (!quiet) toast(getString(R.string.folder_none, system.displayName)); return
        }
        if (!scanning.add(system)) return
        supportActionBar?.subtitle = getString(R.string.folder_scanning, system.displayName)
        val ignore = prefs.getScanIgnore()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { repo.scanFolder(applicationContext, system, tree, ignore) }
            scanning.remove(system)
            if (scanning.isEmpty()) supportActionBar?.subtitle = null
            when {
                r.error -> toast(getString(R.string.folder_unreadable, system.displayName))
                r.added > 0 || r.removed > 0 ->
                    toast(getString(R.string.folder_scan_result, r.added, r.removed))
                !quiet -> toast(getString(R.string.folder_scan_nothing))
            }
            refresh()
        }
    }

    // ---- Import --------------------------------------------------------------

    private fun pick() {
        runCatching { importLauncher.launch(arrayOf("*/*")) }
            .onFailure { toast(getString(R.string.import_failed)) }
    }

    private fun importAll(uris: List<Uri>) {
        // Import screen is always a specific system (Recent has no add button).
        val system = systemTabs[currentTab] ?: GameSystem.NES
        var ok = 0; var skipped = 0
        val now = System.currentTimeMillis()
        uris.forEachIndexed { i, uri ->
            keepAccess(uri)
            val name = queryName(uri) ?: "game_$i"
            if (repo.importForSystem(this, uri, name, system, now + i) != null) ok++ else skipped++
        }
        toast(resources.getQuantityString(R.plurals.imported_count, ok, ok) +
            if (skipped > 0) getString(R.string.import_skipped, skipped, system.displayName) else "")
        refresh()
    }

    /** Keep read+write on the picked file so "delete original" works later. */
    private fun keepAccess(uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, rw) }
            .recoverCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    // ---- Item actions --------------------------------------------------------

    private fun play(e: GameEntry) {
        runCatching { startActivity(PlayerActivity.intent(this, e.id)) }
            .onFailure { toast(getString(R.string.launch_failed)) }
    }

    private fun itemMenu(e: GameEntry) {
        val items = arrayOf(getString(R.string.action_play), getString(R.string.action_rename), getString(R.string.action_delete))
        AlertDialog.Builder(this).setTitle(e.title).setItems(items) { _, w ->
            when (w) {
                0 -> play(e)
                1 -> renameDialog(e)
                2 -> DeleteRomDialog.show(this, e) { refresh() }
            }
        }.show()
    }

    private fun renameDialog(e: GameEntry) {
        val input = android.widget.EditText(this).apply { setText(e.title) }
        AlertDialog.Builder(this).setTitle(R.string.action_rename).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> repo.rename(e.id, input.text.toString()); refresh() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_DELETE, 0, R.string.menu_delete_games).setIcon(R.drawable.ic_delete)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_FOLDER, 1, R.string.menu_rom_folder).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_RESCAN, 2, R.string.menu_rescan).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_SETTINGS, 3, R.string.settings).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val system = systemTabs[currentTab]
        menu.findItem(MENU_DELETE)?.isVisible = shown.isNotEmpty()
        menu.findItem(MENU_FOLDER)?.apply {
            isVisible = system != null
            if (system != null) title = getString(R.string.menu_rom_folder_for, system.displayName)
        }
        menu.findItem(MENU_RESCAN)?.isVisible = system != null && RomFolders.get(this, system) != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            MENU_DELETE -> DeleteRomDialog.showMulti(this, shown) { refresh() }
            MENU_FOLDER -> pickFolder()
            MENU_RESCAN -> systemTabs[currentTab]?.let { scanFolder(it, quiet = false) }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MENU_SETTINGS = 1
        private const val MENU_DELETE = 2
        private const val MENU_FOLDER = 3
        private const val MENU_RESCAN = 4
    }
}
