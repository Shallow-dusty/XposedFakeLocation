package com.noobexon.xposedfakelocation.xposed.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiScanResultPolicyTest {
    @Test
    fun spoofedScanResultsMatchConfiguredWifiIdentity() {
        val identity = SpoofedWifiIdentity(
            ssid = "CodexLab",
            bssid = "12:34:56:78:9A:BC",
            rssi = -42
        )

        val results = WifiScanResultPolicy.createSpecs(identity)

        assertEquals(
            listOf(
                SpoofedWifiScanResultSpec(
                    ssid = "CodexLab",
                    bssid = "12:34:56:78:9A:BC",
                    rssi = -42,
                    frequency = 2412,
                    capabilities = "[ESS]"
                )
            ),
            results
        )
    }
}
