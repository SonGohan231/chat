package pl.somaskan.questgpt2

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PanelSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun capture(name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/questgpt2")
        device.executeShellCommand("screencap -p /sdcard/Download/questgpt2/$name.png")
    }
    @Test fun nativePanelsLaunchAndKeepSharedConversation() {
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("QuestGPT 2")),10000))
        assertTrue(device.hasObject(By.text("Wyślij")))
        assertTrue(device.hasObject(By.text("Live")))
        capture("main")
        device.findObject(By.text("Mini")).click()
        assertTrue(device.wait(Until.hasObject(By.text("QuestGPT · Mini")),8000))
        assertTrue(device.hasObject(By.text("Wyślij")))
        capture("mini")
        device.findObject(By.text("Live")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Połączenie")),5000))
        assertFalse(Hub.state.voiceActive)
        device.findObject(By.res("android", "button2")).click()
    }
    @Test fun keystoreRoundtripNeverStoresPlaintext() {
        val store=CredentialStore(context)
        val fake="sk-test-not-a-real-api-key-0123456789"
        store.saveKey(fake)
        assertTrue(store.hasKey())
        assertEquals(fake,store.readKey())
        val prefs=context.getSharedPreferences("questgpt2_openai",0)
        assertFalse(prefs.all.values.any {it.toString().contains(fake)})
        store.clearKey()
        assertFalse(store.hasKey())
    }
    @Test fun denyingScreenConsentDoesNotStartCapture() {
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Ekran")),8000))
        device.findObject(By.text("Ekran")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Udostępnij swój widok")),5000))
        device.findObject(By.res("android", "button2")).click()
        assertFalse(Hub.state.sharing)
        assertNull(Hub.state.frame)
    }
    @Test fun actualAndroidProjectionProducesAFrameAndStopsCleanly() {
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Ekran")),8000))
        device.findObject(By.text("Ekran")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Udostępnij swój widok")),5000))
        device.findObject(By.res("android", "button1")).click()
        val consent=device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)start now|start recording|start"))),8000)
        assertNotNull("System capture consent should be shown",consent)
        consent.click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Ekran udostępniany")),15000))
        assertNotNull(Hub.state.frame)
        assertFalse(Hub.state.frame!!.blank)
        capture("screen-sharing")
        Hub.screenService!!.stopCapture()
        assertFalse(Hub.state.sharing)
        assertNull(Hub.state.frame)
    }
}
