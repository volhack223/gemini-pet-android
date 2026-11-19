package com.geminipet.android

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 下拉控制中心快捷磁贴：在任意 App 下拉点一下即可一键召唤猫娘截屏吐槽
 */
class PetQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "召唤猫娘截屏"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, PetOverlayService::class.java).apply {
            action = PetOverlayService.ACTION_TRIGGER_CAPTURE
        }
        try {
            startForegroundService(intent)
        } catch (_: Exception) {
            val launch = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        }
    }
}
