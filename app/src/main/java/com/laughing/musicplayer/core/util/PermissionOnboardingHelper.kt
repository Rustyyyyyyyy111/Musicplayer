package com.laughing.musicplayer.core.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionOnboardingHelper {

    fun audioReadPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    fun hasAudioReadPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, audioReadPermission()) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun missingRuntimePermissions(context: Context): List<String> {
        return buildList {
            if (!hasAudioReadPermission(context)) {
                add(audioReadPermission())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(context)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun hasAllFilesAccessPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun buildAllFilesAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            buildAppDetailsSettingsIntent(context)
        }
    }

    fun openAllFilesAccessSettings(context: Context): Boolean {
        val primary = buildAllFilesAccessIntent(context)
        if (runCatching { context.startActivity(primary) }.isSuccess) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val generic = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(generic) }.isSuccess) return true
        }
        return runCatching { context.startActivity(buildAppDetailsSettingsIntent(context)) }.isSuccess
    }

    fun buildAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun isXiaomiOrHyperOsDevice(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty(),
        miuiVersionProp: String? = readSystemProperty("ro.miui.ui.version.name"),
        hyperOsProp: String? = readSystemProperty("ro.mi.os.version.name")
    ): Boolean {
        val normalizedManufacturer = manufacturer.trim().lowercase()
        val normalizedBrand = brand.trim().lowercase()
        val oemMatch = normalizedManufacturer in setOf("xiaomi", "redmi", "poco") ||
            normalizedBrand in setOf("xiaomi", "redmi", "poco")
        val propMatch = !miuiVersionProp.isNullOrBlank() || !hyperOsProp.isNullOrBlank()
        return oemMatch || propMatch
    }

    fun buildMiuiOtherPermissionsIntent(context: Context): Intent {
        return Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            )
            putExtra("extra_pkgname", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openMiuiOtherPermissionsSettings(context: Context): Boolean {
        val explicitMiui = buildMiuiOtherPermissionsIntent(context)
        if (runCatching { context.startActivity(explicitMiui) }.isSuccess) return true

        val genericMiui = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            putExtra("extra_pkgname", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(genericMiui) }.isSuccess) return true

        return runCatching { context.startActivity(buildAppDetailsSettingsIntent(context)) }.isSuccess
    }

    private fun readSystemProperty(key: String): String? {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            (method.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
