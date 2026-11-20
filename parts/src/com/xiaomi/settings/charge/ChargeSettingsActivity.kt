/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.charge

import android.content.Intent
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.SwitchPreference
import android.provider.Settings
import android.widget.Toast
import com.xiaomi.settings.R
import com.xiaomi.settings.thermal.ThermalUtils

class ChargeSettingsActivity : PreferenceActivity(),
    Preference.OnPreferenceChangeListener {

    private lateinit var bypass: BypassChargeUtils
    private lateinit var utils: ThermalUtils

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.charge_settings)
        bypass = BypassChargeUtils.getInstance(this)
        utils = ThermalUtils.getInstance(this)

        val bypassGaming =
            findPreference(BypassChargeUtils.SETTING_BYPASS_GAMING) as SwitchPreference
        bypassGaming.isChecked = isBypassGamingEnabled()
        bypassGaming.setOnPreferenceChangeListener(this)

        val thermalGaming =
            findPreference(ThermalUtils.SETTINGS_THERMAL_GAMING) as SwitchPreference
        thermalGaming.isChecked = utils.isGamingProfileEnabled()
        thermalGaming.setOnPreferenceChangeListener(this)

        val bypassAlways =
            findPreference(BypassChargeUtils.SETTING_BYPASS_ALWAYS) as SwitchPreference
        bypassAlways.isChecked = isBypassAlwaysEnabled()
        bypassAlways.setOnPreferenceChangeListener(this)

        val thermalAlways =
            findPreference(ThermalUtils.SETTINGS_THERMAL_ALWAYS) as SwitchPreference
        thermalAlways.isChecked = utils.isThermalAlwaysEnabled()
        thermalAlways.setOnPreferenceChangeListener(this)

        findPreference(KEY_GAME_LIST)?.setOnPreferenceClickListener {
            startActivity(Intent(this, GameListSettingsActivity::class.java))
            true
        }
    }

    @Suppress("DEPRECATION")
    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if (preference.key == BypassChargeUtils.SETTING_BYPASS_GAMING) {
            val enabled = newValue as Boolean
            setBypassGamingEnabled(enabled)
            (preference as SwitchPreference).isChecked = enabled
            return true
        }
        if (preference.key == BypassChargeUtils.SETTING_BYPASS_ALWAYS) {
            val enabled = newValue as Boolean
            if (enabled) {
                // Forcing bypass is an immediate request: refuse it here so the switch
                // stays off and the reason is visible instead of only in the logs.
                bypass.guardFailure()?.let {
                    Toast.makeText(this, bypass.refusalText(it), Toast.LENGTH_LONG).show()
                    return false
                }
            }
            Settings.System.putInt(
                contentResolver,
                BypassChargeUtils.SETTING_BYPASS_ALWAYS,
                if (enabled) 1 else 0,
            )
            return true
        }
        if (preference.key == ThermalUtils.SETTINGS_THERMAL_GAMING ||
            preference.key == ThermalUtils.SETTINGS_THERMAL_ALWAYS
        ) {
            Settings.System.putInt(
                contentResolver,
                preference.key,
                if (newValue as Boolean) 1 else 0,
            )
            return true
        }
        return false
    }

    private fun isBypassGamingEnabled(): Boolean =
        Settings.System.getInt(contentResolver, BypassChargeUtils.SETTING_BYPASS_GAMING, 1) == 1

    private fun isBypassAlwaysEnabled(): Boolean =
        Settings.System.getInt(contentResolver, BypassChargeUtils.SETTING_BYPASS_ALWAYS, 0) == 1

    private fun setBypassGamingEnabled(enabled: Boolean) {
        Settings.System.putInt(
            contentResolver,
            BypassChargeUtils.SETTING_BYPASS_GAMING,
            if (enabled) 1 else 0,
        )
    }

    companion object {
        private const val KEY_GAME_LIST = "onyx_game_list_page"
    }
}
