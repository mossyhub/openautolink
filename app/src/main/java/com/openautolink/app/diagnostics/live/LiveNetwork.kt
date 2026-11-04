package com.openautolink.app.diagnostics.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class LiveInternet { OFFLINE, VALIDATED, UNKNOWN }
internal interface LiveNetwork:AutoCloseable { val state:StateFlow<LiveInternet> }
internal object UnknownLiveNetwork:LiveNetwork {
    override val state=MutableStateFlow(LiveInternet.UNKNOWN)
    override fun close(){}
}

/** Observes the OS default network only; never binds transport or probes a public host. */
internal class AndroidLiveNetwork(context:Context,private val requireValidatedInternet:Boolean=true):LiveNetwork {
    private val manager=context.getSystemService(ConnectivityManager::class.java)
    private val guard=Any()
    private var closed=false
    private var registered=false
    private val mutableState=MutableStateFlow(LiveInternet.UNKNOWN)
    override val state:StateFlow<LiveInternet> = mutableState
    private fun classify(c:NetworkCapabilities?)=if(c!=null && (!requireValidatedInternet ||
        (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))))LiveInternet.VALIDATED else LiveInternet.OFFLINE
    private fun refresh()=synchronized(guard) {
        if(!closed)mutableState.value=try{classify(manager?.getNetworkCapabilities(manager.activeNetwork))}catch(_:Exception){LiveInternet.UNKNOWN}
    }
    private val callback=object:ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network:Network){refresh()}
        override fun onLost(network:Network){refresh()}
        override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities)=synchronized(guard) {
            if(!closed) {
                try{if(manager?.activeNetwork==network)mutableState.value=classify(caps) else refresh()}
                catch(_:Exception){mutableState.value=LiveInternet.UNKNOWN}
            }
        }
    }
    init {
        try{checkNotNull(manager).registerDefaultNetworkCallback(callback);registered=true;refresh()}
        catch(_:Exception){mutableState.value=LiveInternet.UNKNOWN}
    }
    override fun close()=synchronized(guard) {
        if(!closed){closed=true;if(registered)runCatching{manager?.unregisterNetworkCallback(callback)};registered=false}
    }
}
