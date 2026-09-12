package pl.somaskan.questgpt2

import java.nio.ByteBuffer

data class CameraDescriptor(val id: String, val source: Int?, val position: Int?, val backFacing: Boolean)

object CameraSelection {
    fun choose(cameras: List<CameraDescriptor>, quest: Boolean): CameraDescriptor? =
        cameras.filter { it.source == 0 }.minByOrNull { if (it.position == 0) 0 else 1 }
            ?: if (quest) null else cameras.firstOrNull { it.backFacing }
}

/** Copies YUV_420_888, including padded/interleaved planes and a cropped image. */
object CameraFrames {
    data class Plane(val bytes: ByteBuffer, val rowStride: Int, val pixelStride: Int)
    fun nv21(width: Int, height: Int, left: Int, top: Int, planes: List<Plane>): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(left >= 0 && top >= 0 && left % 2 == 0 && top % 2 == 0 && planes.size == 3)
        val out = ByteArray(width * height * 3 / 2)
        fun read(p: Plane, x: Int, y: Int): Byte =
            p.bytes.get(p.bytes.position() + y * p.rowStride + x * p.pixelStride)
        var i = 0
        for (y in 0 until height) for (x in 0 until width) out[i++] = read(planes[0], left + x, top + y)
        for (y in 0 until height / 2) for (x in 0 until width / 2) {
            out[i++] = read(planes[2], left / 2 + x, top / 2 + y)
            out[i++] = read(planes[1], left / 2 + x, top / 2 + y)
        }
        return out
    }
    fun blank(data: ByteArray, width: Int, height: Int): Boolean {
        for (y in 1..8) for (x in 1..12) {
            if ((data[(y * (height - 1) / 9) * width + x * (width - 1) / 13].toInt() and 255) > 22) return false
        }
        return true
    }
}
