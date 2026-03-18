package com.nemo.networkconditioner

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.ArrayMap
import androidx.appcompat.app.AppCompatActivity
import androidx.loader.app.LoaderManager
import androidx.loader.content.AsyncTaskLoader
import androidx.loader.content.Loader
import com.nemo.networkconditioner.interfaces.AppsLoadListener
import com.nemo.networkconditioner.model.AppDescriptor
import java.util.ArrayList
import java.util.Collections

class AppsLoader(private val context: AppCompatActivity) : LoaderManager.LoaderCallbacks<ArrayList<AppDescriptor>> {
    private var listener: AppsLoadListener? = null

    fun setAppsLoadListener(listener: AppsLoadListener): AppsLoader {
        this.listener = listener
        return this
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun asyncLoadAppsInfo(): ArrayList<AppDescriptor> {
        val packageManager: PackageManager = context.packageManager
        val apps = ArrayList<AppDescriptor>()
        val uidToPos = ArrayMap<Int, Int>()

        Log.d(TAG, "Loading APPs...")
        val packages = Utils.getInstalledPackages(packageManager, 0)
        val appPackage = context.applicationContext.packageName

        Log.d(TAG, "num apps (system+user): ${packages.size}")
        val start = Utils.now()

        var termuxPkgInfo: PackageInfo? = null

        for (packageInfo in packages) {
            val appInfo = packageInfo.applicationInfo ?: continue
            val packageName = appInfo.packageName

            if (packageName == TERMUX_PACKAGE) {
                termuxPkgInfo = packageInfo
            }

            if (!uidToPos.containsKey(appInfo.uid) && packageName != appPackage) {
                val uid = appInfo.uid
                val app = AppDescriptor(packageManager, packageInfo)
                uidToPos.put(uid, apps.size)
                apps.add(app)
            }
        }

        if (termuxPkgInfo != null) {
            val uid = termuxPkgInfo.applicationInfo?.uid ?: return apps.also { Collections.sort(it) }
            val pos = uidToPos[uid]
            if (pos != null) {
                apps.removeAt(pos)
                apps.add(AppDescriptor(packageManager, termuxPkgInfo))
            }
        }

        Collections.sort(apps)
        Log.d(TAG, "${packages.size} apps loaded in ${Utils.now() - start} seconds")
        return apps
    }

    override fun onCreateLoader(opid: Int, args: Bundle?): Loader<ArrayList<AppDescriptor>> {
        return object : AsyncTaskLoader<ArrayList<AppDescriptor>>(context) {
            override fun loadInBackground(): ArrayList<AppDescriptor> {
                return if (opid == OPERATION_LOAD_APPS_INFO) {
                    asyncLoadAppsInfo()
                } else {
                    Log.e(TAG, "unknown loader op: $opid")
                    ArrayList()
                }
            }
        }
    }

    override fun onLoadFinished(loader: Loader<ArrayList<AppDescriptor>>, data: ArrayList<AppDescriptor>) {
        listener?.onAppsInfoLoaded(data)
        finishLoader()
    }

    override fun onLoaderReset(loader: Loader<ArrayList<AppDescriptor>>) {
        Log.d(TAG, "onLoaderReset")
    }

    private fun runLoader(opid: Int, data: ArrayList<AppDescriptor>?) {
        val loaderManager = LoaderManager.getInstance(context)
        var loader = loaderManager.getLoader<ArrayList<AppDescriptor>>(opid)

        val bundle = Bundle()
        bundle.putSerializable("apps", data)

        Log.d(TAG, "Existing loader $opid? ${loader != null}")

        loader = loaderManager.initLoader(opid, bundle, this)
        loader.forceLoad()
    }

    private fun finishLoader() {
        LoaderManager.getInstance(context).destroyLoader(OPERATION_LOAD_APPS_INFO)
    }

    fun loadAllApps(): AppsLoader {
        runLoader(OPERATION_LOAD_APPS_INFO, null)
        return this
    }

    companion object {
        private const val TAG = "AppsLoader"
        private const val OPERATION_LOAD_APPS_INFO = 23
        private const val TERMUX_PACKAGE = "com.termux"
    }
}
