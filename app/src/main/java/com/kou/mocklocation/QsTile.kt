package com.kou.mocklocation

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings toggle: stop if running, else teleport to the last target. */
class QsTile : TileService() {
    override fun onStartListening() = render()

    // Android 12+ refuses a foreground-service start from the tile itself, so starting goes through TileLauncher.
    override fun onClick() {
        if (MockState.status.value.running) MockService.stop(this) else launch(TileLauncher::class.java)
    }

    private fun render() {
        val tile = qsTile ?: return
        val s = MockState.status.value
        tile.state = if (s.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = when {
            s.running -> s.mode.label
            else -> Store(this).target?.let { "Ke titik terakhir" } ?: "Pilih titik di aplikasi"
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(cls: Class<*>) {
        val i = Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }

    companion object {
        fun refresh(ctx: Context) = runCatching { requestListeningState(ctx, ComponentName(ctx, QsTile::class.java)) }
    }
}

/** Invisible activity: being in the foreground lets it start the mock service, then it gets out of the way. */
class TileLauncher : Activity() {
    override fun onResume() {
        super.onResume()
        val store = Store(this)
        val target = store.target
        val ok = target != null && hasLocation(this) && isMockApp(this) && runCatching {
            MockService.start(this, Mode.TELEPORT, listOf(target), store.speedKmh / 3.6, Loop.ONCE, store.humanize, false,
                altitude = store.altitude.toDouble())
        }.isSuccess
        if (ok) store.mode = Mode.TELEPORT
        else startActivity(Intent(this, MainActivity::class.java)) // no target or setup incomplete: let the user fix it
        finish()
    }
}
