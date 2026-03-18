package com.nemo.networkconditioner.model

import java.io.Serializable

class CaptureStats : Serializable {
    @JvmField
    var bytes_sent: Long = 0

    @JvmField
    var bytes_rcvd: Long = 0

    // Invoked by native code.
    fun setData(bytesSent: Long, bytesRcvd: Long) {
        bytes_sent = bytesSent
        bytes_rcvd = bytesRcvd
    }
}
