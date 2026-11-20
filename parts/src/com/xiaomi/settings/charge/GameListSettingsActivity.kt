/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.charge

import android.app.AlertDialog
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R
import com.xiaomi.settings.thermal.ThermalUtils

/**
 * Editor of the game list: the apps that get the gaming thermal scene and, with the
 * "bypass while gaming" switch on, bypass charging.
 */
class GameListSettingsActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, GameListSettingsFragment())
                .commit()
        }
    }

    class GameListSettingsFragment : SettingsBasePreferenceFragment(),
        Preference.OnPreferenceChangeListener {

        private lateinit var games: ThermalUtils

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.game_list_settings, rootKey)
            games = ThermalUtils.getInstance(requireContext())

            val auto = findPreference<SwitchPreferenceCompat>(ThermalUtils.SETTINGS_GAME_LIST_AUTO)
            auto?.isChecked = games.isAutoGameEnabled()
            auto?.setOnPreferenceChangeListener(this)

            findPreference<Preference>(KEY_ADD)?.setOnPreferenceClickListener {
                showAddDialog()
                true
            }

            buildRows()
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
            val enabled = newValue as Boolean
            if (preference.key == ThermalUtils.SETTINGS_GAME_LIST_AUTO) {
                Settings.System.putInt(
                    requireContext().contentResolver,
                    ThermalUtils.SETTINGS_GAME_LIST_AUTO,
                    if (enabled) 1 else 0,
                )
                buildRows()
                return true
            }
            // Every other row is an app: on puts it in the list, off takes it out.
            if (enabled) games.addGame(preference.key) else games.removeGame(preference.key)
            return true
        }

        private fun buildRows() {
            val category = findPreference<PreferenceCategory>(KEY_ENTRIES) ?: return
            category.removeAll()

            val rows = games.apps().filter { it.listed || games.isRemoved(it.packageName) }
            if (rows.isEmpty()) {
                category.addPreference(Preference(requireContext()).apply {
                    isPersistent = false
                    isSelectable = false
                    setTitle(R.string.game_list_empty)
                })
                return
            }
            for (app in rows) {
                category.addPreference(SwitchPreferenceCompat(requireContext()).apply {
                    key = app.packageName
                    setTitle(app.label)
                    summary = app.packageName
                    isPersistent = false
                    isChecked = app.listed
                    setOnPreferenceChangeListener(this@GameListSettingsFragment)
                })
            }
        }

        private fun showAddDialog() {
            val apps = games.apps()
            if (apps.isEmpty()) return

            val labels = apps.map { it.label }.toTypedArray()
            val picked = BooleanArray(apps.size) { apps[it].listed }

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.game_list_add_title)
                .setMultiChoiceItems(labels, picked) { _, which, isChecked ->
                    picked[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    apps.forEachIndexed { index, app ->
                        when {
                            picked[index] -> games.addGame(app.packageName)
                            // Untouched rows stay as they were: only an app that was in the
                            // list is taken out of it.
                            app.listed -> games.removeGame(app.packageName)
                        }
                    }
                    buildRows()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        companion object {
            private const val KEY_ADD = "onyx_game_list_add"
            private const val KEY_ENTRIES = "onyx_game_list_entries"
        }
    }
}
