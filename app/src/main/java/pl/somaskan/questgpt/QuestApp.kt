package pl.somaskan.questgpt

import android.app.Application
import android.content.Context
import pl.somaskan.questgpt.adb.AdbVisionMonitor

class QuestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        AdbVisionMonitor.start()
    }

    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
    }
}
