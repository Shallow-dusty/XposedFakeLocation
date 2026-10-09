package com.noobexon.xposedfakelocation.xposed.hooks

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * App-side Wi-Fi identity hooks.
 *
 * [SystemServicesHooks] hooks `WifiServiceImpl` inside `system_server`, which requires
 * the Xposed framework to deliver `onSystemServerStarting`. On Magisk-rooted devices
 * where the framework only loads modules into app processes (not `system_server`),
 * those hooks never install. This class provides an equivalent fallback that hooks the
 * client-side `WifiManager` in each scoped app process, so Wi-Fi identity spoofing works
 * regardless of whether `system_server` injection is available.
 *
 * On KernelSU + Zygisk Next (where `system_server` injection works) both hook paths are
 * installed; the app-side hook simply returns the spoofed value before the Binder reply
 * reaches the caller, so there is no conflict.
 */
class AppWifiHooks(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val packageName: String
) {
    private val tag = "[AppWifiHooks]"

    fun init() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            module.log(Log.WARN, tag, "App-side Wi-Fi hooks require Android 11 or newer.")
            return
        }
        hookConnectionInfo()
        hookScanResults()
        module.log(Log.INFO, tag, "Instantiated app-side Wi-Fi hooks successfully")
    }

    /**
     * Hooks `WifiManager.getConnectionInfo()` to return a fake [WifiInfo] when spoofing
     * is enabled and a Wi-Fi identity has been configured.
     */
    private fun hookConnectionInfo() {
        runCatching {
            val wifiManagerClass = Class.forName("android.net.wifi.WifiManager", false, classLoader)
            val method = wifiManagerClass.getDeclaredMethod("getConnectionInfo")
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val identity = WifiIdentityHookPolicy.readActiveIdentity(module)
                if (identity?.targets(packageName) == true) {
                    module.log(Log.INFO, tag, "Replaced Wi-Fi connection info (app-side) while spoofing.")
                    createFakeWifiInfo(identity)
                } else {
                    result
                }
            }
            module.log(Log.INFO, tag, "Hooked WifiManager#getConnectionInfo.")
        }.onFailure {
            module.log(Log.ERROR, tag, "Failed hooking WifiManager#getConnectionInfo: ${it.message}")
        }
    }

    /**
     * Hooks `WifiManager.getScanResults()` to return an empty list while spoofing is
     * enabled, mirroring the [SystemServicesHooks] behaviour of clearing scan results
     * so the real SSID/BSSID of nearby APs is not leaked.
     */
    private fun hookScanResults() {
        runCatching {
            val wifiManagerClass = Class.forName("android.net.wifi.WifiManager", false, classLoader)
            val method = wifiManagerClass.getDeclaredMethod("getScanResults")
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                if (WifiIdentityHookPolicy.readActiveIdentity(module)?.targets(packageName) == true) {
                    module.log(Log.INFO, tag, "Cleared Wi-Fi scan results (app-side) while spoofing.")
                    emptyList<ScanResult>()
                } else {
                    result
                }
            }
            module.log(Log.INFO, tag, "Hooked WifiManager#getScanResults.")
        }.onFailure {
            module.log(Log.ERROR, tag, "Failed hooking WifiManager#getScanResults: ${it.message}")
        }
    }

    /**
     * Builds a fake [WifiInfo] from the configured SSID/BSSID/RSSI preferences. Reuses
     * the same construction logic as [SystemServicesHooks.createFakeWifiInfo] so the
     * spoofed values are identical on both hook paths.
     */
    @Suppress("DEPRECATION")
    private fun createFakeWifiInfo(identity: WifiIdentity): WifiInfo {
        val builder = WifiInfo.Builder()
            .setBssid(identity.bssid)
            .setSsid(identity.ssid.toByteArray())
            .setRssi(identity.rssi)
            .setNetworkId(0)
        return builder.build()
    }
}
