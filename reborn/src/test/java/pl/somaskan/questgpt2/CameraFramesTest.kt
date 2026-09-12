package pl.somaskan.questgpt2

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CameraFramesTest {
    @Test fun paddedInterleavedPlanesRespectCropAndBufferPosition() {
        val y = ByteBuffer.wrap(byteArrayOf(99, 99, 1, 2, 3, 4, 0, 0, 5, 6, 7, 8, 0, 0, 9, 10, 11, 12, 0, 0, 13, 14, 15, 16, 0, 0))
        y.position(2)
        val u = ByteBuffer.wrap(byteArrayOf(40, 88, 41, 88, 0, 0, 42, 88, 43, 88))
        val v = ByteBuffer.wrap(byteArrayOf(60, 88, 61, 88, 0, 0, 62, 88, 63, 88))
        val bytes = CameraFrames.nv21(2, 2, 2, 2, listOf(CameraFrames.Plane(y, 6, 1), CameraFrames.Plane(u, 6, 2), CameraFrames.Plane(v, 6, 2)))
        assertArrayEquals(byteArrayOf(11, 12, 15, 16, 63, 43), bytes)
    }
    @Test fun questNeverFallsBackToAvatarOrUnknownCamera() {
        val unknown = CameraDescriptor("avatar", 1, 0, true)
        val absent = CameraDescriptor("unknown", null, null, true)
        assertNull(CameraSelection.choose(listOf(unknown, absent), true))
        val right = CameraDescriptor("rgb-right", 0, 1, false)
        val left = CameraDescriptor("rgb-left", 0, 0, false)
        assertEquals(left, CameraSelection.choose(listOf(unknown, right, left), true))
        assertEquals(right, CameraSelection.choose(listOf(right), true))
    }
    @Test fun imageOriginAndFreshnessControlWhatTheAssistantCanReceive() {
        val screen = Frame("screen", 1000, 1)
        val camera = Frame("camera", 1500, 1, source = VisionSource.CAMERA)
        val state = State(sharing = true, frame = screen, cameraActive = true, cameraFrame = camera, visionSource = VisionSource.CAMERA)
        assertEquals(camera, state.activeFrame(2000))
        assertNull(state.copy(cameraActive = false).activeFrame(2000))
        assertNull(state.activeFrame(7000))
        assertNull(state.copy(cameraFrame = camera.copy(blank = true)).activeFrame(2000))
        assertEquals(screen, state.copy(visionSource = VisionSource.SCREEN).activeFrame(2000))
        assertTrue(Protocol.frameCaption(camera).contains("Otoczenie fizyczne"))
        assertTrue(Protocol.frameCaption(screen).contains("ekran"))
        val request = Protocol.responseBody("test-model", emptyList(), Protocol.frameCaption(camera), listOf(camera.dataUrl))
        val content = request.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("camera", content.getJSONObject(1).getString("image_url"))
    }
    @Test fun blackCameraFramesAreExcluded() {
        assertTrue(CameraFrames.blank(ByteArray(16 * 16 * 3 / 2) { 16 }, 16, 16))
        assertFalse(CameraFrames.blank(ByteArray(16 * 16 * 3 / 2) { 100 }, 16, 16))
    }
}
