package com.nemo.networkconditioner.model

import java.io.Serializable

class CaptureStats : Serializable {
    @JvmField
    var bytes_sent: Long = 0

    @JvmField
    var bytes_rcvd: Long = 0

    @JvmField
    var dropped_pkts: Long = 0

    @JvmField
    var dropped_sent_pkts: Long = 0

    @JvmField
    var dropped_rcvd_pkts: Long = 0

    @JvmField
    var stall_active: Int = 0

    @JvmField
    var stall_uplink_active: Int = 0

    @JvmField
    var stall_downlink_active: Int = 0

    // Invoked by native code.
    fun setData(
        bytesSent: Long,
        bytesRcvd: Long,
        droppedPkts: Long,
        droppedSentPkts: Long,
        droppedRcvdPkts: Long,
        stallActive: Int,
        stallUplinkActive: Int,
        stallDownlinkActive: Int
    ) {
        bytes_sent = bytesSent
        bytes_rcvd = bytesRcvd
        dropped_pkts = droppedPkts
        dropped_sent_pkts = droppedSentPkts
        dropped_rcvd_pkts = droppedRcvdPkts
        stall_active = stallActive
        stall_uplink_active = stallUplinkActive
        stall_downlink_active = stallDownlinkActive
    }
}
