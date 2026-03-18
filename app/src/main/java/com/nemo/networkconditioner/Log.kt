package com.nemo.networkconditioner

import androidx.annotation.NonNull
import androidx.annotation.Nullable

object Log {
    const val LOG_LEVEL_INFO = 4
    const val APP_LOGGER_PATH = "nemo.log"

    @JvmField
    var DEFAULT_LOGGER: Int = 0

    @JvmStatic
    fun init(cachedir: String) {
        DEFAULT_LOGGER = CaptureService.initLogger("$cachedir/$APP_LOGGER_PATH", LOG_LEVEL_INFO)
    }

    @JvmStatic
    fun writeLog(logger: Int, level: Int, @Nullable tag: String?, @NonNull message: String) {
        if (!NemoApplication.isUnderTest()) {
            CaptureService.writeLog(logger, level, (if (tag != null) "[$tag] " else "") + message)
        }
    }

    @JvmStatic
    fun d(@Nullable tag: String?, @NonNull message: String) {
        android.util.Log.d(tag, message)
    }

    @JvmStatic
    fun i(@Nullable tag: String?, @NonNull message: String) {
        android.util.Log.i(tag, message)
        writeLog(DEFAULT_LOGGER, android.util.Log.INFO, tag, message)
    }

    @JvmStatic
    fun i(logger: Int, @NonNull message: String) {
        writeLog(logger, android.util.Log.INFO, null, message)
    }

    @JvmStatic
    fun w(@Nullable tag: String?, @NonNull message: String) {
        android.util.Log.w(tag, message)
        writeLog(DEFAULT_LOGGER, android.util.Log.WARN, tag, message)
    }

    @JvmStatic
    fun w(logger: Int, @NonNull message: String) {
        writeLog(logger, android.util.Log.WARN, null, message)
    }

    @JvmStatic
    fun e(@Nullable tag: String?, @NonNull message: String) {
        android.util.Log.e(tag, message)
        writeLog(DEFAULT_LOGGER, android.util.Log.ERROR, tag, message)
    }

    @JvmStatic
    fun e(logger: Int, @NonNull message: String) {
        writeLog(logger, android.util.Log.ERROR, null, message)
    }

    @JvmStatic
    fun wtf(@Nullable tag: String?, @NonNull message: String) {
        android.util.Log.wtf(tag, message)
        writeLog(DEFAULT_LOGGER, android.util.Log.ASSERT, tag, message)
    }

    @JvmStatic
    fun level(logger: Int, level: Int, @NonNull message: String) {
        when (level) {
            android.util.Log.INFO -> i(logger, message)
            android.util.Log.WARN -> w(logger, message)
            android.util.Log.ERROR -> e(logger, message)
        }
    }
}
