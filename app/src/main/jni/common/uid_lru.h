#ifndef __UID_LRU_H__
#define __UID_LRU_H__

#include "zdtun.h"

typedef struct uid_lru uid_lru_t;

uid_lru_t* uid_lru_init(int max_size);
void uid_lru_destroy(uid_lru_t *lru);
void uid_lru_add(uid_lru_t *lru, const zdtun_5tuple_t *tuple, int uid);
int uid_lru_find(uid_lru_t *lru, const zdtun_5tuple_t *tuple);
int uid_lru_size(uid_lru_t *lru);

#endif // __UID_LRU_H__
