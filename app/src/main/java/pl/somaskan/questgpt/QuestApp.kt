package pl.somaskan.questgpt

import android.app.Application
import android.content.Context
import pl.somaskan.questgpt.adb.AdbVisionMonitor
import pl.somaskan.questgpt.adb.AgentAccessPolicy

class QuestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        AgentAccessPolicy.refreshRuntime()
        if (AgentServiceController.isEnabled(this)) {
            AgentServiceController.start(this)
        } else {
            // On-demand capture still works even if the persistent service is disabled.
            AdbVisionMonitor.stop()
        }
    }

    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
    }
}
