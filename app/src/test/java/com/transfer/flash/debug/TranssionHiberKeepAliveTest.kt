package com.transfer.flash.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import com.transfer.flash.debug.OemBatteryOptimizationHelper.OemBrand
import com.transfer.flash.debug.TranssionHiberKeepAlive.KeepAliveTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying session-aware tiered Transsion Hiber keep-alive logic,
 * on-demand arming, auto-release on idle, and graceful audio focus handling.
 */
class TranssionHiberKeepAliveTest {

    private class MockContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.transfer.flash"
        override fun getSystemService(name: String): Any? = null
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? = null
        override fun unregisterReceiver(receiver: BroadcastReceiver?) {}
    }

    private val context = MockContext()

    @Test
    fun `non-Transsion OEM devices always evaluate to tier NONE even if session active`() {
        val nonTranssionBrands = listOf(
            OemBrand.SAMSUNG,
            OemBrand.XIAOMI,
            OemBrand.HUAWEI,
            OemBrand.ONEPLUS,
            OemBrand.OPPO_REALME,
            OemBrand.VIVO,
            OemBrand.GENERIC,
        )

        for (brand in nonTranssionBrands) {
            val keepAlive = TranssionHiberKeepAlive(
                context = context,
                brandResolver = { brand },
                bluetoothChecker = { true },
                overlayChecker = { true },
                sessionActiveChecker = { true },
            )
            assertEquals(KeepAliveTier.NONE, keepAlive.determineBestTier())
            assertFalse(keepAlive.canUseBluetoothTier())
            assertFalse(keepAlive.canUseOverlayTier())
        }
    }

    @Test
    fun `Transsion device when idle always evaluates to Tier NONE for natural deep sleep`() {
        val keepAlive = TranssionHiberKeepAlive(
            context = context,
            brandResolver = { OemBrand.TRANSSION },
            bluetoothChecker = { true },
            sessionActiveChecker = { false },
        )
        assertFalse(keepAlive.isSessionActive)
        assertEquals(KeepAliveTier.NONE, keepAlive.determineBestTier())
    }

    @Test
    fun `Transsion device with Bluetooth available selects Tier 1 BLUETOOTH when session active`() {
        val keepAlive = TranssionHiberKeepAlive(
            context = context,
            brandResolver = { OemBrand.TRANSSION },
            bluetoothChecker = { true },
            overlayChecker = { true },
            sessionActiveChecker = { true },
        )
        assertTrue(keepAlive.isSessionActive)
        assertTrue(keepAlive.canUseBluetoothTier())
        assertEquals(KeepAliveTier.BLUETOOTH, keepAlive.determineBestTier())
    }

    @Test
    fun `Transsion device with Bluetooth unavailable and session active selects fallback Tier AUDIO`() {
        val keepAlive = TranssionHiberKeepAlive(
            context = context,
            brandResolver = { OemBrand.TRANSSION },
            bluetoothChecker = { false },
            sessionActiveChecker = { true },
        )
        assertTrue(keepAlive.isSessionActive)
        assertFalse(keepAlive.canUseBluetoothTier())
        assertEquals(KeepAliveTier.AUDIO, keepAlive.determineBestTier())
    }

    @Test
    fun `session active state changes dynamically engage and auto-release keep-alive tier`() {
        val transitions = mutableListOf<Pair<KeepAliveTier, KeepAliveTier>>()

        val keepAlive = TranssionHiberKeepAlive(
            context = context,
            brandResolver = { OemBrand.TRANSSION },
            bluetoothChecker = { false },
            onTierChanged = { old, new -> transitions.add(old to new) },
        )

        // Initially idle: no active session
        assertFalse(keepAlive.isSessionActive)
        assertEquals(KeepAliveTier.NONE, keepAlive.determineBestTier())

        // Active session starts (file transfer starts or peer connects)
        keepAlive.setSessionActive(true)
        assertTrue(keepAlive.isSessionActive)
        assertEquals(KeepAliveTier.AUDIO, keepAlive.determineBestTier())

        // Active session ends (transfer completes or peer disconnects) -> auto-release
        keepAlive.setSessionActive(false)
        assertFalse(keepAlive.isSessionActive)
        assertEquals(KeepAliveTier.NONE, keepAlive.determineBestTier())
    }

    @Test
    fun `transitions dynamically elevate and demote between tiers during active session`() {
        var btAvailable = false
        val transitions = mutableListOf<Pair<KeepAliveTier, KeepAliveTier>>()

        val keepAlive = TranssionHiberKeepAlive(
            context = context,
            brandResolver = { OemBrand.TRANSSION },
            bluetoothChecker = { btAvailable },
            sessionActiveChecker = { true },
            onTierChanged = { old, new -> transitions.add(old to new) },
        )

        assertEquals(KeepAliveTier.AUDIO, keepAlive.determineBestTier())

        // User grants Bluetooth permission & Bluetooth is turned on
        btAvailable = true
        assertEquals(KeepAliveTier.BLUETOOTH, keepAlive.determineBestTier())

        // User turns off Bluetooth
        btAvailable = false
        assertEquals(KeepAliveTier.AUDIO, keepAlive.determineBestTier())
    }

    @Test
    fun `audio focus loss handles transient and permanent loss gracefully without fighting`() {
        val keepAlive = TranssionHiberKeepAlive(
            context = context,
            brandResolver = { OemBrand.TRANSSION },
            bluetoothChecker = { false },
            sessionActiveChecker = { true },
        )

        // Transient loss (e.g. notification chime / navigation prompt) -> pauses without fighting
        keepAlive.onAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        // Permanent loss (e.g. user plays Spotify / YouTube) -> pauses and attempts fallback without fighting
        keepAlive.onAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(keepAlive.isPermanentFocusLossCooldownActive)
        assertEquals(KeepAliveTier.NONE, keepAlive.determineBestTier())

        // Gain (e.g. Spotify stops playback) -> clears cooldown
        keepAlive.onAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(keepAlive.isPermanentFocusLossCooldownActive)
    }

    @Test
    fun `permanent audio focus loss cooldown blocks AUDIO tier until cooldown elapses or foregrounded`() {
        var currentTime = 1_000_000L
        val keepAlive = TranssionHiberKeepAlive(
            context = context,
            brandResolver = { OemBrand.TRANSSION },
            bluetoothChecker = { false },
            sessionActiveChecker = { true },
            timeSource = { currentTime },
        )

        assertEquals(KeepAliveTier.AUDIO, keepAlive.determineBestTier())

        // Permanent loss occurs
        keepAlive.onAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(keepAlive.isPermanentFocusLossCooldownActive)
        assertEquals(KeepAliveTier.NONE, keepAlive.determineBestTier())

        // 5 minutes later: cooldown still active
        currentTime += 5 * 60 * 1000L
        assertTrue(keepAlive.isPermanentFocusLossCooldownActive)
        assertEquals(KeepAliveTier.NONE, keepAlive.determineBestTier())

        // User re-opens Flash (app foregrounded) -> cooldown cleared
        keepAlive.onAppForegrounded()
        assertFalse(keepAlive.isPermanentFocusLossCooldownActive)
        assertEquals(KeepAliveTier.AUDIO, keepAlive.determineBestTier())

        // Another loss occurs, test 10-minute time expiry
        keepAlive.onAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(keepAlive.isPermanentFocusLossCooldownActive)

        // 11 minutes later: cooldown expired
        currentTime += 11 * 60 * 1000L
        assertFalse(keepAlive.isPermanentFocusLossCooldownActive)
        assertEquals(KeepAliveTier.AUDIO, keepAlive.determineBestTier())
    }

    @Test
    fun `screen interactive checker reflects screen state`() {
        var screenInteractive = true
        val keepAlive = TranssionHiberKeepAlive(
            context = context,
            brandResolver = { OemBrand.TRANSSION },
            bluetoothChecker = { false },
            sessionActiveChecker = { true },
            screenInteractiveChecker = { screenInteractive },
        )

        assertTrue(keepAlive.isScreenInteractive)
        screenInteractive = false
        assertFalse(keepAlive.isScreenInteractive)
    }
}
