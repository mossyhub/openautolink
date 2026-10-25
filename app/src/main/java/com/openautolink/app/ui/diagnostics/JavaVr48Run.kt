package com.openautolink.app.ui.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.media.NativeCaptureOwnerRegistry
import java.util.concurrent.atomic.AtomicBoolean

/** One callback-free worker owns Java recorder through stop+release. No stored PCM. */
internal class JavaVr48Run(context:Context, private val policy:()->Boolean):NumericCaptureRun {
    private val app=context.applicationContext
    private val canceled=AtomicBoolean()
    @Volatile private var completed:LongArray?=null
    private var thread:Thread?=null
    override var numbers:LongArray?=null
        private set
    private fun allowed() = !canceled.get() && runCatching(policy).getOrDefault(false)
    override fun begin(){thread=Thread(::capture,"oal-java-vr48").apply { isDaemon=true;start() }}
    override fun cancel(){canceled.set(true)}
    override fun pollClosedJoin():NativeCaptureOwnerRegistry.Receipt? {
        val result=completed ?: return null
        thread?.join();thread=null
        numbers=ParkedCaptureNumbers.sanitize(result)
        return NativeCaptureOwnerRegistry.Receipt(result[1].toInt(),result[2].toInt(),result[3].toInt(),result[4].toInt(),result[5]!=0L)
    }
    override fun disposeJoined(){check(thread==null && numbers!=null)}
    private fun capture() {
        val v=cancelledNumbers(0);v[1]=5
        var record:AudioRecord?=null
        val pcm=ShortArray(960)
        try {
            if(!allowed()){v[1]=2;return}
            val minimum=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT)
            check(minimum in 1..1048576)
            if(!allowed()){v[1]=2;return}
            // Recheck the actual public grant at allocation, independently of the policy callback.
            if(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){v[1]=2;return}
            record=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum,3840)).setPrivacySensitive(false).build()
            v[7]=0
            check(record.state==AudioRecord.STATE_INITIALIZED)
            v[8]=record.audioSource.toLong();v[9]=record.sampleRate.toLong();v[10]=record.channelCount.toLong()
            v[11]=if(record.audioFormat==AudioFormat.ENCODING_PCM_16BIT)1 else -1
            v[14]=record.format.channelMask.toLong();v[15]=1
            check(v[8]==6L && v[9]==48000L && v[10]==2L && v[11]==1L)
            if(!allowed()){v[1]=2;return}
            record.startRecording();check(record.recordingState==AudioRecord.RECORDSTATE_RECORDING)
            val route=record.routedDevice?.id // transient, never exported
            val deadline=System.nanoTime()+2_000_000_000L
            var attempts=0
            while(allowed() && v[19]<96000 && System.nanoTime()<deadline && attempts++<400) {
                val wanted=minOf(960L,(96000-v[19])*2).toInt()
                val count=record.read(pcm,0,wanted,AudioRecord.READ_NON_BLOCKING)
                if(!allowed()){pcm.fill(0);break}
                check(count in 0..wanted && count%2==0)
                if(count==0)v[20]++
                for(f in 0 until count/2)for(c in 0..1) {
                    val sample=pcm[f*2+c].toLong();val i=21+c*5
                    v[i]++;if(sample!=0L)v[i+1]++
                    v[i+2]=maxOf(v[i+2],kotlin.math.abs(sample));v[i+3]+=sample*sample
                    if(sample == -32768L || sample==32767L)v[i+4]++
                }
                v[19]+=count/2;pcm.fill(0)
                Thread.sleep(5)
            }
            v[1]=if(!allowed())2 else if(v[19]==96000L)1 else if(System.nanoTime()>=deadline)3 else 4
            val last=record.routedDevice?.id
            v[17]=if(route==null || last==null)-1 else if(route==last)1 else 0
        } catch (_:SecurityException){v[1]=2;v[2]=-1}
        catch (_:Throwable){v[1]=5;v[2]=-1}
        finally {
            pcm.fill(0)
            if(record!=null) {
                try{record.stop()}catch(_:Throwable){v[3]=-1;v[1]=5}
                try{record.release();v[7]=1}catch(_:Throwable){v[4]=-1;v[5]=1;v[1]=5}
            }
            completed=v // poll joins worker before acknowledgement or publication
        }
    }
}
