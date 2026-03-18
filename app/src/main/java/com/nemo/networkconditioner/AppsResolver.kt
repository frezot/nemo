package com.nemo.networkconditioner

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.ArrayMap
import android.util.SparseArray
import androidx.core.content.ContextCompat
import com.nemo.networkconditioner.interfaces.DrawableLoader
import com.nemo.networkconditioner.model.AppDescriptor

class AppsResolver(context: Context) {
    private val apps = SparseArray<AppDescriptor>()
    private val pm: PackageManager = context.packageManager
    private val appContext: Context = context
    private var virtualAppIcon: Drawable? = null

    init {
        initVirtualApps()
    }

    private fun initVirtualApps() {
        val virtualIconLoader = DrawableLoader {
            if (virtualAppIcon == null) {
                virtualAppIcon = ContextCompat.getDrawable(appContext, android.R.drawable.sym_def_app_icon)
            }
            virtualAppIcon
        }
        val unknownIconLoader = DrawableLoader {
            ContextCompat.getDrawable(appContext, android.R.drawable.ic_menu_help)
        }

        apps.put(
            Utils.UID_UNKNOWN,
            AppDescriptor(appContext.getString(R.string.unknown_app), unknownIconLoader, "unknown", Utils.UID_UNKNOWN, true)
                .setDescription(appContext.getString(R.string.unknown_app_info)),
        )
        apps.put(
            0,
            AppDescriptor("Root", virtualIconLoader, "root", 0, true)
                .setDescription(appContext.getString(R.string.root_app_info)),
        )
        apps.put(
            1000,
            AppDescriptor("Android", virtualIconLoader, "android", 1000, true)
                .setDescription(appContext.getString(R.string.android_app_info)),
        )
        apps.put(
            1001,
            AppDescriptor(appContext.getString(R.string.phone_app), virtualIconLoader, "phone", 1001, true)
                .setDescription(appContext.getString(R.string.phone_app_info)),
        )
        apps.put(1013, AppDescriptor("MediaServer", virtualIconLoader, "mediaserver", 1013, true))
        apps.put(1020, AppDescriptor("MulticastDNSResponder", virtualIconLoader, "multicastdnsresponder", 1020, true))
        apps.put(1021, AppDescriptor("GPS", virtualIconLoader, "gps", 1021, true))
        apps.put(
            1051,
            AppDescriptor("netd", virtualIconLoader, "netd", 1051, true)
                .setDescription(appContext.getString(R.string.netd_app_info)),
        )
        apps.put(9999, AppDescriptor("Nobody", virtualIconLoader, "nobody", 9999, true))
    }

    @SuppressLint("DiscouragedPrivateApi")
    fun getAppByUid(uid: Int, pmFlags: Int): AppDescriptor? {
        getMappedApp(uid)?.let { return it }

        apps[uid]?.let { return it }

        val packages = try {
            pm.getPackagesForUid(uid)
        } catch (e: SecurityException) {
            e.printStackTrace()
            null
        }

        if (packages == null || packages.isEmpty()) {
            Log.w(TAG, "could not retrieve package: uid=$uid")
            return null
        }

        var packageName = packages[0]
        for (pkg in packages) {
            if (pkg < packageName) {
                packageName = pkg
            }
        }

        val app = resolveInstalledApp(pm, packageName, pmFlags)?.also {
            apps.put(uid, it)
        }

        return app
    }

    fun getUid(packageName: String): Int {
        getMappedApp(packageName)?.let { return it.getUid() }

        if (!packageName.contains(".")) {
            for (i in 0 until apps.size()) {
                val app = apps.valueAt(i)
                if (app.getPackageName() == packageName) {
                    return app.getUid()
                }
            }
        } else {
            try {
                return Utils.getPackageUid(pm, packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Could not retrieve package $packageName")
            }
        }

        return Utils.UID_NO_FILTER
    }

    companion object {
        private const val TAG = "AppsResolver"
        private val mappedUids = SparseArray<AppDescriptor>()
        private val mappedPackages = ArrayMap<String, AppDescriptor>()

        @JvmStatic
        fun clearMappedApps() {
            mappedUids.clear()
            mappedPackages.clear()
        }

        @Synchronized
        private fun getMappedApp(uid: Int): AppDescriptor? = mappedUids[uid]

        @Synchronized
        private fun getMappedApp(packageName: String): AppDescriptor? = mappedPackages[packageName]

        @JvmStatic
        fun resolveInstalledApp(
            pm: PackageManager,
            packageName: String,
            pmFlags: Int,
            warnNotFound: Boolean,
        ): AppDescriptor? {
            getMappedApp(packageName)?.let { return it }

            val packageInfo: PackageInfo = try {
                Utils.getPackageInfo(pm, packageName, pmFlags)
            } catch (_: PackageManager.NameNotFoundException) {
                if (warnNotFound) {
                    Log.w(TAG, "could not retrieve package: $packageName")
                }
                return null
            }

            return AppDescriptor(pm, packageInfo)
        }

        @JvmStatic
        fun resolveInstalledApp(pm: PackageManager, packageName: String, pmFlags: Int): AppDescriptor? {
            return resolveInstalledApp(pm, packageName, pmFlags, true)
        }
    }
}
