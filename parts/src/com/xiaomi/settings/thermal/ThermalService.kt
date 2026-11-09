/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.xiaomi.settings.charge.BypassChargeUtils

class ThermalService : android.app.Service() {

    companion object {
        private const val TAG = "ThermalService"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var utils: ThermalUtils
    private lateinit var bypass: BypassChargeUtils
    private lateinit var power: PowerManager
    private var currentPackage = ""
    private var retryCount = 0
    @Volatile private var destroyed = false

    private val applyRunnable = Runnable { applyScene() }

    // Ask the power manager instead of caching the state: at boot the display is
    // not interactive yet when the service is created, and no ACTION_SCREEN_ON is
    // sent afterwards if the screen never turned off, which left the cached flag
    // stuck at false and every evaluation silently skipped.
    private fun interactive() = power.isInteractive

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // Re-query foreground instead of trusting the cached package,
            // which is empty after a service restart.
            if (!destroyed && interactive()) scheduleApply()
        }
    }

    private val intentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    currentPackage = ""
                    utils.writeDefault()
                    bypass.release()
                }
                Intent.ACTION_SCREEN_ON -> {
                    scheduleApply()
                }
                // Plug state is a bypass guard: re-evaluate when the cable
                // comes or goes while a game is already in foreground.
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (interactive()) scheduleApply()
                }
            }
        }
    }

    private val taskListener = object : TaskStackListener() {
        override fun onTaskStackChanged() {
            scheduleApply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        utils = ThermalUtils.getInstance(this)
        bypass = BypassChargeUtils.getInstance(this)
        bypass.clearStaleRequest()
        power = getSystemService(Context.POWER_SERVICE) as PowerManager

        registerReceiver(
            intentReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(BypassChargeUtils.SETTING_BYPASS_GAMING), false, settingsObserver,
        )
        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskListener)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot register task listener", e)
        }
        applyScene()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(applyRunnable)
        try {
            unregisterReceiver(intentReceiver)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot unregister receiver", e)
        }
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(taskListener)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot unregister task listener", e)
        }
        utils.writeDefault()
        bypass.release()
        super.onDestroy()
    }

    private fun scheduleApply() {
        handler.removeCallbacks(applyRunnable)
        handler.postDelayed(applyRunnable, 150)
    }

    private fun applyScene() {
        if (destroyed) return
        if (!interactive()) {
            currentPackage = ""
            retryCount = 0
            utils.writeDefault()
            return
        }
        val packageName = try {
            val info = ActivityTaskManager.getService().focusedRootTaskInfo
            if (info == null) {
                Log.d(TAG, "focusedRootTaskInfo is null")
                return
            }
            info.topActivity?.packageName ?: run {
                Log.d(TAG, "topActivity is null")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot query foreground", e)
            return
        }
        if (packageName == currentPackage && retryCount == 0) return
        currentPackage = packageName
        Log.d(TAG, "foreground=$packageName game=${utils.isGame(packageName)}")
        if (utils.writeSceneFor(packageName)) {
            retryCount = 0
        } else if (retryCount < 3) {
            // The node is chowned system:system late at boot; retry a bit.
            retryCount++
            handler.removeCallbacks(applyRunnable)
            handler.postDelayed(applyRunnable, 1000)
        }
        bypass.applyFor(utils.isGame(packageName))
    }
}
