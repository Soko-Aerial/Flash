package com.transfer.flash.debug

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Resolves OEM-specific battery manager and autostart configuration intents across
 * custom Android ROMs (Xiaomi HyperOS/MIUI, Huawei EMUI, Samsung One UI, Transsion Phone Master, etc.)
 * to allow users to exempt Flash from aggressive process death.
 */
object OemBatteryOptimizationHelper {
    private const val TAG = "OEM_BATTERY"

    enum class OemBrand {
        XIAOMI,
        HUAWEI,
        SAMSUNG,
        TRANSSION,
        OPPO_REALME,
        ONEPLUS,
        VIVO,
        GENERIC,
    }

    val currentBrand: OemBrand by lazy {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        when {
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
                manufacturer.contains("redmi") || brand.contains("redmi") ||
                manufacturer.contains("poco") || brand.contains("poco") -> OemBrand.XIAOMI

            manufacturer.contains("huawei") || brand.contains("huawei") ||
                manufacturer.contains("honor") || brand.contains("honor") -> OemBrand.HUAWEI

            manufacturer.contains("samsung") || brand.contains("samsung") -> OemBrand.SAMSUNG

            manufacturer.contains("transsion") || brand.contains("transsion") ||
                manufacturer.contains("infinix") || brand.contains("infinix") ||
                manufacturer.contains("tecno") || brand.contains("tecno") ||
                manufacturer.contains("itel") || brand.contains("itel") -> OemBrand.TRANSSION

            manufacturer.contains("oppo") || brand.contains("oppo") ||
                manufacturer.contains("realme") || brand.contains("realme") -> OemBrand.OPPO_REALME

            manufacturer.contains("oneplus") || brand.contains("oneplus") -> OemBrand.ONEPLUS

            manufacturer.contains("vivo") || brand.contains("vivo") ||
                manufacturer.contains("iqoo") || brand.contains("iqoo") -> OemBrand.VIVO

            else -> OemBrand.GENERIC
        }
    }

    /**
     * Resolves the best OEM-specific deep link intent, if supported and installed on this device.
     */
    fun resolveOemIntent(context: Context): Intent? {
        val candidates = when (currentBrand) {
            OemBrand.XIAOMI -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
            )
            OemBrand.HUAWEI -> listOf(
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.mainscreen.MainScreenActivity")),
            )
            OemBrand.SAMSUNG -> listOf(
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            )
            OemBrand.TRANSSION -> listOf(
                Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.autostart.AutoStartActivity")),
                Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.MainActivity")),
            )
            OemBrand.OPPO_REALME -> listOf(
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            )
            OemBrand.ONEPLUS -> listOf(
                Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
            )
            OemBrand.VIVO -> listOf(
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.MainGuideActivity")),
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
            )
            OemBrand.GENERIC -> emptyList()
        }

        val pm = context.packageManager
        for (intent in candidates) {
            if (isIntentResolvable(pm, intent)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return intent
            }
        }
        return null
    }

    private fun isIntentResolvable(pm: PackageManager, intent: Intent): Boolean {
        return pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }

    /**
     * Attempts to navigate to the OEM-specific manager, falling back to AOSP battery optimization settings.
     */
    fun openOptimizationSettings(context: Context): Boolean {
        val oemIntent = resolveOemIntent(context)
        if (oemIntent != null) {
            val launched = runCatching {
                context.startActivity(oemIntent)
                true
            }.getOrDefault(false)
            if (launched) {
                Log.i(TAG, "Opened OEM optimization screen for $currentBrand")
                return true
            }
        }

        // Fallback: AOSP battery optimization settings or App details
        val fallbacks = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )

        for (intent in fallbacks) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isIntentResolvable(context.packageManager, intent)) {
                val launched = runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
                if (launched) {
                    Log.i(TAG, "Opened fallback optimization settings: ${intent.action}")
                    return true
                }
            }
        }
        return false
    }
}
