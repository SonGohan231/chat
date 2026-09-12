package pl.somaskan.questgpt2

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Compact 2D companion for the Quest system panel; MR is handled by ImmersiveActivity. */
class EnvironmentActivity : Activity() {
    private val controller by lazy { EnvironmentController(this) { accept -> panelView.showCameraExplanation(accept) } }
    private lateinit var panelView: EnvironmentView
    private lateinit var root: FrameLayout
    private var compact = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        compact = savedInstanceState?.getBoolean("compact") ?: intent.getBooleanExtra("compact", false)
        root = FrameLayout(this)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        setContentView(root); draw(); ViewCompat.requestApplyInsets(root)
    }
    private fun draw() {
        root.removeAllViews()
        val actions = EnvironmentActions(controller::camera, controller::voice, controller::ask,
            { compact = true; draw() }, { compact = false; draw() },
            { startActivity(Intent(this, MainActivity::class.java)); finish() },
            { if (QuestRuntime.isQuest(this)) QuestRuntime.openEnvironment(this) else Hub.note("Tryb MR wymaga Meta Quest 3 lub 3S. Ten panel pokazuje podgląd Camera2.") },
            { Hub.stopAll(); finish() })
        panelView = EnvironmentView(this, compact, actions)
        root.addView(panelView, FrameLayout.LayoutParams(if (compact) -2 else -1, if (compact) -2 else -1, Gravity.TOP or Gravity.END))
    }
    override fun onSaveInstanceState(out: Bundle) { out.putBoolean("compact", compact); super.onSaveInstanceState(out) }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results); controller.permissionResult(code, results)
    }
}
