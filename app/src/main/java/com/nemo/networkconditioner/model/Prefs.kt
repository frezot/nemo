package com.nemo.networkconditioner.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.collection.ArraySet
import androidx.preference.PreferenceManager
import com.nemo.networkconditioner.BuildConfig

object Prefs {
    const val IP_MODE_IPV4_ONLY = "ipv4"
    const val IP_MODE_IPV6_ONLY = "ipv6"
    const val IP_MODE_BOTH = "both"
    const val IP_MODE_DEFAULT = IP_MODE_IPV4_ONLY

    const val PREF_APP_FILTER = "app_filter"
    const val PREF_IP_MODE = "ip_mode"
    const val PREF_APP_VERSION = "appver"
    const val PREF_DNS_SERVER_V4 = "dns_v4"
    const val PREF_DNS_SERVER_V6 = "dns_v6"
    const val PREF_USE_SYSTEM_DNS = "system_dns"
    const val PREF_CONDITIONER_ENABLED = "conditioner_enabled"
    const val PREF_CONDITIONER_PRESET = "conditioner_preset"
    const val PREF_CONDITIONER_CUSTOM_UPLINK_KBPS = "conditioner_custom_uplink_kbps"
    const val PREF_CONDITIONER_CUSTOM_DOWNLINK_KBPS = "conditioner_custom_downlink_kbps"
    const val PREF_CONDITIONER_CUSTOM_LATENCY_MS = "conditioner_custom_latency_ms"
    const val PREF_CONDITIONER_CUSTOM_JITTER_MS = "conditioner_custom_jitter_ms"
    const val PREF_CONDITIONER_CUSTOM_PACKET_LOSS_PERCENT = "conditioner_custom_packet_loss_percent"
    const val PREF_CONDITIONER_CUSTOM_STALL_ENABLED = "conditioner_custom_stall_enabled"
    const val PREF_CONDITIONER_CUSTOM_STALL_INTERVAL_MS = "conditioner_custom_stall_interval_ms"
    const val PREF_CONDITIONER_CUSTOM_STALL_DURATION_MS = "conditioner_custom_stall_duration_ms"

    enum class IpMode {
        IPV4_ONLY,
        IPV6_ONLY,
        BOTH,
    }

    @JvmStatic
    fun getIPMode(pref: String?): IpMode {
        return when (pref) {
            IP_MODE_IPV6_ONLY -> IpMode.IPV6_ONLY
            IP_MODE_BOTH -> IpMode.BOTH
            else -> IpMode.IPV4_ONLY
        }
    }

    @JvmStatic
    fun getAppVersion(prefs: SharedPreferences): Int = prefs.getInt(PREF_APP_VERSION, 0)

    @JvmStatic
    fun refreshAppVersion(prefs: SharedPreferences) {
        prefs.edit().putInt(PREF_APP_VERSION, BuildConfig.VERSION_CODE).apply()
    }

    @JvmStatic
    fun getAppFilter(prefs: SharedPreferences): Set<String> = getStringSet(prefs, PREF_APP_FILTER)

    @JvmStatic
    fun getIPMode(prefs: SharedPreferences): IpMode = getIPMode(prefs.getString(PREF_IP_MODE, IP_MODE_DEFAULT))

    @JvmStatic
    fun useSystemDns(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_USE_SYSTEM_DNS, true)

    @JvmStatic
    fun getDnsServerV4(prefs: SharedPreferences): String? = prefs.getString(PREF_DNS_SERVER_V4, "1.1.1.1")

    @JvmStatic
    fun getDnsServerV6(prefs: SharedPreferences): String? = prefs.getString(PREF_DNS_SERVER_V6, "2606:4700:4700::1111")

    @JvmStatic
    fun getConditioningProfile(prefs: SharedPreferences): ConditioningProfile = ConditioningProfile.fromPreferences(prefs)

    @JvmStatic
    fun getCustomConditioningProfile(prefs: SharedPreferences): ConditioningProfile = ConditioningProfile.fromStoredCustom(prefs)

    @SuppressLint("MutatingSharedPrefs")
    @JvmStatic
    fun getStringSet(prefs: SharedPreferences, key: String): Set<String> {
        var result: Set<String>? = null

        try {
            result = prefs.getStringSet(key, null)
        } catch (_: ClassCastException) {
            val value = prefs.getString(key, "")
            if (!value.isNullOrEmpty()) {
                result = ArraySet<String>().apply { add(value) }
            }
        }

        return result ?: ArraySet()
    }

    @JvmStatic
    fun asString(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return "TargetApps: ${getAppFilter(prefs)}" +
            "\nIpMode: ${getIPMode(prefs)}" +
            "\nUseSystemDns: ${useSystemDns(prefs)}"
    }
}
