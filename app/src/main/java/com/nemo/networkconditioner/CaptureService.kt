package com.nemo.networkconditioner

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import com.nemo.networkconditioner.activities.MainActivity
import com.nemo.networkconditioner.model.AppDescriptor
import com.nemo.networkconditioner.model.CaptureSettings
import com.nemo.networkconditioner.model.CaptureStats
import com.nemo.networkconditioner.model.ConditioningProfile
import com.nemo.networkconditioner.model.Prefs
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.ArrayList
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

class CaptureService : VpnService(), Runnable {
    enum class ServiceStatus {
        STOPPED,
        STARTED
    }

    val mLock = ReentrantLock()
    val mCaptureStopped: Condition = mLock.newCondition()
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var revoked = false
    private lateinit var prefs: SharedPreferences
    private lateinit var settings: CaptureSettings
    private lateinit var handler: Handler
    private var captureThread: Thread? = null
    private var vpnIpv4: String? = null
    private var vpnDns: String? = null
    private var dnsServer: String? = null
    private var appFilterUids = IntArray(0)
    private lateinit var statusBuilder: NotificationCompat.Builder
    private var monitoredNetwork = 0L
    private var underlyingNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var nativeAppsResolver: AppsResolver
    private var queueFull = false
    private var stopping = false
    private var lowMemory = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.createConfigurationContext(Utils.getLocalizedConfig(base)))
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate")
        AppsResolver.clearMappedApps()
        nativeAppsResolver = AppsResolver(this)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        settings = CaptureSettings(prefs)
        settings.sanitizeForRuntime()

        INSTANCE = this
        super.onCreate()
    }

    private fun abortStart(): Int {
        stopService()
        updateServiceStatus(ServiceStatus.STOPPED)
        return START_NOT_STICKY
    }

    private fun applyCaptureSettings(intent: Intent?): Boolean {
        val newSettings = if (intent == null) {
            null
        } else {
            Utils.getSerializableExtra(intent, "settings", CaptureSettings::class.java)
        }

        if (newSettings == null) {
            Log.e(TAG, "Missing capture settings")
            return false
        }

        settings = newSettings
        settings.sanitizeForRuntime()
        return true
    }

    private fun initializeRuntimeState() {
        vpnDns = VPN_VIRTUAL_DNS_SERVER
        vpnIpv4 = VPN_IP_ADDRESS
        lowMemory = false
        HAS_ERROR = false
    }

    private fun initializeDnsServer() {
        val fallbackDnsV4 = Prefs.getDnsServerV4(prefs)
        dnsServer = fallbackDnsV4

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val cm = getSystemService(Service.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        underlyingNetwork = net

        if (net == null) {
            return
        }

        if (!Prefs.useSystemDns(prefs)) {
            dnsServer = fallbackDnsV4
            return
        }

        dnsServer = Utils.getDnsServer(cm, net)
        if (dnsServer == null) {
            dnsServer = fallbackDnsV4
            return
        }

        monitoredNetwork = net.networkHandle
        registerNetworkCallbacks()
    }

    private fun updateAppFilterUids() {
        val appFilter = settings.app_filter
        if (appFilter.isEmpty()) {
            appFilterUids = IntArray(0)
            return
        }

        val uids = ArrayList<Int>()
        for (packageName in appFilter) {
            try {
                uids.add(Utils.getPackageUid(packageManager, packageName, 0))
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }
        }

        appFilterUids = IntArray(uids.size)
        for ((index, uid) in uids.withIndex()) {
            appFilterUids[index] = uid
        }
    }

    private fun createVpnBuilder(): Builder {
        val builder = Builder().setMtu(VPN_MTU)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        configureIpv4(builder)
        configureIpv6(builder)
        return builder
    }

    private fun configureIpv4(builder: Builder) {
        if (getIPv4Enabled() != 1) {
            return
        }

        builder.addAddress(vpnIpv4!!, 30)
            .addRoute("0.0.0.0", 1)
            .addRoute("128.0.0.0", 1)
            .addDnsServer(vpnDns!!)
    }

    private fun configureIpv6(builder: Builder) {
        if (getIPv6Enabled() != 1) {
            return
        }

        builder.addAddress(VPN_IP6_ADDRESS, 128)
        builder.addRoute("2000::", 3)
        builder.addRoute("fc00::", 7)

        try {
            builder.addDnsServer(InetAddress.getByName(Prefs.getDnsServerV6(prefs)))
        } catch (_: UnknownHostException) {
            Log.w(TAG, "Could not set IPv6 DNS server")
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Could not set IPv6 DNS server")
        }
    }

    private fun applyVpnAppFilter(builder: Builder): Boolean {
        val appFilter = settings.app_filter
        if (appFilter.isEmpty()) {
            return true
        }

        Log.d(TAG, "Setting app filter: $appFilter")
        return try {
            for (packageName in appFilter) {
                builder.addAllowedApplication(packageName)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            val msg = String.format(resources.getString(R.string.app_not_found), appFilter)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun establishVpn(builder: Builder): Boolean {
        return try {
            parcelFileDescriptor = builder.setSession(VpnSessionName).establish()
            true
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            Utils.showToast(this, R.string.vpn_setup_failed)
            false
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            Utils.showToast(this, R.string.vpn_setup_failed)
            false
        } catch (e: SecurityException) {
            e.printStackTrace()
            Utils.showToast(this, R.string.vpn_setup_failed)
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false

        setupNotifications()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFY_ID_VPNSERVICE,
                getStatusNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFY_ID_VPNSERVICE, getStatusNotification())
        }

        if (captureThread != null) {
            Log.e(TAG, "Restarting the capture is not supported")
            return abortStart()
        }

        handler = Handler(Looper.getMainLooper())
        Log.d(TAG, "onStartCommand")

        if (!applyCaptureSettings(intent)) {
            return abortStart()
        }

        logConditioningProfile()
        initializeRuntimeState()
        initializeDnsServer()
        updateAppFilterUids()

        Log.i(TAG, "Using DNS server $dnsServer")

        val builder = createVpnBuilder()
        if (!applyVpnAppFilter(builder)) {
            return abortStart()
        }

        if (!establishVpn(builder)) {
            return abortStart()
        }

        queueFull = false
        captureThread = Thread(this, "PacketCapture")
        captureThread?.start()

        return START_NOT_STICKY
    }

    override fun onRevoke() {
        Log.d(TAG, "onRevoke")
        revoked = true
        stopService()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")

        unregisterNetworkCallbacks()
        captureThread?.interrupt()

        super.onDestroy()
    }

    private fun setupNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            var chan = NotificationChannel(
                NOTIFY_CHAN_VPNSERVICE,
                NOTIFY_CHAN_VPNSERVICE,
                NotificationManager.IMPORTANCE_LOW
            )
            chan.setShowBadge(false)
            nm.createNotificationChannel(chan)

            chan = NotificationChannel(
                NOTIFY_CHAN_OTHER,
                getString(R.string.other_prefs),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(chan)
        }

        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            Utils.getIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
        statusBuilder = NotificationCompat.Builder(this, NOTIFY_CHAN_VPNSERVICE)
            .setSmallIcon(R.drawable.ic_logo)
            .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
            .setContentIntent(pi)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentTitle(resources.getString(R.string.nemo_app_name))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun getStatusNotification(): Notification = statusBuilder.build()

    fun notifyLowMemory(msg: CharSequence) {
        val notification = NotificationCompat.Builder(this, NOTIFY_CHAN_OTHER)
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_logo)
            .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setWhen(System.currentTimeMillis())
            .setContentTitle(getString(R.string.low_memory))
            .setContentText(msg)
            .build()

        handler.post { Utils.sendImportantNotification(this, NOTIFY_ID_LOW_MEMORY, notification) }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun registerNetworkCallbacks() {
        if (networkCallback != null) {
            return
        }

        val fallbackDns = Prefs.getDnsServerV4(prefs)
        val cm = getSystemService(Service.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                Log.d(TAG, "onLost $network")

                if (network.networkHandle == monitoredNetwork) {
                    Log.i(TAG, "Main network $network lost, using fallback DNS $fallbackDns")
                    dnsServer = fallbackDns
                    monitoredNetwork = 0
                    unregisterNetworkCallbacks()
                    setDnsServer(dnsServer!!)
                }
            }
        }

        try {
            Log.d(TAG, "registerNetworkCallback")
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback!!
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
            Log.w(TAG, "registerNetworkCallback failed, DNS server detection disabled")
            dnsServer = fallbackDns
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallbacks() {
        val callback = networkCallback ?: return
        val cm = getSystemService(Service.CONNECTIVITY_SERVICE) as ConnectivityManager

        try {
            Log.d(TAG, "unregisterNetworkCallback")
            cm.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "unregisterNetworkCallback failed: $e")
        }

        networkCallback = null
    }

    private fun stopAndJoinThreads() {
        Log.d(TAG, "Joining threads...")
    }

    override fun run() {
        underlyingNetwork = null

        val pfd = parcelFileDescriptor
        if (pfd != null) {
            val fd = pfd.fd
            val fdSetSize = getFdSetSize()

            if (fd > 0 && fd < fdSetSize) {
                Log.d(TAG, "VPN fd: $fd - FD_SETSIZE: $fdSetSize")
                runPacketLoop(fd, this, Build.VERSION.SDK_INT)
            } else {
                Log.e(TAG, "Invalid VPN fd: $fd")
            }
        }

        if (parcelFileDescriptor != null) {
            try {
                parcelFileDescriptor?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            parcelFileDescriptor = null
        }

        stopAndJoinThreads()
        stopService()

        mLock.lock()
        try {
            captureThread = null
            mCaptureStopped.signalAll()
        } finally {
            mLock.unlock()
        }

        handler.post { updateServiceStatus(ServiceStatus.STOPPED) }
    }

    private fun checkAvailableHeap() {
        val availableHeap = Utils.getAvailableHeap()

        if (availableHeap <= Utils.LOW_HEAP_THRESHOLD) {
            Log.w(TAG, "Detected low HEAP memory: ${Utils.formatBytes(availableHeap)}")
            handleLowMemory()
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        val lvlStr = Utils.trimlvl2str(level)
        val isLowMemory = level != TRIM_MEMORY_UI_HIDDEN && level >= TRIM_MEMORY_RUNNING_LOW
        val critical = isLowMemory && level >= TRIM_MEMORY_COMPLETE

        Log.d(TAG, "onTrimMemory: $lvlStr - low=$isLowMemory, critical=$critical")

        if (critical && !lowMemory) {
            handleLowMemory()
        }
    }

    private fun handleLowMemory() {
        Log.w(TAG, "handleLowMemory called")
        lowMemory = true
        Log.w(TAG, "low memory detected, expect crashes")
        notifyLowMemory(getString(R.string.low_memory_info))
    }

    fun getVpnIPv4(): String? = vpnIpv4

    fun getVpnDns(): String? = vpnDns

    fun getDnsServer(): String? = dnsServer

    fun getIpv6DnsServer(): String = Prefs.getDnsServerV6(prefs).orEmpty()

    fun getIPv4Enabled(): Int = if (settings.ip_mode != Prefs.IpMode.IPV6_ONLY) 1 else 0

    fun getIPv6Enabled(): Int = if (settings.ip_mode != Prefs.IpMode.IPV4_ONLY) 1 else 0

    fun isVpnCapture(): Int = 1

    fun getAppFilterUids(): IntArray = appFilterUids

    fun getVpnMTU(): Int = VPN_MTU

    fun getConditionerEnabled(): Int =
        if (settings.conditioning_profile != null && settings.conditioning_profile!!.enabled) 1 else 0

    fun getConditionerUplinkKbps(): Int = settings.conditioning_profile?.uplinkKbps ?: 0

    fun getConditionerDownlinkKbps(): Int = settings.conditioning_profile?.downlinkKbps ?: 0

    fun getConditionerBaseLatencyMs(): Int = settings.conditioning_profile?.baseLatencyMs ?: 0

    fun getConditionerJitterMs(): Int = settings.conditioning_profile?.jitterMs ?: 0

    fun getConditionerPacketLossPercent(): Int = settings.conditioning_profile?.packetLossPercent ?: 0

    fun getConditionerStallEnabled(): Int =
        if (settings.conditioning_profile != null && settings.conditioning_profile!!.stallEnabled) 1 else 0

    fun getConditionerStallIntervalMs(): Int = settings.conditioning_profile?.stallIntervalMs ?: 0

    fun getConditionerStallDurationMs(): Int = settings.conditioning_profile?.stallDurationMs ?: 0

    private fun logConditioningProfile() {
        val profile = if (settings.conditioning_profile != null) {
            settings.conditioning_profile!!.copy().sanitize()
        } else {
            ConditioningProfile()
        }
        Log.i(TAG, "Conditioner session config: ${profile.toDebugString()}")
    }

    override fun protect(socket: Int): Boolean = super.protect(socket)

    @TargetApi(Build.VERSION_CODES.Q)
    fun getUidQ(protocol: Int, saddr: String, sport: Int, daddr: String, dport: Int): Int {
        if (protocol != 6 && protocol != 17) {
            return Utils.UID_UNKNOWN
        }

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
            ?: return Utils.UID_UNKNOWN

        val local = InetSocketAddress(saddr, sport)
        val remote = InetSocketAddress(daddr, dport)

        Log.d(TAG, "Get uid local=$local remote=$remote")
        return cm.getConnectionOwnerUid(protocol, local, remote)
    }

    fun sendStatsDump(stats: CaptureStats) {
        lastStats.postValue(stats)
    }

    private fun sendServiceStatus(curStatus: String) {
        updateServiceStatus(if (curStatus == "started") ServiceStatus.STARTED else ServiceStatus.STOPPED)
    }

    private fun updateServiceStatus(curStatus: ServiceStatus) {
        serviceStatus.postValue(curStatus)

        if (curStatus == ServiceStatus.STOPPED && revoked) {
            Log.i(TAG, "VPN disconnected")
        }
    }

    fun getApplicationByUid(uid: Int): String {
        val dsc: AppDescriptor = nativeAppsResolver.getAppByUid(uid, 0) ?: return ""
        return dsc.getName()
    }

    fun reportError(msg: String) {
        HAS_ERROR = true

        handler.post {
            var err = msg
            when (msg) {
            }
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
        }
    }

    fun getWorkingDir(): String = cacheDir.absolutePath

    fun getPersistentDir(): String = filesDir.absolutePath

    private fun resolveHost(host: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || underlyingNetwork == null) {
            return null
        }

        return try {
            underlyingNetwork!!.getByName(host).hostAddress
        } catch (_: UnknownHostException) {
            null
        }
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val VpnSessionName = "NEMO"
        private const val NOTIFY_CHAN_VPNSERVICE = "VPNService"
        private const val NOTIFY_CHAN_OTHER = "Other"
        private const val VPN_MTU = 10000

        const val NOTIFY_ID_VPNSERVICE = 1
        const val NOTIFY_ID_LOW_MEMORY = 2
        const val VPN_IP_ADDRESS = "10.215.173.1"
        const val VPN_IP6_ADDRESS = "fd00:2:fd00:1:fd00:1:fd00:1"
        const val VPN_VIRTUAL_DNS_SERVER = "10.215.173.2"

        private var INSTANCE: CaptureService? = null
        private var HAS_ERROR = false
        private val lastStats = MutableLiveData<CaptureStats>()
        private val serviceStatus = MutableLiveData<ServiceStatus>()

        init {
            try {
                System.loadLibrary("capture")
                initPlatformInfo(Utils.getAppVersionString(), Utils.getDeviceModel(), Utils.getOsVersion())
            } catch (_: UnsatisfiedLinkError) {
            }
        }

        @JvmStatic
        fun stopService() {
            val captureService = INSTANCE
            Log.d(TAG, "stopService called (instance? " + (captureService != null) + ")")

            if (captureService == null) {
                return
            }

            captureService.stopping = true
            stopPacketLoop()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                captureService.stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                captureService.stopForeground(true)
            }

            captureService.stopSelf()
        }

        @JvmStatic
        fun isServiceActive(): Boolean = INSTANCE != null && INSTANCE!!.captureThread != null

        @JvmStatic
        fun isLowMemory(): Boolean = INSTANCE != null && INSTANCE!!.lowMemory

        @JvmStatic
        fun getActiveConditioningProfile(): ConditioningProfile? {
            val instance = INSTANCE
            if (instance == null || !instance::settings.isInitialized || instance.settings.conditioning_profile == null) {
                return null
            }

            return instance.settings.conditioning_profile!!.copy().sanitize()
        }

        @JvmStatic
        fun getAppFilter(): Set<String>? = INSTANCE?.settings?.app_filter

        @JvmStatic
        fun requireInstance(): CaptureService {
            val inst = INSTANCE
            checkNotNull(inst)
            return inst
        }

        @JvmStatic
        fun isIPv6Enabled(): Boolean = INSTANCE != null && INSTANCE!!.getIPv6Enabled() == 1

        @JvmStatic
        fun getStats(): CaptureStats = lastStats.value ?: CaptureStats()

        @JvmStatic
        fun observeStats(lifecycleOwner: LifecycleOwner, observer: Observer<CaptureStats>) {
            lastStats.observe(lifecycleOwner, observer)
        }

        @JvmStatic
        fun observeStatus(lifecycleOwner: LifecycleOwner, observer: Observer<ServiceStatus>) {
            serviceStatus.observe(lifecycleOwner, observer)
        }

        @JvmStatic
        fun waitForCaptureStop() {
            val instance = INSTANCE ?: return

            Log.d(TAG, "waitForCaptureStop ${Thread.currentThread().name}")
            instance.mLock.lock()
            try {
                while (instance.captureThread != null) {
                    try {
                        instance.mCaptureStopped.await()
                    } catch (_: InterruptedException) {
                    }
                }
            } finally {
                instance.mLock.unlock()
            }
            Log.d(TAG, "waitForCaptureStop done ${Thread.currentThread().name}")
        }

        @JvmStatic
        fun hasError(): Boolean = HAS_ERROR

        @JvmStatic external fun initLogger(path: String, level: Int): Int
        @JvmStatic external fun writeLog(logger: Int, lvl: Int, message: String): Int
        @JvmStatic private external fun initPlatformInfo(appver: String, device: String, os: String)
        @JvmStatic private external fun runPacketLoop(fd: Int, vpn: CaptureService, sdk: Int)
        @JvmStatic private external fun stopPacketLoop()
        @JvmStatic private external fun getFdSetSize(): Int
        @JvmStatic private external fun setDnsServer(server: String)
    }
}
