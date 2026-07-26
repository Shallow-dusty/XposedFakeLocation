package com.noobexon.xposedfakelocation.xposed.hooks

import com.android.wifi.x.com.android.modules.utils.ParceledListSlice
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiScanResultPolicyTest {
    @Test
    fun spoofedScanResultsMatchConfiguredWifiIdentity() {
        val identity = WifiIdentity(
            ssid = "CodexLab",
            bssid = "12:34:56:78:9A:BC",
            rssi = -42,
            targetApps = emptySet()
        )

        val results = WifiScanResultPolicy.createSpecs(identity)

        assertEquals(
            listOf(
                SpoofedWifiScanResultSpec(
                    ssid = "CodexLab",
                    bssid = "12:34:56:78:9A:BC",
                    rssi = -42,
                    frequency = 2412,
                    capabilities = "[ESS]",
                    distanceCm = -1,
                    distanceSdCm = -1,
                    operatorFriendlyName = "",
                    venueName = "",
                    ifaceName = ""
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
    fun scanResultReturnAdapterFailsClosedWhenWrappingFails() {
        val original = Any()
        val replacement = listOf("spoofed")

        val result = WifiScanResultReturnAdapter.adapt(
            original = original,
            replacement = replacement,
            isParceledListSlice = { it === original },
            createParceledListSlice = { throw IllegalStateException("no constructor") }
        )

        assertNull(result)
    }

    @Test
    fun informationElementCompatSupportsApi30ZeroArgShape() {
        val bytes = "CodexLab".toByteArray(StandardCharsets.UTF_8)

        val result = WifiScanResultPolicy.createInformationElementCompat(
            elementClass = Api30InformationElement::class.java,
            id = 0,
            idExt = 0,
            bytes = bytes
        ) as Api30InformationElement

        assertEquals(0, result.id)
        assertEquals(0, result.idExt)
        assertArrayEquals(bytes, result.bytes)
    }

    @Test
    fun asciiEncodedFallbackEscapesUtf8Bytes() {
        assertEquals(
            "Codex\\xe7\\xbd\\x91\\xe7\\xbb\\x9c\\\\\\\"",
            WifiScanResultPolicy.encodeSsidForAsciiEncodedFactory("Codex网络\\\"")
        )
    }

    private class Api30InformationElement {
        @JvmField
        var id: Int = -1

        @JvmField
        var idExt: Int = -1

        @JvmField
        var bytes: ByteArray = byteArrayOf()
    }
}
