package com.aigirl.floatball

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 开机自启动接收器：如果用户启用了悬浮球，开机自动拉起前台服务
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED != intent.action) return
        Prefs.init(context)
        if (!Prefs.enabled) return
        val service = Intent(context, FloatBallService::class.java).apply {
            action = FloatBallService.ACTION_SHOW
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, service)
        } else {
            context.startService(service)
        }
    }
}
