package pl.somaskan.questgpt.adb

import android.content.Context
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

    private fun saveEndpoint(host: String, pairPort: Int? = null, connectPort: Int? = null) {
        prefs.edit().apply {
            putString("host", host)
            pairPort?.let { putInt("pair_port", it) }
            connectPort?.let { putInt("connect_port", it) }
        }.apply()
    }
}
