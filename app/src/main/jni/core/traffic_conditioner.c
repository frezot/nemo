#include <limits.h>

#include "nemo_core.h"
#include "common/utils.h"
#include "common/alloc.h"

#define TRAFFIC_CONDITIONER_QUEUE_LIMIT_BYTES (8 * 1024 * 1024)
#define TRAFFIC_CONDITIONER_MIN_BURST_BYTES 2048
#define TRAFFIC_CONDITIONER_STALL_MIN_PERCENT 70

static bool tc_has_knobs(const traffic_conditioner_t *tc, tc_direction_t direction) {
    const traffic_conditioner_direction_t *dir = (direction == TC_DIR_UPLINK) ? &tc->uplink : &tc->downlink;

    return tc->enabled && ((tc->base_latency_ms > 0) || (tc->jitter_ms > 0) ||
            (tc->packet_loss_percent > 0) || tc->stall_enabled || (dir->rate_bytes_per_sec > 0));
}

static traffic_conditioner_direction_t* tc_get_direction(traffic_conditioner_t *tc, tc_direction_t direction) {
    return (direction == TC_DIR_UPLINK) ? &tc->uplink : &tc->downlink;
}

static uint32_t tc_random_percent(traffic_conditioner_t *tc) {
    return rand_r(&tc->rand_state) % 100;
}

static int32_t tc_sample_jitter(traffic_conditioner_t *tc) {
    if(tc->jitter_ms == 0)
        return 0;

    uint32_t span = (tc->jitter_ms * 2) + 1;
    return (int32_t)(rand_r(&tc->rand_state) % span) - (int32_t)tc->jitter_ms;
}

static uint64_t tc_compute_release_ms(traffic_conditioner_t *tc, uint64_t now_ms) {
    int64_t latency_ms = (int64_t) tc->base_latency_ms + tc_sample_jitter(tc);
    if(latency_ms < 0)
        latency_ms = 0;
    return now_ms + (uint64_t) latency_ms;
}

static uint32_t tc_sample_stall_duration_ms(traffic_conditioner_t *tc) {
    if(tc->stall_duration_ms <= 1)
        return tc->stall_duration_ms;

    uint32_t min_duration_ms = (tc->stall_duration_ms * TRAFFIC_CONDITIONER_STALL_MIN_PERCENT) / 100;
    if(min_duration_ms == 0)
        min_duration_ms = 1;

    uint32_t span = tc->stall_duration_ms - min_duration_ms + 1;
    return min_duration_ms + (rand_r(&tc->rand_state) % span);
}

static void tc_update_stall(traffic_conditioner_t *tc, traffic_conditioner_direction_t *dir,
                            uint64_t now_ms) {
    if(!tc->stall_enabled || (tc->stall_interval_ms == 0) || (tc->stall_duration_ms == 0))
        return;

    if(dir->next_stall_at_ms == 0)
        dir->next_stall_at_ms = now_ms + tc->stall_interval_ms;

    if((dir->stall_until_ms <= now_ms) && (now_ms >= dir->next_stall_at_ms)) {
        uint32_t sampled_duration_ms = tc_sample_stall_duration_ms(tc);
        dir->stall_until_ms = now_ms + sampled_duration_ms;
        dir->next_stall_at_ms = dir->stall_until_ms + tc->stall_interval_ms;
    }
}

static void tc_refill_tokens(traffic_conditioner_direction_t *dir, uint64_t now_ms) {
    if((dir->rate_bytes_per_sec == 0) || (dir->last_refill_ms == 0)) {
        dir->last_refill_ms = now_ms;
        return;
    }

    if(now_ms <= dir->last_refill_ms)
        return;

    uint64_t delta_ms = now_ms - dir->last_refill_ms;
    uint64_t bytes = (delta_ms * dir->rate_bytes_per_sec) / 1000;

    dir->tokens = (dir->tokens + bytes > dir->max_tokens) ? dir->max_tokens : (dir->tokens + bytes);
    dir->last_refill_ms = now_ms;
}

static void tc_direction_init(traffic_conditioner_direction_t *dir, uint64_t now_ms) {
    if(dir->rate_bytes_per_sec > 0) {
        uint64_t burst = dir->rate_bytes_per_sec / 4;
        dir->max_tokens = (burst > TRAFFIC_CONDITIONER_MIN_BURST_BYTES) ? burst :
                TRAFFIC_CONDITIONER_MIN_BURST_BYTES;
        dir->tokens = dir->max_tokens;
    }

    dir->last_refill_ms = now_ms;
}

static void tc_free_packet(nemo_core_t *pd, traffic_conditioned_packet_t *packet) {
    if(!packet)
        return;

    if(packet->data)
        nemo_release_connection(pd, packet->data);

    nemo_free(packet->buf);
    nemo_free(packet);
}

static traffic_conditioned_packet_t* tc_alloc_packet(const uint8_t *buf, uint32_t len) {
    traffic_conditioned_packet_t *packet = nemo_calloc(1, sizeof(traffic_conditioned_packet_t));
    if(!packet)
        return NULL;

    packet->buf = nemo_malloc(len);
    if(!packet->buf) {
        nemo_free(packet);
        return NULL;
    }

    memcpy(packet->buf, buf, len);
    packet->len = len;
    return packet;
}

static tc_result_t tc_enqueue_packet(traffic_conditioner_t *tc, traffic_conditioner_direction_t *dir,
                                     traffic_conditioned_packet_t *packet, uint64_t now_ms) {
    if((dir->queued_bytes + packet->len) > TRAFFIC_CONDITIONER_QUEUE_LIMIT_BYTES)
        return TC_RESULT_ABORT;

    tc_update_stall(tc, dir, now_ms);

    packet->release_ms = tc_compute_release_ms(tc, now_ms);
    if((dir->stall_until_ms > now_ms) && (packet->release_ms < dir->stall_until_ms))
        packet->release_ms = dir->stall_until_ms;

    if(dir->tail)
        dir->tail->next = packet;
    else
        dir->head = packet;

    dir->tail = packet;
    dir->queued_bytes += packet->len;
    return TC_RESULT_QUEUED;
}

static bool tc_should_drop(traffic_conditioner_t *tc, tc_direction_t direction, int ipproto) {
    if((tc->packet_loss_percent == 0) || !tc_has_knobs(tc, direction))
        return false;

    if((direction == TC_DIR_DOWNLINK) && (ipproto == IPPROTO_TCP)) {
        if(!tc->warned_downlink_tcp_loss) {
            log_i("Ignoring synthetic downlink TCP loss; uplink loss still applies to ACK traffic");
            tc->warned_downlink_tcp_loss = true;
        }
        return false;
    }

    return tc_random_percent(tc) < tc->packet_loss_percent;
}

static bool tc_packet_ready(traffic_conditioner_t *tc, traffic_conditioner_direction_t *dir,
                            traffic_conditioned_packet_t *packet, uint64_t now_ms) {
    tc_update_stall(tc, dir, now_ms);
    if((dir->stall_until_ms > now_ms) || (packet->release_ms > now_ms))
        return false;

    tc_refill_tokens(dir, now_ms);
    return (dir->rate_bytes_per_sec == 0) || (dir->tokens >= packet->len);
}

static uint32_t tc_next_delay_for_direction(traffic_conditioner_t *tc,
                                            traffic_conditioner_direction_t *dir,
                                            uint64_t now_ms) {
    traffic_conditioned_packet_t *packet = dir->head;
    if(!packet)
        return UINT_MAX;

    tc_update_stall(tc, dir, now_ms);

    if(dir->stall_until_ms > now_ms)
        return (uint32_t)(dir->stall_until_ms - now_ms);

    if(packet->release_ms > now_ms)
        return (uint32_t)(packet->release_ms - now_ms);

    tc_refill_tokens(dir, now_ms);
    if((dir->rate_bytes_per_sec > 0) && (dir->tokens < packet->len)) {
        uint64_t missing = packet->len - dir->tokens;
        uint32_t delay = (uint32_t)((missing * 1000 + dir->rate_bytes_per_sec - 1) /
                dir->rate_bytes_per_sec);
        return delay > 0 ? delay : 1;
    }

    return 0;
}

void tc_init(traffic_conditioner_t *tc, uint64_t now_ms) {
    tc->rand_state = (unsigned int) now_ms;
    tc_direction_init(&tc->uplink, now_ms);
    tc_direction_init(&tc->downlink, now_ms);

    if(!tc->enabled) {
        log_i("Traffic conditioner disabled");
        return;
    }

    log_i("Traffic conditioner active: latency=%ums jitter=%ums uplink=%llukbps downlink=%llukbps loss=%u%% stall=%s interval=%ums duration_max=%ums variance_down=30%%",
          tc->base_latency_ms,
          tc->jitter_ms,
          (unsigned long long)(tc->uplink.rate_bytes_per_sec / 125),
          (unsigned long long)(tc->downlink.rate_bytes_per_sec / 125),
          tc->packet_loss_percent,
          tc->stall_enabled ? "on" : "off",
          tc->stall_interval_ms,
          tc->stall_duration_ms);
}

void tc_finalize(nemo_core_t *pd) {
    traffic_conditioned_packet_t *packet;

    while((packet = pd->conditioner.uplink.head) != NULL) {
        pd->conditioner.uplink.head = packet->next;
        tc_free_packet(pd, packet);
    }

    while((packet = pd->conditioner.downlink.head) != NULL) {
        pd->conditioner.downlink.head = packet->next;
        tc_free_packet(pd, packet);
    }

    pd->conditioner.uplink.tail = NULL;
    pd->conditioner.downlink.tail = NULL;
}

bool tc_is_enabled(const traffic_conditioner_t *tc) {
    return tc->enabled;
}

bool tc_direction_is_active(const traffic_conditioner_t *tc, tc_direction_t direction) {
    return tc_has_knobs(tc, direction);
}

bool tc_can_enqueue(const traffic_conditioner_t *tc, tc_direction_t direction) {
    const traffic_conditioner_direction_t *dir = (direction == TC_DIR_UPLINK) ?
            &tc->uplink : &tc->downlink;
    return dir->queued_bytes < TRAFFIC_CONDITIONER_QUEUE_LIMIT_BYTES;
}

tc_result_t tc_enqueue_uplink(traffic_conditioner_t *tc, const char *buf, uint32_t len,
                              uint64_t now_ms) {
    if(!tc_direction_is_active(tc, TC_DIR_UPLINK))
        return TC_RESULT_BYPASS;

    if(tc_should_drop(tc, TC_DIR_UPLINK, 0))
        return TC_RESULT_DROP;

    traffic_conditioned_packet_t *packet = tc_alloc_packet((const uint8_t *) buf, len);
    if(!packet)
        return TC_RESULT_ABORT;

    tc_result_t result = tc_enqueue_packet(tc, &tc->uplink, packet, now_ms);
    if(result != TC_RESULT_QUEUED)
        tc_free_packet(NULL, packet);

    return result;
}

tc_result_t tc_enqueue_downlink(nemo_core_t *pd, const zdtun_pkt_t *pkt, const zdtun_5tuple_t *tuple,
                                nemo_conn_t *data, const struct timeval *tv, uint64_t pkt_ms,
                                uint64_t now_ms) {
    traffic_conditioner_t *tc = &pd->conditioner;
    if(!tc_direction_is_active(tc, TC_DIR_DOWNLINK))
        return TC_RESULT_BYPASS;

    if(tc_should_drop(tc, TC_DIR_DOWNLINK, tuple->ipproto))
        return TC_RESULT_DROP;

    traffic_conditioned_packet_t *packet = tc_alloc_packet((const uint8_t *) pkt->buf, pkt->len);
    if(!packet)
        return TC_RESULT_ABORT;

    packet->tuple = *tuple;
    packet->tv = *tv;
    packet->pkt_ms = pkt_ms;
    packet->data = data;

    if(data)
        nemo_retain_connection(data);

    tc_result_t result = tc_enqueue_packet(tc, &tc->downlink, packet, now_ms);
    if(result != TC_RESULT_QUEUED) {
        if((result == TC_RESULT_ABORT) && (tuple->ipproto != IPPROTO_TCP))
            result = TC_RESULT_DROP;

        if((result == TC_RESULT_ABORT) && (tuple->ipproto == IPPROTO_TCP))
            log_w("Downlink conditioner queue saturated for TCP flow");
        tc_free_packet(pd, packet);
    }

    return result;
}

int tc_flush_uplink(nemo_core_t *pd, zdtun_t *zdt,
                    int (*process_packet)(nemo_core_t *pd, zdtun_t *zdt, char *buffer, int size)) {
    traffic_conditioner_direction_t *dir = &pd->conditioner.uplink;

    while(dir->head) {
        traffic_conditioned_packet_t *packet = dir->head;
        if(!tc_packet_ready(&pd->conditioner, dir, packet, pd->now_ms))
            break;

        dir->head = packet->next;
        if(dir->head == NULL)
            dir->tail = NULL;
        dir->queued_bytes -= packet->len;

        if(dir->rate_bytes_per_sec > 0) {
            tc_refill_tokens(dir, pd->now_ms);
            dir->tokens -= packet->len;
        }

        int rv = process_packet(pd, zdt, (char *) packet->buf, (int) packet->len);
        tc_free_packet(NULL, packet);
        if(rv < 0)
            return rv;

        nemo_refresh_time(pd);
    }

    return 0;
}

static int tc_write_tun_packet(nemo_core_t *pd, const uint8_t *buf, uint32_t len) {
    int rv = write(pd->vpn.tunfd, buf, len);
    if(rv < 0) {
        if(errno == ENOBUFS) {
            log_e("Got ENOBUFS from conditioned write");
        } else if(errno == EIO) {
            log_i("Got I/O error (terminating?)");
            running = false;
        } else {
            log_f("conditioned zdt write (%u) failed [%d]: %s", len, errno, strerror(errno));
            running = false;
        }
    } else if((uint32_t)rv != len) {
        log_f("conditioned partial zdt write (%d / %u)", rv, len);
        rv = -1;
    } else {
        rv = 0;
    }

    return rv;
}

int tc_flush_downlink(nemo_core_t *pd) {
    traffic_conditioner_direction_t *dir = &pd->conditioner.downlink;

    while(dir->head) {
        traffic_conditioned_packet_t *packet = dir->head;
        if(!tc_packet_ready(&pd->conditioner, dir, packet, pd->now_ms))
            break;

        dir->head = packet->next;
        if(dir->head == NULL)
            dir->tail = NULL;
        dir->queued_bytes -= packet->len;

        if(dir->rate_bytes_per_sec > 0) {
            tc_refill_tokens(dir, pd->now_ms);
            dir->tokens -= packet->len;
        }

        int rv = tc_write_tun_packet(pd, packet->buf, packet->len);
        if((rv == 0) && packet->data) {
            zdtun_pkt_t pkt;
            pkt_context_t pctx;

            if(zdtun_parse_pkt(pd->zdt, (char *) packet->buf, (int) packet->len, &pkt) == 0) {
                nemo_init_pkt_context(&pctx, &pkt, false, &packet->tuple, packet->data, &packet->tv);
                pctx.ms = packet->pkt_ms;
                nemo_account_stats(pd, &pctx);
            }
        }

        tc_free_packet(pd, packet);
        if(rv < 0)
            return rv;

        nemo_refresh_time(pd);
    }

    return 0;
}

uint32_t tc_get_next_timeout_ms(traffic_conditioner_t *tc, uint64_t now_ms, uint32_t fallback_ms) {
    if(!tc_is_enabled(tc))
        return fallback_ms;

    uint32_t uplink_delay = tc_next_delay_for_direction(tc, &tc->uplink, now_ms);
    uint32_t downlink_delay = tc_next_delay_for_direction(tc, &tc->downlink, now_ms);
    uint32_t min_delay = uplink_delay < downlink_delay ? uplink_delay : downlink_delay;

    if(min_delay == UINT_MAX)
        return fallback_ms;
    return min_delay < fallback_ms ? min_delay : fallback_ms;
}
