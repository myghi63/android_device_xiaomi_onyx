/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.charge

import android.app.AlertDialog
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.preference.SwitchPreference
import android.provider.Settings
import com.xiaomi.settings.R
import com.xiaomi.settings.thermal.ThermalUtils

/**
 * Editor of the game list: the apps that get the gaming thermal scene and, with the
 * "bypass while gaming" switch on, bypass charging.
 */
class GameListSettingsActivity : PreferenceActivity(),
    Preference.OnPreferenceChangeListener {

    private lateinit var games: ThermalUtils

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.game_list_settings)
        setTitle(R.string.game_list_title)
        games = ThermalUtils.getInstance(this)

        val auto = findPreference(ThermalUtils.SETTINGS_GAME_LIST_AUTO) as SwitchPreference
        auto.isChecked = games.isAutoGameEnabled()
        auto.setOnPreferenceChangeListener(this)

        findPreference(KEY_ADD)?.setOnPreferenceClickListener {
            showAddDialog()
            true
        }

        buildRows()
    }

    @Suppress("DEPRECATION")
    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val enabled = newValue as Boolean
        if (preference.key == ThermalUtils.SETTINGS_GAME_LIST_AUTO) {
            Settings.System.putInt(
                contentResolver,
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

    @Suppress("DEPRECATION")
    private fun buildRows() {
        val category = findPreference(KEY_ENTRIES) as PreferenceCategory
        category.removeAll()

        val rows = games.apps().filter { it.listed || games.isRemoved(it.packageName) }
        if (rows.isEmpty()) {
            category.addPreference(Preference(this).apply {
                isPersistent = false
                isSelectable = false
                setTitle(R.string.game_list_empty)
            })
            return
        }
        for (app in rows) {
            category.addPreference(SwitchPreference(this).apply {
                key = app.packageName
                setTitle(app.label)
                summary = app.packageName
                isPersistent = false
                isChecked = app.listed
                setOnPreferenceChangeListener(this@GameListSettingsActivity)
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun showAddDialog() {
        val apps = games.apps()
        if (apps.isEmpty()) return

        val labels = apps.map { it.label }.toTypedArray()
        val picked = BooleanArray(apps.size) { apps[it].listed }

        AlertDialog.Builder(this)
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
