package pl.somaskan.questgpt2

/** Stateful mono PCM16 converter. Pairs are averaged at 48 kHz, keeping odd read boundaries. */
class Pcm24(private val inputRate: Int) {
    private var pending: Int?=null
    init { require(inputRate==24000 || inputRate==48000) }
    fun convert(samples: ShortArray, count: Int): ByteArray {
        require(count in 0..samples.size)
        val out=ByteArray(if(inputRate==24000) count*2 else ((count+if(pending==null)0 else 1)/2)*2)
        var position=0
        fun emit(value:Int) {out[position++]=value.toByte();out[position++]=(value shr 8).toByte()}
        for(i in 0 until count) {
            val value=samples[i].toInt()
            if(inputRate==24000) emit(value)
            else {
                val previous=pending
                if(previous==null) pending=value else {emit((previous+value)/2);pending=null}
            }
        }
        return out
    }
}
