package com.lladlam.melox.carfloat

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * 车机悬浮协议（星环悬浮补丁同款，供「海鸥悬浮桌面」指挥）：
 *
 *  · 收 com.autonavi.plus.showmap（extras x/y/w/h = 矩形，w/h 是右下角坐标，dpi）
 *    → 把当前前台 Activity 的 decorView 从 Activity 窗口摘出，挂成
 *      TYPE_APPLICATION_OVERLAY 系统悬浮窗（可触摸），成功后回
 *      cn.kuwo.plus.floated 广播（ACK，海鸥收到后槽卡让位）；
 *    摘不到界面（无前台 Activity）回 cn.kuwo.plus.noview。
 *  · 收 com.autonavi.plus.closemap → 收回悬浮窗，decorView 挂回 Activity 窗口。
 *  · 应用回前台（onActivityResumed）→ 自动收回悬浮（用户点海鸥把手拉起应用时，
 *    悬浮窗让位给全屏应用）；再回桌面海鸥会重发 showmap，自动再浮起来。
 *  · 同矩形重复 showmap 幂等丢弃（防布局回调连发把窗口一闪一闪）。
 *
 * 无需 manifest receiver（运行时注册，RECEIVER_EXPORTED 供跨应用广播）。
 * 海鸥侧协议见 seagull-floatdesktop AmapMapDirect / SlotCard。
 */
object CarFloatHook {

    private const val TAG = "MeloXCarFloat"

    const val ACTION_SHOW = "com.autonavi.plus.showmap"
    const val ACTION_CLOSE = "com.autonavi.plus.closemap"
    const val ACTION_FLOATED = "cn.kuwo.plus.floated"
    const val ACTION_NOVIEW = "cn.kuwo.plus.noview"

    @Volatile private var app: Application? = null
    @Volatile private var topActivity: Activity? = null
    @Volatile private var floatingView: View? = null
    @Volatile private var floatingRect: IntArray? = null
    @Volatile private var wmApp: WindowManager? = null

    fun install(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                topActivity = activity
                // 应用回前台 → 自动收悬浮还窗（点海鸥把手/单击卡拉起时的自然行为）
                if (floatingView != null) restoreToWindow()
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (topActivity === activity) topActivity = null
            }
            override fun onActivityCreated(a: Activity, s: android.os.Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, o: android.os.Bundle) = Unit
        })

        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    when (intent.action) {
                        ACTION_SHOW -> {
                            val x = intent.getIntExtra("x", 0)
                            val y = intent.getIntExtra("y", 0)
                            val w = intent.getIntExtra("w", 0)
                            val h = intent.getIntExtra("h", 0)
                            onShowMap(x, y, w, h)
                        }
                        ACTION_CLOSE -> closeFloat()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "onReceive: ${t.message}")
                    reply(ACTION_NOVIEW)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_CLOSE)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                application.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            } else {
                application.registerReceiver(rx, filter)
            }
            Log.i(TAG, "车机悬浮协议已挂（showmap/floated）")
        } catch (t: Throwable) {
            Log.w(TAG, "协议注册失败: ${t.message}")
        }
    }

    /** showmap（w/h = 右下角坐标语义，海鸥按猴哥协议发）。 */
    private fun onShowMap(x: Int, y: Int, right: Int, bottom: Int) {
        val a = app ?: return reply(ACTION_NOVIEW)
        val w = right - x
        val h = bottom - y
        if (w <= 0 || h <= 0) return reply(ACTION_NOVIEW)

        // 已在同矩形悬浮 → 幂等丢弃，重发 ACK（海鸥重挂后会再发一遍）
        val cur = floatingRect
        if (floatingView != null && cur != null &&
            cur[0] == x && cur[1] == y && cur[2] == w && cur[3] == h) {
            Log.i(TAG, "showmap 同矩形，幂等丢弃")
            reply(ACTION_FLOATED)
            return
        }

        val decor = topActivity?.window?.decorView
        if (decor == null) {
            Log.i(TAG, "showmap：无前台 Activity，回 noview")
            reply(ACTION_NOVIEW)
            return
        }

        try {
            if (floatingView != null) {
                // 已浮着但矩形变了：resize —— Compose 对尺寸变化会自己重排，
                // 直接 updateViewLayout（位置+尺寸一起改）
                val lp = buildParams(x, y, w, h)
                appWindowManager().updateViewLayout(decor, lp)
                floatingRect = intArrayOf(x, y, w, h)
                reply(ACTION_FLOATED)
                Log.i(TAG, "悬浮窗重定位 rect($x,$y,${x + w},${y + h})")
                return
            }
            // 摘出 Activity 窗口 → 挂成系统悬浮窗（同进程 WindowManagerGlobal，
            // remove+add 对 Activity 的 decorView 成立；Compose 界面原样保留）
            (decor.parent as? WindowManager)?.removeViewImmediate(decor)
            val lp = buildParams(x, y, w, h)
            appWindowManager().addView(decor, lp)
            floatingView = decor
            floatingRect = intArrayOf(x, y, w, h)
            reply(ACTION_FLOATED)
            Log.i(TAG, "悬浮成功 rect($x,$y,${x + w},${y + h})，已回 ACK")
        } catch (t: Throwable) {
            Log.w(TAG, "悬浮失败: ${t.message}")
            floatingView = null
            floatingRect = null
            // 失败要把 decor 还回去，应用别跟着黑掉
            restoreToWindow()
            reply(ACTION_NOVIEW)
        }
    }

    /** 收回悬浮：decor 挂回 Activity 窗口（回全屏可用）。 */
    private fun closeFloat() {
        if (floatingView == null) {
            Log.i(TAG, "closemap: not floating")
            return
        }
        restoreToWindow()
    }

    private fun restoreToWindow() {
        val decor = floatingView ?: return
        floatingView = null
        floatingRect = null
        try {
            appWindowManager().removeViewImmediate(decor)
        } catch (_: Throwable) {
        }
        // 还回 Activity 窗口：用 Activity window 的 attributes 重建窗口
        val act = topActivity
        if (act != null && act.window != null && decor.parent == null) {
            try {
                val attrs = act.window.attributes
                (act.window).windowManager.addView(decor, attrs)
                Log.i(TAG, "悬浮窗已还回 Activity 窗口")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "还窗失败（重建 Activity 兜底）: ${t.message}")
            }
        }
        try {
            act?.recreate()
        } catch (_: Throwable) {
        }
    }

    private fun buildParams(x: Int, y: Int, w: Int, h: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = android.view.Gravity.START or android.view.Gravity.TOP
        lp.x = x
        lp.y = y
        return lp
    }

    private fun appWindowManager(): WindowManager {
        wmApp?.let { return it }
        val wm = app!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wmApp = wm
        return wm
    }

    private fun reply(action: String) {
        try {
            val a = app ?: return
            // 隐式广播（不带包名）：海鸥的 ACK 接收器是运行时 RECEIVER_EXPORTED 注册，
            // 星环原版协议同款 —— setPackage 到自己 = 只有自己收到，海鸥永远等不到 ACK
            a.sendBroadcast(Intent(action))
        } catch (_: Throwable) {
        }
    }
}
