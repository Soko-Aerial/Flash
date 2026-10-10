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
    enum class Target { BATTERY_EXEMPTION, WIDGET_PIN, AUTO_START, POWER_SETTINGS, LOCK_SCREEN_CLEANUP, APP_DETAILS, NONE }

    data class Step(
        val id: String,
        val title: String,
        val body: String,
        val target: Target,
        /** True when the app can read whether the step is done. */
        val detectable: Boolean,
    )

    const val AOSP_EXEMPTION_STEP_ID: String = "aosp-exemption"
    const val WIDGET_STEP_ID: String = "home-screen-widget"

    /** The ordered steps. The Home Screen Widget comes first because it permanently bypasses Hiber (Bit 6 = 64). */
    val steps: List<Step> = listOf(
        Step(
            id = WIDGET_STEP_ID,
            title = "Add Flash Widget to Home Screen (Recommended)",
            body = "Tap \"Add\" to place the Flash widget on your home screen. On Transsion phones (Infinix, Tecno, itel), this permanently exempts Flash from the background freezer.",
            target = Target.WIDGET_PIN,
            detectable = true,
        ),
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
            body = "In Phone Master > Toolbox > Auto-start management (the screen is titled \"App Launch\"), switch Flash on.",
            target = Target.AUTO_START,
            detectable = false,
        ),
        Step(
            id = "lock-screen-cleanup",
            title = "Keep Flash out of Lock screen cleanup",
            body = "In Phone Master > Lock screen cleanup, switch Flash off so it is not cleaned when the screen locks.",
            target = Target.LOCK_SCREEN_CLEANUP,
            detectable = false,
        ),
        Step(
            id = "freezer",
            title = "Take Flash out of the Freezer (if your phone has it)",
            body = "Long-press the home screen > Desktop settings > Other settings > Freezer, and make " +
                "sure Flash is not in the list. Newer firmware (XOS 14.5) no longer shows this menu.",
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

    /** The steps still worth showing: detectable steps drop out once completed. */
    fun pending(ignoringBatteryOptimizations: Boolean, hasActiveWidget: Boolean = false): List<Step> =
        steps.filterNot {
            (it.id == AOSP_EXEMPTION_STEP_ID && ignoringBatteryOptimizations) ||
            (it.id == WIDGET_STEP_ID && hasActiveWidget)
        }
}
