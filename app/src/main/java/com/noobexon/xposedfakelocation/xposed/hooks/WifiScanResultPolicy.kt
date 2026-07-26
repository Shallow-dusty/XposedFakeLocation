package com.noobexon.xposedfakelocation.xposed.hooks

import android.net.wifi.ScanResult
import android.os.SystemClock
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class SpoofedWifiScanResultSpec(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val capabilities: String,
    val distanceCm: Int,
    val distanceSdCm: Int,
    val operatorFriendlyName: String,
    val venueName: String,
    val ifaceName: String
)

internal object WifiScanResultPolicy {
    private const val DEFAULT_FREQUENCY = 2412
    private const val DEFAULT_CAPABILITIES = "[ESS]"
    private const val UNSPECIFIED_DISTANCE_CM = -1
    private const val EMPTY_METADATA = ""
    private const val SSID_INFORMATION_ELEMENT_ID = 0

    fun createSpecs(identity: WifiIdentity): List<SpoofedWifiScanResultSpec> =
        listOf(
            SpoofedWifiScanResultSpec(
                ssid = identity.ssid,
                bssid = identity.bssid,
                rssi = identity.rssi,
                frequency = DEFAULT_FREQUENCY,
                capabilities = DEFAULT_CAPABILITIES,
                distanceCm = UNSPECIFIED_DISTANCE_CM,
                distanceSdCm = UNSPECIFIED_DISTANCE_CM,
                operatorFriendlyName = EMPTY_METADATA,
                venueName = EMPTY_METADATA,
                ifaceName = EMPTY_METADATA
            )
        )

    /**
     * Creates spoofed [ScanResult] list from the current Wi-Fi identity preferences.
     * Shared by [SystemServicesHooks] (system_server side) and [AppWifiHooks] (app side)
     * so both hook paths produce identical spoofed scan results.
     */
    fun createFakeScanResults(
        identity: WifiIdentity,
        onFailure: (message: String, error: Throwable?) -> Unit = { _, _ -> }
    ): List<ScanResult> =
        createSpecs(identity).mapNotNull { it.toScanResult(onFailure) }

    @Suppress("DEPRECATION")
    private fun SpoofedWifiScanResultSpec.toScanResult(
        onFailure: (message: String, error: Throwable?) -> Unit
    ): ScanResult? =
        runCatching {
            ScanResult().apply {
                SSID = ssid
                check(setWifiSsidCompat(ssid)) {
                    "Could not set modern Wi-Fi SSID on synthetic ScanResult."
                }
                BSSID = bssid
                level = rssi
                frequency = this@toScanResult.frequency
                channelWidth = 0
                centerFreq0 = 0
                centerFreq1 = 0
                capabilities = this@toScanResult.capabilities
                setIntFieldCompat(this, "distanceCm", this@toScanResult.distanceCm)
                setIntFieldCompat(this, "distanceSdCm", this@toScanResult.distanceSdCm)
                operatorFriendlyName = this@toScanResult.operatorFriendlyName
                venueName = this@toScanResult.venueName
                check(setObjectFieldCompat(this, "ifaceName", this@toScanResult.ifaceName)) {
                    "Could not normalize synthetic ScanResult interface metadata."
                }
                timestamp = SystemClock.elapsedRealtimeNanos() / 1000L
                check(setSyntheticInformationElementsCompat(ssid)) {
                    "Could not initialize synthetic ScanResult information elements."
                }
            }
        }.onFailure {
            onFailure("Could not synthesize Wi-Fi scan result.", it)
        }.getOrNull()

    private fun ScanResult.setWifiSsidCompat(ssid: String): Boolean {
        val wifiSsid = createWifiSsidCompat(ssid) ?: return false
        return runCatching {
            val setter = javaClass.methods.firstOrNull { method ->
                method.name == "setWifiSsid" && method.parameterTypes.size == 1
            } ?: javaClass.declaredMethods.firstOrNull { method ->
                method.name == "setWifiSsid" && method.parameterTypes.size == 1
            }
            if (setter != null) {
                setter.isAccessible = true
                setter.invoke(this, wifiSsid)
                return@runCatching true
            }
            val field = findField(javaClass, "wifiSsid")
                ?: findField(javaClass, "mWifiSsid")
                ?: return@runCatching false
            field.set(this, wifiSsid)
            true
        }.getOrDefault(false)
    }

    private fun ScanResult.setSyntheticInformationElementsCompat(ssid: String): Boolean =
        runCatching {
            val field = findInformationElementsField(javaClass) ?: return@runCatching false
            val componentType = field.type.componentType ?: return@runCatching false
            val ssidElement = createInformationElementCompat(
                componentType,
                id = SSID_INFORMATION_ELEMENT_ID,
                idExt = 0,
                bytes = ssid.toByteArray(StandardCharsets.UTF_8)
            ) ?: return@runCatching false
            val elements = ReflectArray.newInstance(componentType, 1)
            ReflectArray.set(elements, 0, ssidElement)
            field.set(this, elements)
            field.get(this) != null
        }.getOrDefault(false)

    private fun createWifiSsidCompat(ssid: String): Any? {
        val wifiSsidClass = runCatching {
            Class.forName("android.net.wifi.WifiSsid")
        }.getOrNull() ?: return null

        wifiSsidClass.methods.firstOrNull { method ->
            method.name == "fromUtf8Text" && method.parameterTypes.size == 1
        }?.let { method ->
            runCatching { method.invoke(null, ssid) }.getOrNull()?.let { return it }
        }

        wifiSsidClass.methods.firstOrNull { method ->
            method.name == "fromBytes" &&
                method.parameterTypes.contentEquals(arrayOf(ByteArray::class.java))
        }?.let { method ->
            runCatching {
                method.invoke(null, ssid.toByteArray(StandardCharsets.UTF_8))
            }.getOrNull()?.let { return it }
        }

        return wifiSsidClass.methods.firstOrNull { method ->
            method.name == "createFromAsciiEncoded" &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java))
        }?.let { method ->
            runCatching {
                method.invoke(null, encodeSsidForAsciiEncodedFactory(ssid))
            }.getOrNull()
        }
    }

    internal fun createInformationElementCompat(
        elementClass: Class<*>,
        id: Int,
        idExt: Int,
        bytes: ByteArray
    ): Any? {
        val element = instantiateInformationElementCompat(
            elementClass = elementClass,
            id = id,
            idExt = idExt,
            bytes = bytes
        ) ?: return null
        setIntFieldCompat(element, "id", id)
        setIntFieldCompat(element, "idExt", idExt)
        if (!setInformationElementBytesCompat(element, bytes)) {
            return null
        }
        return element
    }

    private fun setInformationElementBytesCompat(element: Any, bytes: ByteArray): Boolean =
        runCatching {
            val field = findField(element.javaClass, "bytes") ?: return@runCatching false
            val value = when {
                field.type == ByteArray::class.java -> bytes.copyOf()
                ByteBuffer::class.java.isAssignableFrom(field.type) ->
                    ByteBuffer.wrap(bytes.copyOf()).asReadOnlyBuffer()
                else -> return@runCatching false
            }
            field.set(element, value)
            field.get(element) != null
        }.getOrDefault(false)

    private fun instantiateInformationElementCompat(
        elementClass: Class<*>,
        id: Int,
        idExt: Int,
        bytes: ByteArray
    ): Any? {
        elementClass.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }?.let { constructor ->
            runCatching {
                constructor.isAccessible = true
                constructor.newInstance()
            }.getOrNull()?.let { return it }
        }

        elementClass.declaredConstructors.firstOrNull { constructor ->
            constructor.parameterTypes.contentEquals(
                arrayOf(
                    java.lang.Integer.TYPE,
                    java.lang.Integer.TYPE,
                    ByteArray::class.java
                )
            )
        }?.let { constructor ->
            runCatching {
                constructor.isAccessible = true
                constructor.newInstance(id, idExt, bytes)
            }.getOrNull()?.let { return it }
        }

        elementClass.declaredConstructors.firstOrNull { constructor ->
            constructor.parameterTypes.contentEquals(
                arrayOf(java.lang.Integer.TYPE, ByteArray::class.java)
            )
        }?.let { constructor ->
            runCatching {
                constructor.isAccessible = true
                constructor.newInstance(id, bytes)
            }.getOrNull()?.let { return it }
        }

        return allocateInstanceCompat(elementClass)
    }

    private fun allocateInstanceCompat(clazz: Class<*>): Any? = runCatching {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, clazz)
    }.getOrNull()

    private fun setIntFieldCompat(target: Any, fieldName: String, value: Int) {
        val field = findField(target.javaClass, fieldName) ?: return
        if (field.type == java.lang.Integer.TYPE) {
            field.setInt(target, value)
        } else {
            field.set(target, value)
        }
    }

    private fun setObjectFieldCompat(target: Any, fieldName: String, value: Any?): Boolean {
        val field = findField(target.javaClass, fieldName) ?: return true
        return runCatching {
            field.set(target, value)
            field.get(target) == value
        }.getOrDefault(false)
    }

    private fun findField(clazz: Class<*>, fieldName: String): Field? {
        var currentClass: Class<*>? = clazz
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredField(fieldName).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                currentClass = currentClass.superclass
            }
        }
        return null
    }

    private fun findInformationElementsField(clazz: Class<*>): Field? {
        findField(clazz, "informationElements")?.let { return it }
        findField(clazz, "mInformationElements")?.let { return it }

        var currentClass: Class<*>? = clazz
        while (currentClass != null) {
            currentClass.declaredFields.firstOrNull { field ->
                !Modifier.isStatic(field.modifiers) &&
                    field.type.isArray &&
                    field.type.componentType?.name == "android.net.wifi.ScanResult\$InformationElement"
            }?.let { field ->
                field.isAccessible = true
                return field
            }
            currentClass = currentClass.superclass
        }
        return null
    }

    internal fun encodeSsidForAsciiEncodedFactory(ssid: String): String =
        ssid.toByteArray(StandardCharsets.UTF_8).joinToString(separator = "") { byte ->
            val value = byte.toInt() and 0xff
            when (value) {
                '\\'.code -> "\\\\"
                '"'.code -> "\\\""
                in 0x20..0x7e -> value.toChar().toString()
                else -> String.format(Locale.US, "\\x%02x", value)
            }
        }
}

internal object WifiScanResultReturnAdapter {
    private const val PARCELED_LIST_SLICE_SIMPLE_NAME = "ParceledListSlice"

    fun adapt(original: Any?, replacement: List<*>): Any? =
        adapt(original = original, replacement = replacement, declaredReturnType = null, onWrapFailure = null)

    fun adapt(
        original: Any?,
        replacement: List<*>,
        declaredReturnType: Class<*>?,
        onWrapFailure: ((Throwable) -> Unit)?
    ): Any? {
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
    ): Any? {
        if (!returnsParceledListSlice && (original == null || !isParceledListSlice(original))) return replacement

        return runCatching { createParceledListSlice(replacement) }
            .onFailure { onWrapFailure?.invoke(it) }
            .getOrNull()
    }

    private fun isParceledListSlice(value: Any): Boolean =
        parceledListSliceClass(value.javaClass) != null

    private fun parceledListSliceClass(type: Class<*>?): Class<*>? =
        type?.takeIf { it.simpleName == PARCELED_LIST_SLICE_SIMPLE_NAME }

    private fun createParceledListSlice(sliceClass: Class<*>, replacement: List<*>): Any {
        val replacementArrayList = ArrayList(replacement)
        val constructor = sliceClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0].isAssignableFrom(replacementArrayList.javaClass)
        } ?: throw NoSuchMethodException("${sliceClass.name}(List)")
        constructor.isAccessible = true
        val argument = if (constructor.parameterTypes[0].isAssignableFrom(replacement.javaClass)) {
            replacement
        } else {
            replacementArrayList
        }
        return constructor.newInstance(argument)
    }
}
