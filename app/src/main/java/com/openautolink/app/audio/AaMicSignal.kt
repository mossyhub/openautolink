package com.openautolink.app.audio

/** Single exact worker, numeric only. Every complete little-endian PCM16 sample, no prefix sampling. */
internal class AaMicSignal {
    var blocks=0L; private set
    var samples=0L; private set
    var nonzero=0L; private set
    var peak=0; private set
    var sumSquares=0L; private set
    var clipped=0L; private set
    var tailBytes=0L; private set
    var readErrors=0L; private set
    var saturated=false; private set
    private fun add(a:Long,b:Long):Long = if(Long.MAX_VALUE-a < b) { saturated=true; Long.MAX_VALUE } else a+b
    fun read(bytes:ByteArray,count:Int) {
        require(count in 1..minOf(1024,bytes.size))
        blocks=add(blocks,1); tailBytes=add(tailBytes,(count%2).toLong())
        var i=0
        while(i+1<count) {
            val value=(bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)
            samples=add(samples,1)
            if(value!=0) nonzero=add(nonzero,1)
            peak=maxOf(peak,kotlin.math.abs(value))
            if(value==-32768 || value==32767) clipped=add(clipped,1)
            sumSquares=add(sumSquares,value.toLong()*value)
            i+=2
        }
    }
    fun readError() { readErrors=add(readErrors,1) }
    fun line(owner:Long,rate:Int):String = "AA_MIC owner=$owner state=terminal rate=$rate channels=1 blocks=$blocks samples=$samples nonzero=$nonzero peak=$peak sumSquares=$sumSquares rms=${if(samples==0L) 0 else kotlin.math.sqrt(sumSquares.toDouble()/samples).toInt()} clipped=$clipped tailBytes=$tailBytes readErrors=$readErrors saturated=$saturated coverage=full"
}

object AaMicDiagnostics {
    private const val N="[0-9]{1,19}"
    private val terminal=Regex("AA_MIC owner=$N state=terminal rate=(8000|16000|24000|48000) channels=1 blocks=$N samples=$N nonzero=$N peak=(?:[0-9]{1,4}|[12][0-9]{4}|3[0-2][0-9]{3}) sumSquares=$N rms=[0-9]{1,5} clipped=$N tailBytes=$N readErrors=$N saturated=(true|false) coverage=full")
    private val release=Regex("AA_MIC owner=$N state=released")
    fun sanitize(line:String):String? = line.takeIf { it.length<=640 && (terminal.matches(it)||release.matches(it)) }
    fun sink(context:android.content.Context?):(String)->Unit {
        val directory=context?.applicationContext?.getExternalFilesDir(null)?.let { java.io.File(it,"openautolink/logs") }
        return { line -> com.openautolink.app.carplay.HostMediaDiagnosticSink.offerAaMic(directory,line) }
    }
}
