/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.charge

import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.SwitchPreference
import android.provider.Settings
import com.xiaomi.settings.R

class ChargeSettingsActivity : PreferenceActivity(),
    Preference.OnPreferenceChangeListener {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.charge_settings)

        val bypassGaming =
            findPreference(BypassChargeUtils.SETTING_BYPASS_GAMING) as SwitchPreference
        bypassGaming.isChecked = isBypassGamingEnabled()
        bypassGaming.setOnPreferenceChangeListener(this)
    }

    @Suppress("DEPRECATION")
    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if (preference.key == BypassChargeUtils.SETTING_BYPASS_GAMING) {
            val enabled = newValue as Boolean
            setBypassGamingEnabled(enabled)
            (preference as SwitchPreference).isChecked = enabled
            return true
        }
        return false
    }

    private fun isBypassGamingEnabled(): Boolean =
        Settings.System.getInt(contentResolver, BypassChargeUtils.SETTING_BYPASS_GAMING, 0) == 1

    private fun setBypassGamingEnabled(enabled: Boolean) {
        Settings.System.putInt(
            contentResolver,
            BypassChargeUtils.SETTING_BYPASS_GAMING,
            if (enabled) 1 else 0,
        )
    }
}
