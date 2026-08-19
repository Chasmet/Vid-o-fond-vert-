#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>

#include <algorithm>
#include <cmath>
#include <mutex>
#include <vector>

#include "net.h"
#include "cpu.h"
#include "mat.h"

namespace {

class RvmCore {
public:
    explicit RvmCore(AAssetManager* assets) {
        net.opt.use_fp16_packed = false;
        net.opt.use_fp16_storage = false;
        net.opt.use_fp16_arithmetic = false;
        net.opt.use_vulkan_compute = false;
        net.opt.num_threads = std::max(2, std::min(4, ncnn::get_cpu_count()));

        if (net.load_param(assets, "rvm_mobilenetv3.ncnn.param") != 0) {
            throw std::runtime_error("param RVM introuvable");
        }
        if (net.load_model(assets, "rvm_mobilenetv3.ncnn.bin") != 0) {
            throw std::runtime_error("poids RVM introuvables");
        }
    }

    void reset() {
        std::lock_guard<std::mutex> guard(lock);
        r1.release();
        r2.release();
        r3.release();
        r4.release();
        recurrentWidth = 0;
        recurrentHeight = 0;
    }

    std::vector<unsigned char> predict(JNIEnv* env, jobject bitmap,
                                       int targetSize, bool highQuality) {
        std::lock_guard<std::mutex> guard(lock);

        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
            throw std::runtime_error("bitmap Android illisible");
        }
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            throw std::runtime_error("format bitmap non RGBA8888");
        }

        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS
                || pixels == nullptr) {
            throw std::runtime_error("pixels caméra inaccessibles");
        }

        const int w = static_cast<int>(info.width);
        const int h = static_cast<int>(info.height);
        const int maxStride = 16;
        const float norm[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};

        ncnn::Mat inPad;
        ncnn::Mat inSmallPad;
        int wpad = 0;
        int hpad = 0;
        bool downsample = std::max(w, h) > targetSize;

        try {
            if (downsample) {
                int w2 = w;
                int h2 = h;
                float scale;
                if (w > h) {
                    scale = static_cast<float>(targetSize) / static_cast<float>(w);
                    w2 = targetSize;
                    h2 = std::max(16, static_cast<int>(std::round(h * scale)));
                } else {
                    scale = static_cast<float>(targetSize) / static_cast<float>(h);
                    h2 = targetSize;
                    w2 = std::max(16, static_cast<int>(std::round(w * scale)));
                }

                ncnn::Mat small = ncnn::Mat::from_pixels_resize(
                        static_cast<const unsigned char*>(pixels),
                        ncnn::Mat::PIXEL_RGBA2RGB, w, h, w2, h2);
                int w2pad = (w2 + maxStride - 1) / maxStride * maxStride - w2;
                int h2pad = (h2 + maxStride - 1) / maxStride * maxStride - h2;
                ncnn::copy_make_border(small, inSmallPad,
                        h2pad / 2, h2pad - h2pad / 2,
                        w2pad / 2, w2pad - w2pad / 2,
                        ncnn::BORDER_CONSTANT, 114.f);
                inSmallPad.substract_mean_normalize(nullptr, norm);

                int w3 = w;
                int h3 = h;
                if (w > h) {
                    h3 = std::max(h, static_cast<int>(std::round(inSmallPad.h / scale)));
                    hpad = h3 - h;
                } else {
                    w3 = std::max(w, static_cast<int>(std::round(inSmallPad.w / scale)));
                    wpad = w3 - w;
                }

                ncnn::Mat full = ncnn::Mat::from_pixels(
                        static_cast<const unsigned char*>(pixels),
                        ncnn::Mat::PIXEL_RGBA2RGB, w, h);
                ncnn::copy_make_border(full, inPad,
                        hpad / 2, hpad - hpad / 2,
                        wpad / 2, wpad - wpad / 2,
                        ncnn::BORDER_CONSTANT, 114.f);
                inPad.substract_mean_normalize(nullptr, norm);
            } else {
                ncnn::Mat full = ncnn::Mat::from_pixels(
                        static_cast<const unsigned char*>(pixels),
                        ncnn::Mat::PIXEL_RGBA2RGB, w, h);
                wpad = (w + maxStride - 1) / maxStride * maxStride - w;
                hpad = (h + maxStride - 1) / maxStride * maxStride - h;
                ncnn::copy_make_border(full, inPad,
                        hpad / 2, hpad - hpad / 2,
                        wpad / 2, wpad - wpad / 2,
                        ncnn::BORDER_CONSTANT, 114.f);
                inPad.substract_mean_normalize(nullptr, norm);
                inSmallPad = inPad;
            }
        } catch (...) {
            AndroidBitmap_unlockPixels(env, bitmap);
            throw;
        }
        AndroidBitmap_unlockPixels(env, bitmap);

        ensureRecurrentState(inSmallPad.w, inSmallPad.h);

        ncnn::Extractor ex = net.create_extractor();
        ex.input("in0", inPad);
        ex.input("in1", inSmallPad);
        ex.input("in2", r1);
        ex.input("in3", r2);
        ex.input("in4", r3);
        ex.input("in5", r4);

        ncnn::Mat outPha;
        int result;
        if (downsample) {
            // out3 = deep guided refinement, out5 = fast guided refinement.
            result = ex.extract(highQuality ? "out3" : "out5", outPha);
        } else {
            result = ex.extract("out1", outPha);
        }
        if (result != 0 || outPha.empty()) {
            throw std::runtime_error("inférence RVM échouée");
        }

        ncnn::Mat next1, next2, next3, next4;
        if (ex.extract("out7", next1, 1) != 0
                || ex.extract("out8", next2, 1) != 0
                || ex.extract("out9", next3, 1) != 0
                || ex.extract("out10", next4, 1) != 0) {
            throw std::runtime_error("mémoire temporelle RVM invalide");
        }
        r1 = next1;
        r2 = next2;
        r3 = next3;
        r4 = next4;

        ncnn::Mat cropped;
        int top = hpad / 2;
        int bottom = hpad - top;
        int left = wpad / 2;
        int right = wpad - left;
        if (top || bottom || left || right) {
            ncnn::copy_cut_border(outPha, cropped, top, bottom, left, right);
        } else {
            cropped = outPha;
        }

        if (cropped.w != w || cropped.h != h) {
            ncnn::Mat resized;
            ncnn::resize_bilinear(cropped, resized, w, h);
            cropped = resized;
        }

        const float denorm[1] = {255.f};
        cropped.substract_mean_normalize(nullptr, denorm);
        std::vector<unsigned char> alpha(static_cast<size_t>(w) * h);
        cropped.to_pixels(alpha.data(), ncnn::Mat::PIXEL_GRAY);
        return alpha;
    }

private:
    void ensureRecurrentState(int width, int height) {
        if (width == recurrentWidth && height == recurrentHeight
                && !r1.empty() && !r2.empty() && !r3.empty() && !r4.empty()) {
            return;
        }
        r1.create(width / 2, height / 2, 16);
        r2.create(width / 4, height / 4, 20);
        r3.create(width / 8, height / 8, 40);
        r4.create(width / 16, height / 16, 64);
        r1.fill(0.f);
        r2.fill(0.f);
        r3.fill(0.f);
        r4.fill(0.f);
        recurrentWidth = width;
        recurrentHeight = height;
    }

    ncnn::Net net;
    ncnn::Mat r1;
    ncnn::Mat r2;
    ncnn::Mat r3;
    ncnn::Mat r4;
    int recurrentWidth = 0;
    int recurrentHeight = 0;
    std::mutex lock;
};

static RvmCore* fromHandle(jlong handle) {
    return reinterpret_cast<RvmCore*>(handle);
}

static void throwJava(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_chasmet_fondvertstudio_RvmNcnnEngine_nativeCreate(
        JNIEnv* env, jclass, jobject assetManager) {
    try {
        AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
        if (!mgr) throw std::runtime_error("AssetManager indisponible");
        auto* engine = new RvmCore(mgr);
        return reinterpret_cast<jlong>(engine);
    } catch (const std::exception& error) {
        throwJava(env, error.what());
        return 0L;
    } catch (...) {
        throwJava(env, "Initialisation native RVM échouée");
        return 0L;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_chasmet_fondvertstudio_RvmNcnnEngine_nativePredict(
        JNIEnv* env, jclass, jlong handle, jobject bitmap,
        jint targetSize, jboolean highQuality) {
    RvmCore* engine = fromHandle(handle);
    if (!engine) {
        throwJava(env, "Moteur RVM absent");
        return nullptr;
    }
    try {
        std::vector<unsigned char> alpha = engine->predict(
                env, bitmap, static_cast<int>(targetSize), highQuality == JNI_TRUE);
        jbyteArray output = env->NewByteArray(static_cast<jsize>(alpha.size()));
        if (!output) throw std::runtime_error("Mémoire alpha insuffisante");
        env->SetByteArrayRegion(output, 0, static_cast<jsize>(alpha.size()),
                                reinterpret_cast<const jbyte*>(alpha.data()));
        return output;
    } catch (const std::exception& error) {
        throwJava(env, error.what());
        return nullptr;
    } catch (...) {
        throwJava(env, "Inférence native RVM échouée");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_chasmet_fondvertstudio_RvmNcnnEngine_nativeReset(
        JNIEnv*, jclass, jlong handle) {
    RvmCore* engine = fromHandle(handle);
    if (engine) engine->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_chasmet_fondvertstudio_RvmNcnnEngine_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    RvmCore* engine = fromHandle(handle);
    delete engine;
}
