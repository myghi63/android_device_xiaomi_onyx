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
import com.xiaomi.settings.R
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

        /**
         * Settings.System switch: bypass charging whatever the foreground app is.
         * Cleared on boot, so it never survives a reboot.
         */
        const val SETTING_BYPASS_ALWAYS = "onyx_bypass_always"

        // Reasons reported by guardFailure().
        const val GUARD_NOT_PLUGGED = 0
        const val GUARD_HIGH_TEMPERATURE = 1
        const val GUARD_LOW_BATTERY = 2
        const val GUARD_NO_BATTERY_STATUS = 3

        @Volatile
        private var sInstance: BypassChargeUtils? = null

        @JvmStatic
        fun getInstance(context: Context): BypassChargeUtils =
            sInstance ?: synchronized(this) {
                sInstance ?: BypassChargeUtils(context.applicationContext).also { sInstance = it }
            }
    }

    /** A guard condition blocking bypass. Reading is deci-degrees C or percent, -1 when unknown. */
    class Guard(val reason: Int, val reading: Int)

    /** What happened to the charge path. */
    enum class Outcome { ENABLED, KEPT, RELEASED, REFUSED }

    /** Outcome of [applyFor], with the guard that caused a refusal. */
    class Decision(val outcome: Outcome, val guard: Guard?)

    private var bypassActive = false

    fun isSupported(): Boolean = File(CHARGE_ENABLE_NODE).canWrite()

    /**
     * Requests or releases bypass for the current foreground state. The guards are checked
     * every time, so a bypass that became unsafe meanwhile (battery heating up, cable pulled)
     * is dropped here instead of waiting for the next app change.
     *
     * @param inGame whether the foreground app is in the game list
     * @return what was done, and why when it was refused
     */
    fun applyFor(inGame: Boolean): Decision {
        val want = isAlwaysEnabled() || (inGame && isSettingEnabled())
        if (!want) {
            if (!bypassActive) return Decision(Outcome.KEPT, null)
            if (!write(CHARGING_VALUE)) return Decision(Outcome.KEPT, null)
            bypassActive = false
            return Decision(Outcome.RELEASED, null)
        }

        val guard = guardFailure()
        if (guard != null) {
            if (bypassActive && write(CHARGING_VALUE)) bypassActive = false
            return Decision(Outcome.REFUSED, guard)
        }
        if (bypassActive) return Decision(Outcome.KEPT, null)
        if (!write(BYPASS_VALUE)) return Decision(Outcome.KEPT, null)
        bypassActive = true
        return Decision(Outcome.ENABLED, null)
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

    /** Drops the forced bypass: it is a one-shot request for the current session. */
    fun clearAlwaysRequest() {
        Settings.System.putInt(context.contentResolver, SETTING_BYPASS_ALWAYS, 0)
    }

    /** Null when every guard condition is met, the blocking one otherwise. */
    fun guardFailure(): Guard? {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Guard(GUARD_NO_BATTERY_STATUS, -1)
        if (sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) {
            return Guard(GUARD_NOT_PLUGGED, -1)
        }
        val temp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        if (temp >= MAX_BATTERY_TEMP) return Guard(GUARD_HIGH_TEMPERATURE, temp)
        // getIntProperty(BATTERY_PROPERTY_CAPACITY) is @SystemApi: it throws for
        // non-platform apps here, so read the level from the same sticky broadcast.
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        if (level < MIN_BATTERY_CAPACITY) return Guard(GUARD_LOW_BATTERY, level)
        return null
    }

    /** On-screen message explaining a refusal. */
    fun refusalText(guard: Guard): String = when (guard.reason) {
        GUARD_HIGH_TEMPERATURE ->
            context.getString(R.string.bypass_refused_temperature, guard.reading / 10.0f)
        GUARD_LOW_BATTERY ->
            context.getString(R.string.bypass_refused_battery, guard.reading)
        GUARD_NOT_PLUGGED ->
            context.getString(R.string.bypass_refused_not_plugged)
        else ->
            context.getString(R.string.bypass_refused)
    }

    private fun isSettingEnabled(): Boolean =
        Settings.System.getInt(context.contentResolver, SETTING_BYPASS_GAMING, 0) == 1

    private fun isAlwaysEnabled(): Boolean =
        Settings.System.getInt(context.contentResolver, SETTING_BYPASS_ALWAYS, 0) == 1

    private fun write(value: String): Boolean = try {
        File(CHARGE_ENABLE_NODE).writeText("$value\n")
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot write '$value'", e)
        false
    }
}
