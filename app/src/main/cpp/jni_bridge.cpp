#include "core/engine.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <jni.h>
#include <spdlog/spdlog.h>
#include <string>

static jstring toJavaString(JNIEnv *env, const std::string &str) {
  return env->NewStringUTF(str.c_str());
}

static std::string fromJavaString(JNIEnv *env, jstring jstr) {
  if (!jstr)
    return "";
  const char *chars = env->GetStringUTFChars(jstr, nullptr);
  std::string result(chars);
  env->ReleaseStringUTFChars(jstr, chars);
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_xiaotong6666_core_native_Core_nativeInit(JNIEnv *env,
                                                        jclass /* clazz */,
                                                        jstring configJson) {
  auto cfg = fromJavaString(env, configJson);
  return tools::Core::instance().initialize(cfg) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_xiaotong6666_core_native_Core_nativeExecute(JNIEnv *env,
                                                           jclass /* clazz */,
                                                           jstring cmdType,
                                                           jstring jsonArgs) {
  auto type = fromJavaString(env, cmdType);
  auto args = fromJavaString(env, jsonArgs);
  auto result = tools::Core::instance().execute(type, args);
  return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_xiaotong6666_core_native_Core_nativeIsAlive(JNIEnv * /* env */,
                                                           jclass /* clazz */) {
  return tools::Core::instance().daemon().isAlive() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_xiaotong6666_core_native_Core_nativeGetBatteryInfo(JNIEnv *env,
                                                                  jclass) {
  return toJavaString(env, tools::Core::instance().getBatteryInfo());
}
