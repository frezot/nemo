// A simple LRU implementation based on uthash
// Inspired by https://jehiah.cz/a/uthash

#include <stdlib.h>
#include "common/utils.h"
#include "ip_lru.h"
#include "third_party/uthash.h"

struct cache_entry {
    zdtun_ip_t key;
    char *host;
    UT_hash_handle hh;
};

struct ip_lru {
    int max_size;
    struct cache_entry *cache;
};

/* ******************************************************* */

ip_lru_t* ip_lru_init(int max_size) {
    ip_lru_t *lru = (ip_lru_t*) nemo_malloc(sizeof(ip_lru_t));

    if(!lru)
        return NULL;

    lru->max_size = max_size;
    lru->cache = NULL;

    return lru;
}

/* ******************************************************* */

void ip_lru_destroy(ip_lru_t *lru) {
    struct cache_entry *entry, *tmp;

    HASH_ITER(hh, lru->cache, entry, tmp) {
        HASH_DELETE(hh, lru->cache, entry);
        nemo_free(entry->host);
        nemo_free(entry);
    }

    nemo_free(lru);
}

/* ******************************************************* */

static struct cache_entry* ip_lru_find_entry(ip_lru_t *lru, const zdtun_ip_t *ip) {
    struct cache_entry *entry;

    HASH_FIND(hh, lru->cache, ip, sizeof(zdtun_ip_t), entry);

    if(entry) {
        // Bring the entry to the front of the list
        HASH_DELETE(hh, lru->cache, entry);
        HASH_ADD(hh, lru->cache, key, sizeof(zdtun_ip_t), entry);

        return(entry);
    }

    return NULL;
}

/* ******************************************************* */

void ip_lru_add(ip_lru_t *lru, const zdtun_ip_t *ip, const char *hostname) {
    struct cache_entry *entry, *tmp;
    char *host = nemo_strdup(hostname);

    if(!host)
        return;

    // guarantee key uniqueness
    entry = ip_lru_find_entry(lru, ip);

    if(entry != NULL) {
        // update existing
        nemo_free(entry->host);
        entry->host = host;
        return;
    }

    entry = nemo_malloc(sizeof(struct cache_entry));

    if(!entry) {
        nemo_free(host);
        return;
    }

    entry->key = *ip;
    entry->host = host;

    HASH_ADD(hh, lru->cache, key, sizeof(zdtun_ip_t), entry);

    if(HASH_COUNT(lru->cache) > lru->max_size) {
        // uthash guarantees that iteration order is same as insertion order
        // see https://troydhanson.github.io/uthash/userguide.html#_sorting
        HASH_ITER(hh, lru->cache, entry, tmp) {
            // delete the oldest entry
            HASH_DELETE(hh, lru->cache, entry);
            nemo_free(entry->host);
            nemo_free(entry);
            break;
        }
    }
}

/* ******************************************************* */

char* ip_lru_find(ip_lru_t *lru, const zdtun_ip_t *ip) {
    struct cache_entry *entry = ip_lru_find_entry(lru, ip);

    return(entry ? nemo_strdup(entry->host) : NULL);
}

/* ******************************************************* */

int ip_lru_size(ip_lru_t *lru) {
    return HASH_COUNT(lru->cache);
}
