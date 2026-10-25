package com.openautolink.app.ui.diagnostics

/** schema 2: backend,termination,error,stopError,closeError,restart,joined,closeReturned,
 * preset,rate,channels,format,sharing,performance,mask,sessionTopology,privacy,routeStable,
 * mmap(-1 UNKNOWN),frames,emptyReads, [samples,nonzero,peak,squares,clipped] x2, requestedMillis.
 * No session/device ID, address, source PCM, exception or backend implementation promise.
 */
object ParkedCaptureNumbers {
    fun sanitize(input: LongArray): LongArray {
        require(input.size == 32)
        val v=input.copyOf()
        require(v[0] in 0..1 && v[1] in 0..5)
        require((2..4).all { v[it] in -100000..100000 })
        require((5..7).all { v[it] in 0..1 })
        require(v[8] in 0..10 && v[9] in 0..192000 && v[10] in 0..8 && v[11] in -1..4)
        require(v[12] in -1..1 && v[13] in -1..12 && v[14] in 0..255)
        require(v[15] in -1..1 && v[16] in 0..1 && v[17] in -1..1 && v[18] == -1L)
        require(v[19] in 0..96000 && v[20] in 0..400 && v[31] == 2000L)
        for(i in listOf(21,26)) {
            require(v[i] == v[19] && v[i+1] in 0..v[i] && v[i+2] in 0..32768 &&
                v[i+3] in 0..(v[i]*1073741824L) && v[i+4] in 0..v[i])
        }
        return v
    }
}
