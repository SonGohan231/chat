package pl.somaskan.questgpt2

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelSettings
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.ViewPanelRegistration
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature

/** Native Meta passthrough supplies the stereoscopic world; Camera2 supplies RGB frames to AI. */
class ImmersiveActivity : AppSystemActivity() {
    private val controls by lazy { EnvironmentController(this) }
    private var panel: Entity? = null
    private var icon: Entity? = null
    @Volatile private var compact = false
    @Volatile private var reposition = true
    @Volatile private var ready = false
    @Volatile private var followPanel = false
    private var cameraNoticeShown = false
    private var focused = true

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compact = savedInstanceState?.getBoolean("compact") ?: false
        systemManager.registerSystem(object : SystemBase() {
            override fun execute() {
                if (!ready) return
                val viewer = scene.getViewerPose()
                // The compact controls stay at the lower-right edge as the wearer turns.
                icon?.setComponent(Transform(viewer * Pose(Vector3(0.34f, -0.21f, 1.05f))))
                if (reposition || followPanel) {
                    panel?.setComponent(Transform(viewer * Pose(Vector3(0.28f, -0.08f, 1.25f))))
                    reposition = false
                }
                panel?.setComponent(Visible(!compact))
                icon?.setComponent(Visible(compact))
            }
        })
    }
    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
        scene.enablePassthrough(true)
        // No opaque skybox or room mesh is created: the actual world stays visible.
        panel = Entity.create(listOf(Panel(R.id.environment_panel), Transform(Pose(Vector3(0f, 1.3f, 1.2f))), Visible(!compact)))
        icon = Entity.create(listOf(Panel(R.id.environment_icon), Transform(Pose(Vector3(0.3f, 1.2f, 1f))), Visible(compact)))
        reposition = true; ready = true
        runOnUiThread {
            if (!cameraNoticeShown && !isFinishing && !Hub.state.cameraActive) {
                cameraNoticeShown = true
                // Explicit explanation / OS permission before any frames can leave the headset.
                controls.camera()
            }
        }
    }
    override fun registerPanels(): List<PanelRegistration> = listOf(registration(false), registration(true))
    private fun registration(small: Boolean): PanelRegistration = ViewPanelRegistration(
        if (small) R.id.environment_icon else R.id.environment_panel,
        dynamicViewCreator = { _, context ->
            EnvironmentView(context, small, EnvironmentActions(
                controls::camera, controls::voice, controls::ask,
                { compact = true }, { compact = false; reposition = true },
                { launchHomePanel() },
                { followPanel = !followPanel; reposition = true; Hub.note(if (followPanel) "Panel podąża za wzrokiem. Naciśnij Ustaw widok, aby go przypiąć." else "Panel przypięty przed Tobą; ikona zawsze podąża za wzrokiem.") },
                { Hub.stopAll(); launchHomePanel() }
            ))
        },
        settingsCreator = {
            object : PanelSettings {
                override fun toPanelConfigOptions(): PanelConfigOptions = UIPanelSettings(
                    shape = QuadShapeOptions(if (small) 0.22f else 0.80f, if (small) 0.10f else 0.91f),
                    display = DpPerMeterDisplayOptions(dpPerMeter = 800f),
                    style = PanelStyleOptions(themeResourceId = R.style.TransparentTheme)
                ).toPanelConfigOptions().apply { enableTransparent = true; includeGlass = false }
            }
        }
    )
    private fun launchHomePanel() {
        // Meta's documented hybrid handoff returns from an immersive scene into a Home 2D panel.
        Hub.cameraService?.stopCamera("Kamera wyłączona po wyjściu z MR. Możesz włączyć ją ponownie w Skrótach.")
        val panelIntent = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, 70, panelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("extra_launch_in_home_pending_intent", pending))
        finish()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results); controls.permissionResult(code, results)
    }
    override fun onStop() {
        // Entering another immersive app ends this camera session; never claim visibility there.
        Hub.cameraService?.stopCamera("MR w tle — kamera zatrzymana. Włącz ją ponownie po powrocie.")
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("compact", compact); super.onSaveInstanceState(outState) }
    override fun onSpatialShutdown() {
        ready = false
        Hub.cameraService?.stopCamera()
        panel?.destroy(); panel = null; icon?.destroy(); icon = null
        super.onSpatialShutdown()
    }
}
