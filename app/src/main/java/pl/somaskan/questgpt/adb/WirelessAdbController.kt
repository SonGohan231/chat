package pl.somaskan.questgpt.adb

import android.content.Context
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
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
            ?: "127.0.0.1"

    fun savedHost(): String = prefs.getString("host", null)?.takeIf { it.isNotBlank() } ?: localHostAddress()
    fun savedPairingPort(): String = prefs.getInt("pair_port", -1).takeIf { it > 0 }?.toString().orEmpty()
    fun savedConnectPort(): String = prefs.getInt("connect_port", -1).takeIf { it > 0 }?.toString().orEmpty()

    suspend fun discoverPairingEndpoint(timeoutMs: Long = 12_000L): AdbEndpoint = withContext(Dispatchers.IO) {
        val host = AtomicReference<String?>(null)
        val port = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(appContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { address, discoveredPort ->
            if (address != null && discoveredPort > 0) {
                host.set(address.hostAddress)
                port.set(discoveredPort)
            }
            latch.countDown()
        }
        mdns.start()
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                error("Nie znaleziono portu parowania. Zostaw otwarte okno „Paruj urządzenie kodem” w ustawieniach Questa.")
            }
        } finally {
            mdns.stop()
        }
        val result = AdbEndpoint(host.get() ?: localHostAddress(), port.get())
        if (result.port <= 0) error("Nie udało się odczytać portu parowania ADB.")
        saveEndpoint(result.host, pairPort = result.port)
        result
    }

    suspend fun pair(host: String, port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) { "Podaj adres IP Questa." }
        require(port in 1..65535) { "Nieprawidłowy port parowania." }
        require(code.length == 6 && code.all(Char::isDigit)) { "Kod parowania musi mieć 6 cyfr." }
        val paired = manager().pair(host.trim(), port, code)
        if (paired) saveEndpoint(host.trim(), pairPort = port)
        paired
    }

    suspend fun autoConnect(timeoutMs: Long = 10_000L): Boolean = withContext(Dispatchers.IO) {
        val mgr = manager()
        if (mgr.isConnected) return@withContext true
        val connected = mgr.autoConnect(appContext, timeoutMs)
        connected || mgr.isConnected
    }

    suspend fun directConnect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) { "Podaj adres IP urządzenia." }
        require(port in 1..65535) { "Nieprawidłowy port połączenia ADB." }
        val mgr = manager()
        if (mgr.isConnected) mgr.disconnect()
        val connected = mgr.connect(host.trim(), port)
        if (connected || mgr.isConnected) saveEndpoint(host.trim(), connectPort = port)
        connected || mgr.isConnected
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (manager().isConnected) manager().disconnect()
    }

    suspend fun runCommand(command: String): String = withContext(Dispatchers.IO) {
        val clean = command.trim()
        require(clean.isNotEmpty()) { "Polecenie jest puste." }
        check(manager().isConnected) { "Najpierw połącz ADB." }

        val stream = manager().openStream("shell:$clean")
        try {
            BufferedReader(InputStreamReader(stream.openInputStream())).use { reader ->
                buildString {
                    var line: String?
                    while (true) {
                        line = reader.readLine() ?: break
                        append(line).append('\n')
                    }
                }.trimEnd().ifBlank { "(polecenie zakończone bez tekstowego wyniku)" }
            }
        } finally {
            runCatching { stream.close() }
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
        val escaped = clean.replace("'", "'\\''")
        runCommand("input text '$escaped'")
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
        require(packageName.matches(Regex("[A-Za-z0-9_.]{3,160}"))) { "Nieprawidłowa nazwa pakietu." }
        val result = runCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        return "Uruchomienie $packageName: ${result.take(500)}"
    }

    suspend fun captureScreenshotPng(autoConnectIfNeeded: Boolean = true): ByteArray = withContext(Dispatchers.IO) {
        val mgr = manager()
        if (!mgr.isConnected && autoConnectIfNeeded) {
            runCatching { mgr.autoConnect(appContext, 4_000L) }
        }
        check(mgr.isConnected) { "ADB nie jest połączone — najpierw użyj Auto Connect." }

        val fromBase64 = runCatching {
            val stream = mgr.openStream("shell:screencap -p | base64")
            try {
                val encoded = stream.openInputStream().bufferedReader(Charsets.US_ASCII).use { it.readText() }
                val compact = encoded.filterNot(Char::isWhitespace)
                Base64.decode(compact, Base64.DEFAULT)
            } finally {
                runCatching { stream.close() }
            }
        }.getOrNull()

        if (fromBase64 != null && isPng(fromBase64)) return@withContext fromBase64

        val raw = runCatching {
            val stream = mgr.openStream("exec:screencap -p")
            try {
                stream.openInputStream().use { it.readBytes() }
            } finally {
                runCatching { stream.close() }
            }
        }.getOrElse { error("Nie udało się pobrać obrazu ekranu przez ADB: ${it.message}") }

        check(isPng(raw)) { "ADB zwróciło dane, ale nie jest to prawidłowy screenshot PNG." }
        raw
    }

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
}
