package com.transfer.flash.radio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide owner of the radio gateway controller.
 *
 * The controller must outlive every Activity: it owns the radio link and decides when the foreground
 * service runs. Built inside the Activity's composition (a `rememberCoroutineScope`), the link died with the
 * Activity while [com.transfer.flash.service.RadioLinkService] kept showing "Connected" and nothing could ever
 * stop it. The scope is confined to the main thread, because the controller's fields are not synchronised.
 * A failed coroutine is logged and ends only itself, never the app or the chat.
 */
object RadioGatewayHolder {
    private const val TAG = "RadioGateway"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "radio coroutine failed: ${e.javaClass.simpleName}") },
    )

    @Volatile
    private var instance: AndroidRadioGatewayController? = null

    /** Main thread only (the first call constructs the controller and may start the stored-on gateway). */
    fun get(context: Context): AndroidRadioGatewayController =
        instance ?: synchronized(this) {
            instance ?: AndroidRadioGatewayController(context.applicationContext, scope).also { instance = it }
        }
}
