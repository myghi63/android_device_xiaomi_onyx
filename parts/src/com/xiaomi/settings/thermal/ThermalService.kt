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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log

class ThermalService : android.app.Service() {

    companion object {
        private const val TAG = "ThermalService"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var utils: ThermalUtils
    private var screenOn = false
    private var currentPackage = ""
    private var retryCount = 0
    @Volatile private var destroyed = false

    private val applyRunnable = Runnable { applyScene() }

    private val intentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    currentPackage = ""
                    utils.writeDefault()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    scheduleApply()
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
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive == true

        registerReceiver(
            intentReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskListener)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot register task listener", e)
        }
        applyScene()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
        super.onDestroy()
    }

    private fun scheduleApply() {
        handler.removeCallbacks(applyRunnable)
        handler.postDelayed(applyRunnable, 150)
    }

    private fun applyScene() {
        if (destroyed) return
        if (!screenOn) {
            currentPackage = ""
            retryCount = 0
            utils.writeDefault()
            return
        }
        val packageName = try {
            ActivityTaskManager.getService()
                .focusedRootTaskInfo?.topActivity?.packageName ?: return
        } catch (e: Exception) {
            Log.w(TAG, "Cannot query foreground", e)
            return
        }
        if (packageName == currentPackage && retryCount == 0) return
        currentPackage = packageName
        if (utils.writeSceneFor(packageName)) {
            retryCount = 0
        } else if (retryCount < 3) {
            // The node is chowned system:system late at boot; retry a bit.
            retryCount++
            handler.removeCallbacks(applyRunnable)
            handler.postDelayed(applyRunnable, 1000)
        }
    }
}
