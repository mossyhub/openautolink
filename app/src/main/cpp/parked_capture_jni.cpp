#include <jni.h>
#include "../../../../tools/native-aaudio-capture/capture.h"
#include <memory>

// Only the process registry's serialized non-main executor may create/begin/poll/delete.
// The worker's policy call has a strong global target until joined disposal. No finalizer.
namespace {
struct Owner {
    JavaVM* vm = nullptr;
    jobject policy = nullptr;
    jmethodID eligible = nullptr;
    std::atomic<bool> fence{false};
    capture_spike::Runner runner;
    bool joined = false;
    Owner(JNIEnv* env, jobject target): runner(fence, [this]{return allowed();}) {
        env->GetJavaVM(&vm);
        policy = env->NewGlobalRef(target);
        jclass type=env->GetObjectClass(target);
        eligible=env->GetMethodID(type,"eligible","()Z");
        env->DeleteLocalRef(type);
    }
    bool allowed() {
        JNIEnv* env=nullptr;
        bool attached=false;
        if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK) {
#ifdef __ANDROID__
            if(vm->AttachCurrentThread(&env,nullptr)!=JNI_OK) return false;
#else
            if(vm->AttachCurrentThread(reinterpret_cast<void**>(&env),nullptr)!=JNI_OK) return false;
#endif
            attached=true;
        }
        bool ok=policy && eligible && env->CallBooleanMethod(policy,eligible)==JNI_TRUE;
        if(env->ExceptionCheck()){env->ExceptionClear();ok=false;}
        if(attached)vm->DetachCurrentThread();
        return ok;
    }
};
Owner* owner(jlong h){return reinterpret_cast<Owner*>(h);}
void fail(JNIEnv* env){jclass type=env->FindClass("java/lang/IllegalStateException");if(type){env->ThrowNew(type,"native capture lifecycle failure");env->DeleteLocalRef(type);}}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_openautolink_app_ui_diagnostics_NativeAAudioRun_nativeCreate(JNIEnv* env,jobject self){
    try { auto value=std::make_unique<Owner>(env,self);
        if(!value->policy || !value->eligible || env->ExceptionCheck()) {if(value->policy)env->DeleteGlobalRef(value->policy);return 0;}
        return reinterpret_cast<jlong>(value.release());
    }catch(...){fail(env);return 0;}
}
extern "C" JNIEXPORT void JNICALL
Java_com_openautolink_app_ui_diagnostics_NativeAAudioRun_nativeBegin(JNIEnv* env,jobject,jlong h){
    if(!h || !owner(h)->runner.start())fail(env);
}
extern "C" JNIEXPORT void JNICALL
Java_com_openautolink_app_ui_diagnostics_NativeAAudioRun_nativeCancel(JNIEnv*,jobject,jlong h){
    if(h)owner(h)->runner.cancel();
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_openautolink_app_ui_diagnostics_NativeAAudioRun_nativePoll(JNIEnv* env,jobject,jlong h){
    if(!h){fail(env);return nullptr;}
    auto& o=*owner(h);
    if(!o.runner.await(std::chrono::milliseconds(0)))return nullptr;
    o.joined=true;
    const auto s=o.runner.summary();
    const jlong v[32]={1,s.termination,s.error,s.stopError,s.closeError,s.recoveryRequiresRestart,
        s.joined,s.closeReturned,s.actualPreset,s.actualRate,s.actualChannels,s.actualFormat,
        s.actualSharing,s.actualPerformance,s.actualMask,s.sessionTopology,s.privacySensitive,
        s.routeStable,-1,static_cast<jlong>(s.frames),static_cast<jlong>(s.emptyReads),
        static_cast<jlong>(s.channel[0].samples),static_cast<jlong>(s.channel[0].nonzero),s.channel[0].peak,
        static_cast<jlong>(s.channel[0].squares),static_cast<jlong>(s.channel[0].clipped),
        static_cast<jlong>(s.channel[1].samples),static_cast<jlong>(s.channel[1].nonzero),s.channel[1].peak,
        static_cast<jlong>(s.channel[1].squares),static_cast<jlong>(s.channel[1].clipped),2000};
    auto a=env->NewLongArray(32);if(a)env->SetLongArrayRegion(a,0,32,v);return a;
}
extern "C" JNIEXPORT void JNICALL
Java_com_openautolink_app_ui_diagnostics_NativeAAudioRun_nativeDispose(JNIEnv* env,jobject,jlong h){
    if(!h || !owner(h)->joined){fail(env);return;}
    auto* o=owner(h);const auto s=o->runner.summary();
    if(!s.closeReturned || s.recoveryRequiresRestart){fail(env);return;}
    env->DeleteGlobalRef(o->policy);delete o;
}
