package pl.somaskan.questgpt.adb

import android.content.Context
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.LocalServices
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class AdbEndpoint(val host: String, val port: Int)

class WirelessAdbController(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("questgpt_adb", Context.MODE_PRIVATE)

    private fun manager(): AbsAdbConnectionManager =
        QuestAdbConnectionManager.getInstance(appContext)

    fun isConnected(): Boolean = runCatching { manager().isConnected }.getOrDefault(false)

    fun localHostAddress(): String =
        runCatching { AndroidUtils.getHostIpAddress(appContext) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: LOOPBACK

    fun savedHost(): String = prefs.getString("host", null)?.takeIf { it.isNotBlank() } ?: localHostAddress()
    fun savedPairingPort(): String = prefs.getInt("pair_port", -1).takeIf { it > 0 }?.toString().orEmpty()
    fun savedConnectPort(): String = prefs.getInt("connect_port", -1).takeIf { it > 0 }?.toString().orEmpty()

    fun connectionSummary(): String {
        val route = prefs.getString("last_route", null).orEmpty()
        val host = prefs.getString("last_connect_host", null).orEmpty()
        val port = prefs.getInt("last_connect_port", -1)
        return if (host.isNotBlank() && port > 0) "$route • $host:$port" else "brak potwierdzonego kanału"
    }

    suspend fun discoverPairingEndpoint(timeoutMs: Long = 12_000L): AdbEndpoint = withContext(Dispatchers.IO) {
        discoverEndpointBlocking(AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs).also {
            saveEndpoint(it.host, pairPort = it.port)
        }
    }

    suspend fun discoverConnectEndpoint(timeoutMs: Long = 8_000L): AdbEndpoint = withContext(Dispatchers.IO) {
        discoverEndpointBlocking(AdbMdns.SERVICE_TYPE_TLS_CONNECT, timeoutMs).also {
            saveEndpoint(it.host, connectPort = it.port)
        }
    }

    suspend fun pair(host: String, port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) { "Podaj adres IP Questa." }
        require(port in 1..65535) { "Nieprawidłowy port parowania." }
        require(code.length == 6 && code.all(Char::isDigit)) { "Kod parowania musi mieć 6 cyfr." }
        val paired = manager().pair(host.trim(), port, code)
        if (paired) {
            saveEndpoint(host.trim(), pairPort = port)
            Thread.sleep(350L)
            runCatching { ensureConnectedInternal(7_000L) }
        }
        paired
    }

    suspend fun autoConnect(timeoutMs: Long = 10_000L): Boolean = withContext(Dispatchers.IO) {
        ensureConnectedInternal(timeoutMs)
    }

    suspend fun isUsable(timeoutMs: Long = 5_000L): Boolean = withContext(Dispatchers.IO) {
        ensureConnectedInternal(timeoutMs)
    }

    suspend fun directConnect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) { "Podaj adres IP urządzenia." }
        require(port in 1..65535) { "Nieprawidłowy port połączenia ADB." }
        val cleanHost = host.trim()
        val mgr = manager()
        val candidates = if (isSelfHost(cleanHost)) linkedSetOf(LOOPBACK, cleanHost) else linkedSetOf(cleanHost)
        candidates.any { candidate ->
            tryConnectAndProbe(mgr, candidate, port, if (candidate == LOOPBACK) "manual-loopback" else "manual")
        }.also { usable ->
            if (usable) saveEndpoint(cleanHost, connectPort = port)
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        forceDisconnect(manager())
        prefs.edit().remove("last_route").remove("last_connect_host").remove("last_connect_port").apply()
    }

    suspend fun runCommand(command: String): String = withContext(Dispatchers.IO) {
        val clean = command.trim()
        require(clean.isNotEmpty()) { "Polecenie jest puste." }
        check(ensureConnectedInternal(8_000L)) {
            "ADB nie ma aktywnego kanału shell. Włącz Debugowanie bezprzewodowe i użyj Auto Connect."
        }
        val mgr = manager()
        runCatching { rawRunCommand(mgr, clean) }.getOrElse { first ->
            forceDisconnect(mgr)
            check(ensureConnectedInternal(8_000L)) {
                "ADB utraciło kanał shell (${first.message}). Nie udało się odzyskać aktualnego portu Wireless ADB."
            }
            rawRunCommand(mgr, clean)
        }
    }

    suspend fun currentActivity(): String = runCatching {
        runCommand("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' | head -n 2")
    }.getOrElse { "Nie udało się odczytać aktywnego okna: ${it.message}" }

    suspend fun displaySize(): String = runCatching {
        runCommand("wm size | head -n 2")
    }.getOrElse { "Rozmiar ekranu nieznany" }

    suspend fun uiHierarchyXml(): String = runCatching {
        runCommand(
            "uiautomator dump /sdcard/questgpt-window.xml >/dev/null 2>&1; " +
                "cat /sdcard/questgpt-window.xml; rm -f /sdcard/questgpt-window.xml"
        )
    }.getOrElse { "" }

    suspend fun tap(x: Int, y: Int): String {
        require(x in 0..10000 && y in 0..10000) { "Współrzędne tap poza zakresem." }
        runCommand("input tap $x $y")
        return "tap($x,$y) wykonany"
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
        require(x1 in 0..10000 && y1 in 0..10000 && x2 in 0..10000 && y2 in 0..10000) {
            "Współrzędne swipe poza zakresem."
        }
        val duration = durationMs.coerceIn(80, 2_500)
        runCommand("input swipe $x1 $y1 $x2 $y2 $duration")
        return "swipe($x1,$y1 -> $x2,$y2, ${duration}ms) wykonany"
    }

    suspend fun inputText(text: String): String {
        val clean = text.take(500)
        require(clean.isNotBlank()) { "Tekst jest pusty." }
        runCommand("input text ${shellQuote(clean)}")
        return "Wpisano tekst (${clean.length} znaków)"
    }

    suspend fun pressKey(key: String): String {
        val normalized = key.uppercase()
        val keyCode = when (normalized) {
            "BACK" -> "KEYCODE_BACK"
            "HOME" -> "KEYCODE_HOME"
            "ENTER" -> "KEYCODE_ENTER"
            "DPAD_UP" -> "KEYCODE_DPAD_UP"
            "DPAD_DOWN" -> "KEYCODE_DPAD_DOWN"
            "DPAD_LEFT" -> "KEYCODE_DPAD_LEFT"
            "DPAD_RIGHT" -> "KEYCODE_DPAD_RIGHT"
            "TAB" -> "KEYCODE_TAB"
            else -> error("Niedozwolony klawisz: $key")
        }
        runCommand("input keyevent $keyCode")
        return "Klawisz $normalized wykonany"
    }

    suspend fun openPackage(packageName: String): String {
        requirePackage(packageName)
        val result = runCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        return "Uruchomienie $packageName: ${result.take(500)}"
    }

    suspend fun openUrl(url: String): String {
        val clean = url.trim().take(2048)
        val parsed = runCatching { URI(clean) }.getOrElse { error("Nieprawidłowy URL.") }
        require(parsed.scheme.equals("http", true) || parsed.scheme.equals("https", true)) { "Dozwolone są tylko adresy http/https." }
        require(!parsed.host.isNullOrBlank()) { "URL nie zawiera prawidłowego hosta." }
        runCommand("am start -a android.intent.action.VIEW -d ${shellQuote(clean)}")
        return "Otworzono URL: $clean"
    }

    suspend fun launchSettings(section: String): String {
        val normalized = section.uppercase()
        val action = when (normalized) {
            "WIFI" -> "android.settings.WIFI_SETTINGS"
            "BLUETOOTH" -> "android.settings.BLUETOOTH_SETTINGS"
            "DISPLAY" -> "android.settings.DISPLAY_SETTINGS"
            "SOUND" -> "android.settings.SOUND_SETTINGS"
            "APPS" -> "android.settings.APPLICATION_SETTINGS"
            "DEVELOPER" -> "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
            "ACCESSIBILITY" -> "android.settings.ACCESSIBILITY_SETTINGS"
            "NETWORK" -> "android.settings.WIRELESS_SETTINGS"
            else -> error("Niedozwolona sekcja ustawień: $section")
        }
        runCommand("am start -a $action")
        return "Otwarto ustawienia: $normalized"
    }

    suspend fun mediaControl(action: String): String {
        val normalized = action.uppercase()
        val keyCode = when (normalized) {
            "PLAY_PAUSE" -> "KEYCODE_MEDIA_PLAY_PAUSE"
            "NEXT" -> "KEYCODE_MEDIA_NEXT"
            "PREVIOUS" -> "KEYCODE_MEDIA_PREVIOUS"
            "STOP" -> "KEYCODE_MEDIA_STOP"
            else -> error("Niedozwolona akcja multimediów: $action")
        }
        runCommand("input keyevent $keyCode")
        return "Sterowanie multimediami: $normalized"
    }

    suspend fun adjustVolume(direction: String, steps: Int): String {
        val normalized = direction.uppercase()
        val keyCode = when (normalized) {
            "UP" -> "KEYCODE_VOLUME_UP"
            "DOWN" -> "KEYCODE_VOLUME_DOWN"
            "MUTE" -> "KEYCODE_VOLUME_MUTE"
            else -> error("Niedozwolona zmiana głośności: $direction")
        }
        val count = if (normalized == "MUTE") 1 else steps.coerceIn(1, 10)
        repeat(count) { runCommand("input keyevent $keyCode") }
        return "Głośność: $normalized${if (normalized == "MUTE") "" else " x$count"}"
    }

    suspend fun setBrightness(percent: Int): String {
        val value = percent.coerceIn(5, 100)
        val raw = ((value / 100.0) * 255.0).toInt().coerceIn(13, 255)
        runCommand("settings put system screen_brightness_mode 0; settings put system screen_brightness $raw")
        return "Jasność ustawiona na około $value%"
    }

    suspend fun toggleWifi(enabled: Boolean): String {
        runCommand("svc wifi ${if (enabled) "enable" else "disable"}")
        return "Wi-Fi: ${if (enabled) "włączone" else "wyłączone"}"
    }

    suspend fun toggleBluetooth(enabled: Boolean): String {
        val verb = if (enabled) "enable" else "disable"
        runCommand("(svc bluetooth $verb 2>/dev/null) || (cmd bluetooth_manager $verb 2>/dev/null) || true")
        return "Bluetooth: wysłano polecenie $verb"
    }

    suspend fun installApk(path: String): String {
        val clean = validateDownloadApkPath(path)
        val result = runCommand("pm install -r --user 0 ${shellQuote(clean)}")
        return "Instalacja APK: ${result.take(1000)}"
    }

    suspend fun uninstallPackage(packageName: String): String {
        requirePackage(packageName)
        val result = runCommand("pm uninstall --user 0 $packageName")
        return "Usuwanie $packageName: ${result.take(1000)}"
    }

    suspend fun captureScreenshotPng(autoConnectIfNeeded: Boolean = true): ByteArray = withContext(Dispatchers.IO) {
        val timeout = if (autoConnectIfNeeded) 8_000L else 5_000L
        check(ensureConnectedInternal(timeout)) {
            "ADB nie ma działającego kanału screencap. Włącz Debugowanie bezprzewodowe i użyj Auto Connect."
        }
        val mgr = manager()
        runCatching { captureScreenshotOnce(mgr) }.getOrElse { first ->
            forceDisconnect(mgr)
            check(ensureConnectedInternal(8_000L)) {
                "ADB rozłączyło się podczas screencap (${first.message}). Nie udało się odzyskać portu."
            }
            captureScreenshotOnce(mgr)
        }
    }

    private fun ensureConnectedInternal(timeoutMs: Long): Boolean {
        val mgr = manager()
        if (mgr.isConnected && probeConnection(mgr)) return true
        forceDisconnect(mgr)

        val savedPort = prefs.getInt("connect_port", -1)
        if (savedPort in 1..65535) {
            if (tryConnectAndProbe(mgr, LOOPBACK, savedPort, "saved-loopback")) return true
            val saved = savedHost()
            if (!isLoopback(saved) && tryConnectAndProbe(mgr, saved, savedPort, "saved-host")) return true
        }

        val mdnsTimeout = timeoutMs.coerceIn(2_000L, 10_000L)
        val discovered = runCatching {
            discoverEndpointBlocking(AdbMdns.SERVICE_TYPE_TLS_CONNECT, mdnsTimeout)
        }.getOrNull()
        if (discovered != null) {
            // libadb explicitly recommends 127.0.0.1 when client and daemon are on the same device.
            if (tryConnectAndProbe(mgr, LOOPBACK, discovered.port, "mDNS-loopback")) {
                saveEndpoint(discovered.host, connectPort = discovered.port)
                return true
            }
            if (tryConnectAndProbe(mgr, discovered.host, discovered.port, "mDNS-host")) {
                saveEndpoint(discovered.host, connectPort = discovered.port)
                return true
            }
        }

        // Last resort for firmwares where mDNS filtering behaves differently.
        val auto = runCatching { mgr.autoConnect(appContext, timeoutMs) }.getOrDefault(false)
        if ((auto || mgr.isConnected) && probeConnection(mgr)) {
            recordConnection(mgr.hostAddress, -1, "libadb-auto")
            return true
        }
        forceDisconnect(mgr)
        return false
    }

    private fun tryConnectAndProbe(
        mgr: AbsAdbConnectionManager,
        host: String,
        port: Int,
        route: String,
    ): Boolean {
        forceDisconnect(mgr)
        val connected = runCatching { mgr.connect(host, port) }.getOrDefault(false)
        if (!(connected || mgr.isConnected)) return false
        if (!probeConnection(mgr)) {
            forceDisconnect(mgr)
            return false
        }
        recordConnection(host, port, route)
        return true
    }

    private fun probeConnection(mgr: AbsAdbConnectionManager): Boolean = runCatching {
        rawRunCommand(mgr, "echo QUESTGPT_ADB_OK").contains("QUESTGPT_ADB_OK")
    }.getOrDefault(false)

    /**
     * Do not use one-shot destinations such as shell:echo ... for health checks.
     * On Quest they can finish before openInputStream() attaches, which surfaces as "Stream closed".
     * Open a persistent shell service first, attach both streams, then execute the command and wait for a marker.
     */
    private fun rawRunCommand(mgr: AbsAdbConnectionManager, clean: String): String {
        val stream = mgr.openStream(LocalServices.SHELL)
        val marker = "__QUESTGPT_DONE_${System.nanoTime()}__"
        return try {
            val reader = BufferedReader(InputStreamReader(stream.openInputStream(), Charsets.UTF_8))
            val output = stream.openOutputStream()
            val script = buildString {
                append(clean).append('\n')
                append("printf '\\n$marker:%s\\n' \"\\$?\"\n")
            }
            output.write(script.toByteArray(Charsets.UTF_8))
            output.flush()

            val text = StringBuilder()
            var markerSeen = false
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("$marker:")) {
                    markerSeen = true
                    break
                }
                text.append(line).append('\n')
            }
            check(markerSeen) { "ADB shell zamknął strumień przed potwierdzeniem wykonania polecenia." }
            text.toString().trimEnd().ifBlank { "(polecenie zakończone bez tekstowego wyniku)" }
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun captureScreenshotOnce(mgr: AbsAdbConnectionManager): ByteArray {
        val path = "/sdcard/questgpt-${System.nanoTime()}.png"
        val encoded = rawRunCommand(
            mgr,
            "screencap -p $path && (base64 $path 2>/dev/null || toybox base64 $path); rm -f $path"
        )
        val compact = encoded.filterNot(Char::isWhitespace)
        val raw = runCatching { Base64.decode(compact, Base64.DEFAULT) }
            .getOrElse { error("Nie udało się zdekodować screencap z ADB: ${it.message}") }
        check(isPng(raw)) { "ADB shell działa, ale screencap nie zwrócił prawidłowego PNG." }
        return raw
    }

    private fun discoverEndpointBlocking(serviceType: String, timeoutMs: Long): AdbEndpoint {
        val host = AtomicReference<String?>(null)
        val port = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(appContext, serviceType) { address, discoveredPort ->
            if (address != null && discoveredPort > 0 && isAddressOwnedByThisDevice(address)) {
                host.set(address.hostAddress)
                port.set(discoveredPort)
                latch.countDown()
            }
        }
        mdns.start()
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                error(
                    if (serviceType == AdbMdns.SERVICE_TYPE_TLS_PAIRING) {
                        "Nie znaleziono portu parowania tego Questa. Zostaw otwarte „Paruj urządzenie kodem”."
                    } else {
                        "Nie znaleziono własnego portu TLS_CONNECT tego Questa przez mDNS."
                    }
                )
            }
        } finally {
            mdns.stop()
        }
        val result = AdbEndpoint(host.get() ?: localHostAddress(), port.get())
        check(result.port in 1..65535) { "mDNS nie zwrócił prawidłowego portu ADB." }
        return result
    }

    private fun isAddressOwnedByThisDevice(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        val target = normalizeAddress(address.hostAddress)
        if (target == normalizeAddress(localHostAddress())) return true
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    if (normalizeAddress(addresses.nextElement().hostAddress) == target) return@runCatching true
                }
            }
            false
        }.getOrDefault(false)
    }

    private fun isSelfHost(host: String): Boolean =
        isLoopback(host) || normalizeAddress(host) == normalizeAddress(localHostAddress())

    private fun isLoopback(host: String): Boolean =
        host == LOOPBACK || host == "::1" || host.equals("localhost", ignoreCase = true)

    private fun normalizeAddress(value: String): String = value.substringBefore('%').trim().lowercase()

    private fun forceDisconnect(mgr: AbsAdbConnectionManager) {
        runCatching { mgr.disconnect() }
    }

    private fun recordConnection(host: String, port: Int, route: String) {
        prefs.edit().apply {
            putString("last_route", route)
            putString("last_connect_host", host)
            if (port > 0) putInt("last_connect_port", port) else remove("last_connect_port")
        }.apply()
    }

    private fun validateDownloadApkPath(path: String): String {
        val clean = path.trim().take(500)
        require(clean.endsWith(".apk", ignoreCase = true)) { "Plik musi mieć rozszerzenie .apk." }
        require(clean.startsWith("/sdcard/Download/") || clean.startsWith("/storage/emulated/0/Download/")) {
            "Agent może instalować APK wyłącznie z folderu Download."
        }
        require(!clean.contains('\n') && !clean.contains('\r') && !clean.contains('\u0000')) { "Nieprawidłowa ścieżka APK." }
        return clean
    }

    private fun requirePackage(packageName: String) {
        require(packageName.matches(Regex("[A-Za-z0-9_.]{3,160}"))) { "Nieprawidłowa nazwa pakietu." }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()

    private fun saveEndpoint(host: String, pairPort: Int? = null, connectPort: Int? = null) {
        prefs.edit().apply {
            putString("host", host)
            pairPort?.let { putInt("pair_port", it) }
            connectPort?.let { putInt("connect_port", it) }
        }.apply()
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
    }
}
