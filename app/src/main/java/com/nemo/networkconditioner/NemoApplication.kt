package com.nemo.networkconditioner

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.annotation.NonNull
import androidx.preference.PreferenceManager
import com.nemo.networkconditioner.activities.ErrorActivity
import com.nemo.networkconditioner.model.Prefs
import java.lang.ref.WeakReference
import cat.ereza.customactivityoncrash.config.CaocConfig

class NemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (!isUnderTest()) {
            Log.init(cacheDir.absolutePath)
        }

        CaocConfig.Builder.create()
            .errorDrawable(R.drawable.ic_app_crash)
            .errorActivity(ErrorActivity::class.java)
            .apply()

        instance = WeakReference(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> {
                        val newInstall = !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        val packageName = intent.data?.schemeSpecificPart
                        Log.d(TAG, "ACTION_PACKAGE_ADDED [new=$newInstall]: $packageName")
                    }
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        val isUpdate = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        val packageName = intent.data?.schemeSpecificPart
                        Log.d(TAG, "ACTION_PACKAGE_REMOVED [update=$isUpdate]: $packageName")
                        if (!isUpdate) {
                            removeUninstalledAppsFromAppFilter()
                        }
                    }
                }
            }
        }, filter)

        removeUninstalledAppsFromAppFilter()
    }

    private fun removeUninstalledAppsFromAppFilter() {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val filter = Prefs.getAppFilter(prefs).toMutableSet()
        val toRemove = ArrayList<String>()
        val pm: PackageManager = packageManager

        for (packageName in filter) {
            try {
                Utils.getPackageInfo(pm, packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                Log.i(TAG, "Package $packageName uninstalled, removing from app filter")
                toRemove.add(packageName)
            }
        }

        if (toRemove.isNotEmpty()) {
            filter.removeAll(toRemove.toSet())
            prefs.edit()
                .putStringSet(Prefs.PREF_APP_FILTER, filter)
                .apply()
        }
    }

    companion object {
        private const val TAG = "NemoApplication"
        private var instance: WeakReference<NemoApplication>? = null

        @JvmField
        protected var isUnderTest: Boolean = false

        @JvmStatic
        @NonNull
        fun getInstance(): NemoApplication = instance!!.get()!!

        @JvmStatic
        fun isUnderTest(): Boolean = isUnderTest
    }
}
