package pl.somaskan.questgptfree

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.WebViewClient
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class FreePanelTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private fun start() {
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Otwórz ChatGPT")),10000))
    }
    private fun capture(name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/questgpt-free")
        device.executeShellCommand("screencap -p /sdcard/Download/questgpt-free/$name.png")
    }
    @Test fun freeLaunchHasNoApiClientOrBackgroundAudioService() {
        start()
        assertFalse(device.hasObject(By.text("Połączenie")))
        assertFalse(device.hasObject(By.text("Zapisz i testuj")))
        val info=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS)
        val services=info.services.orEmpty()
        assertEquals(1,services.size)
        assertTrue(services[0].name.endsWith("SnapshotService"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        assertFalse(runCatching {Class.forName("pl.somaskan.questgpt2.Api")}.isSuccess)
        capture("free-home")
    }
    @Test fun miniHomeFitsQuestWidthAndRemainsScrollable() {
        try {
            device.executeShellCommand("wm size 768x1000")
            device.executeShellCommand("wm density 320")
            context.startActivity(Intent(context,MiniActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("Free · Mini")),10000))
            val browser=device.findObject(By.text("Przeglądarka"))
            assertNotNull(browser)
            assertTrue(browser.visibleBounds.width()>180)
            assertTrue(browser.visibleBounds.bottom<=device.displayHeight)
            assertTrue(device.hasObject(By.scrollable(true)))
            capture("free-mini")
        } finally {
            device.executeShellCommand("wm size reset")
            device.executeShellCommand("wm density reset")
        }
    }
    @Test fun canceledSnapshotDoesNotCreateImage() {
        start()
        val before=SnapshotStore.latest(context)
        device.findObject(By.text("Zrzut za 5 s")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Lokalny zrzut ekranu")),5000))
        device.findObject(By.res("android","button2")).click()
        assertEquals(before,SnapshotStore.latest(context))
    }
    @Test fun actualProjectionSavesPublicImageAndEndsService() {
        start()
        val before=SnapshotStore.latest(context)
        device.findObject(By.text("Zrzut za 5 s")).click()
        device.findObject(By.res("android","button1")).click()
        val consent=device.wait(Until.findObject(By.text(Pattern.compile("(?i)start now|start recording|start"))),8000)
        assertNotNull("Android must ask for screen consent",consent)
        consent.click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Wróć do gry przyciskiem Meta")),8000))
        assertTrue(device.wait(Until.hasObject(By.textContains("Zrzut zapisany:")),18000))
        val uri=SnapshotStore.latest(context)
        assertNotNull(uri);assertNotEquals(before,uri)
        assertTrue(context.contentResolver.openAssetFileDescriptor(uri!!,"r")!!.use {it.length>1000})
        device.waitForIdle()
        val services=device.executeShellCommand("dumpsys activity services pl.somaskan.questgptfree")
        assertFalse("One-shot capture must release foreground service",services.contains("ServiceRecord{"))
        device.findObject(By.text("Ostatni zrzut")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Ostatni zrzut Questa")),5000))
        capture("free-snapshot-preview")
        device.findObject(By.res("android","button1")).click()
    }
    @Test fun webFilePickerAttachesOnlyAfterUserSelectionAndRejectsForeignMicrophone() {
        start()
        val b=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.CYAN)}
        SnapshotStore.save(context,b);b.recycle()
        device.findObject(By.text("Panel w aplikacji")).click()
        val ready=CountDownLatch(1)
        lateinit var panel: FreePanelActivity
        instrumentation.runOnMainSync {
            panel=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<FreePanelActivity>().first()
            val w=panel.web!!
            w.stopLoading()
            w.webViewClient=object: WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView,url: String?) {ready.countDown()}
            }
            // Isolated HTML fixture; does not log in, post to ChatGPT or call any model.
            w.loadDataWithBaseURL("https://chatgpt.com/__questgpt_test__/",
                """<html><meta name="viewport" content="width=device-width"><body style="background:#122233;color:white;font:28px sans-serif"><p>TEST PLIKU</p><button style="font:28px sans-serif" onclick="document.getElementById('file').click()">DOŁĄCZ ZRZUT</button><input hidden id="file" type="file" accept="image/*" onchange="document.getElementById('result').textContent=this.files[0]?'ZAŁĄCZONO':'BRAK'"><p id="result">BRAK</p></body></html>""",
                "text/html","UTF-8",null)
        }
        assertTrue(ready.await(15,TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            var denied=false
            val foreign=object: PermissionRequest() {
                override fun getOrigin()=Uri.parse("https://evil.test")
                override fun getResources()=arrayOf(RESOURCE_AUDIO_CAPTURE)
                override fun grant(resources: Array<out String>?) {fail("Foreign origin must never get microphone")}
                override fun deny() {denied=true}
            }
            panel.web!!.webChromeClient!!.onPermissionRequest(foreign)
            assertTrue(denied)
        }
        val attach=device.wait(Until.findObject(By.text("DOŁĄCZ ZRZUT")),8000)
        assertNotNull(attach)
        attach.click()
        assertTrue(device.wait(Until.hasObject(By.text("Dołącz plik w ChatGPT")),8000))
        device.findObject(By.text("Ostatni zrzut Questa")).click()
        assertTrue(device.wait(Until.hasObject(By.text("ZAŁĄCZONO")),8000))
        capture("free-file-attachment-fixture")
        device.findObject(By.text("Start")).click()
    }
    @Test fun publicChatgptPageCanBeInspectedWithoutAccount() {
        start()
        device.findObject(By.text("Panel w aplikacji")).click()
        device.wait(Until.hasObject(By.text(Pattern.compile("(?i).*log in.*|.*sign up.*|.*verify.*|.*checking.*|.*zaloguj.*"))),15000)
        capture("free-public-chatgpt-network-observation")
        device.dumpWindowHierarchy(java.io.File("/sdcard/Download/questgpt-free/public-page.xml"))
        // This is an observation, not an assertion that authenticated ChatGPT voice works.
        device.findObject(By.text("Start")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Otwórz ChatGPT")),5000))
    }
}
