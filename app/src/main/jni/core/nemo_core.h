#ifndef __NEMO_CORE_H__
#define __NEMO_CORE_H__

#include <stdbool.h>
#include "zdtun.h"
#include "ip_lru.h"
#include "proto_ids.h"
#include "common/jni_utils.h"
#include "common/uid_resolver.h"
#include "third_party/uthash.h"

#define CAPTURE_STATS_UPDATE_FREQUENCY_MS 300
#define SELECT_TIMEOUT_MS 250
#define VPN_BUFFER_SIZE 32768
#define MAX_HOST_LRU_SIZE 256
#define PERIODIC_PURGE_TIMEOUT_MS 5000
#define DNS_FLAGS_MASK 0x8000
#define DNS_TYPE_REQUEST 0x0000
#define DNS_TYPE_RESPONSE 0x8000

typedef struct {
    jlong sent_bytes;
    jlong rcvd_bytes;
    jlong ipv6_sent_bytes;
    jlong ipv6_rcvd_bytes;
    jint sent_pkts;
    jint rcvd_pkts;

    bool new_stats;
    u_int64_t last_update_ms;
} capture_stats_t;

typedef enum {
    TC_DIR_UPLINK = 0,
    TC_DIR_DOWNLINK = 1
} tc_direction_t;

typedef enum {
    TC_RESULT_BYPASS = 0,
    TC_RESULT_QUEUED,
    TC_RESULT_DROP,
    TC_RESULT_ABORT
} tc_result_t;

typedef struct {
    jint incr_id; // an incremental number which identifies a specific connection

    uint16_t l7proto;

    union {
        struct {
            uint64_t last_update_ms; // like last_seen but monotonic
            u_int ifidx;             // the 1-based interface index
        } pcap;
        struct {
            struct pkt_context *fw_pctx; // context for the forwarded packet
            uint16_t local_port;         // local port, from zdtun to the Internet
        } vpn;
    };

    jlong first_seen;
    jlong last_seen;
    jlong payload_length;
    jlong sent_bytes;
    jlong rcvd_bytes;
    jint sent_pkts;
    jint rcvd_pkts;
    zdtun_conn_status_t status;
    int error;
    char *info;
    jint uid;
    uint8_t tcp_flags[2]; // cli2srv, srv2cli
    bool to_purge;
    bool info_from_lru;
    uint16_t conditioner_refs;
} nemo_conn_t;

typedef struct traffic_conditioned_packet {
    struct traffic_conditioned_packet *next;
    uint8_t *buf;
    uint32_t len;
    uint64_t release_ms;
    struct timeval tv;
    uint64_t pkt_ms;
    zdtun_5tuple_t tuple;
    nemo_conn_t *data;
} traffic_conditioned_packet_t;

typedef struct {
    traffic_conditioned_packet_t *head;
    traffic_conditioned_packet_t *tail;
    uint64_t queued_bytes;
    uint64_t rate_bytes_per_sec;
    uint64_t max_tokens;
    uint64_t tokens;
    uint64_t last_refill_ms;
    uint64_t stall_until_ms;
    uint64_t next_stall_at_ms;
} traffic_conditioner_direction_t;

typedef struct {
    bool enabled;
    uint32_t base_latency_ms;
    uint32_t jitter_ms;
    uint32_t packet_loss_percent;
    bool stall_enabled;
    uint32_t stall_interval_ms;
    uint32_t stall_duration_ms;
    unsigned int rand_state;
    bool warned_downlink_tcp_loss;
    traffic_conditioner_direction_t uplink;
    traffic_conditioner_direction_t downlink;
} traffic_conditioner_t;

typedef struct {
    int uid;
    char appname[64];
    UT_hash_handle hh;
} uid_to_app_t;

typedef struct pkt_context {
    zdtun_pkt_t *pkt;
    struct timeval tv;
    uint64_t ms;       // Packet timestamp in ms
    bool is_tx;
    const zdtun_5tuple_t *tuple;
    nemo_conn_t *data;
} pkt_context_t;

struct nemo_core;

// Used to decouple nemo_core.c from the JNI calls
typedef struct {
    void (*send_stats_dump)(struct nemo_core *core);
    void (*notify_service_status)(struct nemo_core *core, const char *status);
} nemo_callbacks_t;

/* ******************************************************* */

typedef struct nemo_core {
#ifdef ANDROID
    JNIEnv *env;
    jobject capture_service;
    jint sdk_ver;
#endif
    int new_conn_id;
    uint64_t now_ms;            // Monotonic timestamp, see nemo_refresh_time
    zdtun_t *zdt;
    ip_lru_t *ip_to_host;
    nemo_callbacks_t cb;
    uid_to_app_t *uid2app;
    char cachedir[PATH_MAX];
    char filesdir[PATH_MAX];
    int cachedir_len;
    int filesdir_len;

    // config
    bool vpn_capture;
    traffic_conditioner_t conditioner;

    // stats
    u_int num_dropped_pkts;
    long num_discarded_fragments;
    uint32_t num_dropped_connections;
    zdtun_statistics_t stats;
    capture_stats_t capture_stats;

    struct {
        int tunfd;
        uid_resolver_t *resolver;

        struct {
            bool enabled;
            uint32_t dns_server;
            uint32_t internal_dns;
        } ipv4;
        struct {
            bool enabled;
            struct in6_addr dns_server;
        } ipv6;
    } vpn;

} nemo_core_t;

typedef struct {
    uint16_t transaction_id;
    uint16_t flags;
    uint16_t questions;
    uint16_t answ_rrs;
    uint16_t auth_rrs;
    uint16_t additional_rrs;
    uint8_t queries[];
} __attribute__((packed)) dns_packet_t;

/* ******************************************************* */

#ifdef ANDROID

typedef struct {
    jmethodID reportError;
    jmethodID getApplicationByUid;
    jmethodID protect;
    jmethodID sendServiceStatus;
    jmethodID sendStatsDump;
    jmethodID statsInit;
    jmethodID statsSetData;
} jni_methods_t;

typedef struct {
    jclass vpn_service;
    jclass stats;
} jni_classes_t;

typedef struct {
} jni_fields_t;

typedef struct {
} jni_enum_t;

extern jni_methods_t mids;
extern jni_classes_t cls;
extern jni_fields_t fields;
extern jni_enum_t enums;

#endif // ANDROID

/* ******************************************************* */

extern bool running;
extern uint32_t new_dns_server;
extern bool dump_capture_stats_now;
extern char *nemo_appver;
extern char *nemo_device;
extern char *nemo_os;

// capture API
int nemo_run(nemo_core_t *pd);
void nemo_refresh_time(nemo_core_t *pd);
void nemo_init_pkt_context(pkt_context_t *pctx,
                         zdtun_pkt_t *pkt, bool is_tx, const zdtun_5tuple_t *tuple,
                         nemo_conn_t *data, struct timeval *tv);
void nemo_process_packet(nemo_core_t *pd, pkt_context_t *pctx);
void nemo_account_stats(nemo_core_t *pd, pkt_context_t *pctx);
void nemo_housekeeping(nemo_core_t *pd);
nemo_conn_t* nemo_new_connection(nemo_core_t *pd, const zdtun_5tuple_t *tuple, int uid);
void nemo_purge_connection(nemo_core_t *pd, nemo_conn_t *data);
void nemo_retain_connection(nemo_conn_t *data);
void nemo_release_connection(nemo_core_t *pd, nemo_conn_t *data);
int nemo_notify_connection_update(nemo_core_t *pd, const zdtun_5tuple_t *tuple, nemo_conn_t *data);
void nemo_giveup_dpi(nemo_core_t *pd, nemo_conn_t *data, const zdtun_5tuple_t *tuple);
const char* nemo_get_proto_name(uint16_t proto, int ipproto);
void tc_init(traffic_conditioner_t *tc, uint64_t now_ms);
void tc_finalize(nemo_core_t *pd);
bool tc_is_enabled(const traffic_conditioner_t *tc);
bool tc_direction_is_active(const traffic_conditioner_t *tc, tc_direction_t direction);
bool tc_can_enqueue(const traffic_conditioner_t *tc, tc_direction_t direction);
tc_result_t tc_enqueue_uplink(traffic_conditioner_t *tc, const char *buf, uint32_t len, uint64_t now_ms);
tc_result_t tc_enqueue_downlink(nemo_core_t *pd, const zdtun_pkt_t *pkt, const zdtun_5tuple_t *tuple,
                                nemo_conn_t *data, const struct timeval *tv, uint64_t pkt_ms,
                                uint64_t now_ms);
int tc_flush_uplink(nemo_core_t *pd, zdtun_t *zdt,
                    int (*process_packet)(nemo_core_t *pd, zdtun_t *zdt, char *buffer, int size));
int tc_flush_downlink(nemo_core_t *pd);
uint32_t tc_get_next_timeout_ms(traffic_conditioner_t *tc, uint64_t now_ms, uint32_t fallback_ms);

// Utility
char* get_appname_by_uid(nemo_core_t *pd, int uid, char *buf, int bufsize);

#ifdef ANDROID

char* getStringPref(nemo_core_t *pd, const char *key, char *buf, int bufsize);
int getIntPref(JNIEnv *env, jobject vpn_inst, const char *key);
int getIntArrayPref(JNIEnv *env, jobject vpn_inst, const char *key, int **out);
zdtun_ip_t getIPPref(JNIEnv *env, jobject vpn_inst, const char *key, int *ip_ver);
uint32_t getIPv4Pref(JNIEnv *env, jobject vpn_inst, const char *key);
struct in6_addr getIPv6Pref(JNIEnv *env, jobject vpn_inst, const char *key);
void getApplicationByUid(nemo_core_t *pd, jint uid, char *buf, int bufsize);

#endif // ANDROID

// Internals
uint32_t crc32(u_char *buf, size_t len, uint32_t crc);

#endif //__NEMO_CORE_H__
