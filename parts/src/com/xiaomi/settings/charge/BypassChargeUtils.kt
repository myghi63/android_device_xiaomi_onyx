/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.charge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Bypass charging: the adapter keeps powering the device while the charge path is
 * disabled, so the battery is neither charged nor heated by charging during gaming.
 *
 * The node is the per-client arbitration interface of the Xiaomi MCA charger driver:
 * "<client> buck 0" disables the charge path, "<client> buck 1" releases the request,
 * and reading it back shows which client currently owns the path. Writing a bare 0/1
 * is rejected by the driver.
 */
class BypassChargeUtils private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BypassChargeUtils"
        private const val CHARGE_ENABLE_NODE =
            "/sys/class/xm_power/charger/charge_interface/charge_enable"
        private const val CLIENT = "onyx_parts"
        private const val BYPASS_VALUE = "$CLIENT buck 0"
        private const val CHARGING_VALUE = "$CLIENT buck 1"

        // Refuse bypass in conditions where it would hurt rather than help.
        private const val MAX_BATTERY_TEMP = 450 // deci-degrees C
        private const val MIN_BATTERY_CAPACITY = 20 // percent

        /** Settings.System switch: bypass charging while a game is in the foreground. */
        const val SETTING_BYPASS_GAMING = "onyx_bypass_gaming"

        @Volatile
        private var sInstance: BypassChargeUtils? = null

        @JvmStatic
        fun getInstance(context: Context): BypassChargeUtils =
            sInstance ?: synchronized(this) {
                sInstance ?: BypassChargeUtils(context.applicationContext).also { sInstance = it }
            }
    }

    private var bypassActive = false

    fun isSupported(): Boolean = File(CHARGE_ENABLE_NODE).canWrite()

    /**
     * Requests or releases bypass for the current foreground state.
     *
     * @return whether bypass is active after the call
     */
    fun applyFor(inGame: Boolean): Boolean {
        val want = inGame && isSettingEnabled()
        if (want == bypassActive) return bypassActive

        if (want) {
            unsafeReason()?.let {
                Log.i(TAG, "Bypass refused: $it")
                return false
            }
        }
        if (!write(if (want) BYPASS_VALUE else CHARGING_VALUE)) return bypassActive

        bypassActive = want
        return bypassActive
    }

    /** Always safe to call: leaves the charge path alone when nothing was requested. */
    fun release() {
        if (!bypassActive) return
        if (write(CHARGING_VALUE)) bypassActive = false
    }

    /** Drops a request left behind by a previous process lifetime (app killed mid-game). */
    fun clearStaleRequest() {
        if (!bypassActive) write(CHARGING_VALUE)
    }

    private fun isSettingEnabled(): Boolean =
        Settings.System.getInt(context.contentResolver, SETTING_BYPASS_GAMING, 0) == 1

    private fun unsafeReason(): String? {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "no battery status"
        if (sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) return "not plugged in"
        val temp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        if (temp >= MAX_BATTERY_TEMP) return "battery at ${temp / 10.0f} C"
        val level = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        if (level < MIN_BATTERY_CAPACITY) return "battery at $level%"
        return null
    }

    private fun write(value: String): Boolean = try {
        File(CHARGE_ENABLE_NODE).writeText("$value\n")
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot write '$value'", e)
        false
    }
}
