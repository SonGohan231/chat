package pl.somaskan.questgpt2

import android.content.Intent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PanelSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun capture(name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/questgpt2")
        device.executeShellCommand("screencap -p /sdcard/Download/questgpt2/$name.png")
    }
    private fun assertComposerAboveSystemBar() {
        device.waitForIdle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val panel = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PanelActivity>().first()
            val decor = panel.window.decorView
            val found = arrayListOf<View>()
            decor.findViewsWithText(found, "Wyślij", View.FIND_VIEWS_WITH_TEXT)
            val send = found.filterIsInstance<Button>().first()
            val position = IntArray(2)
            send.getLocationOnScreen(position)
            val safe = decor.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val bottom = panel.windowManager.currentWindowMetrics.bounds.bottom - safe.bottom
            assertTrue("The whole send button must be above the system bar", position[1] + send.height <= bottom)
            assertTrue("The send button must keep a usable touch area", send.height >= 48 * context.resources.displayMetrics.density)
        }
    }
    @Test fun nativePanelsLaunchAndKeepSharedConversation() {
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("QuestGPT 2")),10000))
        assertTrue(device.hasObject(By.text("Wyślij")))
        assertTrue(device.hasObject(By.text("Live")))
        assertComposerAboveSystemBar()
        capture("main")
        device.findObject(By.text("Mini")).click()
        assertTrue(device.wait(Until.hasObject(By.text("QuestGPT · Mini")),8000))
        assertTrue(device.hasObject(By.text("Wyślij")))
        assertComposerAboveSystemBar()
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
        Hub.screenService!!.scheduleSnapshot()
        assertTrue(device.wait(Until.hasObject(By.textContains("Zrzut gotowy")),12000))
        val snapshot=Hub.state.snapshot
        assertNotNull("Delayed screenshot should retain a real frame",snapshot)
        assertFalse(snapshot!!.blank)
        Hub.screenService!!.stopCapture()
        assertFalse(Hub.state.sharing)
        assertNull(Hub.state.frame)
        assertEquals("Saved screenshot survives stopping live capture",snapshot,Hub.state.snapshot)
    }
    @Test fun miniControlsFitAtQuestMinimumWidth() {
        try {
            device.executeShellCommand("wm size 768x1000")
            device.executeShellCommand("wm density 320")
            context.startActivity(Intent(context,MiniActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("QuestGPT · Mini")),10000))
            device.waitForIdle()
            for(text in listOf("Live","Ekran","Zdjęcia","Wycisz","Przerwij","Widok","Wyślij")) {
                val view=device.findObject(By.text(text))
                assertNotNull("Visible control: $text",view)
                val bounds=view.visibleBounds
                assertTrue("Full control width: $text",bounds.width()>=90)
                assertTrue("Full control height: $text",bounds.height()>=90)
                assertTrue("Inside panel: $text",bounds.right<=device.displayWidth)
            }
            assertComposerAboveSystemBar()
            capture("mini-384dp")
        } finally {
            device.executeShellCommand("wm size reset")
            device.executeShellCommand("wm density reset")
        }
    }
}
