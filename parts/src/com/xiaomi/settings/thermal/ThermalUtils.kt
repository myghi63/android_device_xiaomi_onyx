/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.SystemProperties
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Applies Xiaomi thermal scenes (mi_thermald sconfig) based on the foreground app.
 * Scene values were extracted from this device's decrypted thermal map:
 * writing them also switches the charging scene (mca_smartchg), so anything
 * unknown or idle must restore [SCENE_DEFAULT].
 */
class ThermalUtils private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ThermalUtils"
        private const val SCONFIG = "/sys/class/thermal/thermal_message/sconfig"
        private const val PROP_AUTO = "persist.sys.onyx.thermal.auto"

        /** Game list maintained in the bypass charging page. */
        const val SETTINGS_GAME_LIST = "onyx_game_list"

        /** Apps the user took out of the list: the automatic source must not put them back. */
        const val SETTINGS_GAME_LIST_EXCLUDED = "onyx_game_list_excluded"

        /** Switch that lets apps declaring FLAG_IS_GAME join the list automatically. Default on. */
        const val SETTINGS_GAME_LIST_AUTO = "onyx_game_list_auto"

        const val SCENE_DEFAULT = 19  // MGAME, boot default (keeps fast charging)
        const val SCENE_PHONE = 5
        const val SCENE_CAMERA = 15
        const val SCENE_GAMING = 18

        @Volatile
        private var sInstance: ThermalUtils? = null

        @JvmStatic
        fun getInstance(context: Context): ThermalUtils =
            sInstance ?: synchronized(this) {
                sInstance ?: ThermalUtils(context.applicationContext).also { sInstance = it }
            }

        @JvmStatic
        fun startService(context: Context) {
            if (!SystemProperties.getBoolean(PROP_AUTO, true)) return
            if (!File(SCONFIG).canWrite()) return
            context.startService(Intent(context, ThermalService::class.java))
        }

        private fun writeScene(scene: Int): Boolean {
            return try {
                val file = File(SCONFIG)
                val current = file.readText().trim().toIntOrNull()
                if (current != scene) {
                    file.writeText("$scene\n")
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Cannot write scene $scene", e)
                false
            }
        }
    }

    fun writeDefault(): Boolean = writeScene(SCENE_DEFAULT)

    fun writeSceneFor(packageName: String): Boolean = writeScene(sceneFor(packageName))

    private fun sceneFor(packageName: String): Int {
        if (packageName.isEmpty()) return SCENE_DEFAULT

        if (isDefaultDialer(packageName)) return SCENE_PHONE

        if (isCameraApp(packageName)) return SCENE_CAMERA
        if (isGame(packageName)) return SCENE_GAMING

        return SCENE_DEFAULT
    }

    // android.telecom.DefaultDialerManager is @hide in a mainline module (telecomm)
    // and invisible to this module's Kotlin classpath; the default dialer is the
    // ROLE_DIALER holder, which wins ACTION_DIAL resolution, so resolve instead.
    private fun isDefaultDialer(packageName: String): Boolean =
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
            .resolveActivity(context.packageManager)?.packageName == packageName

    private fun isCameraApp(packageName: String): Boolean = packageName in cameraApps

    private val cameraApps: Set<String> by lazy {
        val pm = context.packageManager
        val actions = listOf(
            MediaStore.ACTION_IMAGE_CAPTURE,
            MediaStore.ACTION_IMAGE_CAPTURE_SECURE,
            MediaStore.ACTION_VIDEO_CAPTURE,
        )
        actions.flatMap { action ->
            pm.queryIntentActivities(Intent(action), 0)
                .map { it.activityInfo.packageName }
        }.toSet()
    }

    private val gameFlags = HashMap<String, Boolean>()

    private fun hasGameFlag(packageName: String): Boolean =
        gameFlags.getOrPut(packageName) {
            try {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Membership of the game list, which is what decides the gaming scene and the bypass:
     * the apps the user put in the list, plus the ones declaring FLAG_IS_GAME while
     * [SETTINGS_GAME_LIST_AUTO] is on. Apps the user took out stay out.
     */
    fun isGame(packageName: String): Boolean =
        gameList().contains(packageName) ||
            (isAutoGameEnabled() && hasGameFlag(packageName) && !isRemoved(packageName))

    fun isAutoGameEnabled(): Boolean =
        Settings.System.getInt(context.contentResolver, SETTINGS_GAME_LIST_AUTO, 1) == 1

    fun addGame(packageName: String) {
        writePackages(SETTINGS_GAME_LIST, gameList() + packageName)
        writePackages(SETTINGS_GAME_LIST_EXCLUDED, excludedGames() - packageName)
    }

    fun removeGame(packageName: String) {
        writePackages(SETTINGS_GAME_LIST, gameList() - packageName)
        writePackages(SETTINGS_GAME_LIST_EXCLUDED, excludedGames() + packageName)
    }

    /** An app that can be launched, with its membership in the game list. */
    class App(val packageName: String, val label: String, val listed: Boolean)

    /** Launchable apps, sorted by label. */
    fun apps(): List<App> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0)
            .mapNotNull { info ->
                if (pm.getLaunchIntentForPackage(info.packageName) == null) return@mapNotNull null
                val listed = isGame(info.packageName)
                App(info.packageName, pm.getApplicationLabel(info).toString(), listed)
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Whether the user took this app out of the list. */
    fun isRemoved(packageName: String): Boolean = excludedGames().contains(packageName)

    private fun gameList(): Set<String> = readPackages(SETTINGS_GAME_LIST)

    private fun excludedGames(): Set<String> = readPackages(SETTINGS_GAME_LIST_EXCLUDED)

    private fun readPackages(key: String): Set<String> =
        Settings.System.getString(context.contentResolver, key)
            ?.split(':', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    private fun writePackages(key: String, packages: Set<String>) {
        Settings.System.putString(
            context.contentResolver,
            key,
            if (packages.isEmpty()) null else packages.joinToString(","),
        )
    }
}
