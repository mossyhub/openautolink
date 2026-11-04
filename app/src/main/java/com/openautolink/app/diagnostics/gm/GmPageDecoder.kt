package com.openautolink.app.diagnostics.gm

import android.content.Context
import android.content.Intent
import android.os.Parcelable

/** Only framework-unmarshalled objects from the optional installed OEM library are accepted.
 * No OEM classes are copied into OAL, no ordinal/Parcel interpretation or hidden APIs.
 * Enum.name and DisplayInfo's seven public SDK fields are present in the retained defining
 * OEM bytecode. getField reads only those public members (never getDeclaredField/setAccessible).
 * Missing library, schema drift and BadParcelableException all fail closed. */
internal object GmPageDecoder {
    data class Page(val selected:Boolean,val type:String,val view:String,val displayType:Int,val width:Int,val height:Int)
    @Suppress("DEPRECATION")
    fun decode(intent:Intent):Page? = try {
        intent.setExtrasClassLoader(GmPageDecoder::class.java.classLoader)
        val state=intent.getIntExtra("app_selected_state",-1)
        if(state !in 0..1) null else {
            val type=intent.getParcelableExtra<Parcelable>("full_map_type") as? Enum<*>
            val display=intent.getParcelableExtra<Parcelable>("display_info")
            if(type?.javaClass?.name!="gm.navigation.enums.NavigationAppType" ||
                type.name !in setOf("NATIVE","PROJECTION") || display?.javaClass?.name!="gm.cluster.DisplayInfo") null
            else {
                val c=display.javaClass
                val view=c.getField("mDisplayId").get(display) as? String
                val kind=c.getField("mDisplayType").getInt(display)
                val width=c.getField("mDisplayWidth").getInt(display)
                val height=c.getField("mDisplayHeight").getInt(display)
                if(view.isNullOrBlank() || view.length>96 || kind<0 || width !in 1..16384 || height !in 1..16384) null
                else Page(state==1,type.name,view,kind,width,height)
            }
        }
    } catch(_:Exception){null} catch(_:LinkageError){null}
}

/** Own signature-protected primitive relay, not another OEM protocol or a cold app launcher. */
internal object GmPageRelay {
    fun action(context:Context)=context.packageName+".action.DECODED_GM_PAGE"
    fun send(context:Context,page:GmPageDecoder.Page?,generation:Long,elapsed:Long) {
        val intent=Intent(action(context)).setPackage(context.packageName)
            .putExtra("generation",generation).putExtra("elapsed",elapsed)
            .putExtra("selected",page?.let{if(it.selected)1 else 0}?:-1)
            .putExtra("type",page?.type?:"").putExtra("view",page?.view?:"")
            .putExtra("displayType",page?.displayType?:-1)
            .putExtra("width",page?.width?:0).putExtra("height",page?.height?:0)
        context.sendBroadcast(intent,context.packageName+".permission.OBSERVE_CLUSTER_PAGE")
    }
}
