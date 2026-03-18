package com.nemo.networkconditioner.activities

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.TypedArray
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cat.ereza.customactivityoncrash.CustomActivityOnCrash
import com.nemo.networkconditioner.R
import com.nemo.networkconditioner.Utils
import com.nemo.networkconditioner.model.Prefs

class ErrorActivity : AppCompatActivity() {
    @SuppressLint("PrivateResource")
    override fun onCreate(savedInstanceState: Bundle?) {
        Utils.enableEdgeToEdge(this)
        super.onCreate(savedInstanceState)

        findViewById<View?>(R.id.toolbar)?.let { toolbar ->
            ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
                val topInset = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() or
                        WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout(),
                ).top
                if (topInset > 0) {
                    view.setPadding(0, topInset, 0, 0)
                }
                WindowInsetsCompat.CONSUMED
            }
        }

        val a: TypedArray = obtainStyledAttributes(R.styleable.AppCompatTheme)
        if (!a.hasValue(R.styleable.AppCompatTheme_windowActionBar)) {
            setTheme(R.style.Theme_AppCompat_Light_DarkActionBar)
        }
        a.recycle()

        setContentView(R.layout.error_activity)

        val restartButton = findViewById<Button>(R.id.customactivityoncrash_error_activity_restart_button)
        val config = CustomActivityOnCrash.getConfigFromIntent(intent)
        if (config == null) {
            finish()
            return
        }

        if (config.isShowRestartButton && config.restartActivityClass != null) {
            restartButton.setText(R.string.customactivityoncrash_error_activity_restart_app)
            restartButton.setOnClickListener {
                CustomActivityOnCrash.restartApplication(this@ErrorActivity, config)
            }
        } else {
            restartButton.setOnClickListener {
                CustomActivityOnCrash.closeApplication(this@ErrorActivity, config)
            }
        }

        val moreInfoButton = findViewById<Button>(R.id.customactivityoncrash_error_activity_more_info_button)
        if (config.isShowErrorDetails) {
            moreInfoButton.setOnClickListener {
                val dialog = AlertDialog.Builder(this@ErrorActivity)
                    .setTitle(R.string.customactivityoncrash_error_activity_error_details_title)
                    .setMessage(getErrorDetails())
                    .setPositiveButton(R.string.customactivityoncrash_error_activity_error_details_close, null)
                    .setNeutralButton(
                        R.string.customactivityoncrash_error_activity_error_details_copy,
                    ) { _: DialogInterface, _: Int ->
                        copyErrorToClipboard()
                    }
                    .show()

                dialog.findViewById<TextView>(android.R.id.message)?.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.customactivityoncrash_error_activity_error_details_text_size),
                )
            }
        } else {
            moreInfoButton.visibility = View.GONE
        }

        val defaultErrorActivityDrawableId = config.errorDrawable
        val errorImageView = findViewById<ImageView>(R.id.customactivityoncrash_error_activity_image)
        if (defaultErrorActivityDrawableId != null) {
            errorImageView.setImageDrawable(
                ResourcesCompat.getDrawable(resources, defaultErrorActivityDrawableId, theme),
            )
        }
    }

    private fun copyErrorToClipboard() {
        val errorInformation = getErrorDetails()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager?
        if (clipboard != null) {
            val clip = ClipData.newPlainText(
                getString(R.string.customactivityoncrash_error_activity_error_details_clipboard_label),
                errorInformation,
            )
            clipboard.setPrimaryClip(clip)
            Toast.makeText(
                this@ErrorActivity,
                R.string.customactivityoncrash_error_activity_error_details_copied,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun getErrorDetails(): String = getAllErrorDetailsFromIntent(this@ErrorActivity, intent)

    companion object {
        @JvmStatic
        fun getAllErrorDetailsFromIntent(context: Context, intent: Intent): String {
            var errorDetails = Utils.getBuildInfo(context)
            errorDetails += "\nStack trace:  \n"
            errorDetails += CustomActivityOnCrash.getStackTraceFromIntent(intent)

            val activityLog = CustomActivityOnCrash.getActivityLogFromIntent(intent)
            if (activityLog != null) {
                errorDetails += "\nUser actions: \n"
                errorDetails += activityLog
            }

            errorDetails += "\n" + Prefs.asString(context)
            return errorDetails
        }
    }
}
