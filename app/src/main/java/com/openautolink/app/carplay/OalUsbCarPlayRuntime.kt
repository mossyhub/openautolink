// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.app.carplay

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.location.LocationManager
import android.net.VpnService
import android.os.*
import android.view.Surface
import com.openautolink.app.input.GnssForwarderImpl
import com.openautolink.carplaylab.media.*
import com.openautolink.carplaylab.network.CarPlayNetworkSelection
import com.openautolink.carplaylab.usb.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.transport.*
import java.io.File
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Existing USB mode adapter. The caller chooses this exact USB device and retires AA first.
 * No Wi-Fi, Bluetooth, tethering or default-network mutation. close() acknowledges exact owners. */
class OalUsbCarPlayRuntime(
    private val context: Context,
    private val device: UsbDevice,
    private val display: CarPlayDisplayPreferences,
    private val surface: Surface,
    private val report: (String) -> Unit,
    private val onDisplayQualified: (CarPlayDisplayPreferences) -> Unit = {},
    private val onReenumerationStarted: () -> Boolean = { false },
    private val onReplacementAccepted: (UsbDevice) -> Boolean = { false },
    private val initialPermissionOwner: () -> Boolean = { true },
    private val consumeExplicitPermissionRetry: () -> Boolean = { false },
    private val restrictionPolicy: CarPlayRestrictionPolicy = CarPlayRestrictionPolicy.from(com.openautolink.app.data.CarPlaySettings()),
    receiverAddress: String = "",
    private val attemptContext: CarPlayAttemptContext? = null,
) : CarPlayRuntime {
    // Snapshot before USB side effects. Empty preserves the existing descriptor/fallback offer.
    private val manualBluetoothAddress = com.openautolink.app.data.CarPlayReceiverAddressPolicy.runtimeAddress(receiverAddress)
        .takeIf { it.isNotEmpty() }
    private val canceled=AtomicBoolean()
    private val resourcesLock=Any()
    private val closeLock=Any()
    private val resources=mutableListOf<AutoCloseable>()
    @Volatile private var receiver:ProjectionReceiver?=null
    // Callbacks cannot wait behind the resource factory/retirement lock.
    private class PresentationReceipt(val surface: Surface?)
    private val initialPresentation = PresentationReceipt(surface)
    private val requestedPresentation = java.util.concurrent.atomic.AtomicReference(initialPresentation)
    // Applied target and receiver publication are owned together under resourcesLock.
    @Volatile private var presentation: PresentationReceipt? = initialPresentation
    private val presentationQueued = AtomicBoolean()
    private val presentationWorker = Executors.newSingleThreadExecutor {
        Thread(it, "oal-usb-carplay-presentation").apply { isDaemon = true }
    }
    override fun attachSurface(surface: Surface) {
        while (!canceled.get()) {
            val old = requestedPresentation.get()
            if (old.surface === surface) return
            if (requestedPresentation.compareAndSet(old, PresentationReceipt(surface))) { schedulePresentation(); return }
        }
    }
    override fun detachSurface(surface: Surface) {
        while (!canceled.get()) {
            val old = requestedPresentation.get()
            if (old.surface !== surface) return
            if (requestedPresentation.compareAndSet(old, PresentationReceipt(null))) { schedulePresentation(); return }
        }
    }
    private fun schedulePresentation() {
        if (canceled.get() || receiver == null || !presentationQueued.compareAndSet(false, true)) return
        try {
            presentationWorker.execute {
                var observed = requestedPresentation.get()
                try {
                    synchronized(resourcesLock) { observed = requestedPresentation.get(); reconcilePresentation() }
                } finally {
                    presentationQueued.set(false)
                    if (requestedPresentation.get() !== observed) schedulePresentation()
                }
            }
        } catch (_: RejectedExecutionException) { presentationQueued.set(false) }
    }
    private val touchMapper=CarPlayTouchMapper()
    private fun active() { check(!canceled.get()) { "USB CarPlay attempt revoked" } }
    private val audioDiagnostics=CarPlayAudioDiagnosticReporter(diagnosticSessions.incrementAndGet(),System::nanoTime) { line ->
        runCatching { com.openautolink.app.diagnostics.DiagnosticLog.i("carplay-audio",line) }
        runCatching { android.util.Log.i("OalCarPlayAudio",line) }
    }
    private fun status(line:String) { if(!canceled.get()) { audioDiagnostics.record(line);report(line) } }
    // Exact historical receiver evidence remains exportable after cancellation; live UI does not.
    private fun receiverStatus(line:String) { audioDiagnostics.record(line);if(!canceled.get()) report(line) }
    private fun <T:AutoCloseable> own(create:()->T):T = synchronized(resourcesLock) {
        // Serialize creation and installation against close's canceled retirement snapshot.
        check(!canceled.get()) { "USB CarPlay attempt revoked" }
        val resource=create()
        resources.add(resource)
        // Nonblocking cancel can arrive during construction. Retain this exact owner for
        // awaited close/retry even when installation cannot return it to the caller.
        check(!canceled.get()) { "USB CarPlay attempt revoked" }
        resource
    }
    private fun requestHostUi(isCurrent: () -> Boolean) {
        CarPlayHostUi.request { !canceled.get() && isCurrent() }
    }

    private fun ownReceiver(create: () -> ProjectionReceiver): ProjectionReceiver = synchronized(resourcesLock) {
        val projection = own(create)
        active()
        projection.setHostUiHandler(::requestHostUi)
        receiver = projection
        presentation = initialPresentation // Constructor starts with the qualified bootstrap target.
        reconcilePresentation()
        projection
    }
    private fun reconcilePresentation() {
        if (canceled.get()) return
        val projection = receiver ?: return
        val target = requestedPresentation.get()
        if (target === presentation) return
        val targetSurface = target.surface
        if (targetSurface != null) {
            // SurfaceHolder may recreate the SAME Java Surface. Preserve a coalesced
            // destroy/attach receipt by forcing the receiver's generation to advance.
            if (targetSurface === presentation?.surface) projection.detachSurface(targetSurface)
            projection.attachSurface(targetSurface)
        } else presentation?.surface?.let(projection::detachSurface)
        presentation = target
    }
    private var trustPending = false
    private var permissionAwaitDenied = false
    private var topology = topology(device)
    override fun run() {
        try { runConnection() }
        catch(failure:Exception) {
            if (failure is CarPlayConnectionFailure) throw failure
            if (failure is LockdownPairingException.RemoteError &&
                failure.code in setOf("InvalidPairRecord", "MissingPairRecord", "InvalidHostID")) {
                status("CarPlay USB pairing-error code=${failure.code}")
            }
            val reason = if(canceled.get()) "CANCELLED" else UsbCarPlayFailurePolicy.reason(failure, trustPending)
            val receipt = "CarPlay USB stage=failed reason=$reason"
            if(canceled.get()) audioDiagnostics.record(receipt) else status(receipt)
            if(reason == "CANCELLED") throw failure
            retryFence.failed(topology, reason, permissionAwaitDenied)
            throw failureUi(reason)
        }
    }
    private fun configurationAction(configuration: UsbConfiguration?, transitions: Int): String {
        val appleEthernet=configuration?.let(IphoneCarPlayConfiguration::isCarPlayMode)==true
        val action=when {
            configuration!=null && appleEthernet -> "reuse-descriptors"
            configuration!=null && transitions==1 -> "reuse-without-apple-ethernet"
            transitions==0 -> "request-transition"
            else -> "reject-missing-configuration"
        }
        status("CarPlay USB configuration found=${configuration!=null} appleEthernet=$appleEthernet transitions=$transitions decision=$action")
        return action
    }
    private fun runConnection() {
        active()
        require(isCandidate(device)) { "Selected USB device lacks Apple USBMUX" }
        val usb=context.getSystemService(UsbManager::class.java) ?: error("USB host unavailable")
        // Consume on first admission even without a failure: the user's original selection
        // cannot survive its own denial and authorize an automatic runtime rebuild.
        val explicitPermissionRetry = consumeExplicitPermissionRetry()
        if (!initialPermissionOwner()) throw CancellationException("USB selection retired before permission")
        retryFence.blocked(topology, usb.deviceList.values.map(::topology).toSet(), explicitPermissionRetry)?.let { reason ->
            status("CarPlay USB stage=retry result=blocked")
            throw failureUi(reason)
        }
        status("CarPlay USB stage=usb-permission result=pending")
        requirePermission(UsbCarPlayPermission.await(context, usb, device) {
            canceled.get() || !initialPermissionOwner()
        })
        active()
        if (!initialPermissionOwner()) throw CancellationException("USB selection retired during permission")
        val serial=device.serialNumber?.takeIf { it.isNotBlank() } ?: error("Private USB serial required for exact ownership")
        VpnService.prepare(context)?.let {
            status("CarPlay USB stage=vpn-consent result=pending")
            check(UsbCarPlayVpnConsent.await(it) { canceled.get() }) { "USB VPN consent denied" }
        }
        active()
        val host=IphoneUsbHost(context,usb,IphoneUsbMatcher.appleVendor())
        var selected=device
        val initialConfiguration=IphoneCarPlayConfiguration.find(selected)
        // Pinned DiPlay WiredConfigurationAction: ready descriptors need no request;
        // otherwise try once, retaining an older peer's still-valid rejected-request fallback.
        if(configurationAction(initialConfiguration,0)=="request-transition") {
            val detached=AtomicBoolean(false)
            own { registerDetach(device.deviceName) { detached.set(true) } }
            check(onReenumerationStarted()) { "USB transition ownership unavailable" }
            val started=SystemClock.elapsedRealtime()
            var transition:IphoneUsbHost.TransitionResult?=null
            active()
            host.requestCarPlayReenumerationAsync(device,Executor { it.run() }) { transition=it }
            active()
            val reenumerating=when(val result=transition) {
                is IphoneUsbHost.TransitionResult.Failed -> {
                    val attached=usb.deviceList[device.deviceName]
                    // No fallback after delivered detach, replacement, cancellation or lost permission.
                    val fallback=initialConfiguration!=null && !detached.get() && attached!=null &&
                        attached.deviceId==device.deviceId && attached.vendorId==device.vendorId &&
                        attached.productId==device.productId && usb.hasPermission(device) &&
                        IphoneCarPlayConfiguration.find(attached)!=null
                    status("CarPlay USB transition attempt=1 result=rejected fallback=$fallback")
                    if(!fallback) throw result.error
                    check(onReplacementAccepted(device)) { "USB unchanged configuration ownership revoked" }
                    active()
                    check(!detached.get()) { "USB unchanged configuration detached during admission" }
                    configurationAction(initialConfiguration,1)
                    false
                }
                is IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    status("CarPlay USB transition attempt=1 result=accepted fallback=false")
                    true
                }
                else -> error("USB transition unavailable")
            }
            if(reenumerating) status("CarPlay USB stage=reenumeration result=pending")
            while(reenumerating) {
                active(); val elapsed=SystemClock.elapsedRealtime()-started
                if(elapsed>=UsbCarPlayPermission.REENUMERATION_TIMEOUT_MS) throw TimeoutException("Selected USB reenumeration expired")
                val candidates=usb.deviceList.values.filter { isCandidate(it) && it.deviceName!=device.deviceName &&
                    it.deviceName.substringBeforeLast('/')==device.deviceName.substringBeforeLast('/') }
                check(candidates.size<=1) { "Ambiguous USB replacement" }
                val candidate=candidates.singleOrNull()
                if(detached.get() && candidate!=null) {
                    topology=topology(candidate)
                    requirePermission(UsbCarPlayPermission.await(context, usb, candidate,
                        UsbCarPlayPermission.REENUMERATION_TIMEOUT_MS-elapsed) { canceled.get() })
                    active()
                    val acceptedElapsed=SystemClock.elapsedRealtime()-started
                    val fresh=usb.deviceList.values.filter { isCandidate(it) && it.deviceName!=device.deviceName &&
                        it.deviceName.substringBeforeLast('/')==device.deviceName.substringBeforeLast('/') }
                    check(fresh.size==1 && fresh.single().deviceName==candidate.deviceName &&
                        fresh.single().deviceId==candidate.deviceId) { "USB replacement changed during permission" }
                    check(UsbCarPlayPolicy.acceptReplacement(serial,candidate.serialNumber.orEmpty(),detached.get(),acceptedElapsed))
                    check(configurationAction(IphoneCarPlayConfiguration.find(candidate),1)!="reject-missing-configuration") { "USB replacement lacks CarPlay configuration" }
                    check(onReplacementAccepted(candidate)) { "USB replacement ownership revoked" }
                    selected=candidate;topology=topology(candidate);break
                }
                Thread.sleep(100)
            }
        }
        active()
        val configuration=IphoneCarPlayConfiguration.find(selected) ?: error("USB CarPlay configuration missing")
        val function=NcmFunctionDiscovery.find(configuration) ?: error("USB NCM function missing")
        val usbMux=checkNotNull(IphoneCarPlayConfiguration.usbMuxInterface(configuration))
        // Chosen descriptor receipt, not a claim of continuous active device/driver state.
        status("CarPlay USB selection configurationId=${configuration.id} usbmuxInterfaceId=${usbMux.id} usbmuxAlt=${usbMux.alternateSetting} ncmControlInterfaceId=${function.control.id} ncmControlAlt=${function.control.alternateSetting} ncmDataInterfaceId=${function.data.id} ncmDataAlt=${function.data.alternateSetting}")
        val pipe=own {
            // Owner callbacks may race a delivered detach. Recheck the exact census and real
            // permission at the open boundary; the host independently checks permission again.
            val attached=usb.deviceList[selected.deviceName]
            check(attached!=null && attached.deviceId==selected.deviceId &&
                attached.vendorId==selected.vendorId && attached.productId==selected.productId) {
                "Selected USB detached before open"
            }
            check(usb.hasPermission(selected)) { "Selected USB permission revoked before open" }
            active()
            var opened:IphoneUsbHost.Iap2SessionResult?=null
            host.openIap2UsbSessionAsync(selected,Executor { it.run() }) { opened=it }
            when(val result=opened) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> result.session
                is IphoneUsbHost.Iap2SessionResult.Failed -> throw result.error
                else -> error("USBMUX unavailable")
            }
        }
        val ncm=own {
            val ncmConnection=usb.openDevice(selected) ?: error("USB NCM unavailable")
            NcmUsbBridge.open(ncmConnection,function,onDiagnostic={ status(it) })
        }
        val mux=own { Iap2UsbMuxHost.open(pipe) }
        status("CarPlay USB stage=usbmux result=ready")
        // Descriptor shape is only admission. Lockdown distinguishes iPhone from iPad/iPod.
        LockdownPlistChannel(mux.connect()).use { plist ->
            val response=plist.request(LockdownPlistValue.Dictionary(linkedMapOf(
                "Label" to LockdownPlistValue.Text("OpenAutoLink"),
                "Request" to LockdownPlistValue.Text("GetValue"),
                "Key" to LockdownPlistValue.Text("ProductType"))),5000)
            val type=(response.entries["Value"] as? LockdownPlistValue.Text)?.value
            check(type?.matches(Regex("iPhone[0-9]+,[0-9]+"))==true) { "Selected USB device is not an iPhone" }
        }
        status("CarPlay USB stage=phone-identification result=accepted")
        val trust=UsbLockdownTrust(File(context.noBackupFilesDir,"usb-lockdown"))
        val savedRecord = try { trust.load(serial) } catch (_: Exception) {
            throw LockdownPairingException.RemoteError("InvalidPairRecord")
        }
        val record=savedRecord ?: run {
            // Create once only for fresh pairing; persisted identities and pending retries stay exact.
            val identity = UsbLockdownFreshIdentity.create()
            LockdownPairingClient(mux).pair("OpenAutoLink",
                identity.hostId,identity.systemBuid,120000, onStage = { stage ->
                    if(stage == LockdownPairingStage.TRUST_PENDING) trustPending = true
                    status("CarPlay USB pairing stage=${stage.receipt}")
                }) { canceled.get() }.pairRecord.also {
                synchronized(resourcesLock) { active();trust.save(serial,it) }
            }
        }
        val carkit=own { LockdownCarKitClient(mux).open(record,"OpenAutoLink") }
        status("CarPlay USB stage=carkit result=ready")
        val names=context.assets.list("offline-mfi").orEmpty().toSet()
        val provisioning=com.openautolink.app.ui.settings.CarPlayMfiAssetBootstrap(context.noBackupFilesDir) { path ->
            if(path.substringAfterLast('/') in names) context.assets.open(path) else null
        }.install()
        check(provisioning!=com.openautolink.app.ui.settings.CarPlayMfiAssetBootstrap.Result.INVALID_BUNDLE &&
            provisioning!=com.openautolink.app.ui.settings.CarPlayMfiAssetBootstrap.Result.INVALID_EXISTING)
        val auth=LocalMfiAuthenticationClient.load(File(context.noBackupFilesDir,LocalMfiAuthenticationClient.DIRECTORY)) {}
        val mac=ncm.hostMac ?: ByteArray(6).also { java.security.SecureRandom().nextBytes(it);it[0]=2 }
        val identity=mac.joinToString(":") { "%02x".format(it) }
        val address=linkLocal(mac)
        val vpn=bindVpn()
        val token=Any()
        lateinit var fd:ParcelFileDescriptor
        own {
            fd=vpn.attach(token,address)
            AutoCloseable { vpn.detach(token) }
        }
        val bridge=own { Ipv6NcmBridge(ncm,fd,mac,hostIpv6=InetAddress.getByName(address).address) { cancel() } }
        synchronized(resourcesLock) { active();bridge.start() }
        val nicDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        var nic:NetworkInterface?=null
        while(nic==null) {
            active();nic=NetworkInterface.getNetworkInterfaces().toList().singleOrNull { n ->
                n.inetAddresses.toList().any { it.address.contentEquals(InetAddress.getByName(address).address) }
            }
            if(System.nanoTime()>nicDeadline) throw TimeoutException("USB TUN address unavailable")
            if(nic==null) Thread.sleep(50)
        }
        val exactNic=checkNotNull(nic)
        val local=Inet6Address.getByAddress(null,InetAddress.getByName(address).address,exactNic.index)
        val gps=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED &&
            runCatching { context.getSystemService(LocationManager::class.java)?.isProviderEnabled(LocationManager.GPS_PROVIDER)==true }.getOrDefault(false)
        val vehicle=OalCarPlayVehicleSnapshotAdapter()
        val capabilities=vehicle.capabilities(gps,::status)
        val vehicleBootstrap=own { com.openautolink.carplaylab.intake.OalCarDataBootstrap(capabilities,SystemClock::elapsedRealtime,::status) }
        val identification=Iap2IdentificationConfig("OpenAutoLink","OAL-native-CarPlay","OpenAutoLink",
            "oal-private-receiver","0.1","Android",function.data.id,externalAccessoryProtocol="com.openautolink.carplaylab")
        val projection=ownReceiver { ProjectionReceiver(context,identity,surface,auth,display,trustedOnly=true,
            runtimeCarDataFactory={ session,send,receipt ->
                vehicleBootstrap.wrap(OalCarPlayIntakeLink(gps,vehicle,OalCarPlayGnssSnapshotAdapter(SystemClock::elapsedRealtime, requireSourceTime = true) {
                    sink -> GnssForwarderImpl(context.applicationContext,sink,true)
                },SystemClock::elapsedRealtime,CarPlayAppearanceBridge(),session::setNightModeIfEventReady,send,receipt,
                    frozenCapabilities=capabilities, restrictionPolicy = restrictionPolicy,
                    sendLimitedUI = session::setLimitedUIIfEventReady))
            },runtimeMetadataFactory={ session, receipt -> own {
                active()
                val metadata = OalCarPlayMetadataLink(SystemClock::elapsedRealtime, receipt,
                    OalCarPlayMetadataHost(context.applicationContext, receipt = receipt, attemptContext = attemptContext) { index ->
                        receiver?.mediaCommand(session, index) == true
                    })
                CarPlayBluetoothDiagnosticLink(metadata, { listener ->
                    runCatching {
                        (context.applicationContext as? com.openautolink.app.OalApplication)
                            ?.carBluetoothConnectionObserver?.subscribeEvidence(listener)
                    }.getOrNull()
                }, receipt)
            } },wired=true,drivingRestrictions = restrictionPolicy.enabled,
            advertisedBluetoothAddress = manualBluetoothAddress,
            onCaptureTerminal=captureTerminalSink(context,::receiverStatus),onStatus=::receiverStatus) }
        active();onDisplayQualified(projection.displayConfiguration)
        projection.setNetworkStartupDiagnosticStop(ncm::stopStartupDiagnostics)
        val scale=com.openautolink.carplaylab.media.CarPlayDisplayPolicy.scaleDiagnostic(projection.displayConfiguration)
        com.openautolink.app.diagnostics.DiagnosticLog.i("carplay-scale",scale)
        projection.setRuntimeIdentification(identification)
        val csm=own { Iap2Session.open(carkit,onArtwork=projection::offerWiredArtwork) }
        Iap2IdentificationClient(csm,vehicleBootstrap::identification)
            .identify(identification,30000,onOtherFrame=vehicleBootstrap::handle)
        status("CarPlay phone identification accepted")
        Iap2MfiAuthenticationClient(auth).run(csm,30000,onOtherFrame=vehicleBootstrap::handle) {}
        active();status("CarPlay receiver MFi authentication accepted · phone AirPlay trust pending")
        projection.authorizeControllerEnrollment(120000)
        projection.start(CarPlayNetworkSelection(local,exactNic.index,null))
        projection.setRuntimeReply { frame ->
            if(frame.messageId==0x4300) Iap2CarPlayMessages.startSession(wiredIpv6Addresses=listOf(address),
                airPlayPort=projection.port,deviceIdentifier=identity,publicKey=projection.publicKeyHex,sourceVersion="950.7.1") else null
        }
        csm.send(Iap2ControlMessages.powerSourceUpdate(0,charging=false),5000)
        com.openautolink.carplaylab.metadata.CarPlayMetadataIdentification.bootstrapSubscriptions(Iap2ControlMessages.subscriptions())
            .forEach { active();csm.send(it,5000) }
        val readyDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120)
        var endpointReceipts=0
        while(!canceled.get() && !csm.isClosed) {
            active()
            // Handoff is exact; this worker never receives CSM again after ownership transfer.
            if(projection.startWiredRuntime(csm)) {
                while(!canceled.get() && !csm.isClosed) {
                    active();check(projection.runtimeFailureStage==null) { "USB runtime control failed" }
                    Thread.sleep(100)
                }
                break
            }
            if(System.nanoTime()>readyDeadline) throw TimeoutException("USB AirPlay startup expired")
            val frame=csm.recv(500) ?: continue
            if(vehicleBootstrap.handle(frame)) continue
            if(frame.messageId==0x4300) {
                val requestMonoNanos=System.nanoTime()
                // Already on the USB owner worker. Await this exact frame, not earlier queued work.
                csm.sendAndAwaitWritten(Iap2CarPlayMessages.startSession(wiredIpv6Addresses=listOf(address),airPlayPort=projection.port,
                    deviceIdentifier=identity,publicKey=projection.publicKeyHex,sourceVersion="950.7.1"),5000)
                val writtenReceiptMonoNanos=System.nanoTime()
                active()
                if(endpointReceipts<4) {
                    endpointReceipts++
                    status("CarPlay USB stage=endpoint result=written requestMonoNs=$requestMonoNanos " +
                        "writtenReceiptMonoNs=$writtenReceiptMonoNanos writeAwaitElapsedUs=${(writtenReceiptMonoNanos-requestMonoNanos)/1_000}")
                }
            }
        }
    }
    private fun requirePermission(result: UsbCarPlayPermission.Result) {
        permissionAwaitDenied = result == UsbCarPlayPermission.Result.DENIED
        status("CarPlay USB stage=usb-permission result=${result.name.lowercase()}")
        when(result) {
            UsbCarPlayPermission.Result.GRANTED -> Unit
            UsbCarPlayPermission.Result.TIMED_OUT -> throw TimeoutException("USB permission expired")
            UsbCarPlayPermission.Result.CANCELLED -> throw CancellationException("USB permission cancelled")
            UsbCarPlayPermission.Result.DETACHED -> throw IphoneUsbException.DeviceUnavailable("Selected USB detached during permission")
            else -> throw SecurityException("USB permission rejected")
        }
    }
    private fun registerDetach(name:String,onDetach:()->Unit):AutoCloseable {
        val receiver=object:BroadcastReceiver() { override fun onReceive(context:Context,intent:Intent) {
            @Suppress("DEPRECATION") val removed=intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if(removed?.deviceName==name) onDetach()
        } }
        context.registerReceiver(receiver,IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),Context.RECEIVER_NOT_EXPORTED)
        return AutoCloseable { context.unregisterReceiver(receiver) }
    }
    private fun bindVpn():OalUsbCarPlayVpnService {
        val ready=CountDownLatch(1)
        var service:OalUsbCarPlayVpnService?=null
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder) {
                service=(binder as OalUsbCarPlayVpnService.LocalBinder).service;ready.countDown()
            }
            override fun onServiceDisconnected(name:ComponentName) { cancel() }
        }
        own {
            check(context.bindService(Intent(context,OalUsbCarPlayVpnService::class.java),connection,Context.BIND_AUTO_CREATE))
            AutoCloseable { context.unbindService(connection) }
        }
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(!ready.await(100,TimeUnit.MILLISECONDS)) { active();if(System.nanoTime()>deadline) throw TimeoutException("USB VPN bind expired") }
        active();return checkNotNull(service)
    }
    fun touch(actionMasked:Int,actionIndex:Int,pointers:List<CarPlayPointer>,width:Int,height:Int) {
        if(!canceled.get()) receiver?.touchContacts(touchMapper.map(actionMasked,actionIndex,pointers,width,height))
    }
    override fun telemetrySnapshot(): ReceiverTelemetrySnapshot? {
        if (canceled.get()) return null
        // Sampling must certify the requested receipt, not a rendered predecessor while
        // the presentation worker is queued. Never wait behind resource/decoder work.
        val requested = requestedPresentation.get()
        val applied = presentation
        val snapshot = receiver?.telemetrySnapshot() ?: return null
        if (canceled.get()) return null
        return if (applied === requested && presentation === requested &&
            requestedPresentation.get() === requested && requested.surface != null) snapshot
        else snapshot.copy(presentationRendered = false)
    }
    override fun cancel() { canceled.set(true);presentationWorker.shutdownNow();receiver?.revokePendingAdmission() }
    override fun close() = synchronized(closeLock) {
        cancel();val retiring=synchronized(resourcesLock) { resources.toList().asReversed() }
        val closeFailures=java.util.IdentityHashMap<AutoCloseable,Exception>()
        // Revoke all transports before awaiting workers that can depend on one another.
        retiring.forEach { owner ->
            try { owner.close() } catch(error:Exception) { closeFailures[owner]=error }
        }
        var failed:Exception?=null
        retiring.forEach { owner ->
            try {
                when(owner) {
                    is Iap2Session -> {
                        check(owner.awaitClosed(5000)) { "USB CSM retirement unacknowledged" }
                        // The session can rethrow historical EOF after its worker has retired.
                        closeFailures.remove(owner)
                    }
                    is Iap2UsbMuxHost -> check(owner.awaitClosed(5000)) { "USBMUX reader retirement unacknowledged" }
                    is ProjectionReceiver -> check(owner.awaitClosed(10000)) { "USB receiver retirement unacknowledged" }
                }
                closeFailures[owner]?.let { throw it }
                // Remove only this successfully closed and acknowledged identity. Failed
                // owners stay available for retry; successful owners are never retried.
                synchronized(resourcesLock) { resources.removeAll { it === owner } }
            } catch(error:Exception) { if(failed==null) failed=error }
        }
        failed?.let { throw it }
        check(presentationWorker.awaitTermination(5,TimeUnit.SECONDS)) { "USB presentation worker retirement timed out" }
        synchronized(resourcesLock) { receiver=null;presentation=null };Unit
    }
    companion object {
        private val retryFence = UsbCarPlayRetryFence()
        private fun topology(value: UsbDevice) = UsbCarPlayTopology(value.deviceName, value.deviceId, value.vendorId, value.productId)
        private fun failureUi(reason: String) = CarPlayConnectionFailure(when(reason) {
            "PASSWORD_PROTECTED" -> CarPlayFailureKind.USB_PASSWORD_PROTECTED
            "USER_DENIED" -> CarPlayFailureKind.USB_USER_DENIED
            "TRUST_PENDING_TIMEOUT" -> CarPlayFailureKind.USB_TRUST_PENDING_TIMEOUT
            "INVALID_RECORD" -> CarPlayFailureKind.USB_INVALID_RECORD
            "TRANSPORT_LOSS" -> CarPlayFailureKind.USB_TRANSPORT_LOSS
            "PERMISSION" -> CarPlayFailureKind.USB_PERMISSION
            "TIMEOUT" -> CarPlayFailureKind.USB_STARTUP_TIMEOUT
            else -> CarPlayFailureKind.USB_SETUP
        }, false)
        /** Rejection-only preflight. A real permission-await denial is left to the runtime's
         * exact, single-use consent check; this query never grants retry authority or clears a fence. */
        internal fun blockedAttachmentFailure(context: Context, device: UsbDevice): CarPlayFailureUi? {
            val usb = context.getSystemService(UsbManager::class.java) ?: return null
            val target = topology(device)
            val attached = usb.deviceList.values.map(::topology).toSet()
            if (target !in attached) return null
            val reason = retryFence.blocked(target, attached, explicitPermissionRetry = true) ?: return null
            val failure = failureUi(reason)
            return CarPlayFailureUi(failure.kind, failure.manualPeer, failure.userGuidance)
        }

        private val diagnosticSessions=java.util.concurrent.atomic.AtomicLong()
        fun isCandidate(device:UsbDevice):Boolean=UsbCarPlayPolicy.isCandidate(device.vendorId,
            (0 until device.configurationCount).flatMap { c -> val cfg=device.getConfiguration(c)
                (0 until cfg.interfaceCount).map { val i=cfg.getInterface(it);Triple(i.interfaceClass,i.interfaceSubclass,i.interfaceProtocol) } })
        private fun linkLocal(mac:ByteArray):String {
            val bytes=ByteArray(16);bytes[0]=0xfe.toByte();bytes[1]=0x80.toByte()
            bytes[8]=(mac[0].toInt() xor 2).toByte();bytes[9]=mac[1];bytes[10]=mac[2]
            bytes[11]=0xff.toByte();bytes[12]=0xfe.toByte();bytes[13]=mac[3];bytes[14]=mac[4];bytes[15]=mac[5]
            return checkNotNull(InetAddress.getByAddress(bytes).hostAddress)
        }
    }
}
