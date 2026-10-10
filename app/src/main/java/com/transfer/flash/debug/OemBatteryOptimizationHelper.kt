package com.transfer.flash.debug

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.transfer.flash.widget.FlashAppWidgetProvider

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

    val currentBrand: OemBrand by lazy { brandOf(Build.MANUFACTURER, Build.BRAND) }

    /** Pure so it can be unit-tested; [currentBrand] feeds it the device's build fields. */
    fun brandOf(rawManufacturer: String, rawBrand: String): OemBrand {
        val manufacturer = rawManufacturer.lowercase()
        val brand = rawBrand.lowercase()
        return when {
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
            OemBrand.TRANSSION -> transsionCandidates(TranssionSetupGuide.Target.AUTO_START) +
                transsionCandidates(TranssionSetupGuide.Target.POWER_SETTINGS)
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

    private const val PHONE_MASTER = "com.transsion.phonemaster"

    /**
     * Phone Master screens for [target], most specific first. Class names move between HiOS / XOS
     * builds and were never settled on a device (HIB-01), so each target lists every name reported
     * and [resolveTranssionIntent] launches the first one this phone resolves; the Phone Master
     * main screen is the last resort and the AOSP fallbacks follow it.
     */
    internal fun transsionClassNames(target: TranssionSetupGuide.Target): List<String> = when (target) {
        // Verified on an Infinix X6882B, XOS 14.5, Phone Master 6.3.3 (2026-10-09, `pm dump`): the real classes
        // live under com.cyin.himgr; the com.transsion.phonemaster.* names listed first before were wrong there.
        TranssionSetupGuide.Target.AUTO_START -> listOf(
            "com.cyin.himgr.autostart.AutoStartActivity",
            "com.transsion.phonemaster.autostart.AutoStartActivity",
            "com.transsion.phonemaster.autostart.AutoStartManageActivity",
        )
        TranssionSetupGuide.Target.POWER_SETTINGS -> listOf(
            "com.cyin.himgr.powermanager.views.activity.OsPowerActivity",
            "com.cyin.himgr.powermanager.views.activity.PowerManagerActivity",
            "com.transsion.phonemaster.power.activity.PowerSettingsActivity",
        )
        TranssionSetupGuide.Target.LOCK_SCREEN_CLEANUP -> listOf(
            "com.cyin.himgr.applicationmanager.view.activities.LockScreenCleanupActivity",
        )
        else -> emptyList()
    } + "com.transsion.phonemaster.MainActivity"

    private fun transsionCandidates(target: TranssionSetupGuide.Target): List<Intent> =
        transsionClassNames(target).map { Intent().setComponent(ComponentName(PHONE_MASTER, it)) }

    /** The first Phone Master screen for [target] that resolves on this phone, or null. */
    fun resolveTranssionIntent(context: Context, target: TranssionSetupGuide.Target): Intent? {
        val pm = context.packageManager
        return transsionCandidates(target).firstOrNull { isIntentResolvable(pm, it) }
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /** True when Android's own battery optimisation does not apply to this app (below API 23 there is none). */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    /** Opens the screen behind a guide step; falls back to the app's own details page. False if nothing launched. */
    fun openTarget(context: Context, target: TranssionSetupGuide.Target): Boolean {
        if (target == TranssionSetupGuide.Target.WIDGET_PIN) {
            return FlashAppWidgetProvider.requestPinWidget(context)
        }
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        val intent = when (target) {
            TranssionSetupGuide.Target.BATTERY_EXEMPTION ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                } else {
                    null
                }
            TranssionSetupGuide.Target.WIDGET_PIN -> null
            TranssionSetupGuide.Target.AUTO_START,
            TranssionSetupGuide.Target.POWER_SETTINGS,
            TranssionSetupGuide.Target.LOCK_SCREEN_CLEANUP -> resolveTranssionIntent(context, target)
            TranssionSetupGuide.Target.APP_DETAILS -> details
            TranssionSetupGuide.Target.NONE -> null
        }
        for (candidate in listOfNotNull(intent, details)) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(candidate) }.isSuccess) return true
        }
        return false
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
