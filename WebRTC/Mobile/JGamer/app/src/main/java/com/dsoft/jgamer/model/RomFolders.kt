package com.dsoft.jgamer.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Per-system ROM folders picked with ACTION_OPEN_DOCUMENT_TREE. The tree grant
 * is persisted read+write so the scan survives restarts and "delete original"
 * works on every file inside the folder.
 */
object RomFolders {

    fun get(context: Context, system: GameSystem): Uri? =
        Prefs(context).getRomFolder(system.id)?.let { Uri.parse(it) }

    /** Store [treeUri] for [system]. Returns false if the grant couldn't be kept. */
    fun set(context: Context, system: GameSystem, treeUri: Uri): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val ok = runCatching { context.contentResolver.takePersistableUriPermission(treeUri, rw) }.isSuccess
        if (ok) Prefs(context).setRomFolder(system.id, treeUri.toString())
        return ok
    }

    fun clear(context: Context, system: GameSystem) {
        val prefs = Prefs(context)
        val old = prefs.getRomFolder(system.id) ?: return
        prefs.setRomFolder(system.id, null)
        // Release the grant only if no other system still uses the same folder.
        if (GameSystem.entries.none { prefs.getRomFolder(it.id) == old }) runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(old),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /** Human-readable folder, e.g. "primary:ROMs/NES" -> "Internal/ROMs/NES". */
    fun label(treeUri: Uri): String = runCatching {
        val id = DocumentsContract.getTreeDocumentId(treeUri)
        val vol = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        (if (vol == "primary") "Internal" else vol) + "/" + path
    }.getOrDefault(treeUri.toString())
}
