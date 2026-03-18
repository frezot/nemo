package com.nemo.networkconditioner.activities

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.NonNull
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.nemo.networkconditioner.CaptureService
import com.nemo.networkconditioner.Log
import com.nemo.networkconditioner.R
import com.nemo.networkconditioner.Utils
import com.nemo.networkconditioner.interfaces.AppStateListener
import com.nemo.networkconditioner.model.AppState
import com.nemo.networkconditioner.model.CaptureSettings
import com.nemo.networkconditioner.model.Prefs

class MainActivity : BaseActivity(), MenuProvider {
    private var state = AppState.ready
    private var listener: AppStateListener? = null
    private lateinit var prefs: SharedPreferences
    private var pendingCaptureSettings: CaptureSettings? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    // Helps detecting duplicate state reporting of STOPPED in MutableLiveData.
    private var wasStarted = false
    private var restartCapturePending = false

    private val vpnPrepareLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult(), ::onVpnPrepareResult)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme_NoActionBar)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.main_activity)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        Prefs.refreshAppVersion(prefs)

        initAppState()
        setupToolbar()
        addMenuProvider(this)

        CaptureService.observeStatus(this) { serviceStatus ->
            Log.d(TAG, "Service status: ${serviceStatus.name}")

            if (serviceStatus == CaptureService.ServiceStatus.STARTED) {
                appStateRunning()
                wasStarted = true
            } else if (wasStarted) {
                if (CaptureService.isServiceActive()) {
                    CaptureService.stopService()
                }

                wasStarted = false
                if (restartCapturePending) {
                    restartCapturePending = false
                    uiHandler.postDelayed(::doStartCaptureService, RESTART_STOPPING_STATE_MIN_VISIBLE_MS)
                } else {
                    appStateReady()
                }
            } else {
                appStateReady()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        pendingCaptureSettings = null
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setTitle(R.string.nemo_app_name)
            setSubtitle(R.string.nemo_expanded_name)
        }

        val rootView = findViewById<android.view.View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, windowInsets ->
            val insets: Insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            val mlp = v.layoutParams as ViewGroup.MarginLayoutParams
            mlp.leftMargin = insets.left
            mlp.rightMargin = insets.right

            windowInsets.inset(insets.left, 0, insets.right, 0)
        }
    }

    override fun onCreateMenu(@NonNull menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.about_menu, menu)
    }

    override fun onMenuItemSelected(@NonNull item: MenuItem): Boolean {
        if (item.itemId == R.id.open_about) {
            startActivity(Intent(this, AboutActivity::class.java))
            return true
        }

        return false
    }

    fun setAppStateListener(listener: AppStateListener?) {
        this.listener = listener
    }

    private fun notifyAppState() {
        listener?.appStateChanged(state)
    }

    fun appStateReady() {
        state = AppState.ready
        notifyAppState()
    }

    fun appStateStarting() {
        state = AppState.starting
        notifyAppState()
    }

    fun appStateRunning() {
        state = AppState.running
        notifyAppState()
    }

    fun appStateStopping() {
        state = AppState.stopping
        notifyAppState()
    }

    private fun initAppState() {
        if (!CaptureService.isServiceActive()) {
            appStateReady()
        } else {
            appStateRunning()
        }
    }

    private fun doStartCaptureService() {
        appStateStarting()

        val settings = CaptureSettings(prefs)
        settings.sanitizeForRuntime()
        settings.conditioning_profile?.enabled = true

        uiHandler.postDelayed({ startCaptureWithSettings(settings) }, STARTING_STATE_MIN_VISIBLE_MS)
    }

    private fun onVpnPrepareResult(result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK) {
            startCaptureService()
        } else {
            Utils.showToastLong(this, R.string.vpn_setup_failed)
            Log.w(TAG, "Capture start failed")
            appStateReady()
        }
    }

    private fun startCaptureWithSettings(settings: CaptureSettings) {
        if (CaptureService.isServiceActive()) {
            CaptureService.stopService()
        }

        pendingCaptureSettings = settings

        val vpnPrepareIntent = try {
            VpnService.prepare(this)
        } catch (e: RuntimeException) {
            e.printStackTrace()
            null
        }

        if (vpnPrepareIntent != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.vpn_setup_title)
                .setMessage(R.string.vpn_setup_msg)
                .setPositiveButton(R.string.ok) { _, _ ->
                    try {
                        vpnPrepareLauncher.launch(vpnPrepareIntent)
                    } catch (e: ActivityNotFoundException) {
                        Utils.showToastLong(this, R.string.no_intent_handler_found)
                        Log.w(TAG, "Capture start failed")
                        appStateReady()
                    }
                }
                .setOnCancelListener {
                    Utils.showToastLong(this, R.string.vpn_setup_failed)
                    Log.w(TAG, "Capture start failed")
                    appStateReady()
                }
                .show()
        } else {
            startCaptureService()
        }
    }

    private fun startCaptureService() {
        val settings = pendingCaptureSettings
        if (settings == null) {
            Log.e(TAG, "Missing capture settings")
            appStateReady()
            return
        }

        val intent = Intent(this, CaptureService::class.java)
        intent.putExtra("settings", settings)
        ContextCompat.startForegroundService(this, intent)
    }

    fun startCapture() {
        if (Utils.getRunningVpn(this) != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.active_vpn_detected)
                .setMessage(R.string.disconnect_vpn_confirm)
                .setPositiveButton(R.string.ok) { _, _ -> doStartCaptureService() }
                .setNegativeButton(R.string.cancel_action, null)
                .show()
        } else {
            doStartCaptureService()
        }
    }

    fun stopCapture() {
        appStateStopping()
        CaptureService.stopService()
    }

    fun restartCapture() {
        when (state) {
            AppState.running -> {
                restartCapturePending = true
                stopCapture()
            }
            AppState.ready -> startCapture()
            else -> Unit
        }
    }

    fun getState(): AppState = state

    companion object {
        private const val TAG = "Main"
        private const val STARTING_STATE_MIN_VISIBLE_MS = 180L
        private const val RESTART_STOPPING_STATE_MIN_VISIBLE_MS = 180L
    }
}
