package com.openautolink.app.carplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One exact-device request per call. No retries; the receiver and capability die with this wait. */
object UsbCarPlayPermission {
    const val REENUMERATION_TIMEOUT_MS = 15_000L
    private const val PERMISSION_TIMEOUT_MS = 120_000L
    private val generations = AtomicLong()
    enum class Result { GRANTED, DENIED, DETACHED, CANCELLED, TIMED_OUT }

    fun await(context: Context, usb: UsbManager, device: UsbDevice,
              timeoutMs: Long = PERMISSION_TIMEOUT_MS, isCancelled: () -> Boolean): Result {
        if (isCancelled()) return Result.CANCELLED
        if (timeoutMs <= 0) return Result.TIMED_OUT
        val generation = generations.incrementAndGet()
        val action = "${context.packageName}.CARPLAY_USB_PERMISSION.$generation"
        val lock = Any()
        val done = CountDownLatch(1)
        var result: Result? = null
        fun exact(other: UsbDevice?) = other != null && other.deviceName == device.deviceName &&
            other.vendorId == device.vendorId && other.productId == device.productId && other.deviceId == device.deviceId
        fun attached() = exact(usb.deviceList[device.deviceName])
        fun complete(value: Result) = synchronized(lock) {
            if (result == null) { result = value; done.countDown() }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val received = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (!exact(received)) return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> complete(Result.DETACHED)
                    action -> if (intent.getLongExtra("generation", -1) == generation) {
                        complete(if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                            attached() && usb.hasPermission(device)) Result.GRANTED else Result.DENIED)
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(action).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, Context.RECEIVER_NOT_EXPORTED)
        var capability: PendingIntent? = null
        try {
            if (isCancelled()) return Result.CANCELLED
            if (!attached()) return Result.DETACHED
            if (usb.hasPermission(device)) return Result.GRANTED
            capability = PendingIntent.getBroadcast(context, generation.toInt(),
                Intent(action).setPackage(context.packageName).putExtra("generation", generation),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT)
            usb.requestPermission(device, capability)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                if (isCancelled()) return Result.CANCELLED
                if (!attached()) return Result.DETACHED
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return Result.TIMED_OUT
                if (done.await(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)) {
                    if (isCancelled()) return Result.CANCELLED
                    val resolved = synchronized(lock) { checkNotNull(result) }
                    return if (resolved == Result.GRANTED && (!attached() || !usb.hasPermission(device)))
                        Result.DETACHED else resolved
                }
            }
        } finally {
            try { capability?.cancel() } finally { context.unregisterReceiver(receiver) }
        }
    }
}
