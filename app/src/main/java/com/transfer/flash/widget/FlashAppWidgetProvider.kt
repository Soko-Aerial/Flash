package com.transfer.flash.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import com.transfer.flash.MainActivity
import com.transfer.flash.R

/**
 * Flash Home Screen Widget provider (HIB-16).
 *
 * Firmware Mechanics (Transsion Hiber Bit 6 = 64):
 * On Transsion devices (Infinix, Tecno, itel), Hiber freezes background apps on screen-off
 * and tears down open TCP sockets.
 *
 * When an AppWidget is placed on the user's home screen launcher (e.g. `com.transsion.XOSLauncher`),
 * Android's `AppWidgetServiceImpl` notifies Transsion's `TranAppStateManager.hookUpdateWidget`,
 * permanently setting `STATUS_WIDGET` in `AppStateInfo`.
 *
 * In `IMPORT_SCREEN_OFF_FLAG = 389052`, Bit 6 (`widgetState(uid)`) is active:
 * Hiber logs `Hiber/importantScene: widget: packageName=com.transfer.flash` and completely
 * exempts Flash from the screen-off cgroup freeze!
 *
 * Advantages:
 * - 100% official Android platform API (`AppWidgetProvider`)
 * - Zero permissions required
 * - Zero audio focus interference
 * - 0% CPU and 0 bytes network traffic
 * - Genuinely useful home screen quick launcher for user
 */
class FlashAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_PIN_WIDGET) {
            val result = requestPinWidget(context)
            Log.i(TAG, "Pin widget requested via action: result=$result")
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.i(TAG, "FlashAppWidget enabled — STATUS_WIDGET active in Transsion Hiber")
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Log.i(TAG, "FlashAppWidget disabled")
    }

    companion object {
        private const val TAG = "WIDGET"
        const val ACTION_PIN_WIDGET = "com.transfer.flash.action.PIN_WIDGET"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            statusText: String? = null,
        ) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val views = RemoteViews(context.packageName, R.layout.widget_flash).apply {
                setOnClickPendingIntent(R.id.widget_container, pendingIntent)
                if (statusText != null) {
                    setTextViewText(R.id.widget_status, statusText)
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Checks if at least one Flash widget is actively placed on the home screen.
         */
        fun hasActiveWidget(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            val component = ComponentName(context, FlashAppWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            return ids != null && ids.isNotEmpty()
        }

        /**
         * Returns true if pinning widgets is supported by the device's launcher (API 26+).
         */
        fun isPinningSupported(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val manager = context.getSystemService(AppWidgetManager::class.java) ?: return false
            return manager.isRequestPinAppWidgetSupported
        }

        /**
         * Requests the launcher to pin the Flash widget to the home screen.
         * Shows the system confirmation dialog.
         */
        fun requestPinWidget(context: Context): Boolean {
            if (!isPinningSupported(context)) return false
            val manager = context.getSystemService(AppWidgetManager::class.java) ?: return false
            val component = ComponentName(context, FlashAppWidgetProvider::class.java)
            return try {
                manager.requestPinAppWidget(component, null, null)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to request pinning widget", t)
                false
            }
        }
    }
}
