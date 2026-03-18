package com.nemo.networkconditioner.model

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.nemo.networkconditioner.interfaces.DrawableLoader
import java.io.Serializable
import java.util.Locale
import java.util.Objects

class AppDescriptor : Comparable<AppDescriptor>, Serializable {
    private val mName: String
    private val mPackageName: String
    private val mUid: Int
    private val mIsSystem: Boolean
    private val mHasLauncherIntent: Boolean
    private var mIcon: Drawable? = null
    private val mIconLoader: DrawableLoader?
    private var mDescription: String = ""

    @JvmField
    var mPm: PackageManager? = null

    @JvmField
    var mPackageInfo: PackageInfo? = null

    constructor(
        name: String,
        icon_loader: DrawableLoader?,
        package_name: String,
        uid: Int,
        is_system: Boolean,
    ) {
        mName = name
        mIconLoader = icon_loader
        mPackageName = package_name
        mUid = uid
        mIsSystem = is_system
        mHasLauncherIntent = false
    }

    private constructor(
        name: String,
        icon_loader: DrawableLoader?,
        package_name: String,
        uid: Int,
        is_system: Boolean,
        has_launcher: Boolean,
    ) {
        mName = name
        mIconLoader = icon_loader
        mPackageName = package_name
        mUid = uid
        mIsSystem = is_system
        mHasLauncherIntent = has_launcher
    }

    constructor(pm: PackageManager, pkgInfo: PackageInfo) : this(
        pm,
        pkgInfo,
        Objects.requireNonNull(pkgInfo.applicationInfo) as ApplicationInfo,
    )

    private constructor(pm: PackageManager, pkgInfo: PackageInfo, appInfo: ApplicationInfo) : this(
        appInfo.loadLabel(pm).toString(),
        makeIconLoader(pm, appInfo),
        appInfo.packageName,
        appInfo.uid,
        appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        pm.getLaunchIntentForPackage(appInfo.packageName) != null,
    ) {
        mPm = pm
        mPackageInfo = pkgInfo
    }

    fun setDescription(description: String): AppDescriptor {
        mDescription = description
        return this
    }

    fun getDescription(): String = mDescription

    fun getName(): String = mName

    fun getCachedIcon(): Drawable? = mIcon

    // NOTE: must be called from the main thread. For background loading use loadIcon() + setLoadedIcon()
    fun getIcon(): Drawable? {
        if (mIcon == null && mIconLoader != null) {
            mIcon = mIconLoader.getDrawable()
        }
        return mIcon
    }

    fun loadIcon(): Drawable? = mIconLoader?.getDrawable()

    fun setLoadedIcon(icon: Drawable?) {
        mIcon = icon
    }

    fun getPackageName(): String = mPackageName

    fun getUid(): Int = mUid

    fun isSystem(): Boolean = mIsSystem

    fun isBackgroundSystemApp(): Boolean = mIsSystem && !mHasLauncherIntent

    fun isVirtual(): Boolean = mPackageInfo == null

    fun getPackageInfo(): PackageInfo? = mPackageInfo

    override fun compareTo(other: AppDescriptor): Int {
        var result = getName().lowercase(Locale.US).compareTo(other.getName().lowercase(Locale.US))
        if (result == 0) {
            result = getPackageName().compareTo(other.getPackageName())
        }
        return result
    }

    fun matches(filter: String, exactPackage: Boolean): Boolean {
        val packageName = getPackageName().lowercase(Locale.US)
        val appName = getName().lowercase(Locale.US)
        return appName.contains(filter) ||
            (exactPackage && packageName == filter) ||
            (!exactPackage && packageName.contains(filter))
    }

    companion object {
        private fun makeIconLoader(pm: PackageManager, appInfo: ApplicationInfo): DrawableLoader {
            return DrawableLoader { appInfo.loadIcon(pm) }
        }
    }
}
