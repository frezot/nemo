package com.nemo.networkconditioner.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.NonNull
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.card.MaterialCardView
import com.nemo.networkconditioner.AppsResolver
import com.nemo.networkconditioner.CaptureService
import com.nemo.networkconditioner.R
import com.nemo.networkconditioner.Utils
import com.nemo.networkconditioner.activities.AppFilterActivity
import com.nemo.networkconditioner.activities.CustomProfileActivity
import com.nemo.networkconditioner.activities.MainActivity
import com.nemo.networkconditioner.interfaces.AppStateListener
import com.nemo.networkconditioner.model.AppDescriptor
import com.nemo.networkconditioner.model.AppState
import com.nemo.networkconditioner.model.CaptureStats
import com.nemo.networkconditioner.model.ConditioningProfile
import com.nemo.networkconditioner.model.Prefs

class StatusFragment : Fragment(), AppStateListener {
    private var activityHost: MainActivity? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var statusCard: MaterialCardView
    private lateinit var profilePickerCard: MaterialCardView
    private lateinit var targetAppsCard: MaterialCardView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var profilePickerTitle: TextView
    private lateinit var targetAppsSummary: TextView
    private lateinit var profileLatencyLabel: TextView
    private lateinit var profileJitterLabel: TextView
    private lateinit var profileLossLabel: TextView
    private lateinit var profileStallsLabel: TextView
    private lateinit var profileLatencyValue: TextView
    private lateinit var profileJitterValue: TextView
    private lateinit var profileUploadValue: TextView
    private lateinit var profileDownloadValue: TextView
    private lateinit var profileLossValue: TextView
    private lateinit var profileStallsValue: TextView
    private var lastStats = CaptureStats()
    private var committedProfileId = ConditioningProfile.Preset.WIFI_80211AC.id
    private lateinit var profileIds: Array<String>
    private lateinit var profileLabels: Array<String>
    private var defaultStatusCardColor = 0
    private var defaultStatusTitleColor = 0
    private var defaultStatusDetailColor = 0
    private var defaultStallsTextSizePx = 0f
    private var pendingAppFilter: Set<String>? = null

    private val customProfileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val profile = Prefs.getCustomConditioningProfile(prefs).apply {
                    preset = ConditioningProfile.Preset.CUSTOM
                    enabled = true
                }
                commitProfile(profile, restartIfRunning = true)
            } else {
                showProfileChooser()
            }
        }

    private val appFilterLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val currentFilter = Prefs.getAppFilter(prefs)
            refreshAppFilterUi(currentFilter)

            if (pendingAppFilter != null &&
                pendingAppFilter != currentFilter &&
                activityHost?.getState() == AppState.running
            ) {
                activityHost?.restartCapture()
            }

            pendingAppFilter = null
        }

    override fun onAttach(@NonNull context: Context) {
        super.onAttach(context)
        activityHost = context as MainActivity
    }

    override fun onDetach() {
        super.onDetach()
        activityHost?.setAppStateListener(null)
        activityHost = null
    }

    override fun onResume() {
        super.onResume()
        reloadFromPreferences()
        refreshStatus()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.status, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        statusCard = view.findViewById(R.id.status_card)
        profilePickerCard = view.findViewById(R.id.profile_picker_card)
        targetAppsCard = view.findViewById(R.id.target_apps_card)
        statusTitle = view.findViewById(R.id.status_title)
        statusDetail = view.findViewById(R.id.status_detail)
        profilePickerTitle = view.findViewById(R.id.profile_picker_title)
        targetAppsSummary = view.findViewById(R.id.target_apps_summary)
        profileLatencyLabel = view.findViewById(R.id.profile_latency_label)
        profileJitterLabel = view.findViewById(R.id.profile_jitter_label)
        profileLossLabel = view.findViewById(R.id.profile_loss_label)
        profileStallsLabel = view.findViewById(R.id.profile_stalls_label)
        profileLatencyValue = view.findViewById(R.id.profile_latency_value)
        profileJitterValue = view.findViewById(R.id.profile_jitter_value)
        profileUploadValue = view.findViewById(R.id.profile_upload_value)
        profileDownloadValue = view.findViewById(R.id.profile_download_value)
        profileLossValue = view.findViewById(R.id.profile_loss_value)
        profileStallsValue = view.findViewById(R.id.profile_stalls_value)
        profileIds = resources.getStringArray(R.array.conditioner_profile_ids)
        profileLabels = resources.getStringArray(R.array.conditioner_profile_labels)
        defaultStatusCardColor = statusCard.cardBackgroundColor.defaultColor
        defaultStatusTitleColor = statusTitle.currentTextColor
        defaultStatusDetailColor = statusDetail.currentTextColor
        defaultStallsTextSizePx = profileStallsValue.textSize

        statusCard.setOnClickListener {
            when (activityHost?.getState()) {
                AppState.ready -> {
                    activityHost?.startCapture()
                    refreshStatus()
                }
                AppState.running -> {
                    activityHost?.stopCapture()
                    refreshStatus()
                }
                else -> Unit
            }
        }
        profilePickerCard.setOnClickListener { showProfileChooser() }
        targetAppsCard.setOnClickListener {
            pendingAppFilter = Prefs.getAppFilter(prefs)
            appFilterLauncher.launch(Intent(requireContext(), AppFilterActivity::class.java))
        }

        bindExplainDialog(profileLatencyLabel, R.string.profile_detail_latency, R.string.profile_detail_latency_tooltip)
        bindExplainDialog(profileJitterLabel, R.string.profile_detail_jitter, R.string.profile_detail_jitter_tooltip)
        bindExplainDialog(profileLossLabel, R.string.profile_detail_loss, R.string.profile_detail_loss_tooltip)
        bindExplainDialog(profileStallsLabel, R.string.profile_detail_stalls, R.string.profile_detail_stalls_tooltip)
        bindExplainDialog(profileUploadValue, R.string.profile_detail_upload, R.string.profile_detail_upload_tooltip)
        bindExplainDialog(profileDownloadValue, R.string.profile_detail_download, R.string.profile_detail_download_tooltip)

        CaptureService.observeStats(viewLifecycleOwner) { stats ->
            lastStats = stats
            refreshStatus()
        }

        lastStats = CaptureService.getStats()
        activityHost?.setAppStateListener(this)
        reloadFromPreferences()
        refreshStatus()
    }

    override fun appStateChanged(state: AppState) {
        refreshStatus()
    }

    private fun reloadFromPreferences() {
        val profile = Prefs.getConditioningProfile(prefs).apply { enabled = true }
        committedProfileId = profile.preset.id
        refreshProfileUi(profile)
        refreshAppFilterUi(Prefs.getAppFilter(prefs))
    }

    private fun commitProfile(profile: ConditioningProfile, restartIfRunning: Boolean) {
        profile.enabled = true
        profile.persistSelection(prefs)
        committedProfileId = profile.preset.id
        refreshProfileUi(profile)
        refreshStatus()

        if (restartIfRunning && activityHost?.getState() == AppState.running) {
            activityHost?.restartCapture()
        }
    }

    private fun refreshProfileUi(profile: ConditioningProfile) {
        profilePickerTitle.setText(getPresetLabel(profile.preset))
        profileLatencyValue.text = formatLatencyValue(profile)
        profileJitterValue.text = formatJitterValue(profile)
        profileUploadValue.text = formatUploadValue(profile)
        profileDownloadValue.text = formatDownloadValue(profile)
        profileLossValue.text = formatLossValue(profile)
        profileStallsValue.text = formatStallsValue(profile)
        profileStallsValue.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            if (profile.stallEnabled) defaultStallsTextSizePx * 0.84f else defaultStallsTextSizePx
        )
    }

    private fun refreshStatus() {
        if (activityHost == null || context == null) {
            return
        }

        val state = activityHost!!.getState()
        val cardEnabled = state == AppState.ready || state == AppState.running
        statusCard.isEnabled = cardEnabled
        statusCard.isClickable = cardEnabled
        statusCard.alpha = if (cardEnabled) 1.0f else 0.85f
        profilePickerCard.isEnabled = cardEnabled
        profilePickerCard.isClickable = cardEnabled
        profilePickerCard.alpha = if (cardEnabled) 1.0f else 0.85f
        targetAppsCard.isEnabled = cardEnabled
        targetAppsCard.isClickable = cardEnabled
        targetAppsCard.alpha = if (cardEnabled) 1.0f else 0.85f

        when (state) {
            AppState.starting -> {
                statusTitle.setText(R.string.vpn_starting)
                statusDetail.setText(R.string.vpn_starting_detail)
            }
            AppState.running -> {
                statusTitle.setText(R.string.vpn_running)
                statusDetail.text = getString(
                    R.string.transferred_bytes,
                    Utils.formatBytes(lastStats.bytes_sent + lastStats.bytes_rcvd)
                )
            }
            AppState.stopping -> {
                statusTitle.setText(R.string.vpn_stopping)
                statusDetail.setText(R.string.vpn_stopping_detail)
            }
            AppState.ready -> {
                statusTitle.setText(R.string.vpn_stopped)
                statusDetail.setText(R.string.vpn_stopped_detail)
            }
        }

        applyStatusCardTheme(state)
        refreshProfileUi(getCommittedProfile())
        refreshAppFilterUi(Prefs.getAppFilter(prefs))
    }

    private fun refreshAppFilterUi(appFilter: Set<String>) {
        targetAppsSummary.text = buildAppFilterSummary(appFilter)
    }

    private fun buildAppFilterSummary(appFilter: Set<String>): CharSequence {
        if (appFilter.isEmpty()) {
            return getString(R.string.all_apps)
        }

        if (appFilter.size == 1) {
            val packageName = appFilter.first()
            val app: AppDescriptor? = AppsResolver.resolveInstalledApp(
                requireContext().packageManager,
                packageName,
                0,
                false
            )
            return app?.getName() ?: packageName
        }

        return resources.getQuantityString(
            R.plurals.target_apps_selected,
            appFilter.size,
            appFilter.size
        )
    }

    private fun applyStatusCardTheme(state: AppState) {
        val (backgroundColor, titleColor, detailColor) = when (state) {
            AppState.running -> {
                val foregroundColor = ContextCompat.getColor(requireContext(), android.R.color.white)
                Triple(
                    ContextCompat.getColor(requireContext(), R.color.statusOpen),
                    foregroundColor,
                    foregroundColor
                )
            }
            AppState.starting -> Triple(
                ContextCompat.getColor(requireContext(), R.color.statusStarting),
                ContextCompat.getColor(requireContext(), R.color.statusStartingText),
                ContextCompat.getColor(requireContext(), R.color.statusStartingText)
            )
            AppState.stopping -> Triple(
                ContextCompat.getColor(requireContext(), R.color.statusStopping),
                ContextCompat.getColor(requireContext(), R.color.statusStoppingText),
                ContextCompat.getColor(requireContext(), R.color.statusStoppingText)
            )
            AppState.ready -> Triple(
                defaultStatusCardColor,
                defaultStatusTitleColor,
                defaultStatusDetailColor
            )
        }

        statusCard.setCardBackgroundColor(backgroundColor)
        statusTitle.setTextColor(titleColor)
        statusDetail.setTextColor(detailColor)
    }

    private fun bindExplainDialog(view: TextView, titleRes: Int, messageRes: Int) {
        view.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun getCommittedProfile(): ConditioningProfile {
        var profile = Prefs.getConditioningProfile(prefs)
        if (profile.preset == ConditioningProfile.Preset.CUSTOM) {
            profile = Prefs.getCustomConditioningProfile(prefs)
        }

        profile.enabled = true
        return profile.sanitize()
    }

    private fun formatLatencyValue(profile: ConditioningProfile): String =
        getString(R.string.profile_value_ms, profile.baseLatencyMs)

    private fun formatJitterValue(profile: ConditioningProfile): String =
        if (profile.jitterMs <= 0) {
            getString(R.string.profile_value_off)
        } else {
            getString(R.string.profile_value_jitter, profile.jitterMs)
        }

    private fun formatUploadValue(profile: ConditioningProfile): String {
        val value = if (profile.uplinkKbps <= 0) {
            getString(R.string.profile_value_unlimited)
        } else {
            getString(R.string.profile_value_kbps, profile.uplinkKbps)
        }
        return getString(R.string.profile_value_upload, value)
    }

    private fun formatDownloadValue(profile: ConditioningProfile): String {
        val value = if (profile.downlinkKbps <= 0) {
            getString(R.string.profile_value_unlimited)
        } else {
            getString(R.string.profile_value_kbps, profile.downlinkKbps)
        }
        return getString(R.string.profile_value_download, value)
    }

    private fun formatLossValue(profile: ConditioningProfile): String =
        if (profile.packetLossPercent <= 0) {
            getString(R.string.profile_value_off)
        } else {
            getString(R.string.profile_value_loss, profile.packetLossPercent)
        }

    private fun formatStallsValue(profile: ConditioningProfile): String =
        if (!profile.stallEnabled) {
            getString(R.string.profile_value_off)
        } else {
            getString(
                R.string.profile_value_stalls,
                intervalMsToSeconds(profile.stallIntervalMs),
                profile.stallDurationMs
            )
        }

    private fun showProfileChooser() {
        if (activityHost == null || context == null) {
            return
        }

        val checkedItem = getProfilePos(committedProfileId)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.conditioner_profile)
            .setSingleChoiceItems(profileLabels, checkedItem) { dialog, which ->
                dialog.dismiss()
                handleProfileSelection(which)
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun handleProfileSelection(position: Int) {
        if (position < 0 || position >= profileIds.size) {
            return
        }

        val preset = ConditioningProfile.Preset.fromId(profileIds[position])
        if (preset == ConditioningProfile.Preset.CUSTOM) {
            customProfileLauncher.launch(Intent(requireContext(), CustomProfileActivity::class.java))
            return
        }

        if (preset.id == committedProfileId) {
            return
        }

        val profile = ConditioningProfile.fromPreset(preset, true).apply {
            this.preset = preset
            enabled = true
        }
        commitProfile(profile.sanitize(), restartIfRunning = true)
    }

    private fun getProfilePos(profileId: String): Int {
        for (i in profileIds.indices) {
            if (profileId == profileIds[i]) {
                return i
            }
        }

        return 0
    }

    private fun intervalMsToSeconds(intervalMs: Int): Int {
        if (intervalMs <= 0) {
            return 0
        }

        return maxOf(1, kotlin.math.round(intervalMs / 1000.0f).toInt())
    }

    private fun getPresetLabel(preset: ConditioningProfile.Preset): Int =
        when (preset) {
            ConditioningProfile.Preset.LOSS_100 -> R.string.profile_100_loss
            ConditioningProfile.Preset.THREE_G -> R.string.profile_3g
            ConditioningProfile.Preset.HSPA_PLUS -> R.string.profile_hspa_plus
            ConditioningProfile.Preset.DSL -> R.string.profile_dsl
            ConditioningProfile.Preset.EDGE -> R.string.profile_edge
            ConditioningProfile.Preset.LTE_UNSTABLE -> R.string.profile_lte_unstable
            ConditioningProfile.Preset.LTE -> R.string.profile_lte
            ConditioningProfile.Preset.FIVE_G_UNSTABLE -> R.string.profile_5g_unstable
            ConditioningProfile.Preset.FIVE_G -> R.string.profile_5g
            ConditioningProfile.Preset.VERY_BAD_NETWORK -> R.string.profile_very_bad_network
            ConditioningProfile.Preset.WIFI -> R.string.profile_wifi
            ConditioningProfile.Preset.WIFI_80211AC -> R.string.profile_wifi_80211ac
            ConditioningProfile.Preset.CUSTOM -> R.string.profile_custom
        }
}
