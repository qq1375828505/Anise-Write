package com.ai.assistance.quro.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import com.ai.assistance.quro.service.QuroVoiceBallService

/**
 * 开机自启动接收器：监听 BOOT_COMPLETED。
 *
 * 1. 若用户在「语音设置」开启「后台自启动」，拉起常住语音球服务。
 */
class QuroBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // 1) 语音球自启动（按用户偏好）
        if (QuroVoiceFeaturePrefs.getAutostart(context)) {
            val i = Intent(context, QuroVoiceBallService::class.java).apply {
                putExtra(QuroVoiceBallService.EXTRA_NO_LISTEN, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        // 2) 应用内闹钟重建排程（开机后 AlarmManager 已清空，需按持久化数据重排）
        com.ai.assistance.quro.permissions.QuroAlarmScheduler(context).scheduleAll()
    }
}
