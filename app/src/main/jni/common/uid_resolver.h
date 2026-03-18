#ifndef __UID_RESOLVER_H__
#define __UID_RESOLVER_H__

#include "jni_utils.h"
#include "zdtun.h"

#define UID_UNKNOWN -1
#define UID_ROOT 0
#define UID_PHONE 1001
#define UID_NETD 1051

typedef struct uid_resolver uid_resolver_t;

#ifdef ANDROID
uid_resolver_t* init_uid_resolver(jint sdk_version, JNIEnv *env, jobject vpn);
#endif

uid_resolver_t* init_uid_resolver_from_proc();
void destroy_uid_resolver(uid_resolver_t *resolver);
int get_uid(uid_resolver_t *resolver, const zdtun_5tuple_t *conn_info);

#endif // __UID_RESOLVER_H__
