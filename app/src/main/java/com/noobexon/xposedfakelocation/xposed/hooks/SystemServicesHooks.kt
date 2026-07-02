// SystemServicesHooks.kt
package com.noobexon.xposedfakelocation.xposed.hooks

import android.location.Location
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.SystemClock
import android.telephony.CellInfo
import android.util.ArrayMap
import android.util.Log
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_BSSID
import com.noobexon.xposedfakelocation.xposed.utils.LocationUtil
import com.noobexon.xposedfakelocation.xposed.utils.PreferencesUtil
import dalvik.system.PathClassLoader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.util.Locale

class SystemServicesHooks(
    private val module: XposedInterface,
    private val classLoader: ClassLoader
) {
    private val tag = "[SystemServicesHooks]"

    fun init() {
        hookLastLocation(classLoader)
        hookCurrentLocation(classLoader)
        hookLocationDispatch(classLoader)
        hookMiuiLocationServices(classLoader)
        hookWifiServices(classLoader)
        hookGnssRegistration(classLoader)
        hookGeofence(classLoader)
        module.log(Log.INFO, tag, "Instantiated hooks successfully")
    }

    private fun hookLastLocation(classLoader: ClassLoader) {
        val serviceClass = findClass(
            classLoader,
            "com.android.server.location.LocationManagerService",
            "com.android.server.LocationManagerService"
        ) ?: return

        hookAll(serviceClass, "getLastLocation") { chain ->
            val result = chain.proceed()
            if (shouldSpoofArgs(chain.args)) {
                val original = result as? Location
                module.log(Log.INFO, tag, "Replaced getLastLocation result.")
                LocationUtil.createFakeLocation(original)
            } else {
                result
            }
        }
    }

    private fun hookCurrentLocation(classLoader: ClassLoader) {
        val serviceClass = findClass(
            classLoader,
            "com.android.server.location.LocationManagerService",
            "com.android.server.LocationManagerService"
        ) ?: return

        hookAll(serviceClass, "getCurrentLocation") { chain ->
            if (shouldSpoofArgs(chain.args)) {
                module.log(Log.INFO, tag, "Blocked getCurrentLocation request for spoofed target.")
                defaultReturnValue(chain.executable as? Method)
            } else {
                chain.proceed()
            }
        }
    }

    private fun hookLocationDispatch(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hookLocationProviderManager(classLoader)
        }
        hookReceiverCallbacks(classLoader)
    }

    private fun hookLocationProviderManager(classLoader: ClassLoader) {
        val providerClass = findClass(
            classLoader,
            "com.android.server.location.provider.LocationProviderManager"
        ) ?: return

        hookAll(providerClass, "onReportLocation") { chain ->
            interceptOnReportLocation(providerClass, chain)
        }
    }

    private fun interceptOnReportLocation(providerClass: Class<*>, chain: Chain): Any? {
        if (PreferencesUtil.getIsPlaying() != true) return chain.proceed()
        val locationResult = chain.args.firstOrNull() ?: return chain.proceed()
        val registrationsField = findField(providerClass, "mRegistrations") ?: return chain.proceed()
        val registrations = registrationsField.get(chain.thisObject) as? Map<*, *> ?: return chain.proceed()

        val locationsField = findField(locationResult.javaClass, "mLocations") ?: return chain.proceed()
        val originalLocations = locationsField.get(locationResult) as? List<*> ?: return chain.proceed()
        val original = originalLocations.firstOrNull() as? Location
        val fakeLocation = LocationUtil.createFakeLocation(original)
        val originalRegistrations = ArrayMap<Any?, Any?>()
        val passthroughRegistrations = ArrayMap<Any?, Any?>()

        registrations.forEach { (key, value) ->
            originalRegistrations[key] = value
            val packageNames = collectPackageNames(value)
            val spoofedPackage = packageNames.firstOrNull { shouldSpoofPackage(it) }
            if (spoofedPackage != null) {
                // Deliver a fake location directly to this target registration and exclude it from
                // the passthrough set so the real location is never pushed to it below.
                locationsField.set(locationResult, arrayListOf(fakeLocation))
                deliverLocationToRegistration(value, locationResult)
                module.log(Log.INFO, tag, "Delivered spoofed provider location to $spoofedPackage.")
            } else {
                passthroughRegistrations[key] = value
            }
        }

        locationsField.set(locationResult, ArrayList(originalLocations))
        registrationsField.set(chain.thisObject, passthroughRegistrations)
        return try {
            chain.proceed()
        } finally {
            registrationsField.set(chain.thisObject, originalRegistrations)
        }
    }

    private fun hookMiuiLocationServices(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!isXiaomiFamilyDevice()) {
            module.log(Log.INFO, tag, "Skipping MIUI location hooks on non-Xiaomi device.")
            return
        }

        val miuiClass = findClass(
            classLoader,
            "com.android.server.location.MiuiBlurLocationManagerImpl",
            "com.android.server.location.MiuiBlurLocationManager"
        ) ?: return

        hookAll(miuiClass, "getBlurryLocation") { chain ->
            val result = chain.proceed()
            if (shouldSpoofArgs(chain.args)) {
                module.log(Log.INFO, tag, "Replaced MIUI blurry location result.")
                replaceLocationLikeResult(result, chain.executable as? Method)
            } else {
                result
            }
        }

        hookAll(miuiClass, "getBlurryCellLocation") { chain ->
            val result = chain.proceed()
            if (shouldSpoofArgs(chain.args)) {
                module.log(Log.INFO, tag, "Cleared MIUI blurry cell location result.")
                null
            } else {
                result
            }
        }

        hookAll(miuiClass, "getBlurryCellInfos") { chain ->
            val result = chain.proceed()
            if (shouldSpoofArgs(chain.args)) {
                module.log(Log.INFO, tag, "Cleared MIUI blurry cell info result.")
                emptyList<CellInfo>()
            } else {
                result
            }
        }

        hookAll(miuiClass, "handleGpsLocationChangedLocked") { chain ->
            if (shouldSpoofArgs(chain.args)) {
                module.log(Log.INFO, tag, "Blocked MIUI GPS location refresh while spoofing.")
                defaultReturnValue(chain.executable as? Method)
            } else {
                chain.proceed()
            }
        }
    }

    private fun hookReceiverCallbacks(classLoader: ClassLoader) {
        val receiverClass = findClass(
            classLoader,
            "com.android.server.location.LocationManagerService\$Receiver",
            "com.android.server.LocationManagerService\$Receiver"
        ) ?: return

        hookAll(receiverClass, "callLocationChangedLocked") { chain ->
            interceptCallLocationChanged(chain)
        }
    }

    private fun interceptCallLocationChanged(chain: Chain): Any? {
        if (PreferencesUtil.getIsPlaying() != true) return chain.proceed()
        // The Receiver itself carries the caller package, so attribute by inspecting `thisObject`.
        if (collectPackageNames(chain.thisObject).none { shouldSpoofPackage(it) }) return chain.proceed()

        val args = chain.args
        val locationArgIndex = args.indexOfFirst { it is Location }
        if (locationArgIndex == -1) return chain.proceed()

        val original = args[locationArgIndex] as? Location
        val newArgs = args.toTypedArray()
        newArgs[locationArgIndex] = LocationUtil.createFakeLocation(original)
        module.log(Log.INFO, tag, "Replaced Receiver.callLocationChangedLocked argument.")
        return chain.proceed(newArgs)
    }

    // Name-based scope attribution for the system-level hooks: a package is spoofed only when it is
    // one of the manager-selected target apps (mirrored into the remote `target_apps` preference).
    private fun shouldSpoofPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return PreferencesUtil.getTargetApps().contains(packageName)
    }

    private fun hookGnssRegistration(classLoader: ClassLoader) {
        val serviceClasses = listOfNotNull(
            findClass(classLoader, "com.android.server.location.gnss.GnssManagerService"),
            findClass(
                classLoader,
                "com.android.server.location.LocationManagerService",
                "com.android.server.LocationManagerService"
            )
        ).distinct()

        val methodsToBlock = listOf(
            "addGnssBatchingCallback",
            "addGnssMeasurementsListener",
            "addGnssNavigationMessageListener",
            "addGnssAntennaInfoListener",
            "registerGnssStatusCallback",
            "registerGnssNmeaCallback"
        )

        serviceClasses.forEach { serviceClass ->
            methodsToBlock.forEach { methodName ->
                hookAll(serviceClass, methodName) { chain ->
                    if (shouldSpoofArgs(chain.args)) {
                        module.log(Log.INFO, tag, "Blocked $methodName while spoofing is enabled.")
                        defaultReturnValue(chain.executable as? Method)
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookWifiServices(classLoader: ClassLoader) {
        val systemServiceManagerClass = findClass(
            classLoader,
            "com.android.server.SystemServiceManager"
        ) ?: return

        hookAll(systemServiceManagerClass, "loadClassFromLoader") { chain ->
            val result = chain.proceed()
            val serviceName = chain.args.getOrNull(0) as? String
            if (serviceName == "com.android.server.wifi.WifiService") {
                val serviceClassLoader = chain.args.getOrNull(1) as? PathClassLoader
                if (serviceClassLoader != null) {
                    val wifiServiceClass = findClass(
                        serviceClassLoader,
                        "com.android.server.wifi.WifiServiceImpl"
                    )
                    if (wifiServiceClass != null) {
                        hookWifiServiceImpl(wifiServiceClass)
                    }
                }
            }
            result
        }
    }

    private fun hookWifiServiceImpl(wifiServiceClass: Class<*>) {
        hookAll(wifiServiceClass, "getScanResults") { chain ->
            val result = chain.proceed()
            if (shouldSpoofArgs(chain.args)) {
                val fakeResults = createFakeScanResults()
                module.log(Log.INFO, tag, "Replaced Wi-Fi scan results while spoofing (${fakeResults.size} result(s)).")
                WifiScanResultReturnAdapter.adapt(
                    original = result,
                    replacement = fakeResults,
                    declaredReturnType = (chain.executable as? Method)?.returnType,
                    onWrapFailure = {
                        module.log(Log.WARN, tag, "Could not wrap Wi-Fi scan results: ${it.message}")
                    }
                )
            } else {
                result
            }
        }

        hookAll(wifiServiceClass, "getConnectionInfo") { chain ->
            val result = chain.proceed()
            if (shouldSpoofArgs(chain.args)) {
                module.log(Log.INFO, tag, "Replaced Wi-Fi connection info while spoofing.")
                createFakeWifiInfo()
            } else {
                result
            }
        }
    }

    private fun createFakeWifiInfo(): WifiInfo =
        readWifiIdentity().let { identity ->
            WifiInfo.Builder()
                .setBssid(identity.bssid)
                .setSsid(identity.ssid.toByteArray())
                .setRssi(identity.rssi)
                .setNetworkId(0)
                .build()
    }

    private fun createFakeScanResults(): List<ScanResult> {
        val specs = WifiScanResultPolicy.createSpecs(readWifiIdentity())
        return specs.mapNotNull { it.toScanResult() }
    }

    private fun readWifiIdentity(): SpoofedWifiIdentity =
        SpoofedWifiIdentity(
            ssid = PreferencesUtil.getWifiSsid(),
            bssid = PreferencesUtil.getWifiBssid().takeIf(MAC_ADDRESS_REGEX::matches) ?: DEFAULT_WIFI_BSSID,
            rssi = PreferencesUtil.getWifiRssi()
        )

    @Suppress("DEPRECATION")
    private fun SpoofedWifiScanResultSpec.toScanResult(): ScanResult? {
        val spec = this
        return runCatching {
            ScanResult().apply {
                applySpoofedSpec(spec)
                setSyntheticInformationElementsCompat(spec.ssid)
            }
        }.onFailure {
            module.log(Log.WARN, tag, "Could not synthesize Wi-Fi scan result: ${it.message}")
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.applySpoofedSpec(spec: SpoofedWifiScanResultSpec) {
        SSID = spec.ssid
        setWifiSsidCompat(spec.ssid)
        BSSID = spec.bssid
        level = spec.rssi
        frequency = spec.frequency
        channelWidth = 0
        centerFreq0 = 0
        centerFreq1 = 0
        capabilities = spec.capabilities
        timestamp = SystemClock.elapsedRealtimeNanos() / 1000L
    }

    private fun ScanResult.setWifiSsidCompat(ssid: String) {
        val wifiSsid = createWifiSsidCompat(ssid) ?: return

        runCatching {
            val setter = javaClass.methods.firstOrNull { method ->
                method.name == "setWifiSsid" && method.parameterTypes.size == 1
            } ?: javaClass.declaredMethods.firstOrNull { method ->
                method.name == "setWifiSsid" && method.parameterTypes.size == 1
            }
            if (setter != null) {
                setter.isAccessible = true
                setter.invoke(this, wifiSsid)
                return
            }

            val field = findField(javaClass, "wifiSsid") ?: return
            field.set(this, wifiSsid)
        }.onFailure {
            module.log(Log.WARN, tag, "Could not set modern Wi-Fi SSID on ScanResult: ${it.message}")
        }
    }

    private fun ScanResult.setSyntheticInformationElementsCompat(ssid: String) {
        runCatching {
            val field = findInformationElementsField(javaClass)
                ?: return module.log(Log.WARN, tag, "Could not find ScanResult information elements field.")
            val componentType = field.type.componentType ?: return
            val ssidElement = createInformationElementCompat(
                componentType,
                id = SSID_INFORMATION_ELEMENT_ID,
                idExt = 0,
                bytes = ssid.toByteArray(StandardCharsets.UTF_8)
            ) ?: return module.log(Log.WARN, tag, "Could not create ScanResult SSID information element.")

            val elements = ReflectArray.newInstance(componentType, 1)
            ReflectArray.set(elements, 0, ssidElement)
            field.set(this, elements)
        }.onFailure {
            module.log(Log.WARN, tag, "Could not initialize synthetic ScanResult information elements: ${it.message}")
        }
    }

    private fun createInformationElementCompat(
        elementClass: Class<*>,
        id: Int,
        idExt: Int,
        bytes: ByteArray
    ): Any? {
        val element = instantiateInformationElementCompat(elementClass) ?: return null
        setIntFieldCompat(element, "id", id)
        setIntFieldCompat(element, "idExt", idExt)
        findField(element.javaClass, "bytes")?.set(element, bytes)
        return element
    }

    private fun instantiateInformationElementCompat(elementClass: Class<*>): Any? {
        val noArgConstructor = elementClass.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
        if (noArgConstructor != null) {
            return runCatching {
                noArgConstructor.isAccessible = true
                noArgConstructor.newInstance()
            }.getOrNull()
        }

        return runCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = unsafeField.get(null)
            unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, elementClass)
        }.getOrNull()
    }

    private fun setIntFieldCompat(target: Any, fieldName: String, value: Int) {
        val field = findField(target.javaClass, fieldName) ?: return
        if (field.type == java.lang.Integer.TYPE) {
            field.setInt(target, value)
        } else {
            field.set(target, value)
        }
    }

    private fun Any.informationElementIdCompat(): Int? {
        return runCatching {
            (findMethod(javaClass, "getId")?.invoke(this) as? Int)
                ?: (findField(javaClass, "id")?.get(this) as? Int)
        }.getOrNull()
    }

    private fun findInformationElementsField(clazz: Class<*>): Field? {
        findField(clazz, "informationElements")?.let { return it }

        var currentClass: Class<*>? = clazz
        while (currentClass != null) {
            currentClass.declaredFields.firstOrNull { field ->
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

    private fun createWifiSsidCompat(ssid: String): Any? {
        val wifiSsidClass = runCatching {
            Class.forName("android.net.wifi.WifiSsid")
        }.getOrNull() ?: return null

        val fromUtf8Text = wifiSsidClass.methods.firstOrNull { method ->
            method.name == "fromUtf8Text" && method.parameterTypes.size == 1
        }
        if (fromUtf8Text != null) {
            return runCatching { fromUtf8Text.invoke(null, ssid) }.getOrNull()
        }

        val fromBytes = wifiSsidClass.methods.firstOrNull { method ->
            method.name == "fromBytes" && method.parameterTypes.contentEquals(arrayOf(ByteArray::class.java))
        }
        if (fromBytes != null) {
            return runCatching { fromBytes.invoke(null, ssid.toByteArray(StandardCharsets.UTF_8)) }.getOrNull()
        }

        val createFromAsciiEncoded = wifiSsidClass.methods.firstOrNull { method ->
            method.name == "createFromAsciiEncoded" && method.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        return createFromAsciiEncoded?.let {
            runCatching { it.invoke(null, encodeSsidForAsciiEncodedFactory(ssid)) }.getOrNull()
        }
    }

    private fun encodeSsidForAsciiEncodedFactory(ssid: String): String =
        ssid.toByteArray(StandardCharsets.UTF_8).joinToString(separator = "") { byte ->
            val value = byte.toInt() and 0xff
            when (value) {
                '\\'.code -> "\\\\"
                '"'.code -> "\\\""
                in 0x20..0x7e -> value.toChar().toString()
                else -> String.format(Locale.US, "\\x%02x", value)
            }
        }

    private fun hookGeofence(classLoader: ClassLoader) {
        val serviceClass = findClass(
            classLoader,
            "com.android.server.location.LocationManagerService",
            "com.android.server.LocationManagerService"
        ) ?: return

        hookAll(serviceClass, "requestGeofence") { chain ->
            if (shouldSpoofArgs(chain.args)) {
                module.log(Log.INFO, tag, "Blocked geofence registration while spoofing is enabled.")
                defaultReturnValue(chain.executable as? Method)
            } else {
                chain.proceed()
            }
        }
    }

    private fun isXiaomiFamilyDevice(): Boolean {
        val markers = listOf("xiaomi", "redmi", "poco")
        val buildInfo = listOf(
            Build.MANUFACTURER.orEmpty(),
            Build.BRAND.orEmpty(),
            Build.PRODUCT.orEmpty(),
            Build.DEVICE.orEmpty()
        )
        return buildInfo.any { info ->
            val lower = info.lowercase()
            markers.any(lower::contains)
        }
    }

    private fun hookAll(clazz: Class<*>, methodName: String, hooker: Hooker) {
        val methods = clazz.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) {
            module.log(Log.WARN, tag, "No method named $methodName on ${clazz.name}")
            return
        }

        var hooked = 0
        methods.forEach { method ->
            try {
                module.hook(method).intercept(hooker)
                hooked++
            } catch (e: Throwable) {
                module.log(Log.ERROR, tag, "Failed hooking ${clazz.name}#$methodName: ${e.message}")
            }
        }

        if (hooked > 0) {
            module.log(Log.INFO, tag, "Hooked ${clazz.name}#$methodName ($hooked overloads).")
        }
    }

    private fun findClass(classLoader: ClassLoader, vararg names: String): Class<*>? {
        names.forEach { name ->
            try {
                return Class.forName(name, false, classLoader)
            } catch (_: Throwable) {
                // Try the next framework class name. AOSP moved these across releases.
            }
        }
        module.log(Log.WARN, tag, "None of these classes were found: ${names.joinToString()}")
        return null
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

    private fun deliverLocationToRegistration(registration: Any?, locationResult: Any) {
        if (registration == null) return
        runCatching {
            val acceptMethod = registration.javaClass.methods.firstOrNull { it.name == "acceptLocationChange" }
                ?: registration.javaClass.declaredMethods.firstOrNull { it.name == "acceptLocationChange" }
                ?: return
            acceptMethod.isAccessible = true
            val operation = acceptMethod.invoke(registration, locationResult)

            val executeMethod = registration.javaClass.methods.firstOrNull { it.name == "executeOperation" }
                ?: registration.javaClass.declaredMethods.firstOrNull { it.name == "executeOperation" }
                ?: return
            executeMethod.isAccessible = true
            executeMethod.invoke(registration, operation)
        }.onFailure {
            module.log(Log.ERROR, tag, "Failed delivering spoofed provider location: ${it.message}")
        }
    }

    // Name-based attribution for pull/query style calls: only spoof while playing and when a target
    // package can be recovered from the call arguments (caller identity, work source, request, etc.).
    private fun shouldSpoofArgs(args: List<Any?>?): Boolean {
        if (PreferencesUtil.getIsPlaying() != true) return false
        return args?.asSequence()
            ?.flatMap { collectPackageNames(it).asSequence() }
            ?.distinct()
            ?.any { shouldSpoofPackage(it) } == true
    }

    private fun collectPackageNames(value: Any?): Set<String> {
        return collectPackageNames(value, mutableSetOf(), 0)
    }

    private fun collectPackageNames(value: Any?, visited: MutableSet<Int>, depth: Int): Set<String> {
        if (value == null || depth > 5) return emptySet()
        if (value is String) return setOfNotNull(value.takeIf(::looksLikePackageName))

        val identity = System.identityHashCode(value)
        if (!visited.add(identity)) return emptySet()

        val packageNames = linkedSetOf<String>()

        if (value is Iterable<*>) {
            value.forEach { packageNames += collectPackageNames(it, visited, depth + 1) }
            return packageNames
        }

        if (value is Map<*, *>) {
            value.forEach { (key, mapValue) ->
                packageNames += collectPackageNames(key, visited, depth + 1)
                packageNames += collectPackageNames(mapValue, visited, depth + 1)
            }
            return packageNames
        }

        packageNames += collectWorkSourcePackageNames(value)

        listOf(
            "mPackageName",
            "packageName",
            "callingPackage",
            "mCallingPackage",
            "mCallerPackageName",
            "callerPackageName",
            "mOpPackageName",
            "opPackageName"
        ).forEach { fieldName ->
            val packageName = findField(value.javaClass, fieldName)?.get(value) as? String
            packageName?.takeIf(::looksLikePackageName)?.let(packageNames::add)
        }

        listOf(
            "getPackageName",
            "getCallingPackage",
            "getCallerPackageName",
            "getOpPackageName"
        ).forEach { methodName ->
            val packageName = runCatching {
                findMethod(value.javaClass, methodName)?.invoke(value) as? String
            }.getOrNull()
            packageName?.takeIf(::looksLikePackageName)?.let(packageNames::add)
        }

        listOf(
            "mIdentity",
            "mCallerIdentity",
            "callerIdentity",
            "identity",
            "mCallingIdentity",
            "callingIdentity",
            "mAttributionSource",
            "attributionSource",
            "mNext",
            "next",
            "mWorkSource",
            "workSource",
            "mRequest",
            "request",
            "mLocationRequest",
            "locationRequest"
        ).forEach { fieldName ->
            packageNames += collectPackageNames(findField(value.javaClass, fieldName)?.get(value), visited, depth + 1)
        }

        listOf("getAttributionSource", "getNext", "getWorkSource", "getLocationRequest").forEach { methodName ->
            val nestedValue = runCatching {
                findMethod(value.javaClass, methodName)?.invoke(value)
            }.getOrNull()
            packageNames += collectPackageNames(nestedValue, visited, depth + 1)
        }

        return packageNames
    }

    private fun collectWorkSourcePackageNames(value: Any): Set<String> {
        if (value.javaClass.name != "android.os.WorkSource") return emptySet()

        val packageNames = linkedSetOf<String>()
        val size = runCatching {
            findMethod(value.javaClass, "size")?.invoke(value) as? Int
        }.getOrNull() ?: return emptySet()

        repeat(size) { index ->
            val name = runCatching {
                findMethod(value.javaClass, "getName", Integer.TYPE)?.invoke(value, index) as? String
            }.getOrNull()
            name?.takeIf(::looksLikePackageName)?.let(packageNames::add)
        }

        return packageNames
    }

    private fun findMethod(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method? {
        var currentClass: Class<*>? = clazz
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                currentClass = currentClass.superclass
            }
        }

        return clazz.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.contentEquals(parameterTypes)
        }?.apply { isAccessible = true }
    }

    private fun looksLikePackageName(value: String?): Boolean {
        return value != null && "." in value && !value.startsWith("android.location.")
    }

    private fun replaceLocationLikeResult(result: Any?, method: Method?): Any? {
        if (result is Location) {
            return LocationUtil.createFakeLocation(result)
        }

        if (result != null) {
            val locationsField = findField(result.javaClass, "mLocations")
            val originalLocations = locationsField?.get(result) as? List<*>
            val original = originalLocations?.firstOrNull() as? Location
            if (locationsField != null) {
                locationsField.set(result, arrayListOf(LocationUtil.createFakeLocation(original)))
                return result
            }

            if (result is List<*>) {
                val original = result.firstOrNull() as? Location
                return listOf(LocationUtil.createFakeLocation(original))
            }

            runCatching {
                val sizeMethod = result.javaClass.methods.firstOrNull { it.name == "size" && it.parameterTypes.isEmpty() }
                val getMethod = result.javaClass.methods.firstOrNull { it.name == "get" && it.parameterTypes.size == 1 }
                val size = sizeMethod?.invoke(result) as? Int ?: return@runCatching
                if (size > 0) {
                    val originalLocation = getMethod?.invoke(result, 0) as? Location ?: return@runCatching
                    val fakeLocation = LocationUtil.createFakeLocation(originalLocation)
                    originalLocation.latitude = fakeLocation.latitude
                    originalLocation.longitude = fakeLocation.longitude
                    originalLocation.altitude = fakeLocation.altitude
                    originalLocation.accuracy = fakeLocation.accuracy
                    originalLocation.speed = fakeLocation.speed
                }
            }.onFailure {
                module.log(Log.ERROR, tag, "Could not inspect MIUI location container: ${it.message}")
            }

            return result
        }

        return if (method?.returnType?.let { Location::class.java.isAssignableFrom(it) } == true) {
            LocationUtil.createFakeLocation(provider = LocationManager.FUSED_PROVIDER)
        } else {
            null
        }
    }

    private fun defaultReturnValue(method: Method?): Any? {
        return when (method?.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0F
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }

    private companion object {
        private val MAC_ADDRESS_REGEX = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
        private const val SSID_INFORMATION_ELEMENT_ID = 0
    }
}
