package example.faceclaw

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.faceclaw.sdk.*
import java.util.concurrent.Executors

/** Copy into an Android app and declare the SDK service contract from the README. */
class CanvasAppService : FaceclawAppService() {
    private var width = 0
    private var height = 0
    private var clicks = 0
    private val renderer = Executors.newSingleThreadExecutor()

    override fun onSessionReady(session: FaceclawSession) {
        session.windowSurface().setCanvasRenderer(renderer) { canvas, _ -> draw(canvas) }
    }

    override fun onHostSnapshot(snapshot: HostSnapshot) {
        width = snapshot.windowWidth
        height = snapshot.windowHeight
    }

    override fun onInput(surface: RenderSurface, event: FaceclawInputEvent) {
        if (event.type == "click") clicks++
        surface.invalidate(InvalidateReason.INPUT)
    }

    override fun onControlEvent(event: ControlEvent) = Unit

    private fun draw(canvas: Canvas) {
        width = canvas.width; height = canvas.height
        canvas.drawColor(Color.BLACK)
        Ui.card(canvas, 12f, 12f, width - 12f, height - 12f, 6f, Color.DKGRAY)
        Ui.text(canvas, "Hello from an APK", 26f, 42f, 18f, Color.WHITE)
        Ui.text(canvas, "Clicks: $clicks", 26f, 72f, 16f, Color.WHITE)
        canvas.drawCircle(width - 42f, height - 42f, 12f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    override fun onDestroy() {
        renderer.shutdownNow()
        super.onDestroy()
    }
}
