// A simple LRU implementation based on uthash
// Inspired by https://jehiah.cz/a/uthash

#include <stdlib.h>
#include "utils.h"
#include "uid_lru.h"
#include "third_party/uthash.h"

struct cache_entry {
    zdtun_5tuple_t key;
    int uid;
    UT_hash_handle hh;
};

struct uid_lru {
    int max_size;
    struct cache_entry *cache;
};

/* ******************************************************* */

uid_lru_t* uid_lru_init(int max_size) {
    uid_lru_t *lru = (uid_lru_t*) nemo_malloc(sizeof(uid_lru_t));

    if(!lru)
        return NULL;

    lru->max_size = max_size;
    lru->cache = NULL;

    return lru;
}

/* ******************************************************* */

void uid_lru_destroy(uid_lru_t *lru) {
    struct cache_entry *entry, *tmp;

    HASH_ITER(hh, lru->cache, entry, tmp) {
        HASH_DELETE(hh, lru->cache, entry);
        nemo_free(entry);
    }

    nemo_free(lru);
}

/* ******************************************************* */

static struct cache_entry* uid_lru_find_entry(uid_lru_t *lru, const zdtun_5tuple_t *tuple) {
    struct cache_entry *entry;

    HASH_FIND(hh, lru->cache, tuple, sizeof(zdtun_5tuple_t), entry);

    if(entry) {
        // Bring the entry to the front of the list
        HASH_DELETE(hh, lru->cache, entry);
        HASH_ADD(hh, lru->cache, key, sizeof(zdtun_5tuple_t), entry);

        return(entry);
    }

    return NULL;
}

/* ******************************************************* */

void uid_lru_add(uid_lru_t *lru, const zdtun_5tuple_t *tuple, int uid) {
    struct cache_entry *entry, *tmp;

    entry = nemo_malloc(sizeof(struct cache_entry));

    if(!entry)
        return;

    entry->key = *tuple;
    entry->uid = uid;

    HASH_ADD(hh, lru->cache, key, sizeof(zdtun_5tuple_t), entry);

    if(HASH_COUNT(lru->cache) > lru->max_size) {
        // uthash guarantees that iteration order is same as insertion order
        // see https://troydhanson.github.io/uthash/userguide.html#_sorting
        HASH_ITER(hh, lru->cache, entry, tmp) {
            // delete the oldest entry
            HASH_DELETE(hh, lru->cache, entry);
            nemo_free(entry);
            break;
        }
    }
}

/* ******************************************************* */

int uid_lru_find(uid_lru_t *lru, const zdtun_5tuple_t *tuple) {
    struct cache_entry *entry = uid_lru_find_entry(lru, tuple);

    return(entry ? entry->uid : -2);
}

/* ******************************************************* */

int uid_lru_size(uid_lru_t *lru) {
    return HASH_COUNT(lru->cache);
}
