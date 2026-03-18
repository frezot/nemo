#ifndef __IP_LRU_H__
#define __IP_LRU_H__

#include "zdtun.h"

typedef struct ip_lru ip_lru_t;

ip_lru_t* ip_lru_init(int max_size);
void ip_lru_destroy(ip_lru_t *lru);
void ip_lru_add(ip_lru_t *lru, const zdtun_ip_t *ip, const char *hostname);
char* ip_lru_find(ip_lru_t *lru, const zdtun_ip_t *ip);
int ip_lru_size(ip_lru_t *lru);

#endif // __IP_LRU_H__
