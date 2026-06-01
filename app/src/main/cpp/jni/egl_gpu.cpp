#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <jni.h>
#include <nlohmann/json.hpp>
#include <string>

#define LOG_TAG "EglGpu"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;

namespace tools {
namespace egl {

std::string getGpuRendererJson() {
  json j;
  j["vendor"] = "";
  j["renderer"] = "";
  j["version"] = "";

  LOGD("getGpuRendererJson: starting EGL probe");

  EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (dpy == EGL_NO_DISPLAY) {
    LOGE("getGpuRendererJson: eglGetDisplay returned EGL_NO_DISPLAY");
    return j.dump();
  }
  LOGD("getGpuRendererJson: eglGetDisplay ok");

  EGLint major, minor;
  if (!eglInitialize(dpy, &major, &minor)) {
    auto err = eglGetError();
    LOGE("getGpuRendererJson: eglInitialize failed, error=0x%x", err);
    return j.dump();
  }
  LOGD("getGpuRendererJson: eglInitialize ok, version %d.%d", major, minor);

  EGLint configAttribs[] = {EGL_SURFACE_TYPE,
                            EGL_PBUFFER_BIT,
                            EGL_RENDERABLE_TYPE,
                            EGL_OPENGL_ES2_BIT,
                            EGL_RED_SIZE,
                            8,
                            EGL_GREEN_SIZE,
                            8,
                            EGL_BLUE_SIZE,
                            8,
                            EGL_ALPHA_SIZE,
                            8,
                            EGL_NONE};
  EGLConfig config;
  EGLint numConfigs;
  if (!eglChooseConfig(dpy, configAttribs, &config, 1, &numConfigs)) {
    auto err = eglGetError();
    LOGE("getGpuRendererJson: eglChooseConfig failed, error=0x%x", err);
    eglTerminate(dpy);
    return j.dump();
  }
  LOGD("getGpuRendererJson: eglChooseConfig ok, numConfigs=%d", numConfigs);

  EGLint pbAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
  EGLSurface surf = eglCreatePbufferSurface(dpy, config, pbAttribs);
  if (surf == EGL_NO_SURFACE) {
    auto err = eglGetError();
    LOGE("getGpuRendererJson: eglCreatePbufferSurface failed, error=0x%x", err);
    eglTerminate(dpy);
    return j.dump();
  }
  LOGD("getGpuRendererJson: eglCreatePbufferSurface ok");

  EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
  EGLContext ctx = eglCreateContext(dpy, config, EGL_NO_CONTEXT, ctxAttribs);
  if (ctx == EGL_NO_CONTEXT) {
    auto err = eglGetError();
    LOGE("getGpuRendererJson: eglCreateContext failed, error=0x%x", err);
    eglDestroySurface(dpy, surf);
    eglTerminate(dpy);
    return j.dump();
  }
  LOGD("getGpuRendererJson: eglCreateContext ok");

  if (!eglMakeCurrent(dpy, surf, surf, ctx)) {
    auto err = eglGetError();
    LOGE("getGpuRendererJson: eglMakeCurrent failed, error=0x%x", err);
    eglDestroyContext(dpy, ctx);
    eglDestroySurface(dpy, surf);
    eglTerminate(dpy);
    return j.dump();
  }
  LOGD("getGpuRendererJson: eglMakeCurrent ok");

  const char *s = (const char *)glGetString(GL_VENDOR);
  if (s) {
    j["vendor"] = s;
    LOGD("getGpuRendererJson: GL_VENDOR = '%s'", s);
  } else {
    LOGE("getGpuRendererJson: glGetString(GL_VENDOR) returned null, "
         "glError=0x%x",
         glGetError());
  }
  s = (const char *)glGetString(GL_RENDERER);
  if (s) {
    j["renderer"] = s;
    LOGD("getGpuRendererJson: GL_RENDERER = '%s'", s);
  } else {
    LOGE("getGpuRendererJson: glGetString(GL_RENDERER) returned null, "
         "glError=0x%x",
         glGetError());
  }
  s = (const char *)glGetString(GL_VERSION);
  if (s) {
    j["version"] = s;
    LOGD("getGpuRendererJson: GL_VERSION = '%s'", s);
  } else {
    LOGE("getGpuRendererJson: glGetString(GL_VERSION) returned null, "
         "glError=0x%x",
         glGetError());
  }

  eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  eglDestroyContext(dpy, ctx);
  eglDestroySurface(dpy, surf);
  eglTerminate(dpy);

  LOGD("getGpuRendererJson: done, result=%s", j.dump().c_str());
  return j.dump();
}

} // namespace egl
} // namespace tools

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_xiaotong6666_core_native_Core_nativeGetGpuRenderer(JNIEnv *env,
                                                                  jclass) {
  return env->NewStringUTF(tools::egl::getGpuRendererJson().c_str());
}
