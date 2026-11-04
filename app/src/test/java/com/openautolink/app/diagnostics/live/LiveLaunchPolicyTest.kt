package com.openautolink.app.diagnostics.live

import android.app.Application
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,35],application=Application::class,manifest=Config.NONE)
class LiveLaunchPolicyTest {
    private class Consent(var value:Boolean=false):LiveConsent {
        override fun load()=value
        override fun save(enabled:Boolean){value=enabled}
    }
    private class Credential(var value:String?="remembered"):LiveCredentials {
        override fun load()=value
        override fun save(credential:String){value=credential}
        override fun clear(){value=null}
    }
    private fun await(predicate:()->Boolean) {val end=System.nanoTime()+5_000_000_000;while(!predicate()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(predicate())}
    @Test fun stopDuringConsentLoadRetainsChoiceButNeverActivates() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,Credential(),object:LiveConsent {
            override fun load():Boolean{entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return true}
            override fun save(enabled:Boolean){}
        },{error("stopped restore cannot connect")})
        try{owner.restore();assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));owner.stop();release.countDown();await{owner.autoConnect.value};assertEquals("Off",owner.status.value)}finally{release.countDown();owner.stop();scope.cancel()}
    }
    @Test fun durableChoiceColdReopenAndCorruptStateFailClosed() {
        val context=RuntimeEnvironment.getApplication()
        val file=java.io.File(context.noBackupFilesDir,"oal-live-launch-consent.bin");file.delete()
        try {
            val store=LiveConsentStore(context);assertFalse(store.load());store.save(true)
            assertTrue(LiveConsentStore(context).load());store.save(false);assertFalse(LiveConsentStore(context).load())
            file.writeBytes(byteArrayOf(1,1));assertFalse(LiveConsentStore(context).load())
        }finally{file.delete()}
    }
    @Test fun failedChoiceWriteRemainsOffAndDoesNotErasePairing() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val credential=Credential()
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,credential,object:LiveConsent {
            override fun load()=false;override fun save(enabled:Boolean){throw java.io.IOException()}
        },{error("no network")})
        try{owner.updateAutoConnect(true);await{owner.status.value.contains("not saved")};assertFalse(owner.autoConnect.value);assertEquals("remembered",credential.value)}finally{owner.stop();scope.cancel()}
    }
    @Test fun stopDuringCredentialReadRejectsLateActivation() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,object:LiveCredentials {
            override fun load():String {entered.countDown();release.await(10,java.util.concurrent.TimeUnit.SECONDS);return "remembered"}
            override fun save(credential:String){};override fun clear(){}
        },Consent(true),{error("late credential cannot connect")})
        try{owner.restore();assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));owner.stop();release.countDown();Thread.sleep(150);assertEquals("Off",owner.status.value)}finally{release.countDown();owner.stop();scope.cancel()}
    }
    @Test fun explicitOffPersistsWithoutStoppingCurrentRunOrForgettingPairing() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val consent=Consent(true);val credential=Credential();val posts=AtomicInteger()
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,credential,consent,
            {object:LiveWire {override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {posts.incrementAndGet();throw java.io.IOException()};override fun close(){}}},{UnknownLiveNetwork})
        try{owner.restore();await{posts.get()>0};owner.updateAutoConnect(false);await{!consent.value};assertFalse(owner.autoConnect.value);assertEquals("remembered",credential.value);assertNotEquals("Off",owner.status.value)}finally{owner.stop();scope.cancel()}
    }
    @Test fun rememberedOptInRestoresWithoutInvitationAndStopFencesLateWork() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO); val consent=Consent(true);val posts=AtomicInteger()
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,Credential(),consent,
            {object:LiveWire {override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {posts.incrementAndGet();throw java.io.IOException()};override fun close(){}}},{UnknownLiveNetwork})
        try {owner.restore();await{posts.get()>0};owner.stop();assertEquals("Off",owner.status.value);Thread.sleep(150);assertEquals("Off",owner.status.value);assertTrue(consent.value)}finally{owner.stop();scope.cancel()}
    }
    @Test fun freshOffDoesNotLoadCredentialOrConnect() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val reads=AtomicInteger()
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,object:LiveCredentials {
            override fun load():String?{reads.incrementAndGet();error("not allowed")};override fun save(credential:String){};override fun clear(){}
        },Consent(),{error("no network")})
        try{owner.restore();Thread.sleep(200);assertEquals(0,reads.get());assertEquals("Off",owner.status.value)}finally{owner.stop();scope.cancel()}
    }
    @Test fun unpairedRestoreRequiresPairingWithoutEmptyInvitationRequest() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,Credential(null),Consent(true),{error("no network")})
        try{owner.restore();await{owner.status.value.contains("pairing required")};assertFalse(owner.status.value.startsWith("Live"))}finally{owner.stop();scope.cancel()}
    }
    @Test fun revokeDisablesFutureLaunchEvenWhenServerFails() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val consent=Consent(true)
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,Credential(),consent,{object:LiveWire {
            override fun post(path:String,body:JSONObject,bearer:String?):JSONObject{assertFalse(consent.value);throw java.io.IOException()};override fun close(){}
        }})
        try{owner.revoke();await{owner.status.value.contains("unconfirmed")};assertFalse(consent.value);assertFalse(owner.autoConnect.value)}finally{owner.stop();scope.cancel()}
    }
    @Test fun credentialFailureIsVisibleAndDoesNotErasePairing() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);var cleared=false
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,object:LiveCredentials {
            override fun load():String?=throw java.io.IOException();override fun save(credential:String){};override fun clear(){cleared=true}
        },Consent(true),{error("no network")})
        try{owner.restore();await{owner.status.value.contains("storage")};assertFalse(cleared);assertFalse(owner.status.value.contains("auth",ignoreCase=true))}finally{owner.stop();scope.cancel()}
    }
}
