#ifndef __JNI_UTILS_H__
#define __JNI_UTILS_H__

#ifdef ANDROID

#include <jni.h>

jclass jniFindClass(JNIEnv *env, const char *name);
jmethodID jniGetMethodID(JNIEnv *env, jclass cls, const char *name, const char *signature);
jmethodID jniGetStaticMethodID(JNIEnv *env, jclass cls, const char *name, const char *signature);
jfieldID jniFieldID(JNIEnv *env, jclass cls, const char *name, const char *type);
jobject jniEnumVal(JNIEnv *env, const char *class_name, const char *enum_key);
int jniCheckException(JNIEnv *env);
void jniDumpReferences(JNIEnv *env);

#else // if ANDROID

#include <stdint.h>

// https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/types.html
typedef uint8_t  jboolean;
typedef int8_t   jbyte;
typedef int32_t  jint;
typedef uint64_t jlong;

#endif // ANDROID

#endif // __JNI_UTILS_H__
