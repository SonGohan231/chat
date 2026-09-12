package pl.somaskan.questgpt2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnvironmentSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun launch() {
        context.startActivity(Intent(context, EnvironmentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Otoczenie · QuestGPT")), 10000))
    }
    private fun capture(name: String) { device.executeShellCommand("mkdir -p /sdcard/Download/questgpt2"); device.executeShellCommand("screencap -p /sdcard/Download/questgpt2/$name.png") }
    @After fun stop() { InstrumentationRegistry.getInstrumentation().runOnMainSync { Hub.stopAll() }; device.pressBack() }
    @Test fun cancelledCameraExplanationDoesNotStartSharing() {
        context.getSharedPreferences("questgpt2_ui", 0).edit().putBoolean("camera_explained", false).commit()
        launch()
        device.findObject(By.text("Kamera")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Pokaż otoczenie asystentowi")), 5000))
        val cancel = device.wait(Until.findObject(By.res("android", "button2")), 3000)
        assertNotNull(cancel); cancel.click()
        assertFalse(Hub.state.cameraActive); assertNull(Hub.state.cameraFrame)
    }
    @Test fun actualCamera2FramesAppearAndCompactStopClearsThem() {
        launch()
        device.findObject(By.text("Kamera")).click()
        if (device.wait(Until.hasObject(By.text("Pokaż otoczenie asystentowi")), 1500)) {
            device.wait(Until.findObject(By.res("android", "button1")), 3000).click()
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val selector = By.res("com.android.permissioncontroller", "permission_allow_foreground_only_button")
            assertNotNull("Android must display camera consent", device.wait(Until.findObject(selector), 10000))
            // On a cold emulator the permission sheet can move after its node first appears.
            // Reacquire the visible button after settling; never grant permission through adb.
            repeat(2) {
                if (device.hasObject(selector)) {
                    device.waitForIdle(3000)
                    device.findObject(selector)?.click()
                    device.wait(Until.gone(selector), 5000)
                }
            }
            assertTrue("Camera consent must be accepted before waiting for frames", device.wait(Until.gone(selector), 5000))
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        }
        val shown = device.wait(Until.hasObject(By.textContains("Kamera otoczenia ·")), 15000)
        if (!shown) capture("environment-camera-failure")
        assertTrue("A real camera frame should reach the visible panel: ${Hub.state.cameraStatus}", shown)
        val f = Hub.state.activeFrame(SystemClock.elapsedRealtime())
        assertNotNull(f); assertEquals(VisionSource.CAMERA, f!!.source); assertFalse(f.blank)
        val bytes = Base64.decode(f.dataUrl.substringAfter(','), Base64.DEFAULT)
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(b); assertTrue(b.width >= 320); assertTrue(b.height >= 240); b.recycle()
        assertFalse("Local preview must not start paid Live by itself", Hub.state.voiceActive)
        assertEquals(0, Hub.state.sentFrames)
        capture("environment-camera2")
        device.findObject(By.desc("Zwiń do ikony")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("Rozwiń menu QuestGPT")), 3000))
        assertTrue(device.hasObject(By.text("Stop")))
        capture("environment-compact")
        device.findObject(By.text("Stop")).click()
        assertTrue(device.wait(Until.hasObject(By.text("AI ○")), 5000))
        assertFalse(Hub.state.cameraActive); assertNull(Hub.state.cameraFrame)
        assertNull(Hub.state.activeFrame(SystemClock.elapsedRealtime()))
        device.findObject(By.desc("Rozwiń menu QuestGPT")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Kamera")), 3000))
        assertTrue(device.hasObject(By.text("Brak świeżego obrazu dla AI.")))
        capture("environment-stopped")
    }
}
