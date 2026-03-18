package com.nemo.networkconditioner.model

import android.content.SharedPreferences
import java.io.Serializable
import java.util.HashSet

class CaptureSettings(prefs: SharedPreferences) : Serializable {
    @JvmField
    var app_filter: HashSet<String> = HashSet(Prefs.getAppFilter(prefs))

    @JvmField
    var ip_mode: Prefs.IpMode = Prefs.getIPMode(prefs)

    @JvmField
    var conditioning_profile: ConditioningProfile? = Prefs.getConditioningProfile(prefs)

    fun sanitizeForRuntime() {
        ip_mode = Prefs.IpMode.BOTH
    }
}
