/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.charge

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R
import com.xiaomi.settings.thermal.ThermalUtils

class ChargeSettingsActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, ChargeSettingsFragment())
                .commit()
        }
    }

    class ChargeSettingsFragment : SettingsBasePreferenceFragment(),
        Preference.OnPreferenceChangeListener {

        private lateinit var bypass: BypassChargeUtils
        private lateinit var utils: ThermalUtils

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.charge_settings, rootKey)

            bypass = BypassChargeUtils.getInstance(requireContext())
            utils = ThermalUtils.getInstance(requireContext())

            val bypassGaming =
                findPreference<SwitchPreferenceCompat>(BypassChargeUtils.SETTING_BYPASS_GAMING)
            bypassGaming?.isChecked = isBypassGamingEnabled()
            bypassGaming?.setOnPreferenceChangeListener(this)

            val thermalGaming =
                findPreference<SwitchPreferenceCompat>(ThermalUtils.SETTINGS_THERMAL_GAMING)
            thermalGaming?.isChecked = utils.isGamingProfileEnabled()
            thermalGaming?.setOnPreferenceChangeListener(this)

            val bypassAlways =
                findPreference<SwitchPreferenceCompat>(BypassChargeUtils.SETTING_BYPASS_ALWAYS)
            bypassAlways?.isChecked = isBypassAlwaysEnabled()
            bypassAlways?.setOnPreferenceChangeListener(this)

            val thermalAlways =
                findPreference<SwitchPreferenceCompat>(ThermalUtils.SETTINGS_THERMAL_ALWAYS)
            thermalAlways?.isChecked = utils.isThermalAlwaysEnabled()
            thermalAlways?.setOnPreferenceChangeListener(this)

            findPreference<Preference>(KEY_GAME_LIST)?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), GameListSettingsActivity::class.java))
                true
            }
        }

        override fun onCreateRecyclerView(
            inflater: LayoutInflater,
            parent: ViewGroup,
            savedInstanceState: Bundle?,
        ): RecyclerView {
            val view = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(insets.left, v.paddingTop, insets.right, insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            return view
        }

        override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
            if (preference.key == BypassChargeUtils.SETTING_BYPASS_GAMING) {
                setBypassGamingEnabled(newValue as Boolean)
                return true
            }
            if (preference.key == BypassChargeUtils.SETTING_BYPASS_ALWAYS) {
                val enabled = newValue as Boolean
                if (enabled) {
                    // Forcing bypass is an immediate request: refuse it here so the switch
                    // stays off and the reason is visible instead of only in the logs.
                    bypass.guardFailure()?.let {
                        Toast.makeText(
                            requireContext(),
                            bypass.refusalText(it),
                            Toast.LENGTH_LONG,
                        ).show()
                        return false
                    }
                }
                Settings.System.putInt(
                    requireContext().contentResolver,
                    BypassChargeUtils.SETTING_BYPASS_ALWAYS,
                    if (enabled) 1 else 0,
                )
                return true
            }
            if (preference.key == ThermalUtils.SETTINGS_THERMAL_GAMING ||
                preference.key == ThermalUtils.SETTINGS_THERMAL_ALWAYS
            ) {
                Settings.System.putInt(
                    requireContext().contentResolver,
                    preference.key,
                    if (newValue as Boolean) 1 else 0,
                )
                return true
            }
            return false
        }

        private fun isBypassGamingEnabled(): Boolean =
            Settings.System.getInt(
                requireContext().contentResolver,
                BypassChargeUtils.SETTING_BYPASS_GAMING,
                1,
            ) == 1

        private fun isBypassAlwaysEnabled(): Boolean =
            Settings.System.getInt(
                requireContext().contentResolver,
                BypassChargeUtils.SETTING_BYPASS_ALWAYS,
                0,
            ) == 1

        private fun setBypassGamingEnabled(enabled: Boolean) {
            Settings.System.putInt(
                requireContext().contentResolver,
                BypassChargeUtils.SETTING_BYPASS_GAMING,
                if (enabled) 1 else 0,
            )
        }

        companion object {
            private const val KEY_GAME_LIST = "onyx_game_list_page"
        }
    }
}
