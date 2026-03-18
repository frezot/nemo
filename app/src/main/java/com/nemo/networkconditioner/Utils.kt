package com.nemo.networkconditioner

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.text.SpannedString
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.MenuItem
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.app.NotificationManagerCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Serializable
import java.net.Inet4Address
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Utils {
    const val UID_UNKNOWN = -1
    const val UID_NO_FILTER = -2
    const val LOW_HEAP_THRESHOLD = 10485760L

    @JvmStatic
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val (divisor, suffix) = when {
            bytes < 1024L * 1024L -> 1024L to "KB"
            bytes < 1024L * 1024L * 1024L -> 1024L * 1024L to "MB"
            else -> 1024L * 1024L * 1024L to "GB"
        }
        return String.format(Locale.US, "%.1f %s", bytes.toFloat() / divisor, suffix)
    }

    @JvmStatic
    fun getLocalizedConfig(context: Context): Configuration {
        val config = Configuration(context.resources.configuration)
        val locale = Locale.US
        Locale.setDefault(locale)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return config
    }

    @JvmStatic
    fun getDnsServer(cm: ConnectivityManager, net: Network): String? {
        val props: LinkProperties? = cm.getLinkProperties(net)
        props?.dnsServers?.forEach { addr ->
            if (addr is Inet4Address) {
                return addr.hostAddress
            }
        }
        return null
    }

    @JvmStatic
    fun now(): Long = Calendar.getInstance().timeInMillis / 1000

    private val HEX_ARRAY = "0123456789ABCDEF".toCharArray()

    @JvmStatic
    @Suppress("deprecation")
    fun getRunningVpn(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        if (cm != null) {
            try {
                val networks = cm.allNetworks
                for (net in networks) {
                    val cap = cm.getNetworkCapabilities(net)
                    if (cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        Log.d("hasVPNRunning", "detected VPN connection: $net")
                        return net
                    }
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
        return null
    }

    @JvmStatic
    fun showToast(context: Context, id: Int, vararg args: Any) {
        val msg = context.resources.getString(id, *args)
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    @JvmStatic
    fun showToastLong(context: Context, id: Int, vararg args: Any) {
        val msg = context.resources.getString(id, *args)
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    @JvmStatic
    fun showHelpDialog(context: Context, id: Int) {
        showHelpDialog(context, context.resources.getString(id))
    }

    @JvmStatic
    fun showHelpDialog(context: Context, msg: CharSequence) {
        val alert = AlertDialog.Builder(context)
            .setTitle(R.string.hint)
            .setMessage(msg)
            .setCancelable(true)
            .setNeutralButton(R.string.ok) { dialog, _ -> dialog.cancel() }
            .create()
        alert.show()
        alert.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }

    @JvmStatic
    fun getIntentFlags(flags: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags or PendingIntent.FLAG_IMMUTABLE
        } else {
            flags
        }
    }

    @JvmStatic
    fun startActivity(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showToastLong(ctx, R.string.no_intent_handler_found)
        } catch (_: SecurityException) {
            showToastLong(ctx, R.string.no_intent_handler_found)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copy(src: File, out: OutputStream) {
        FileInputStream(src).use { input ->
            val bytesIn = ByteArray(4096)
            while (true) {
                val read = input.read(bytesIn)
                if (read == -1) break
                out.write(bytesIn, 0, read)
            }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copy(input: InputStream, dst: File) {
        FileOutputStream(dst).use { out ->
            val bytesIn = ByteArray(4096)
            while (true) {
                val read = input.read(bytesIn)
                if (read == -1) break
                out.write(bytesIn, 0, read)
            }
        }
    }

    @JvmStatic
    fun getText(context: Context, resid: Int, vararg args: String): CharSequence {
        val encoded = args.map { TextUtils.htmlEncode(it) }.toTypedArray()
        val htmlOnly = String.format(
            HtmlCompat.toHtml(
                SpannedString(context.getText(resid)),
                HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE,
            ),
            *encoded,
        )
        return HtmlCompat.fromHtml(htmlOnly, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    @JvmStatic
    fun getAvailableHeap(): Long {
        val runtime = Runtime.getRuntime()
        val unallocated = runtime.maxMemory() - runtime.totalMemory()
        return unallocated + runtime.freeMemory()
    }

    @JvmStatic
    @Suppress("deprecation")
    fun trimlvl2str(lvl: Int): String {
        return when (lvl) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "TRIM_MEMORY_UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "TRIM_MEMORY_RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "TRIM_MEMORY_RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "TRIM_MEMORY_RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "TRIM_MEMORY_BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "TRIM_MEMORY_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "TRIM_MEMORY_COMPLETE"
            else -> "TRIM_UNKNOWN"
        }
    }

    @JvmStatic
    fun sendImportantNotification(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            val title = notification.extras.getString(Notification.EXTRA_TITLE)
            val description = notification.extras.getString(Notification.EXTRA_TEXT)
            val text = "$title - $description"
            Log.w("Utils", "Important notification not sent because notifications are disabled: $text")
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        } else {
            manager.notify(id, notification)
        }
    }

    @JvmStatic
    fun setSearchQuery(searchView: SearchView?, searchItem: MenuItem, query: String?) {
        if (searchView == null || query == null) return
        searchView.isIconified = false
        searchItem.expandActionView()
        searchView.isIconified = false
        searchItem.expandActionView()
        searchView.post { searchView.setQuery(query, true) }
    }

    @JvmStatic
    fun backHandleSearchview(searchView: SearchView?): Boolean {
        if (searchView != null && !searchView.isIconified) {
            searchView.isIconified = true
            return true
        }
        return false
    }

    @JvmStatic
    fun getDeviceModel(): String {
        return if (Build.MODEL.startsWith(Build.MANUFACTURER)) {
            Build.MANUFACTURER
        } else {
            Build.MANUFACTURER + " " + Build.MODEL
        }
    }

    @JvmStatic
    fun getOsVersion(): String = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    @JvmStatic
    fun getBuildInfo(ctx: Context): String {
        val dateFormat: DateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return "Build version: ${BuildConfig.VERSION_NAME}\n" +
            "Current date: ${dateFormat.format(Date())}\n" +
            "Device: ${getDeviceModel()}\n" +
            "OS version: ${getOsVersion()}\n"
    }

    @JvmStatic
    fun getAppVersionString(): String = "NEMO v${BuildConfig.VERSION_NAME}"

    @JvmStatic
    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    fun <T : Serializable> getSerializableExtra(intent: Intent, key: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(key, clazz)
        } else {
            try {
                intent.getSerializableExtra(key) as T?
            } catch (_: ClassCastException) {
                null
            }
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    fun <T : Serializable> getSerializable(bundle: Bundle, key: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getSerializable(key, clazz)
        } else {
            try {
                bundle.getSerializable(key) as T?
            } catch (_: ClassCastException) {
                null
            }
        }
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    @Throws(PackageManager.NameNotFoundException::class)
    fun getPackageInfo(pm: PackageManager, package_name: String, flags: Int): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(package_name, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(package_name, flags)
        }
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    @Throws(PackageManager.NameNotFoundException::class)
    fun getPackageUid(pm: PackageManager, package_name: String, flags: Int): Int {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                pm.getPackageUid(package_name, PackageManager.PackageInfoFlags.of(flags.toLong()))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ->
                pm.getPackageUid(package_name, 0)
            else ->
                pm.getApplicationInfo(package_name, 0).uid
        }
    }

    @JvmStatic
    @SuppressLint("QueryPermissionsNeeded")
    @Suppress("DEPRECATION")
    fun getInstalledPackages(pm: PackageManager, flags: Int): List<PackageInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getInstalledPackages(flags)
        }
    }

    @JvmStatic
    fun enableEdgeToEdge(activity: ComponentActivity) {
        activity.enableEdgeToEdge()
        val window: Window = activity.window
        WindowCompat.getInsetsController(window, window.decorView)
            .setAppearanceLightStatusBars(false)
    }
}
