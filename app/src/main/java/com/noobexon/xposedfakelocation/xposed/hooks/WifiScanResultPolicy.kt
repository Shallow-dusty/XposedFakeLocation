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

internal object WifiScanResultReturnAdapter {
    private const val PARCELED_LIST_SLICE_SIMPLE_NAME = "ParceledListSlice"

    fun adapt(original: Any?, replacement: List<*>): Any =
        adapt(original = original, replacement = replacement, declaredReturnType = null, onWrapFailure = null)

    fun adapt(
        original: Any?,
        replacement: List<*>,
        declaredReturnType: Class<*>?,
        onWrapFailure: ((Throwable) -> Unit)?
    ): Any {
        val sliceClass = parceledListSliceClass(declaredReturnType)
            ?: parceledListSliceClass(original?.javaClass)

        return adapt(
            original = original,
            replacement = replacement,
            returnsParceledListSlice = sliceClass != null,
            isParceledListSlice = ::isParceledListSlice,
            createParceledListSlice = { createParceledListSlice(sliceClass ?: it.javaClass, it) },
            onWrapFailure = onWrapFailure
        )
    }

    internal fun adapt(
        original: Any?,
        replacement: List<*>,
        returnsParceledListSlice: Boolean = false,
        isParceledListSlice: (Any) -> Boolean,
        createParceledListSlice: (List<*>) -> Any,
        onWrapFailure: ((Throwable) -> Unit)? = null
    ): Any {
        if (!returnsParceledListSlice && (original == null || !isParceledListSlice(original))) return replacement

        return runCatching { createParceledListSlice(replacement) }
            .onFailure { onWrapFailure?.invoke(it) }
            .getOrElse { original ?: replacement }
    }

    private fun isParceledListSlice(value: Any): Boolean =
        parceledListSliceClass(value.javaClass) != null

    private fun parceledListSliceClass(type: Class<*>?): Class<*>? =
        type?.takeIf { it.simpleName == PARCELED_LIST_SLICE_SIMPLE_NAME }

    private fun createParceledListSlice(sliceClass: Class<*>, replacement: List<*>): Any {
        val constructor = sliceClass.getDeclaredConstructor(List::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(replacement)
    }
}
