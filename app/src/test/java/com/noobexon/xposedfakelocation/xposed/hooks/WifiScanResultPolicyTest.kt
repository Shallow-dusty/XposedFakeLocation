package com.noobexon.xposedfakelocation.xposed.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import com.android.wifi.x.com.android.modules.utils.ParceledListSlice

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

    @Test
    fun scanResultReturnAdapterWrapsParceledListSliceResults() {
        val original = Any()
        val replacement = listOf("spoofed")
        val wrapped = Any()

        val result = WifiScanResultReturnAdapter.adapt(
            original = original,
            replacement = replacement,
            isParceledListSlice = { it === original },
            createParceledListSlice = {
                assertSame(replacement, it)
                wrapped
            }
        )

        assertSame(wrapped, result)
    }

    @Test
    fun scanResultReturnAdapterWrapsDeclaredParceledListSliceResults() {
        val original = emptyList<String>()
        val replacement = listOf("spoofed")
        val wrapped = Any()

        val result = WifiScanResultReturnAdapter.adapt(
            original = original,
            replacement = replacement,
            returnsParceledListSlice = true,
            isParceledListSlice = { false },
            createParceledListSlice = {
                assertSame(replacement, it)
                wrapped
            }
        )

        assertSame(wrapped, result)
    }

    @Test
    fun scanResultReturnAdapterWrapsMainlineParceledListSliceResults() {
        val replacement = listOf("spoofed")

        val result = WifiScanResultReturnAdapter.adapt(
            original = emptyList<String>(),
            replacement = replacement,
            declaredReturnType = ParceledListSlice::class.java,
            onWrapFailure = null
        )

        assertTrue(result is ParceledListSlice)
        assertSame(replacement, (result as ParceledListSlice).list)
    }

    @Test
    fun scanResultReturnAdapterKeepsListResultsAsLists() {
        val original = emptyList<String>()
        val replacement = listOf("spoofed")

        val result = WifiScanResultReturnAdapter.adapt(
            original = original,
            replacement = replacement,
            isParceledListSlice = { false },
            createParceledListSlice = { error("should not wrap list returns") }
        )

        assertSame(replacement, result)
    }

    @Test
    fun scanResultReturnAdapterFallsBackToOriginalSliceWhenWrappingFails() {
        val original = Any()
        val replacement = listOf("spoofed")

        val result = WifiScanResultReturnAdapter.adapt(
            original = original,
            replacement = replacement,
            isParceledListSlice = { it === original },
            createParceledListSlice = { throw IllegalStateException("no constructor") }
        )

        assertSame(original, result)
    }
}
