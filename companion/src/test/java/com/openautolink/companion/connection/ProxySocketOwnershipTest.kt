package com.openautolink.companion.connection

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ProxySocketOwnershipTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun prewarmDeliversBothDirections() = ProxySocketOwnershipCases.prewarm(context)
    @Test fun idleReplacementSupersedesConstructor() = ProxySocketOwnershipCases.idleReplacement(context)
    @Test fun neverPumpedSocketIsRetained() = ProxySocketOwnershipCases.neverPumped(context)
    @Test fun failedConstructorIsNeverReused() = ProxySocketOwnershipCases.failedFallback(context)
    @Test fun firstEofRetiresBothPumps() = ProxySocketOwnershipCases.oneDirection(context, false)
    @Test fun firstResetRetiresBothPumps() = ProxySocketOwnershipCases.oneDirection(context, true)
    @Test fun constructorCallbackRetiresExactSocketWithoutRelaunch() = ProxySocketOwnershipCases.advertiserRetires(context, "constructor")
    @Test fun warmCallbackRetiresExactSocketWithoutRelaunch() = ProxySocketOwnershipCases.advertiserRetires(context, "warm")
    @Test fun wppCallbackRetiresExactSocketWithoutRelaunch() = ProxySocketOwnershipCases.advertiserRetires(context, "wpp")
    @Test fun oldFinalizerCannotOverwriteReplacement() = ProxySocketOwnershipCases.replacementDuringFinalizer(context)
    @Test fun oldConstructorCallbackCannotCloseReplacement() = ProxySocketOwnershipCases.replacementDuringCallback(context, "constructor")
    @Test fun oldWarmCallbackCannotCloseReplacement() = ProxySocketOwnershipCases.replacementDuringCallback(context, "warm")
    @Test fun oldWppCallbackCannotCloseReplacement() = ProxySocketOwnershipCases.replacementDuringCallback(context, "wpp")
    @Test fun staleStopCannotAffectSuccessorProxy() = ProxySocketOwnershipCases.staleProxyStop(context)
    @Test fun staleProxyCallbackCannotAffectSuccessor() = ProxySocketOwnershipCases.staleCallback(context)
}
