#include <netinet/tcp.h>
#include <netinet/udp.h>

#include "nemo_core.h"
#include "common/utils.h"

/* ******************************************************* */

static bool check_dns_req_allowed(nemo_core_t *pd, zdtun_conn_t *conn, pkt_context_t *pctx);

static int resolve_uid(nemo_core_t *pd, const zdtun_5tuple_t *conn_info) {
    char buf[256];
    jint uid;

    zdtun_5tuple2str(conn_info, buf, sizeof(buf));
    uid = get_uid(pd->vpn.resolver, conn_info);

    if(uid >= 0) {
        char appbuf[64];

        get_appname_by_uid(pd, uid, appbuf, sizeof(appbuf));
        log_d( "%s [%d/%s]", buf, uid, appbuf);
    } else {
        uid = UID_UNKNOWN;
        log_w("%s => UID not found!", buf);
    }

    return(uid);
}

static void protectSocketCallback(zdtun_t *zdt, socket_t sock) {
#if ANDROID
    nemo_core_t *pd = ((nemo_core_t*)zdtun_userdata(zdt));
    JNIEnv *env = pd->env;

    if(!pd->vpn_capture)
        return;

    /* Call VpnService protect */
    jboolean isProtected = (*env)->CallBooleanMethod(
            env, pd->capture_service, mids.protect, sock);
    jniCheckException(env);

    if(!isProtected)
        log_e("socket protect failed");
#endif
}

/* ******************************************************* */

static struct timeval* get_pkt_timestamp(nemo_core_t *pd, struct timeval *tv) {
    struct timespec ts;

    if(!clock_gettime(CLOCK_REALTIME, &ts)) {
        tv->tv_sec = ts.tv_sec;
        tv->tv_usec = ts.tv_nsec / 1000;
        return tv;
    }

    log_w("clock_gettime failed[%d]: %s", errno, strerror(errno));
    return tv;
}

/* ******************************************************* */

static int process_tun_packet(nemo_core_t *pd, zdtun_t *zdt, char *buffer, int size) {
    zdtun_pkt_t pkt;
    if(zdtun_parse_pkt(zdt, buffer, size, &pkt) != 0) {
        log_d("zdtun_parse_pkt failed");
        return 0;
    }

    if(pkt.flags & ZDTUN_PKT_IS_FRAGMENT) {
        log_d("discarding IP fragment");
        pd->num_discarded_fragments++;
        return 0;
    }

    if(((pkt.tuple.ipver == 6) && !pd->vpn.ipv6.enabled) ||
            ((pkt.tuple.ipver == 4) && !pd->vpn.ipv4.enabled)) {
        char buf[512];

        log_d("ignoring IPv%d packet: %s", pkt.tuple.ipver,
              zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));
        return 0;
    }

    uint8_t is_tcp_established = ((pkt.tuple.ipproto == IPPROTO_TCP) &&
            (!(pkt.tcp->th_flags & TH_SYN) || (pkt.tcp->th_flags & TH_ACK)));

    zdtun_conn_t *conn = zdtun_lookup(zdt, &pkt.tuple, !is_tcp_established);
    if (!conn) {
        char buf[512];

        if(!is_tcp_established) {
            pd->num_dropped_connections++;
            log_e("zdtun_lookup failed: %s",
                  zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));
        } else {
            log_d("skipping established TCP: %s",
                  zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));
        }
        return 0;
    }

    struct timeval tv;
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn);
    pkt_context_t pctx;
    nemo_conn_t *data = zdtun_conn_get_userdata(conn);

    nemo_init_pkt_context(&pctx, &pkt, true, tuple, data, get_pkt_timestamp(pd, &tv));
    nemo_process_packet(pd, &pctx);
    if((data->sent_pkts == 0) && !check_dns_req_allowed(pd, conn, &pctx)) {
        return 0;
    }

    data->vpn.fw_pctx = &pctx;
    if(zdtun_forward(zdt, &pkt, conn) != 0) {
        char buf[512];
        zdtun_conn_status_t status = zdtun_conn_get_status(conn);

        if(status != CONN_STATUS_UNREACHABLE) {
            log_e("zdtun_forward failed[%d]: %s", status,
                  zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));

            pd->num_dropped_connections++;
        } else
            log_w("%s: net/host unreachable", zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));

        zdtun_conn_close(zdt, conn, CONN_STATUS_ERROR);
        return 0;
    }

    if(data->vpn.fw_pctx) {
        nemo_account_stats(pd, data->vpn.fw_pctx);
        data->vpn.fw_pctx = NULL;
    }

    if(data->sent_pkts == 1) {
        socket_t sock = zdtun_conn_get_socket(conn);

        if((sock != INVALID_SOCKET) && (tuple->ipver == 4)) {
            struct sockaddr_in local_addr;
            socklen_t addrlen = sizeof(local_addr);

            if(getsockname(sock, (struct sockaddr*) &local_addr, &addrlen) == 0)
                data->vpn.local_port = local_addr.sin_port;
        }
    }

    return 0;
}

/* ******************************************************* */

static int remote2vpn(zdtun_t *zdt, zdtun_pkt_t *pkt, const zdtun_conn_t *conn_info) {
    if(!running)
        // e.g. during zdtun_finalize
        return 0;

    nemo_core_t *pd = (nemo_core_t*) zdtun_userdata(zdt);
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn_info);
    nemo_conn_t *data = zdtun_conn_get_userdata(conn_info);

    // if this is called inside zdtun_forward, account the egress packet before the subsequent ingress packet
    if(data->vpn.fw_pctx) {
        nemo_account_stats(pd, data->vpn.fw_pctx);
        data->vpn.fw_pctx = NULL;
    }

    struct timeval tv;
    pkt_context_t pctx;
    nemo_refresh_time(pd);

    nemo_init_pkt_context(&pctx, pkt, false, tuple, data, get_pkt_timestamp(pd, &tv));
    nemo_process_packet(pd, &pctx);

    switch (tc_enqueue_downlink(pd, pkt, tuple, data, &pctx.tv, pctx.ms, pd->now_ms)) {
        case TC_RESULT_QUEUED:
            return 0;
        case TC_RESULT_DROP:
            return 0;
        case TC_RESULT_ABORT:
            return -1;
        case TC_RESULT_BYPASS:
        default:
            break;
    }

    int rv = write(pd->vpn.tunfd, pkt->buf, pkt->len);
    if(rv < 0) {
        if(errno == ENOBUFS) {
            char buf[256];

            // Do not abort, the connection will be terminated
            log_e("Got ENOBUFS %s", zdtun_5tuple2str(tuple, buf, sizeof(buf)));
        } else if(errno == EIO) {
            log_i("Got I/O error (terminating?)");
            running = false;
        } else {
            log_f("zdt write (%d) failed [%d]: %s", pkt->len, errno, strerror(errno));
            running = false;
        }
    } else if(rv != pkt->len) {
        log_f("partial zdt write (%d / %d)", rv, pkt->len);
        rv = -1;
    } else {
        // Success
        rv = 0;
        nemo_account_stats(pd, &pctx);
    }

    return rv;
}

/* ******************************************************* */

/*
 * If a packet targets the app's internal virtual DNS address,
 * rewrite it to the active upstream DNS server.
 */
static bool check_dns_req_allowed(nemo_core_t *pd, zdtun_conn_t *conn, pkt_context_t *pctx) {
    const zdtun_5tuple_t *tuple = pctx->tuple;

    if(new_dns_server != 0) {
        log_i("Using new DNS server");
        pd->vpn.ipv4.dns_server = new_dns_server;
        new_dns_server = 0;
    }

    if(pctx->tuple->ipproto == IPPROTO_ICMP)
        return true;

    bool is_internal_dns = pd->vpn.ipv4.enabled && (tuple->ipver == 4) && (tuple->dst_ip.ip4 == pd->vpn.ipv4.internal_dns);
    bool is_dns_server = is_internal_dns
                         || (pd->vpn.ipv6.enabled && (tuple->ipver == 6) && (memcmp(&tuple->dst_ip.ip6, &pd->vpn.ipv6.dns_server, 16) == 0));

    if(!is_dns_server)
        return(true);

    if((tuple->ipproto == IPPROTO_UDP) && (ntohs(tuple->dst_port) == 53)) {
        zdtun_pkt_t *pkt = pctx->pkt;
        int dns_length = pkt->l7_len;

        if(dns_length >= sizeof(dns_packet_t)) {
            dns_packet_t *dns_data = (dns_packet_t*) pkt->l7;

            if((dns_data->flags & DNS_FLAGS_MASK) != DNS_TYPE_REQUEST)
                return(true);

            if(is_internal_dns) {
                /*
                 * Direct the packet to the public DNS server. Checksum recalculation is not strictly necessary
                 * here as zdtun will pd the connection.
                 */
                zdtun_ip_t ip = {0};
                ip.ip4 = pd->vpn.ipv4.dns_server;
                zdtun_conn_dnat(conn, &ip, htons(53), 4);
            }

            return(true);
        }
    }

    // allow
    return(true);
}

/* ******************************************************* */

static int handle_new_connection(zdtun_t *zdt, zdtun_conn_t *conn_info) {
    nemo_core_t *pd = ((nemo_core_t *) zdtun_userdata(zdt));
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn_info);

    nemo_conn_t *data = nemo_new_connection(pd, tuple, resolve_uid(pd, tuple));
    if(!data) {
        /* reject connection */
        return (1);
    }

    zdtun_conn_set_userdata(conn_info, data);

    /* accept connection */
    return(0);
}

/* ******************************************************* */

static void connection_closed(zdtun_t *zdt, const zdtun_conn_t *conn_info) {
    nemo_core_t *pd = (nemo_core_t*) zdtun_userdata(zdt);
    nemo_conn_t *data = zdtun_conn_get_userdata(conn_info);

    if(!data) {
        log_e("Missing data in connection");
        return;
    }

    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn_info);

    nemo_giveup_dpi(pd, data, tuple);
    data->status = zdtun_conn_get_status(conn_info);
    data->error = zdtun_conn_get_error(conn_info);
    if(data->conditioner_refs == 0)
        nemo_purge_connection(pd, data);
    else
        data->to_purge = true;
}

/* ******************************************************* */

// This is called after remote2vpn or zdtun_forward
// No need to call nemo_notify_connection_update, nemo_account_stats is executed before
static void update_conn_status(zdtun_t *zdt, const zdtun_pkt_t *pkt, uint8_t from_tun, const zdtun_conn_t *conn_info) {
    nemo_conn_t *data = zdtun_conn_get_userdata(conn_info);

    // Update the connection status
    data->status = zdtun_conn_get_status(conn_info);
    if(data->status >= CONN_STATUS_CLOSED) {
        data->to_purge = true;
        data->error = zdtun_conn_get_error(conn_info);
    }
}

/* ******************************************************* */

int run_vpn(nemo_core_t *pd) {
    zdtun_t *zdt;
    char buffer[VPN_BUFFER_SIZE];
    u_int64_t next_purge_ms;

    int flags = fcntl(pd->vpn.tunfd, F_GETFL, 0);
    if (flags < 0 || fcntl(pd->vpn.tunfd, F_SETFL, flags & ~O_NONBLOCK) < 0) {
        log_f("fcntl ~O_NONBLOCK error [%d]: %s", errno,
                    strerror(errno));
        return (-1);
    }

#if ANDROID
    pd->vpn.resolver = init_uid_resolver(pd->sdk_ver, pd->env, pd->capture_service);

    pd->vpn.ipv4.enabled = (bool) getIntPref(pd->env, pd->capture_service, "getIPv4Enabled");
    pd->vpn.ipv4.dns_server = getIPv4Pref(pd->env, pd->capture_service, "getDnsServer");
    pd->vpn.ipv4.internal_dns = getIPv4Pref(pd->env, pd->capture_service, "getVpnDns");

    pd->vpn.ipv6.enabled = (bool) getIntPref(pd->env, pd->capture_service, "getIPv6Enabled");
    pd->vpn.ipv6.dns_server = getIPv6Pref(pd->env, pd->capture_service, "getIpv6DnsServer");
#endif

    zdtun_callbacks_t callbacks = {
        .send_client = remote2vpn,
        .account_packet = update_conn_status,
        .on_socket_open = protectSocketCallback,
        .on_connection_open = handle_new_connection,
        .on_connection_close = connection_closed,
    };

    zdt = zdtun_init(&callbacks, pd);
    if(zdt == NULL) {
        log_f("zdtun_init failed");
        return(-2);
    }

#if ANDROID
    zdtun_set_mtu(zdt, getIntPref(pd->env, pd->capture_service, "getVpnMTU"));
#endif

    pd->zdt = zdt;
    new_dns_server = 0;

    nemo_refresh_time(pd);
    tc_init(&pd->conditioner, pd->now_ms);
    next_purge_ms = pd->now_ms + PERIODIC_PURGE_TIMEOUT_MS;

    log_i("Starting packet loop");
    if(pd->cb.notify_service_status && running)
        pd->cb.notify_service_status(pd, "started");

    while(running) {
        int max_fd;
        fd_set fdset = {0};
        fd_set wrfds = {0};
        int size;
        bool allow_uplink_read = !tc_direction_is_active(&pd->conditioner, TC_DIR_UPLINK) ||
                tc_can_enqueue(&pd->conditioner, TC_DIR_UPLINK);
        bool allow_downlink_read = !tc_direction_is_active(&pd->conditioner, TC_DIR_DOWNLINK) ||
                tc_can_enqueue(&pd->conditioner, TC_DIR_DOWNLINK);

        nemo_refresh_time(pd);
        if(tc_is_enabled(&pd->conditioner)) {
            if(tc_flush_uplink(pd, zdt, process_tun_packet) < 0)
                break;
            if(tc_flush_downlink(pd) < 0)
                break;
        }

        nemo_refresh_time(pd);

        if(allow_downlink_read)
            zdtun_fds(zdt, &max_fd, &fdset, &wrfds);
        else
            max_fd = -1;

        if(allow_uplink_read) {
            FD_SET(pd->vpn.tunfd, &fdset);
            max_fd = max(max_fd, pd->vpn.tunfd);
        }

        uint32_t timeout_ms = tc_get_next_timeout_ms(&pd->conditioner, pd->now_ms, SELECT_TIMEOUT_MS);
        struct timeval timeout = {.tv_sec = timeout_ms / 1000,
                .tv_usec = (timeout_ms % 1000) * 1000};

        if((select(max_fd + 1, &fdset, &wrfds, NULL, &timeout) < 0) && (errno != EINTR)) {
            log_e("select failed[%d]: %s", errno, strerror(errno));
            break;
        }

        if(!running)
            break;

        if(FD_ISSET(pd->vpn.tunfd, &fdset)) {
            /* Packet from VPN */
            size = read(pd->vpn.tunfd, buffer, sizeof(buffer));
            if(size > 0) {
                nemo_refresh_time(pd);
                switch (tc_enqueue_uplink(&pd->conditioner, buffer, size, pd->now_ms)) {
                    case TC_RESULT_QUEUED:
                    case TC_RESULT_DROP:
                    case TC_RESULT_ABORT:
                        goto housekeeping;
                    case TC_RESULT_BYPASS:
                    default:
                        process_tun_packet(pd, zdt, buffer, size);
                        break;
                }
            } else {
                nemo_refresh_time(pd);
                if(size < 0)
                    log_e("recv(tunfd) returned error [%d]: %s", errno,
                          strerror(errno));
            }
        } else if(allow_downlink_read) {
            nemo_refresh_time(pd);
            zdtun_handle_fd(zdt, &fdset, &wrfds);
        } else {
            nemo_refresh_time(pd);
        }

        housekeeping:
        nemo_housekeeping(pd);

        if(pd->now_ms >= next_purge_ms) {
            zdtun_purge_expired(zdt);
            next_purge_ms = pd->now_ms + PERIODIC_PURGE_TIMEOUT_MS;
        }
    }

    tc_finalize(pd);
    zdtun_finalize(zdt);

#if ANDROID
    destroy_uid_resolver(pd->vpn.resolver);
#endif

    return(0);
}
