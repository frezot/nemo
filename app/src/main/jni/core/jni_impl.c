#if ANDROID

#include <pthread.h>
#include "nemo_core.h"
#include "common/utils.h"
#include "log_writer.h"
// This files contains functions to make the capture core communicate
// with the Android system.
// Exported functions are defined in nemo_core.h

static nemo_core_t *global_pd = NULL;
static pthread_t jni_thread;

jni_classes_t cls;
jni_methods_t mids;
jni_fields_t fields;
jni_enum_t enums;

/* ******************************************************* */

static void log_callback(int lvl, const char *line) {
    nemo_core_t *pd = global_pd;

    // quick path for debug logs
    if(lvl < PD_DEFAULT_LOGGER_LEVEL)
        return;

    nemo_log_write(PD_DEFAULT_LOGGER, lvl, line);

    // ensure that we are invoking jni from the attached thread
    if(!pd || !(pthread_equal(jni_thread, pthread_self())))
        return;

    if(lvl >= ANDROID_LOG_FATAL) {
        // This is a fatal error, report it to the gui
        jobject info_string = (*pd->env)->NewStringUTF(pd->env, line);

        if((jniCheckException(pd->env) != 0) || (info_string == NULL))
            return;

        (*pd->env)->CallVoidMethod(pd->env, pd->capture_service, mids.reportError, info_string);
        jniCheckException(pd->env);

        (*pd->env)->DeleteLocalRef(pd->env, info_string);
    }
}

/* ******************************************************* */

static void sendStatsDump(nemo_core_t *pd) {
    JNIEnv *env = pd->env;
    const capture_stats_t *capstats = &pd->capture_stats;

    jobject stats_obj = (*env)->NewObject(env, cls.stats, mids.statsInit);

    if((stats_obj == NULL) || jniCheckException(env)) {
        log_e("NewObject(CaptureStats) failed");
        return;
    }

    (*env)->CallVoidMethod(env, stats_obj, mids.statsSetData,
                           capstats->sent_bytes, capstats->rcvd_bytes,
                           capstats->dropped_pkts,
                           capstats->dropped_sent_pkts, capstats->dropped_rcvd_pkts,
                           capstats->stall_active,
                           capstats->stall_uplink_active, capstats->stall_downlink_active);

    if(!jniCheckException(env)) {
        (*env)->CallVoidMethod(env, pd->capture_service, mids.sendStatsDump, stats_obj);
        jniCheckException(env);
    }

    (*env)->DeleteLocalRef(env, stats_obj);
}

/* ******************************************************* */

static void notifyServiceStatus(nemo_core_t *pd, const char *status) {
    JNIEnv *env = pd->env;
    jstring status_str;

    status_str = (*env)->NewStringUTF(env, status);

    (*env)->CallVoidMethod(env, pd->capture_service, mids.sendServiceStatus, status_str);
    jniCheckException(env);

    (*env)->DeleteLocalRef(env, status_str);
}

static void init_jni(JNIEnv *env) {
    // NOTE: these are bound to this specific env

    /* Classes */
    cls.vpn_service = jniFindClass(env, "com/nemo/networkconditioner/CaptureService");
    cls.stats = jniFindClass(env, "com/nemo/networkconditioner/model/CaptureStats");

    /* Methods */
    mids.reportError = jniGetMethodID(env, cls.vpn_service, "reportError", "(Ljava/lang/String;)V");
    mids.getApplicationByUid = jniGetMethodID(env, cls.vpn_service, "getApplicationByUid", "(I)Ljava/lang/String;"),
    mids.protect = jniGetMethodID(env, cls.vpn_service, "protect", "(I)Z");
    mids.sendStatsDump = jniGetMethodID(env, cls.vpn_service, "sendStatsDump", "(Lcom/nemo/networkconditioner/model/CaptureStats;)V");
    mids.sendServiceStatus = jniGetMethodID(env, cls.vpn_service, "sendServiceStatus", "(Ljava/lang/String;)V");
    mids.statsInit = jniGetMethodID(env, cls.stats, "<init>", "()V");
    mids.statsSetData = jniGetMethodID(env, cls.stats, "setData", "(JJJJJIII)V");
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_nemo_networkconditioner_CaptureService_runPacketLoop(JNIEnv *env, jclass type, jint tunfd,
                                                              jobject vpn, jint sdk) {

    init_jni(env);

    nemo_core_t pd = {
            .sdk_ver = sdk,
            .env = env,
            .capture_service = vpn,
            .cb = {
                    .send_stats_dump = sendStatsDump,
                    .notify_service_status = notifyServiceStatus,
            },
            .vpn_capture = (bool) getIntPref(env, vpn, "isVpnCapture"),
            .conditioner = {
                    .enabled = (bool) getIntPref(env, vpn, "getConditionerEnabled"),
                    .base_latency_ms = (uint32_t) getIntPref(env, vpn, "getConditionerBaseLatencyMs"),
                    .jitter_ms = (uint32_t) getIntPref(env, vpn, "getConditionerJitterMs"),
                    .packet_loss_percent = (uint32_t) getIntPref(env, vpn, "getConditionerPacketLossPercent"),
                    .stall_enabled = (bool) getIntPref(env, vpn, "getConditionerStallEnabled"),
                    .stall_interval_ms = (uint32_t) getIntPref(env, vpn, "getConditionerStallIntervalMs"),
                    .stall_duration_ms = (uint32_t) getIntPref(env, vpn, "getConditionerStallDurationMs"),
                    .uplink = {
                            .rate_bytes_per_sec = (uint64_t) getIntPref(env, vpn, "getConditionerUplinkKbps") * 125,
                    },
                    .downlink = {
                            .rate_bytes_per_sec = (uint64_t) getIntPref(env, vpn, "getConditionerDownlinkKbps") * 125,
                    },
            },
    };

    if(pd.vpn_capture)
        pd.vpn.tunfd = tunfd;

    getStringPref(&pd, "getWorkingDir", pd.cachedir, sizeof(pd.cachedir));
    strcat(pd.cachedir, "/");
    pd.cachedir_len = strlen(pd.cachedir);

    getStringPref(&pd, "getPersistentDir", pd.filesdir, sizeof(pd.filesdir));
    strcat(pd.filesdir, "/");
    pd.filesdir_len = strlen(pd.filesdir);

    global_pd = &pd;
    jni_thread = pthread_self();
    logcallback = log_callback;
    signal(SIGPIPE, SIG_IGN);

    // Run the capture
    nemo_run(&pd);

    global_pd = NULL;
    logcallback = NULL;

#if 0
    // free JNI local objects to ease references leak detection
    for(int i=0; i<sizeof(cls)/sizeof(jclass); i++) {
        jclass cur = ((jclass*)&cls)[i];
        (*env)->DeleteLocalRef(env, cur);
    }
    for(int i=0; i<sizeof(enums)/sizeof(jobject); i++) {
        jobject cur = ((jobject*)&enums)[i];
        (*env)->DeleteLocalRef(env, cur);
    }

    // at this point the local reference table should only contain 2 entries (VMDebug + Thread)
    jniDumpReferences(env);
#endif

}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_nemo_networkconditioner_CaptureService_stopPacketLoop(JNIEnv *env, jclass type) {
    /* NOTE: the select on the packets loop uses a timeout to wake up periodically */
    log_i("stopPacketLoop called");
    running = false;
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_nemo_networkconditioner_CaptureService_initPlatformInfo(JNIEnv *env, jclass clazz,
                                                                   jstring appver, jstring device,
                                                                   jstring os) {
    const char *appver_s = (*env)->GetStringUTFChars(env, appver, 0);
    const char *device_s = (*env)->GetStringUTFChars(env, device, 0);
    const char *os_s = (*env)->GetStringUTFChars(env, os, 0);
    nemo_appver = strdup(appver_s);
    nemo_device = strdup(device_s);
    nemo_os = strdup(os_s);
    (*env)->ReleaseStringUTFChars(env, appver, appver_s);
    (*env)->ReleaseStringUTFChars(env, device, device_s);
    (*env)->ReleaseStringUTFChars(env, os, os_s);
}

/* ******************************************************* */

JNIEXPORT jint JNICALL
Java_com_nemo_networkconditioner_CaptureService_getFdSetSize(JNIEnv *env, jclass clazz) {
    return FD_SETSIZE;
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_nemo_networkconditioner_CaptureService_setDnsServer(JNIEnv *env, jclass clazz,
                                                               jstring server) {
    struct in_addr addr = {0};
    const char *value = (*env)->GetStringUTFChars(env, server, 0);

    if(inet_aton(value, &addr) != 0)
        new_dns_server = addr.s_addr;

    (*env)->ReleaseStringUTFChars(env, server, value);
}

/* ******************************************************* */

JNIEXPORT jint JNICALL
Java_com_nemo_networkconditioner_CaptureService_initLogger(JNIEnv *env, jclass clazz,
                                                             jstring path, jint level) {
    const char *path_s = (*env)->GetStringUTFChars(env, path, 0);
    int rv = nemo_init_logger(path_s, level);
    (*env)->ReleaseStringUTFChars(env, path, path_s);
    return rv;
}

/* ******************************************************* */

JNIEXPORT jint JNICALL
Java_com_nemo_networkconditioner_CaptureService_writeLog(JNIEnv *env, jclass clazz,
                                                      jint logger, jint lvl, jstring message) {
    const char *message_s = (*env)->GetStringUTFChars(env, message, 0);
    int rv = nemo_log_write(logger, lvl, message_s);
    (*env)->ReleaseStringUTFChars(env, message, message_s);
    return rv;
}

/* ******************************************************* */

static bool arraylist_add_string(JNIEnv *env, jmethodID arrayListAdd, jobject arr, const char *s) {
    jobject s_obj = (*env)->NewStringUTF(env, s);
    if(!s_obj || jniCheckException(env))
        return false;

    bool rv = (*env)->CallBooleanMethod(env, arr, arrayListAdd, s_obj);
    (*env)->DeleteLocalRef(env, s_obj);
    return rv;
}

char* getStringPref(nemo_core_t *pd, const char *key, char *buf, int bufsize) {
    JNIEnv *env = pd->env;

    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()Ljava/lang/String;");
    jstring obj = (*env)->CallObjectMethod(env, pd->capture_service, midMethod);
    char *rv = NULL;

    if(!jniCheckException(env)) {
        // Null string
        if(obj == NULL)
            return NULL;

        const char *value = (*env)->GetStringUTFChars(env, obj, 0);
        log_d("getStringPref(%s) = %s", key, value);

        strncpy(buf, value, bufsize);
        buf[bufsize - 1] = '\0';
        rv = buf;

        (*env)->ReleaseStringUTFChars(env, obj, value);
    }

    (*env)->DeleteLocalRef(env, obj);

    return(rv);
}

/* ******************************************************* */

u_int32_t getIPv4Pref(JNIEnv *env, jobject vpn_inst, const char *key) {
    struct in_addr addr = {0};

    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()Ljava/lang/String;");
    jstring obj = (*env)->CallObjectMethod(env, vpn_inst, midMethod);

    if(!jniCheckException(env)) {
        const char *value = (*env)->GetStringUTFChars(env, obj, 0);
        log_d("getIPv4Pref(%s) = %s", key, value);

        if(*value && (inet_aton(value, &addr) == 0))
            log_e("%s() returned invalid IPv4 address: %s", key, value);

        (*env)->ReleaseStringUTFChars(env, obj, value);
    }

    (*env)->DeleteLocalRef(env, obj);

    return(addr.s_addr);
}

/* ******************************************************* */

zdtun_ip_t getIPPref(JNIEnv *env, jobject vpn_inst, const char *key, int *ip_ver) {
    zdtun_ip_t rv = {};

    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()Ljava/lang/String;");
    jstring obj = (*env)->CallObjectMethod(env, vpn_inst, midMethod);

    if(!jniCheckException(env)) {
        const char *value = (*env)->GetStringUTFChars(env, obj, 0);
        log_d("getIPPref(%s) = %s", key, value);

        if(*value) {
            *ip_ver = zdtun_parse_ip(value, &rv);

            if(*ip_ver < 0)
                log_e("%s() returned invalid IP address: %s", key, value);
        }

        (*env)->ReleaseStringUTFChars(env, obj, value);
    }

    (*env)->DeleteLocalRef(env, obj);
    return(rv);
}

/* ******************************************************* */

struct in6_addr getIPv6Pref(JNIEnv *env, jobject vpn_inst, const char *key) {
    struct in6_addr addr = {0};

    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()Ljava/lang/String;");
    jstring obj = (*env)->CallObjectMethod(env, vpn_inst, midMethod);

    if(!jniCheckException(env)) {
        const char *value = (*env)->GetStringUTFChars(env, obj, 0);
        log_d("getIPv6Pref(%s) = %s", key, value);

        if(inet_pton(AF_INET6, value, &addr) != 1)
            log_e("%s() returned invalid IPv6 address", key);

        (*env)->ReleaseStringUTFChars(env, obj, value);
    }

    (*env)->DeleteLocalRef(env, obj);

    return(addr);
}

/* ******************************************************* */

int getIntPref(JNIEnv *env, jobject vpn_inst, const char *key) {
    jint value;
    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()I");

    value = (*env)->CallIntMethod(env, vpn_inst, midMethod);
    jniCheckException(env);

    log_d("getIntPref(%s) = %d", key, value);

    return(value);
}

/* ******************************************************* */

// Retrieve a int[] pref.
// If rv is >0, out points to the allocated array. It's up to the caller to free it with nemo_free
int getIntArrayPref(JNIEnv *env, jobject vpn_inst, const char *key, int **out) {
    int rv = -1;
    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()[I");
    jintArray jarr = (jintArray) (*env)->CallObjectMethod(env, vpn_inst, midMethod);

    if (!jniCheckException(env)) {
        int size = (*env)->GetArrayLength(env, jarr);
        log_d("getIntArrayPref(%s) = #%d", key, size);

        if (size > 0) {
            jint *array = (*env)->GetIntArrayElements(env, jarr, NULL);
            if (array) {
                size_t arr_size = size * sizeof(int);

                *out = (int*) nemo_malloc(arr_size);
                if (*out) {
                    // success
                    memcpy(*out, array, arr_size);
                    rv = size;
                }

                (*env)->ReleaseIntArrayElements(env, jarr, array, 0);
            }
        } else
            rv = size;
    }

    (*env)->DeleteLocalRef(env, jarr);
    return rv;
}

/* ******************************************************* */

void getApplicationByUid(nemo_core_t *pd, jint uid, char *buf, int bufsize) {
    JNIEnv *env = pd->env;
    const char *value = NULL;

    jstring obj = (*env)->CallObjectMethod(env, pd->capture_service, mids.getApplicationByUid, uid);
    jniCheckException(env);

    if(obj)
        value = (*env)->GetStringUTFChars(env, obj, 0);

    if(value)
        snprintf(buf, bufsize, "%s", value);
    else
        snprintf(buf, bufsize, "???");

    if(value) (*env)->ReleaseStringUTFChars(env, obj, value);
    if(obj) (*env)->DeleteLocalRef(env, obj);
}

/* ******************************************************* */

#endif // ANDROID
