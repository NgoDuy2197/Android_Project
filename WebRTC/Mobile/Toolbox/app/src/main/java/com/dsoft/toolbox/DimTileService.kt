package com.dsoft.toolbox

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to toggle the dimmer. */
class DimTileService : TileService() {
    override fun onStartListening() = update(DimService.active)

    override fun onClick() {
        if (DimService.active) {
            DimService.stop(this)
            update(false)
        } else if (DimService.canOverlay(this) || DimAccessibility.instance != null) {
            runCatching { DimService.start(this) }
            update(true)
        }
    }

    private fun update(on: Boolean) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
