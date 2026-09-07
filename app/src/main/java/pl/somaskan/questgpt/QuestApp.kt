package pl.somaskan.questgpt

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import pl.somaskan.questgpt.adb.AdbVisionMonitor
import pl.somaskan.questgpt.adb.AgentAccessPolicy

class QuestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                foregroundActivities++
                changingConfiguration = false
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0)
                changingConfiguration = activity.isChangingConfigurations
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        AgentAccessPolicy.refreshRuntime()
        if (AgentServiceController.isEnabled(this)) {
            AgentServiceController.start(this)
        } else {
            AdbVisionMonitor.stop()
        }
    }

    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
        @Volatile private var foregroundActivities: Int = 0
        @Volatile private var changingConfiguration: Boolean = false

        fun isUiForeground(): Boolean = foregroundActivities > 0
        fun shouldHandoffVoiceToService(): Boolean = !isUiForeground() && !changingConfiguration
    }
}
