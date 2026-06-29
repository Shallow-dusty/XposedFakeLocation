package com.noobexon.xposedfakelocation.xposed.hooks

internal data class SpoofedWifiIdentity(
    val ssid: String,
    val bssid: String,
    val rssi: Int
)

internal data class SpoofedWifiScanResultSpec(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val capabilities: String
)

internal object WifiScanResultPolicy {
    private const val DEFAULT_FREQUENCY = 2412
    private const val DEFAULT_CAPABILITIES = "[ESS]"

    fun createSpecs(identity: SpoofedWifiIdentity): List<SpoofedWifiScanResultSpec> =
        listOf(
            SpoofedWifiScanResultSpec(
                ssid = identity.ssid,
                bssid = identity.bssid,
                rssi = identity.rssi,
                frequency = DEFAULT_FREQUENCY,
                capabilities = DEFAULT_CAPABILITIES
            )
        )
}
