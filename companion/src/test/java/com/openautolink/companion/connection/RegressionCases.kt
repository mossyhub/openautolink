package com.openautolink.companion.connection

import android.content.Context
import com.openautolink.companion.service.TcpAdvertiser
import kotlinx.coroutines.*
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.CoroutineContext

/** Production listener bodies and watchdog coroutine; Android effects supplied by caller. */
object RegressionCases {
 private fun field(o: Any,n:String):Any? = o.javaClass.getDeclaredField(n).let {it.isAccessible=true;it.get(o)}
 private fun set(o:Any,n:String,v:Any?) {o.javaClass.getDeclaredField(n).let {it.isAccessible=true;it.set(o,v)}}
 private fun call(o:Any,n:String,vararg args:Any?) {o.javaClass.declaredMethods.single {it.name==n && it.parameterCount==args.size}.let {it.isAccessible=true;it.invoke(o,*args)}}
 private fun await(l:CountDownLatch) {check(l.await(5,TimeUnit.SECONDS)) {"barrier timeout"}}
 @OptIn(InternalCoroutinesApi::class)
 private class Clock:CoroutineDispatcher(),Delay {
  val work=LinkedBlockingQueue<Runnable>(); val timers=LinkedBlockingQueue<CancellableContinuation<Unit>>()
  override fun dispatch(context:CoroutineContext,block:Runnable) {work.add(block)}
  override fun scheduleResumeAfterDelay(timeMillis:Long,continuation:CancellableContinuation<Unit>) {timers.add(continuation)}
  fun run() {while(true) (work.poll()?:break).run()}
  fun tick() {val c=timers.poll()?:error("missing live timer");c.resume(Unit) {};run()}
 }
 private class F(val context:Context):AutoCloseable {
  val clock=Clock(); val root=SupervisorJob();val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val status=AtomicReference("idle");val hook=AtomicReference<(String)->Unit>({})
  val a=TcpAdvertiser(context,object:TcpAdvertiser.StateListener {
   override fun onConnecting() {hook.get()("connecting");status.set("connecting")}
   override fun onProxyConnected() {hook.get()("connected");status.set("connected")}
   override fun onProxyDisconnected() {hook.get()("disconnected");status.set("disconnected")}
   override fun onLaunchTimeout() {}
  })
  val car=Socket(); lateinit var p:AaProxy
  init {set(a,"scope",CoroutineScope(clock+root));set(a,"isRunning",true);call(a,"handleCarConnection",car);clock.run();p=field(a,"activeProxy") as AaProxy}
  fun listener()=field(p,"listener")!!
  fun connected(s:Socket=car,b:Any=Any()) {call(listener(),"onConnected",s,b)}
  override fun close(){release.countDown();hook.set({});a.stop();root.cancel();p.stop();car.close()}
 }
 fun publication(context:Context)=F(context).use {f ->
  val b=Any();f.connected(b=b)
  f.hook.set {if(it=="disconnected") {f.entered.countDown();await(f.release)}}
  val old=Thread {call(f.listener(),"onDisconnected",f.car,b,true,false)};old.start();await(f.entered)
  val fresh=Socket()
  try {call(f.a,"handleCarConnection",fresh);f.connected(fresh);f.release.countDown();old.join(5000)
   check(!old.isAlive);check(f.status.get()=="connected") {"predecessor overwrote successor: ${f.status.get()}"}
  } finally {f.release.countDown();fresh.close()}
 }
 fun connectedAfterStop(context:Context)=F(context).use {f ->
  f.hook.set {if(it=="connected") {f.entered.countDown();await(f.release)}}
  val old=Thread {f.connected()};old.start();await(f.entered)
  val stopped=CountDownLatch(1);val stop=Thread {f.a.stop();f.status.set("stopped");stopped.countDown()};stop.start()
  // Baseline returns immediately; corrected Stop waits for already-admitted publication.
  stopped.await(200,TimeUnit.MILLISECONDS);f.release.countDown();old.join(5000);stop.join(5000)
  check(!old.isAlive && !stop.isAlive);check(f.status.get()=="stopped") {"Connected reacquired service state after Stop"}
 }
 fun reentrantSuccessor(context:Context)=F(context).use {f->
  val b=Any();f.connected(b=b);val fresh=Socket()
  try {f.hook.set {if(it=="disconnected") {call(f.a,"handleCarConnection",fresh);f.connected(fresh)}}
   call(f.listener(),"onDisconnected",f.car,b,true,false)
   check(f.status.get()=="connected") {"reentrant predecessor overwrote successor"}
  }finally{fresh.close()}
 }
 fun currentEvent(context:Context)=F(context).use {f->f.connected();check(f.status.get()=="connected")}
 fun watchdog(context:Context,kind:String,installHook:(((String)->Unit)?)->Unit,launches:()->Int)=F(context).use {f->
  val once=AtomicBoolean(true)
  installHook {msg->if(msg.contains("AA didn't connect") && once.compareAndSet(true,false)){f.entered.countDown();await(f.release)}}
  val timer=Thread {f.clock.tick()};val before=launches();timer.start();await(f.entered)
  var successor:Job?=null;var fresh:Socket?=null
  try {
   when(kind){
    "replacement"->{fresh=Socket();call(f.a,"handleCarConnection",fresh);successor=field(f.a,"aaConnectWatchdog") as Job}
    "connected"->f.connected()
    "stop"->f.a.stop()
   }
   val afterTransition=launches();f.release.countDown();timer.join(5000);check(!timer.isAlive)
   check(launches()==afterTransition) {"obsolete watchdog launched AA ($before -> ${launches()})"}
   if(successor!=null) check(field(f.a,"aaConnectWatchdog")===successor && !successor.isCancelled) {"obsolete watchdog replaced/cancelled successor job"}
   check((field(f.a,"aaLaunchAttempts") as Int)==0) {"obsolete watchdog mutated retry budget"}
  }finally{installHook(null);f.release.countDown();fresh?.close()}
 }
 fun retryExhaustion(context:Context,launches:()->Int)=F(context).use {f->
  val before=launches();repeat(3){f.clock.tick();check(!f.car.isClosed)}
  check(launches()==before+3) {"current owner lost three retries"};f.clock.tick()
  check(f.car.isClosed && f.p.isAccepting) {"exhaustion must cycle car, retain accepting proxy"}
  check(field(f.a,"aaLaunchAttempts")==0)
 }
}
