package com.openautolink.carplaylab.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle

/** OS-bound managed service. Declaring it does not register/enable a PhoneAccount. */
class ManagedCarPlayConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        ManagedTelecomExperiment.get(this).create(request.accountHandle,
            request.extras?.getString(ManagedTelecomExperiment.REQUEST_TOKEN))

    override fun onCreateOutgoingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        ManagedIncomingBridge.failed() // no outgoing, redial or fake seed calls
    override fun onCreateIncomingConnectionFailed(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest) {
        ManagedTelecomExperiment.get(this).admissionFailed(request.accountHandle,
            request.extras?.getString(ManagedTelecomExperiment.REQUEST_TOKEN))
    }
}
