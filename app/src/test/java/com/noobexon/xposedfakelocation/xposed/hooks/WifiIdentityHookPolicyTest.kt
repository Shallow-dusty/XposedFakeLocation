package com.noobexon.xposedfakelocation.xposed.hooks

import com.noobexon.xposedfakelocation.data.DEFAULT_ENABLE_WIFI_IDENTITY
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_SSID
import com.noobexon.xposedfakelocation.data.normalizeWifiSsid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiIdentityHookPolicyTest {
    @Test
    fun wifiIdentityHooksAreDisabledByDefault() {
        assertFalse(DEFAULT_ENABLE_WIFI_IDENTITY)
    }

    @Test
    fun gateRequiresActiveSpoofingAndExplicitOptIn() {
        assertFalse(WifiIdentityHookPolicy.shouldSpoof(isPlaying = null, wifiIdentityEnabled = true))
        assertFalse(WifiIdentityHookPolicy.shouldSpoof(isPlaying = false, wifiIdentityEnabled = true))
        assertFalse(WifiIdentityHookPolicy.shouldSpoof(isPlaying = true, wifiIdentityEnabled = false))
        assertTrue(WifiIdentityHookPolicy.shouldSpoof(isPlaying = true, wifiIdentityEnabled = true))
    }

    @Test
    fun identityOnlyAppliesToSelectedTargetApps() {
        val identity = WifiIdentity(
            ssid = "CodexLab",
            bssid = "12:34:56:78:9A:BC",
            rssi = -42,
            targetApps = setOf("codex.wifiprobe")
        )

        assertTrue(identity.targets("codex.wifiprobe"))
        assertFalse(identity.targets("com.noobexon.xposedfakelocation"))
    }

    @Test
    fun systemWifiCallerGateIgnoresAttributionTag() {
        val targetApps = setOf("codex.wifiprobe")

        assertTrue(
            WifiIdentityHookPolicy.targetsSystemWifiCaller(
                args = listOf("codex.wifiprobe", "feature-tag"),
                targetApps = targetApps
            )
        )
        assertFalse(
            WifiIdentityHookPolicy.targetsSystemWifiCaller(
                args = listOf("com.example.other", "codex.wifiprobe"),
                targetApps = targetApps
            )
        )
        assertFalse(
            WifiIdentityHookPolicy.targetsSystemWifiCaller(
                args = emptyList(),
                targetApps = targetApps
            )
        )
    }

    @Test
    fun wifiSsidNormalizationUsesUtf8ByteLimit() {
        assertEquals("a".repeat(32), normalizeWifiSsid("a".repeat(32)))
        assertEquals(DEFAULT_WIFI_SSID, normalizeWifiSsid("a".repeat(33)))
        assertEquals("网".repeat(10), normalizeWifiSsid("网".repeat(10)))
        assertEquals(DEFAULT_WIFI_SSID, normalizeWifiSsid("网".repeat(11)))
        assertEquals("😀".repeat(8), normalizeWifiSsid("😀".repeat(8)))
        assertEquals(DEFAULT_WIFI_SSID, normalizeWifiSsid("😀".repeat(9)))
        assertEquals(DEFAULT_WIFI_SSID, normalizeWifiSsid("\uD800"))
    }
}
