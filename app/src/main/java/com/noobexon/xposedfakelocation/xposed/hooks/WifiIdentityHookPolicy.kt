package com.noobexon.xposedfakelocation.xposed.hooks

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.noobexon.xposedfakelocation.data.DEFAULT_ENABLE_WIFI_IDENTITY
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_BSSID
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_SSID
import com.noobexon.xposedfakelocation.data.KEY_ENABLE_WIFI_IDENTITY
import com.noobexon.xposedfakelocation.data.KEY_IS_PLAYING
import com.noobexon.xposedfakelocation.data.KEY_TARGET_APPS
import com.noobexon.xposedfakelocation.data.KEY_WIFI_BSSID
import com.noobexon.xposedfakelocation.data.KEY_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.KEY_WIFI_SSID
import com.noobexon.xposedfakelocation.data.MAC_ADDRESS_REGEX
import com.noobexon.xposedfakelocation.data.MAX_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.MIN_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.REMOTE_PREFS_GROUP
import com.noobexon.xposedfakelocation.data.normalizeWifiSsid
import io.github.libxposed.api.XposedInterface

internal data class WifiIdentity(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val targetApps: Set<String>
) {
    fun targets(packageName: String): Boolean = packageName in targetApps
}

/**
 * Shared runtime gate for system-server and app-process Wi-Fi identity hooks.
 *
 * Hook installation and module scope only determine where interception is available. Actual
 * replacement remains disabled unless location spoofing and the dedicated Wi-Fi identity option
 * are both enabled.
 */
internal object WifiIdentityHookPolicy {
    /** Reads the current remote-preference snapshot used by the calling hook process. */
    fun readActiveIdentity(module: XposedInterface): WifiIdentity? = runCatching {
        val preferences = module.getRemotePreferences(REMOTE_PREFS_GROUP)
        if (
            !shouldSpoof(
                isPlaying = preferences.getBoolean(KEY_IS_PLAYING, false),
                wifiIdentityEnabled = preferences.getBoolean(
                    KEY_ENABLE_WIFI_IDENTITY,
                    DEFAULT_ENABLE_WIFI_IDENTITY
                )
            )
        ) {
            return@runCatching null
        }

        val ssid = normalizeWifiSsid(
            preferences.getString(KEY_WIFI_SSID, DEFAULT_WIFI_SSID)
        )
        val bssid = preferences.getString(KEY_WIFI_BSSID, DEFAULT_WIFI_BSSID)
            ?.trim()
            ?.takeIf(MAC_ADDRESS_REGEX::matches)
            ?: DEFAULT_WIFI_BSSID
        val rssi = preferences.getInt(KEY_WIFI_RSSI, DEFAULT_WIFI_RSSI)
            .coerceIn(MIN_WIFI_RSSI, MAX_WIFI_RSSI)
        val targetApps = preferences.getString(KEY_TARGET_APPS, null)
            ?.let { json ->
                runCatching { gson.fromJson<Set<String>>(json, targetAppsType) }.getOrNull()
            }
            .orEmpty()

        WifiIdentity(ssid = ssid, bssid = bssid, rssi = rssi, targetApps = targetApps)
    }.getOrNull()

    internal fun shouldSpoof(
        isPlaying: Boolean?,
        wifiIdentityEnabled: Boolean
    ): Boolean = isPlaying == true && wifiIdentityEnabled

    /**
     * Wi-Fi service calls pass the caller package first and the optional attribution tag second.
     * Only the package identifies target membership.
     */
    internal fun targetsSystemWifiCaller(
        args: List<Any?>?,
        targetApps: Set<String>
    ): Boolean {
        val callingPackage = args?.firstOrNull() as? String ?: return false
        return callingPackage in targetApps
    }

    private val gson = Gson()
    private val targetAppsType = object : TypeToken<Set<String>>() {}.type
}
