#include <jni.h>
#include <whisper.h>
#include <string>
#include <vector>
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_chasmet_fondvertstudio_LocalWhisper_transcribe(JNIEnv *env, jclass, jstring file, jfloatArray audio, jint threads, jobject progress) {
    const char *path = env->GetStringUTFChars(file, nullptr);
    auto cp = whisper_context_default_params();
    cp.use_gpu = false;
    auto *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(file, path);
    if (!ctx) return nullptr;
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "fr";
    params.n_threads = threads;
    params.translate = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_timestamps = false;
    params.no_context = true;
    struct ProgressData { JavaVM *vm; jobject object; jmethodID method; };
    ProgressData data{};
    env->GetJavaVM(&data.vm);
    if (progress) {
        data.object = env->NewGlobalRef(progress);
        jclass type = env->GetObjectClass(progress);
        data.method = env->GetMethodID(type, "update", "(I)V");
        env->DeleteLocalRef(type);
        params.progress_callback_user_data = &data;
        params.progress_callback = [](struct whisper_context *, struct whisper_state *, int value, void *opaque) {
            auto *d = static_cast<ProgressData *>(opaque);
            JNIEnv *e = nullptr;
            bool attached = false;
            if (d->vm->GetEnv(reinterpret_cast<void **>(&e), JNI_VERSION_1_6) != JNI_OK) {
                if (d->vm->AttachCurrentThread(&e, nullptr) != JNI_OK) return;
                attached = true;
            }
            if (d->method) e->CallVoidMethod(d->object, d->method, value);
            if (e->ExceptionCheck()) e->ExceptionClear();
            if (attached) d->vm->DetachCurrentThread();
        };
    }
    auto size = env->GetArrayLength(audio);
    std::vector<float> pcm(size);
    env->GetFloatArrayRegion(audio, 0, size, pcm.data());
    int rc = whisper_full(ctx, params, pcm.data(), size);
    if (data.object) env->DeleteGlobalRef(data.object);
    if (rc != 0) { whisper_free(ctx); return nullptr; }
    int count = whisper_full_n_segments(ctx);
    auto cls = env->FindClass("java/lang/String");
    auto arr = env->NewObjectArray(count * 3, cls, nullptr);
    for (int i=0;i<count;i++) {
        auto t0=std::to_string(whisper_full_get_segment_t0(ctx,i)*10);
        auto t1=std::to_string(whisper_full_get_segment_t1(ctx,i)*10);
        const char *s=whisper_full_get_segment_text(ctx,i);
        const char *items[]={t0.c_str(),t1.c_str(),s};
        for(int j=0;j<3;j++) {
            auto val=env->NewStringUTF(items[j]);
            env->SetObjectArrayElement(arr,i*3+j,val);
            env->DeleteLocalRef(val);
        }
    }
    whisper_free(ctx);
    return arr;
}
