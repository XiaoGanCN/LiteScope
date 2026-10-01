package com.litescope.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.litescope.R
import com.litescope.core.ScopeHub
import com.litescope.core.Tool
import com.litescope.ui.MainActivity

/**
 * Quick Settings tile.
 *
 * While a capture is running, tapping the tile shows/hides every scope window *without* leaving the
 * app you are in - which is the point of the tile. Starting a capture itself still needs the
 * MediaProjection consent dialog, which only an activity can raise, so in that case the tile opens
 * LiteScope.
 */
class ScopeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val hub = ScopeHub.get(this)
        if (hub.status.get().active) {
            val show = !anyToolVisible(hub)
            ScopeService.setAllTools(this, show)
        } else {
            openActivity()
        }
        updateTile()
    }

    private fun anyToolVisible(hub: ScopeHub): Boolean =
        Tool.entries.any { hub.prefs.toolVisible(it) }

    private fun updateTile() {
        val tile = qsTile ?: return
        val hub = ScopeHub.get(this)
        val capturing = hub.status.get().active
        val showing = capturing && anyToolVisible(hub)
        tile.state = if (showing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = when {
            !capturing -> getString(R.string.tile_label)
            showing -> "Scopes shown"
            else -> "Scopes hidden"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openActivity() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                900,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
