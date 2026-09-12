package pl.somaskan.questgpt2
import org.junit.Assert.*
import org.junit.Test
class Pcm24Test {
    @Test fun preservesSignedLittleEndianSamples() {
        assertArrayEquals(byteArrayOf(0, -128, -1, -1, 0, 0, -1, 127),Pcm24(24000).convert(shortArrayOf(-32768,-1,0,32767),4))
    }
    @Test fun downsamplingKeepsOddBoundariesAndDoesNotOverflow() {
        val converter=Pcm24(48000)
        assertArrayEquals(byteArrayOf(-1,127),converter.convert(shortArrayOf(32767,32767,-32768),3))
        assertArrayEquals(byteArrayOf(0,-128,0,0),converter.convert(shortArrayOf(-32768,1000,-1000),3))
    }
    @Test fun oneSecondAt48kProducesExactly24kPcm() {
        assertEquals(48000,Pcm24(48000).convert(ShortArray(48000){100},48000).size)
    }
}
