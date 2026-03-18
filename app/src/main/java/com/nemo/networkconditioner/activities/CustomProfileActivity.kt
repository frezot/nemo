package com.nemo.networkconditioner.activities

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.nemo.networkconditioner.R
import com.nemo.networkconditioner.model.ConditioningProfile
import kotlin.math.roundToInt

class CustomProfileActivity : BaseActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var latencyLayout: TextInputLayout
    private lateinit var jitterLayout: TextInputLayout
    private lateinit var uplinkLayout: TextInputLayout
    private lateinit var downlinkLayout: TextInputLayout
    private lateinit var lossLayout: TextInputLayout
    private lateinit var stallIntervalLayout: TextInputLayout
    private lateinit var stallDurationLayout: TextInputLayout
    private lateinit var latencyInput: TextInputEditText
    private lateinit var jitterInput: TextInputEditText
    private lateinit var uplinkInput: TextInputEditText
    private lateinit var downlinkInput: TextInputEditText
    private lateinit var lossInput: TextInputEditText
    private lateinit var stallIntervalInput: TextInputEditText
    private lateinit var stallDurationInput: TextInputEditText
    private lateinit var stallSwitch: SwitchCompat
    private lateinit var stallFields: View
    private lateinit var contentScroll: NestedScrollView
    private lateinit var contentContainer: View
    private var focusedField: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme_NoActionBar)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.custom_profile_activity)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        setTitle(R.string.custom_profile)
        displayBackAction()

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        latencyLayout = findViewById(R.id.latency_layout)
        jitterLayout = findViewById(R.id.jitter_layout)
        uplinkLayout = findViewById(R.id.uplink_layout)
        downlinkLayout = findViewById(R.id.downlink_layout)
        lossLayout = findViewById(R.id.loss_layout)
        stallIntervalLayout = findViewById(R.id.stall_interval_layout)
        stallDurationLayout = findViewById(R.id.stall_duration_layout)
        latencyInput = findViewById(R.id.latency_input)
        jitterInput = findViewById(R.id.jitter_input)
        uplinkInput = findViewById(R.id.uplink_input)
        downlinkInput = findViewById(R.id.downlink_input)
        lossInput = findViewById(R.id.loss_input)
        stallIntervalInput = findViewById(R.id.stall_interval_input)
        stallDurationInput = findViewById(R.id.stall_duration_input)
        stallSwitch = findViewById(R.id.stall_switch)
        stallFields = findViewById(R.id.stall_fields)
        contentScroll = findViewById(R.id.content_scroll)
        contentContainer = findViewById(R.id.content_container)

        ViewCompat.setOnApplyWindowInsetsListener(contentScroll) { _, insets ->
            val ime: Insets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomPadding = maxOf(ime.bottom, systemBars.bottom) + dpToPx(24)
            contentContainer.setPadding(
                contentContainer.paddingLeft,
                contentContainer.paddingTop,
                contentContainer.paddingRight,
                bottomPadding,
            )

            if (ime.bottom > 0 && focusedField != null) {
                scrollFieldIntoView(focusedField!!, 32)
            }

            insets
        }

        loadProfile()
        bindScrollOnFocus(latencyInput)
        bindScrollOnFocus(jitterInput)
        bindScrollOnFocus(uplinkInput)
        bindScrollOnFocus(downlinkInput)
        bindScrollOnFocus(lossInput)
        bindScrollOnFocus(stallIntervalInput)
        bindScrollOnFocus(stallDurationInput)

        stallSwitch.setOnCheckedChangeListener { _, isChecked -> updateStallVisibility(isChecked) }

        findViewById<MaterialButton>(R.id.cancel_custom_profile).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.apply_custom_profile).setOnClickListener { applyProfile() }
    }

    private fun loadProfile() {
        val profile = ConditioningProfile.fromStoredCustom(prefs)

        setInputValue(latencyInput, profile.baseLatencyMs)
        setInputValue(jitterInput, profile.jitterMs)
        setInputValue(uplinkInput, profile.uplinkKbps)
        setInputValue(downlinkInput, profile.downlinkKbps)
        setInputValue(lossInput, profile.packetLossPercent)
        setInputValue(stallIntervalInput, intervalMsToSeconds(profile.stallIntervalMs))
        setInputValue(stallDurationInput, profile.stallDurationMs)
        stallSwitch.isChecked = profile.stallEnabled
        updateStallVisibility(profile.stallEnabled)
    }

    private fun applyProfile() {
        clearErrors()

        val latency = readInt(latencyLayout, latencyInput, 0, Int.MAX_VALUE)
        val jitter = readInt(jitterLayout, jitterInput, 0, Int.MAX_VALUE)
        val uplink = readInt(uplinkLayout, uplinkInput, 0, Int.MAX_VALUE)
        val downlink = readInt(downlinkLayout, downlinkInput, 0, Int.MAX_VALUE)
        val loss = readInt(lossLayout, lossInput, 0, 100)
        val stallIntervalSeconds = readInt(stallIntervalLayout, stallIntervalInput, 0, Int.MAX_VALUE / 1000)
        val stallDuration = readInt(stallDurationLayout, stallDurationInput, 0, Int.MAX_VALUE)

        if (latency == null || jitter == null || uplink == null || downlink == null ||
            loss == null || stallIntervalSeconds == null || stallDuration == null
        ) {
            return
        }

        val profile = ConditioningProfile()
        profile.preset = ConditioningProfile.Preset.CUSTOM
        profile.baseLatencyMs = latency
        profile.jitterMs = jitter
        profile.uplinkKbps = uplink
        profile.downlinkKbps = downlink
        profile.packetLossPercent = loss
        profile.stallEnabled = stallSwitch.isChecked
        profile.stallIntervalMs = secondsToIntervalMs(stallIntervalSeconds)
        profile.stallDurationMs = stallDuration
        profile.persistCustomValues(prefs)
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun clearErrors() {
        latencyLayout.error = null
        jitterLayout.error = null
        uplinkLayout.error = null
        downlinkLayout.error = null
        lossLayout.error = null
        stallIntervalLayout.error = null
        stallDurationLayout.error = null
    }

    private fun updateStallVisibility(enabled: Boolean) {
        stallFields.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun bindScrollOnFocus(view: View) {
        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                focusedField = v
                scrollFieldIntoView(v, 220)
            } else if (focusedField === v) {
                focusedField = null
            }
        }

        view.setOnClickListener { v ->
            focusedField = v
            scrollFieldIntoView(v, 120)
        }
    }

    private fun scrollFieldIntoView(field: View, delayMs: Long) {
        contentScroll.postDelayed({
            val anchor = resolveScrollAnchor(field)
            val rect = Rect()
            anchor.getDrawingRect(rect)
            contentScroll.offsetDescendantRectToMyCoords(anchor, rect)

            val topMargin = dpToPx(20)
            val bottomMargin = dpToPx(36)
            val visibleTop = contentScroll.scrollY
            val visibleBottom = visibleTop + contentScroll.height - contentScroll.paddingBottom

            if (rect.bottom + bottomMargin > visibleBottom) {
                contentScroll.smoothScrollBy(0, rect.bottom + bottomMargin - visibleBottom)
            } else if (rect.top - topMargin < visibleTop) {
                contentScroll.smoothScrollBy(0, rect.top - topMargin - visibleTop)
            }
        }, delayMs)
    }

    private fun resolveScrollAnchor(field: View): View {
        var current = field
        while (current.parent is View && current.parent !== contentContainer) {
            val parent = current.parent as View
            if (parent is TextInputLayout) {
                return parent
            }
            current = parent
        }
        return field
    }

    private fun dpToPx(dp: Int): Int = kotlin.math.round(dp * resources.displayMetrics.density).toInt()

    private fun setInputValue(view: TextInputEditText, value: Int) {
        view.setText(value.toString())
    }

    private fun intervalMsToSeconds(intervalMs: Int): Int {
        if (intervalMs <= 0) return 0
        return maxOf(1, (intervalMs / 1000.0f).roundToInt())
    }

    private fun secondsToIntervalMs(seconds: Int): Int {
        if (seconds <= 0) return 0
        val millis = seconds * 1000L
        return if (millis > Int.MAX_VALUE) Int.MAX_VALUE else millis.toInt()
    }

    private fun readInt(
        layout: TextInputLayout,
        input: TextInputEditText,
        min: Int,
        max: Int,
    ): Int? {
        var text = input.text?.toString()?.trim() ?: ""
        if (TextUtils.isEmpty(text)) {
            text = "0"
        }

        return try {
            val value = text.toInt()
            if (value < min || value > max) {
                layout.error = getString(R.string.value_must_be_between, min, max)
                null
            } else {
                value
            }
        } catch (_: NumberFormatException) {
            layout.error = getString(R.string.invalid_number)
            null
        }
    }
}
