#include <inttypes.h>
#include <assert.h> // NOTE: look for "assertion" in logcat
#include <pthread.h>
#include "nemo_core.h"
#include "common/utils.h"

extern int run_vpn(nemo_core_t *pd);
/* ******************************************************* */

bool running = false;
uint32_t new_dns_server = 0;

bool dump_capture_stats_now = false;

char *nemo_appver = (char*) "";
char *nemo_device = (char*) "";
char *nemo_os = (char*) "";

/* ******************************************************* */

static uint16_t guess_l7proto(const zdtun_5tuple_t *tuple) {
    uint16_t dst_port = ntohs(tuple->dst_port);
    uint16_t src_port = ntohs(tuple->src_port);

    if((tuple->ipproto == IPPROTO_UDP) && ((dst_port == 53) || (src_port == 53)))
        return NDPI_PROTOCOL_DNS;

    if((tuple->ipproto == IPPROTO_UDP) && ((dst_port == 443) || (src_port == 443)))
        return NDPI_PROTOCOL_QUIC;

    if((tuple->ipproto == IPPROTO_TCP) && ((dst_port == 443) || (src_port == 443)))
        return NDPI_PROTOCOL_TLS;

    if((tuple->ipproto == IPPROTO_TCP) && ((dst_port == 80) || (src_port == 80)))
        return NDPI_PROTOCOL_HTTP;

    return NDPI_PROTOCOL_UNKNOWN;
}

/* ******************************************************* */

void nemo_purge_connection(nemo_core_t *pd, nemo_conn_t *data) {
    if(!data)
        return;

    if(data->info)
        nemo_free(data->info);

    nemo_free(data);
}

/* ******************************************************* */

void nemo_retain_connection(nemo_conn_t *data) {
    if(data)
        data->conditioner_refs++;
}

/* ******************************************************* */

void nemo_release_connection(nemo_core_t *pd, nemo_conn_t *data) {
    if(!data || (data->conditioner_refs == 0))
        return;

    data->conditioner_refs--;
    if((data->conditioner_refs == 0) && data->to_purge)
        nemo_purge_connection(pd, data);
}

/* ******************************************************* */

int nemo_notify_connection_update(nemo_core_t *pd, const zdtun_5tuple_t *tuple, nemo_conn_t *data) {
    if(data && (data->status >= CONN_STATUS_CLOSED))
        nemo_giveup_dpi(pd, data, tuple);

    return 0;
}

/* ******************************************************* */

char* get_appname_by_uid(nemo_core_t *pd, int uid, char *buf, int bufsize) {
#ifdef ANDROID
    uid_to_app_t *app_entry;

    HASH_FIND_INT(pd->uid2app, &uid, app_entry);
    if(app_entry == NULL) {
        app_entry = (uid_to_app_t*) nemo_malloc(sizeof(uid_to_app_t));

        if(app_entry) {
            // Resolve the app name
            getApplicationByUid(pd, uid, app_entry->appname, sizeof(app_entry->appname));

            log_d("uid %d resolved to \"%s\"", uid, app_entry->appname);

            app_entry->uid = uid;
            HASH_ADD_INT(pd->uid2app, uid, app_entry);
        }
    }
#else
    uid_to_app_t *app_entry = NULL;
#endif

    if(app_entry) {
        strncpy(buf, app_entry->appname, bufsize-1);
        buf[bufsize-1] = '\0';
    } else
        buf[0] = '\0';

    return buf;
}

/* ******************************************************* */

const char* nemo_get_proto_name(uint16_t proto, int ipproto) {
    if(proto == NDPI_PROTOCOL_UNKNOWN) {
        // Return the L3 protocol
        return zdtun_proto2str(ipproto);
    }

    switch (proto) {
        case NDPI_PROTOCOL_DNS: return "DNS";
        case NDPI_PROTOCOL_HTTP: return "HTTP";
        case NDPI_PROTOCOL_TLS: return "TLS";
        case NDPI_PROTOCOL_QUIC: return "QUIC";
        case NDPI_PROTOCOL_MAIL_IMAP: return "IMAP";
        case NDPI_PROTOCOL_MAIL_SMTP: return "SMTP";
        default: return zdtun_proto2str(ipproto);
    }
}

/* ******************************************************* */

nemo_conn_t* nemo_new_connection(nemo_core_t *pd, const zdtun_5tuple_t *tuple, int uid) {
    nemo_conn_t *data = nemo_calloc(1, sizeof(nemo_conn_t));
    if(!data) {
        log_e("calloc(nemo_conn_t) failed with code %d/%s",
                    errno, strerror(errno));
        return(NULL);
    }

    data->uid = uid;
    data->incr_id = pd->new_conn_id++;
    data->l7proto = guess_l7proto(tuple);

    // Query country info
    const zdtun_ip_t dst_ip = tuple->dst_ip;
    char remote_ip[INET6_ADDRSTRLEN];
    int family = (tuple->ipver == 4) ? AF_INET : AF_INET6;

    remote_ip[0] = '\0';
    inet_ntop(family, &dst_ip, remote_ip, sizeof(remote_ip));

    // Try to resolve host name via the LRU cache
    data->info = ip_lru_find(pd->ip_to_host, &dst_ip);

    if(data->info) {
        log_d("Host LRU cache HIT: %s -> %s", remote_ip, data->info);
        data->info_from_lru = true;

    }

    return(data);
}

/* ******************************************************* */

void nemo_giveup_dpi(nemo_core_t *pd, nemo_conn_t *data, const zdtun_5tuple_t *tuple) {
    (void) pd;
    (void) data;
    (void) tuple;
}

/* ******************************************************* */

static void process_payload(nemo_core_t *pd, pkt_context_t *pctx) {
    (void) pd;
    (void) pctx;
}

/* ******************************************************* */

static void process_dns_reply(nemo_conn_t *data, nemo_core_t *pd, const struct zdtun_pkt *pkt) {
    const char *query = data->info;

    if((!query[0]) || !strchr(query, '.') || (pkt->l7_len < sizeof(dns_packet_t)))
        return;

    dns_packet_t *dns = (dns_packet_t*)pkt->l7;

    if(((ntohs(dns->flags) & 0x8000) == 0x8000) && (dns->questions != 0) && (dns->answ_rrs != 0)) {
        u_char *reply = dns->queries;
        int len = pkt->l7_len - sizeof(dns_packet_t);
        int num_queries = ntohs(dns->questions);
        int num_replies = min(ntohs(dns->answ_rrs), 32);

        // Skip queries
        for(int i=0; (i<num_queries) && (len > 0); i++) {
            while((len > 0) && (*reply != '\0')) {
                reply++;
                len--;
            }

            reply += 5; len -= 5;
        }

        for(int i=0; (i<num_replies) && (len > 0); i++) {
            int ipver = 0;
            zdtun_ip_t rsp_addr = {0};

            // Skip name
            while(len > 0) {
                if(*reply == 0x00) {
                    reply++; len--;
                    break;
                } else if(*reply == 0xc0) {
                    reply+=2; len-=2;
                    break;
                }

                reply++; len--;
            }

            if(len < 10)
                return;

            uint16_t rec_type = ntohs((*(uint16_t*)reply));
            uint16_t addr_len = ntohs((*(uint16_t*)(reply + 8)));
            reply += 10; len -= 10;

            if (len < addr_len)
                return;

            if((rec_type == 0x1) && (addr_len == 4)) { // A record
                ipver = 4;
                rsp_addr.ip4 = *((u_int32_t*)reply);
            } else if((rec_type == 0x1c) && (addr_len == 16)) { // AAAA record
                ipver = 6;
                memcpy(&rsp_addr.ip6, reply, 16);
            }

            if(ipver != 0) {
                char rspip[INET6_ADDRSTRLEN];
                int family = (ipver == 4) ? AF_INET : AF_INET6;

                rspip[0] = '\0';
                inet_ntop(family, &rsp_addr, rspip, sizeof(rspip));

                log_d("Host LRU cache ADD [v%d]: %s -> %s", ipver, rspip, query);
                ip_lru_add(pd->ip_to_host, &rsp_addr, query);
            }

            reply += addr_len; len -= addr_len;
        }
    }
}

/* ******************************************************* */

static void perform_dpi(nemo_core_t *pd, pkt_context_t *pctx) {
    nemo_conn_t *data = pctx->data;
    zdtun_pkt_t *pkt = pctx->pkt;
    uint16_t old_proto = data->l7proto;

    data->l7proto = guess_l7proto(&pkt->tuple);

    if(old_proto != data->l7proto) {
        nemo_notify_connection_update(pd, pctx->tuple, data);
    }

    if(!pctx->is_tx && (data->l7proto == NDPI_PROTOCOL_DNS) && data->info)
        process_dns_reply(data, pd, pkt);

}

/* ******************************************************* */

/* Perfom periodic tasks. This should be called after processing a packet or after some time has
 * passed (e.g. after a select with no packet). */
void nemo_housekeeping(nemo_core_t *pd) {
    if(dump_capture_stats_now ||
            (pd->capture_stats.new_stats && ((pd->now_ms - pd->capture_stats.last_update_ms) >= CAPTURE_STATS_UPDATE_FREQUENCY_MS))) {
        dump_capture_stats_now = false;
        //log_d("Send stats");

        if(pd->vpn_capture)
            zdtun_get_stats(pd->zdt, &pd->stats);

        if(pd->cb.send_stats_dump)
            pd->cb.send_stats_dump(pd);

        pd->capture_stats.new_stats = false;
        pd->capture_stats.last_update_ms = pd->now_ms;
    }
}

/* ******************************************************* */

/* Refresh the monotonic time. This must be called before any call to nemo_housekeeping. */
void nemo_refresh_time(nemo_core_t *pd) {
    struct timespec ts;

    if(clock_gettime(CLOCK_MONOTONIC_COARSE, &ts)) {
        log_d("clock_gettime failed[%d]: %s", errno, strerror(errno));
        return;
    }

    pd->now_ms = (uint64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ******************************************************* */

void nemo_init_pkt_context(pkt_context_t *pctx,
                         zdtun_pkt_t *pkt, bool is_tx, const zdtun_5tuple_t *tuple,
                         nemo_conn_t *data, struct timeval *tv
) {
    pctx->pkt = pkt;
    pctx->tv = *tv;
    pctx->ms = (uint64_t)tv->tv_sec * 1000 + tv->tv_usec / 1000;
    pctx->is_tx = is_tx;
    pctx->tuple = tuple;
    pctx->data = data;
}

/* ******************************************************* */

/* Process the packet (e.g. perform DPI) and fill the packet context. */
void nemo_process_packet(nemo_core_t *pd, pkt_context_t *pctx) {
    nemo_conn_t *data = pctx->data;
    zdtun_pkt_t *pkt = pctx->pkt;

    // NOTE: nemo_account_stats will not be called for blocked connections
    data->last_seen = pctx->ms;
    if(!data->first_seen)
        data->first_seen = pctx->ms;

    if((!(pkt->flags & ZDTUN_PKT_IS_FRAGMENT) || (pkt->flags & ZDTUN_PKT_IS_FIRST_FRAGMENT))) {
        perform_dpi(pd, pctx);
    }

    process_payload(pd, pctx);
}

/* ******************************************************* */

/* Update the stats for the current packet. */
void nemo_account_stats(nemo_core_t *pd, pkt_context_t *pctx) {
    zdtun_pkt_t *pkt = pctx->pkt;
    nemo_conn_t *data = pctx->data;

    data->payload_length += pkt->l7_len;

    if(pctx->is_tx) {
        data->sent_pkts++;
        data->sent_bytes += pkt->len;
        pd->capture_stats.sent_pkts++;
        pd->capture_stats.sent_bytes += pkt->len;
        if(pkt->tuple.ipver == 6) {
            pd->capture_stats.ipv6_sent_bytes += pkt->len;
        }
    } else {
        data->rcvd_pkts++;
        data->rcvd_bytes += pkt->len;
        pd->capture_stats.rcvd_pkts++;
        pd->capture_stats.rcvd_bytes += pkt->len;
        if(pkt->tuple.ipver == 6) {
            pd->capture_stats.ipv6_rcvd_bytes += pkt->len;
        }
    }

    /* New stats to notify */
    pd->capture_stats.new_stats = true;
    nemo_notify_connection_update(pd, pctx->tuple, pctx->data);
}

/* ******************************************************* */

int nemo_run(nemo_core_t *pd) {
    /* Important: init global state every time. Android may reuse the service. */
    running = true;
    pd->ip_to_host = ip_lru_init(MAX_HOST_LRU_SIZE);

    memset(&pd->stats, 0, sizeof(pd->stats));

    nemo_refresh_time(pd);
    // Run the capture
    int rv = run_vpn(pd);

    log_i("Stopped packet loop");

    // send last dump
    if(pd->cb.send_stats_dump)
        pd->cb.send_stats_dump(pd);

    uid_to_app_t *e, *tmp;
    HASH_ITER(hh, pd->uid2app, e, tmp) {
        HASH_DEL(pd->uid2app, e);
        nemo_free(e);
    }

    log_i("Host LRU cache size: %d", ip_lru_size(pd->ip_to_host));
    log_i("Discarded fragments: %ld", pd->num_discarded_fragments);
    ip_lru_destroy(pd->ip_to_host);

    return(rv);
}
