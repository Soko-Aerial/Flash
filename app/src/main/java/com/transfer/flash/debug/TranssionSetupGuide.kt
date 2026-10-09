package com.transfer.flash.debug

/**
 * The guided set-up for Transsion phones (Infinix, Tecno, itel; HiOS / XOS / itelOS), HIB-03.
 *
 * Why this exists: on these phones the Hiber freezer stops the whole app UID about 10 s after
 * screen-off, even with a foreground service (ERROR-074, EXP-002). Nothing in code stops that;
 * a person has to switch the per-app exemptions on once per phone (docs/network/
 * TRANSSION-BACKGROUND-DELIVERY-RESEARCH.md, option A). Menu names move between firmware
 * builds, so every step is worded by what to look for, and only the steps that have a screen
 * we can open carry a [Target].
 *
 * Only the first step can be detected ([OemBatteryOptimizationHelper.isIgnoringBatteryOptimizations]);
 * the four overrides are manual and the guide never claims they are done.
 */
object TranssionSetupGuide {

    /** Where "Open" takes the user, resolved by [OemBatteryOptimizationHelper]. */
    enum class Target { BATTERY_EXEMPTION, AUTO_START, POWER_SETTINGS, APP_DETAILS, NONE }

    data class Step(
        val id: String,
        val title: String,
        val body: String,
        val target: Target,
        /** True when the app can read whether the step is done. */
        val detectable: Boolean,
    )

    const val AOSP_EXEMPTION_STEP_ID: String = "aosp-exemption"

    /** The ordered steps. Android's own battery exemption comes first because it is checkable. */
    val steps: List<Step> = listOf(
        Step(
            id = AOSP_EXEMPTION_STEP_ID,
            title = "Allow Flash to run without battery limits",
            body = "Choose \"Allow\" in the system prompt.",
            target = Target.BATTERY_EXEMPTION,
            detectable = true,
        ),
        Step(
            id = "smart-power-saving",
            title = "Turn off Smart Power Saving / Power Boost",
            body = "In Phone Master or Settings > Battery (Battery Lab), switch off Smart Power Saving, " +
                "Power Boost and any \"Sleep mode\" or \"Stop app when idle\" option for Flash.",
            target = Target.POWER_SETTINGS,
            detectable = false,
        ),
        Step(
            id = "auto-start",
            title = "Turn on Auto-start for Flash",
            body = "In Phone Master > Auto-start management, switch Flash on.",
            target = Target.AUTO_START,
            detectable = false,
        ),
        Step(
            id = "freezer",
            title = "Take Flash out of the Freezer",
            body = "Long-press the home screen > Desktop settings > Other settings > Freezer, and make " +
                "sure Flash is not in the list.",
            target = Target.NONE,
            detectable = false,
        ),
        Step(
            id = "lock-recents",
            title = "Lock Flash in recent apps",
            body = "Open the recent apps screen, then tap the Flash card's menu (or pull it down) and choose Lock.",
            target = Target.NONE,
            detectable = false,
        ),
    )

    /** The guide is only offered on a Transsion phone. */
    fun appliesTo(brand: OemBatteryOptimizationHelper.OemBrand): Boolean =
        brand == OemBatteryOptimizationHelper.OemBrand.TRANSSION

    /** The steps still worth showing: the detectable step drops out once it is done. */
    fun pending(ignoringBatteryOptimizations: Boolean): List<Step> =
        steps.filterNot { it.id == AOSP_EXEMPTION_STEP_ID && ignoringBatteryOptimizations }
}
