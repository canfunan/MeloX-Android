package com.lladlam.melox.carfloat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 车机悬浮保活前台服务（mediaPlayback 类型，无 root 方案）。
 *
 * 为什么必须有它：HyperOS/ColorOS 等冻结引擎（greezer/millet）对「无前台服务、
 * 低 standby bucket」的进程直接冻结 —— 冻结后 showmap 广播被 SKIPPED（Greezer
 * Denial），悬浮协议全瘫。高德/音乐注入版不受影响是因为它们运行时挂着
 * mediaPlayback 前台服务，冻结引擎对这类应用不冻。
 *
 * MeloX 没在播放时是裸进程 = 秒冻。这里在用户打开应用时挂一个常驻 FGS：
 *  · mediaPlayback 语义对本应用完全成立（音乐应用）
 *  · 有 FGS 的进程冻结引擎不碰 → showmap 随时可达
 *  · START_STICKY 被杀自动重启
 *
 * 普通（无 root）用户零配置：打开一次 MeloX，保活即生效。
 */
class CarFloatKeepService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTI_ID, buildNoti(this))
        } catch (t: Throwable) {
            Log.w("MeloXCarFloat", "保活 FGS startForeground 失败: ${t.message}")
        }
        Log.i("MeloXCarFloat", "悬浮保活前台服务已挂（冻结引擎豁免）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY：被系统杀掉后自动重启，保活链不断
        return START_STICKY
    }

    private fun buildNoti(ctx: Context): Notification {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "车机悬浮保活", NotificationManager.IMPORTANCE_MIN)
            ch.setDescription("保持 MeloX 可被车机桌面指挥悬浮")
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(ctx, CH_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(ctx)
        }
        return b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MeloX 车机悬浮")
            .setContentText("保持可被海鸥桌面指挥悬浮")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CH_ID = "melox_carfloat_keep"
        private const val NOTI_ID = 0xC8F1

        /** 应用前台时拉起（MainActivity.onCreate 调，前台启动 FGS 不受后台限制）。 */
        fun ensure(ctx: Context) {
            try {
                val i = Intent(ctx, CarFloatKeepService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (t: Throwable) {
                Log.w("MeloXCarFloat", "保活服务拉起失败: ${t.message}")
            }
        }
    }
}
