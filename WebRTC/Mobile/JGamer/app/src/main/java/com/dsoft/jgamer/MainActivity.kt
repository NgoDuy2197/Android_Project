package com.dsoft.jgamer

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dsoft.jgamer.model.GameEntry
import com.dsoft.jgamer.model.GameRepository
import com.dsoft.jgamer.model.GameSystem
import com.dsoft.jgamer.model.Prefs
import com.dsoft.jgamer.ui.GameListAdapter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout

/**
 * Home: tabs for Recent / NES / SNES / PICO-8, a game list, and import. If
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
    private val systemTabs = listOf(null, GameSystem.NES, GameSystem.SNES, GameSystem.GB, GameSystem.GBA, GameSystem.GENESIS, GameSystem.ARCADE, GameSystem.PICO8)
    private var currentTab = 1

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) importAll(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = GameRepository.get(this)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        adapter = GameListAdapter(onClick = { play(it) }, onLongClick = { itemMenu(it) })
        recycler = findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        emptyView = findViewById(R.id.emptyView)

        tabs = findViewById(R.id.tabs)
        listOf(R.string.tab_recent, R.string.tab_nes, R.string.tab_snes, R.string.tab_gb, R.string.tab_gba, R.string.tab_genesis, R.string.tab_arcade, R.string.tab_pico8).forEach {
            tabs.addTab(tabs.newTab().setText(it))
        }
        tabs.getTabAt(currentTab)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { currentTab = tab.position; refresh() }
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

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val list = if (currentTab == 0) repo.recent() else repo.bySystem(systemTabs[currentTab]!!)
        adapter.submitList(list)
        val empty = list.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.setText(if (currentTab == 0) R.string.empty_recent else R.string.empty_library)
        // Recent tab is history-only: no add button.
        fab.visibility = if (currentTab == 0) View.GONE else View.VISIBLE
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
            val name = queryName(uri) ?: "game_$i"
            if (repo.importForSystem(this, uri, name, system, now + i) != null) ok++ else skipped++
        }
        toast(resources.getQuantityString(R.plurals.imported_count, ok, ok) +
            if (skipped > 0) getString(R.string.import_skipped, skipped, system.displayName) else "")
        refresh()
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
                2 -> { repo.remove(e.id); refresh() }
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
        menu.add(0, MENU_SETTINGS, 0, R.string.settings).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SETTINGS) { startActivity(android.content.Intent(this, SettingsActivity::class.java)); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object { private const val MENU_SETTINGS = 1 }
}
