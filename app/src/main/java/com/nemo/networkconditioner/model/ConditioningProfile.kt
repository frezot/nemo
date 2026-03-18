package com.nemo.networkconditioner.model

import android.content.Intent
import android.content.SharedPreferences
import java.io.Serializable

class ConditioningProfile : Serializable {
    enum class Preset(@JvmField val id: String) {
        LOSS_100("loss_100"),
        THREE_G("3g"),
        HSPA_PLUS("hspa_plus"),
        DSL("dsl"),
        EDGE("edge"),
        LTE_UNSTABLE("lte_unstable"),
        LTE("lte"),
        FIVE_G_UNSTABLE("5g_unstable"),
        FIVE_G("5g"),
        VERY_BAD_NETWORK("very_bad_network"),
        WIFI("wifi"),
        WIFI_80211AC("wifi_80211ac"),
        CUSTOM("custom");

        companion object {
            @JvmStatic
            fun fromId(id: String?): Preset {
                if (id == null) {
                    return WIFI_80211AC
                }

                values().firstOrNull { it.id == id }?.let { return it }

                return when (id) {
                    "good" -> WIFI_80211AC
                    "3g_like" -> THREE_G
                    "edge_like" -> EDGE
                    "high_latency_dns", "high_latency", "lossy" -> VERY_BAD_NETWORK
                    else -> WIFI_80211AC
                }
            }
        }
    }

    @JvmField
    var enabled: Boolean = false

    @JvmField
    var preset: Preset = Preset.WIFI_80211AC

    @JvmField
    var uplinkKbps: Int = 0

    @JvmField
    var downlinkKbps: Int = 0

    @JvmField
    var baseLatencyMs: Int = 0

    @JvmField
    var jitterMs: Int = 0

    @JvmField
    var packetLossPercent: Int = 0

    @JvmField
    var stallEnabled: Boolean = false

    @JvmField
    var stallIntervalMs: Int = 0

    @JvmField
    var stallDurationMs: Int = 0

    fun copy(): ConditioningProfile {
        val profile = ConditioningProfile()
        profile.enabled = enabled
        profile.preset = preset
        profile.uplinkKbps = uplinkKbps
        profile.downlinkKbps = downlinkKbps
        profile.baseLatencyMs = baseLatencyMs
        profile.jitterMs = jitterMs
        profile.packetLossPercent = packetLossPercent
        profile.stallEnabled = stallEnabled
        profile.stallIntervalMs = stallIntervalMs
        profile.stallDurationMs = stallDurationMs
        return profile
    }

    fun sameAs(other: ConditioningProfile?): Boolean {
        if (other == null) {
            return false
        }

        if (!enabled && !other.enabled) {
            return true
        }

        return enabled == other.enabled &&
            preset == other.preset &&
            uplinkKbps == other.uplinkKbps &&
            downlinkKbps == other.downlinkKbps &&
            baseLatencyMs == other.baseLatencyMs &&
            jitterMs == other.jitterMs &&
            packetLossPercent == other.packetLossPercent &&
            stallEnabled == other.stallEnabled &&
            stallIntervalMs == other.stallIntervalMs &&
            stallDurationMs == other.stallDurationMs
    }

    fun toDebugString(): String {
        return "enabled=$enabled" +
            ", preset=${preset.id}" +
            ", uplinkKbps=$uplinkKbps" +
            ", downlinkKbps=$downlinkKbps" +
            ", latencyMs=$baseLatencyMs" +
            ", jitterMs=$jitterMs" +
            ", lossPercent=$packetLossPercent" +
            ", stallEnabled=$stallEnabled" +
            ", stallIntervalMs=$stallIntervalMs" +
            ", stallDurationMs=$stallDurationMs"
    }

    fun persistSelection(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(Prefs.PREF_CONDITIONER_ENABLED, enabled)
            .putString(Prefs.PREF_CONDITIONER_PRESET, preset.id)
            .apply()
    }

    fun persistCustomValues(prefs: SharedPreferences) {
        val sanitized = copy().sanitize()
        prefs.edit()
            .putInt(Prefs.PREF_CONDITIONER_CUSTOM_UPLINK_KBPS, sanitized.uplinkKbps)
            .putInt(Prefs.PREF_CONDITIONER_CUSTOM_DOWNLINK_KBPS, sanitized.downlinkKbps)
            .putInt(Prefs.PREF_CONDITIONER_CUSTOM_LATENCY_MS, sanitized.baseLatencyMs)
            .putInt(Prefs.PREF_CONDITIONER_CUSTOM_JITTER_MS, sanitized.jitterMs)
            .putInt(Prefs.PREF_CONDITIONER_CUSTOM_PACKET_LOSS_PERCENT, sanitized.packetLossPercent)
            .putBoolean(Prefs.PREF_CONDITIONER_CUSTOM_STALL_ENABLED, sanitized.stallEnabled)
            .putInt(Prefs.PREF_CONDITIONER_CUSTOM_STALL_INTERVAL_MS, sanitized.stallIntervalMs)
            .putInt(Prefs.PREF_CONDITIONER_CUSTOM_STALL_DURATION_MS, sanitized.stallDurationMs)
            .apply()
    }

    fun sanitize(): ConditioningProfile {
        uplinkKbps = maxOf(0, uplinkKbps)
        downlinkKbps = maxOf(0, downlinkKbps)
        baseLatencyMs = maxOf(0, baseLatencyMs)
        jitterMs = maxOf(0, jitterMs)
        packetLossPercent = packetLossPercent.coerceIn(0, 100)
        stallIntervalMs = maxOf(0, stallIntervalMs)
        stallDurationMs = maxOf(0, stallDurationMs)

        if (stallIntervalMs == 0 || stallDurationMs == 0) {
            stallEnabled = false
        }

        return this
    }

    private fun copyCustomFieldsFrom(other: ConditioningProfile) {
        uplinkKbps = other.uplinkKbps
        downlinkKbps = other.downlinkKbps
        baseLatencyMs = other.baseLatencyMs
        jitterMs = other.jitterMs
        packetLossPercent = other.packetLossPercent
        stallEnabled = other.stallEnabled
        stallIntervalMs = other.stallIntervalMs
        stallDurationMs = other.stallDurationMs
    }

    companion object {
        @JvmStatic
        fun fromPreferences(prefs: SharedPreferences): ConditioningProfile {
            val enabled = prefs.getBoolean(Prefs.PREF_CONDITIONER_ENABLED, true)
            val preset = Preset.fromId(prefs.getString(Prefs.PREF_CONDITIONER_PRESET, Preset.WIFI_80211AC.id))
            val profile = fromPreset(preset, enabled)

            if (preset == Preset.CUSTOM) {
                profile.copyCustomFieldsFrom(fromStoredCustom(prefs))
            }

            return profile.sanitize()
        }

        @JvmStatic
        fun fromIntent(intent: Intent?): ConditioningProfile {
            if (intent == null) {
                return fromPreset(Preset.WIFI_80211AC, false)
            }

            val enabled = intent.getBooleanExtra(Prefs.PREF_CONDITIONER_ENABLED, false)
            val preset = Preset.fromId(intent.getStringExtra(Prefs.PREF_CONDITIONER_PRESET))
            val profile = fromPreset(preset, enabled)

            if (preset == Preset.CUSTOM) {
                profile.uplinkKbps = intent.getIntExtra(Prefs.PREF_CONDITIONER_CUSTOM_UPLINK_KBPS, 0)
                profile.downlinkKbps = intent.getIntExtra(Prefs.PREF_CONDITIONER_CUSTOM_DOWNLINK_KBPS, 0)
                profile.baseLatencyMs = intent.getIntExtra(Prefs.PREF_CONDITIONER_CUSTOM_LATENCY_MS, 0)
                profile.jitterMs = intent.getIntExtra(Prefs.PREF_CONDITIONER_CUSTOM_JITTER_MS, 0)
                profile.packetLossPercent = intent.getIntExtra(Prefs.PREF_CONDITIONER_CUSTOM_PACKET_LOSS_PERCENT, 0)
                profile.stallEnabled = intent.getBooleanExtra(Prefs.PREF_CONDITIONER_CUSTOM_STALL_ENABLED, false)
                profile.stallIntervalMs = intent.getIntExtra(Prefs.PREF_CONDITIONER_CUSTOM_STALL_INTERVAL_MS, 0)
                profile.stallDurationMs = intent.getIntExtra(Prefs.PREF_CONDITIONER_CUSTOM_STALL_DURATION_MS, 0)
            }

            return profile.sanitize()
        }

        @JvmStatic
        fun fromPreset(preset: Preset, enabled: Boolean): ConditioningProfile {
            val profile = ConditioningProfile()
            profile.enabled = enabled
            profile.preset = preset

            when (preset) {
                Preset.LOSS_100 -> profile.packetLossPercent = 100
                Preset.THREE_G -> {
                    profile.baseLatencyMs = 100
                    profile.uplinkKbps = 330
                    profile.downlinkKbps = 780
                }
                Preset.HSPA_PLUS -> {
                    profile.baseLatencyMs = 70
                    profile.jitterMs = 10
                    profile.uplinkKbps = 1500
                    profile.downlinkKbps = 12000
                }
                Preset.DSL -> {
                    profile.baseLatencyMs = 5
                    profile.uplinkKbps = 256
                    profile.downlinkKbps = 2000
                }
                Preset.EDGE -> {
                    profile.baseLatencyMs = 420
                    profile.jitterMs = 20
                    profile.uplinkKbps = 200
                    profile.downlinkKbps = 240
                }
                Preset.LTE_UNSTABLE -> {
                    profile.baseLatencyMs = 78
                    profile.jitterMs = 18
                    profile.uplinkKbps = 8000
                    profile.downlinkKbps = 30000
                    profile.packetLossPercent = 3
                    profile.stallEnabled = true
                    profile.stallIntervalMs = 18_000
                    profile.stallDurationMs = 900
                }
                Preset.LTE -> {
                    profile.baseLatencyMs = 58
                    profile.jitterMs = 8
                    profile.uplinkKbps = 10000
                    profile.downlinkKbps = 50000
                }
                Preset.FIVE_G_UNSTABLE -> {
                    profile.baseLatencyMs = 35
                    profile.jitterMs = 12
                    profile.uplinkKbps = 75000
                    profile.downlinkKbps = 250000
                    profile.packetLossPercent = 4
                    profile.stallEnabled = true
                    profile.stallIntervalMs = 15_000
                    profile.stallDurationMs = 600
                }
                Preset.FIVE_G -> {
                    profile.baseLatencyMs = 20
                    profile.jitterMs = 4
                    profile.uplinkKbps = 100000
                    profile.downlinkKbps = 350000
                }
                Preset.VERY_BAD_NETWORK -> {
                    profile.baseLatencyMs = 500
                    profile.uplinkKbps = 1000
                    profile.downlinkKbps = 1000
                    profile.packetLossPercent = 10
                }
                Preset.WIFI -> {
                    profile.baseLatencyMs = 2
                    profile.uplinkKbps = 15000
                    profile.downlinkKbps = 30000
                }
                Preset.WIFI_80211AC -> {
                    profile.baseLatencyMs = 1
                    profile.uplinkKbps = 100000
                    profile.downlinkKbps = 250000
                }
                Preset.CUSTOM -> Unit
            }

            return profile.sanitize()
        }

        @JvmStatic
        fun fromStoredCustom(prefs: SharedPreferences): ConditioningProfile {
            val profile = ConditioningProfile()
            profile.enabled = prefs.getBoolean(Prefs.PREF_CONDITIONER_ENABLED, true)
            profile.preset = Preset.CUSTOM
            profile.uplinkKbps = prefs.getInt(Prefs.PREF_CONDITIONER_CUSTOM_UPLINK_KBPS, 0)
            profile.downlinkKbps = prefs.getInt(Prefs.PREF_CONDITIONER_CUSTOM_DOWNLINK_KBPS, 0)
            profile.baseLatencyMs = prefs.getInt(Prefs.PREF_CONDITIONER_CUSTOM_LATENCY_MS, 0)
            profile.jitterMs = prefs.getInt(Prefs.PREF_CONDITIONER_CUSTOM_JITTER_MS, 0)
            profile.packetLossPercent = prefs.getInt(Prefs.PREF_CONDITIONER_CUSTOM_PACKET_LOSS_PERCENT, 0)
            profile.stallEnabled = prefs.getBoolean(Prefs.PREF_CONDITIONER_CUSTOM_STALL_ENABLED, false)
            profile.stallIntervalMs = prefs.getInt(Prefs.PREF_CONDITIONER_CUSTOM_STALL_INTERVAL_MS, 0)
            profile.stallDurationMs = prefs.getInt(Prefs.PREF_CONDITIONER_CUSTOM_STALL_DURATION_MS, 0)
            return profile.sanitize()
        }
    }
}
