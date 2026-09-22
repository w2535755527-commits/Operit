#include <jni.h>

#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "Saba/Viewer/Viewer.h"
#include "android/AndroidAssetSupport.h"

namespace {

constexpr const char* kTag = "MmdRendererBridge";

struct RendererHandle {
    std::mutex mutex;
    std::unique_ptr<saba::Viewer> viewer;
    std::string lastError;
};

RendererHandle* FromHandle(jlong handle) {
    return reinterpret_cast<RendererHandle*>(handle);
}

void SetError(RendererHandle* handle, const std::string& error) {
    if (handle == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    if (handle->lastError != error && !error.empty()) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kTag,
            "renderer=%p error=%s",
            static_cast<void*>(handle),
            error.c_str()
        );
    }
    handle->lastError = error;
}

void ClearError(RendererHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    handle->lastError.clear();
}

std::string GetError(RendererHandle* handle) {
    if (handle == nullptr) {
        return "";
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    return handle->lastError;
}

std::string JStringToString(JNIEnv* env, jstring value) {
    if (env == nullptr || value == nullptr) {
        return "";
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return "";
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeCreateRenderer(JNIEnv*, jclass) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = new RendererHandle();
    handle->viewer = std::make_unique<saba::Viewer>();
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "create renderer handle=%p",
        static_cast<void*>(handle)
    );
    return reinterpret_cast<jlong>(handle);
#else
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeDestroyRenderer(JNIEnv*, jclass, jlong handleValue) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle == nullptr) {
        return;
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "destroy renderer handle=%p",
        static_cast<void*>(handle)
    );
    delete handle;
#else
    (void) handleValue;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeOnSurfaceCreated(
    JNIEnv* env,
    jclass,
    jlong handleValue,
    jobject assetManager
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle == nullptr || handle->viewer == nullptr) {
        return;
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "surface created handle=%p",
        static_cast<void*>(handle)
    );
    operit::androidbridge::SetAssetManager(AAssetManager_fromJava(env, assetManager));
    std::string error;
    if (!handle->viewer->OnSurfaceCreated(&error)) {
        SetError(handle, error);
    } else {
        ClearError(handle);
    }
#else
    (void) env;
    (void) handleValue;
    (void) assetManager;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeOnSurfaceChanged(
    JNIEnv*,
    jclass,
    jlong handleValue,
    jint width,
    jint height
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle == nullptr || handle->viewer == nullptr) {
        return;
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "surface changed handle=%p size=%dx%d",
        static_cast<void*>(handle),
        static_cast<int>(width),
        static_cast<int>(height)
    );
    std::string error;
    if (!handle->viewer->OnSurfaceChanged(width, height, &error)) {
        SetError(handle, error);
    } else {
        ClearError(handle);
    }
#else
    (void) handleValue;
    (void) width;
    (void) height;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeRender(JNIEnv*, jclass, jlong handleValue) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle == nullptr || handle->viewer == nullptr) {
        return JNI_FALSE;
    }

    std::string error;
    if (!handle->viewer->RenderFrame(&error)) {
        SetError(handle, error);
        return JNI_FALSE;
    }
    ClearError(handle);
    return JNI_TRUE;
#else
    (void) handleValue;
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativePause(JNIEnv*, jclass, jlong handleValue) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle != nullptr && handle->viewer != nullptr) {
        handle->viewer->Pause();
    }
#else
    (void) handleValue;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeResume(JNIEnv*, jclass, jlong handleValue) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle != nullptr && handle->viewer != nullptr) {
        handle->viewer->Resume();
    }
#else
    (void) handleValue;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetModelPath(
    JNIEnv* env,
    jclass,
    jlong handleValue,
    jstring pathModel
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle == nullptr || handle->viewer == nullptr) {
        return;
    }

    const std::string modelPath = JStringToString(env, pathModel);
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "set model handle=%p path=%s",
        static_cast<void*>(handle),
        modelPath.empty() ? "<empty>" : modelPath.c_str()
    );
    std::string error;
    if (!handle->viewer->SetModelPath(modelPath, &error)) {
        SetError(handle, error);
    } else {
        ClearError(handle);
    }
#else
    (void) env;
    (void) handleValue;
    (void) pathModel;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetAnimationState(
    JNIEnv* env,
    jclass,
    jlong handleValue,
    jstring animationName,
    jboolean isLooping
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle == nullptr || handle->viewer == nullptr) {
        return;
    }

    const std::string normalizedAnimationName = JStringToString(env, animationName);
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "set animation handle=%p name=%s looping=%d",
        static_cast<void*>(handle),
        normalizedAnimationName.empty() ? "<none>" : normalizedAnimationName.c_str(),
        isLooping == JNI_TRUE ? 1 : 0
    );
    std::string error;
    if (!handle->viewer->SetAnimationState(
            normalizedAnimationName,
            isLooping == JNI_TRUE,
            &error
        )) {
        SetError(handle, error);
    } else {
        ClearError(handle);
    }
#else
    (void) env;
    (void) handleValue;
    (void) animationName;
    (void) isLooping;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetModelRotation(
    JNIEnv*,
    jclass,
    jlong handleValue,
    jfloat rotationX,
    jfloat rotationY,
    jfloat rotationZ
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle != nullptr && handle->viewer != nullptr) {
        handle->viewer->SetModelRotation(rotationX, rotationY, rotationZ);
    }
#else
    (void) handleValue;
    (void) rotationX;
    (void) rotationY;
    (void) rotationZ;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetCameraDistanceScale(
    JNIEnv*,
    jclass,
    jlong handleValue,
    jfloat scale
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle != nullptr && handle->viewer != nullptr) {
        handle->viewer->SetCameraDistanceScale(scale);
    }
#else
    (void) handleValue;
    (void) scale;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetCameraTargetHeight(
    JNIEnv*,
    jclass,
    jlong handleValue,
    jfloat height
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle != nullptr && handle->viewer != nullptr) {
        handle->viewer->SetCameraTargetHeight(height);
    }
#else
    (void) handleValue;
    (void) height;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetLookAt(
    JNIEnv*,
    jclass,
    jlong handleValue,
    jfloat x,
    jfloat y
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle != nullptr && handle->viewer != nullptr) {
        handle->viewer->SetLookAt(x, y);
    }
#else
    (void) handleValue;
    (void) x;
    (void) y;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetAutoBlink(
    JNIEnv*,
    jclass,
    jlong handleValue,
    jboolean enable
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle != nullptr && handle->viewer != nullptr) {
        handle->viewer->SetAutoBlink(enable == JNI_TRUE);
    }
#else
    (void) handleValue;
    (void) enable;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeGetRendererLastError(
    JNIEnv* env,
    jclass,
    jlong handleValue
) {
    const std::string error = GetError(FromHandle(handleValue));
    return env->NewStringUTF(error.c_str());
}

// === Operit patch: morph (expression) introspection + control ===

JNIEXPORT jint JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeGetMorphCount(JNIEnv*, jclass, jlong handleValue) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle == nullptr || handle->viewer == nullptr) {
        return 0;
    }
    return static_cast<jint>(handle->viewer->GetMorphCount());
#else
    (void) handleValue;
    return 0;
#endif
}

JNIEXPORT jobjectArray JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeGetMorphNames(
    JNIEnv* env,
    jclass,
    jlong handleValue
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle == nullptr || handle->viewer == nullptr) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    const std::vector<std::string> names = handle->viewer->GetMorphNames();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(names.size()), stringClass, nullptr);
    for (size_t i = 0; i < names.size(); ++i) {
        jstring item = env->NewStringUTF(names[i].c_str());
        env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    return array;
#else
    (void) handleValue;
    return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetMorphWeight(
    JNIEnv* env,
    jclass,
    jlong handleValue,
    jstring name,
    jfloat weight
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle == nullptr || handle->viewer == nullptr) {
        return;
    }
    handle->viewer->SetMorphOverride(JStringToString(env, name), weight);
#else
    (void) handleValue;
    (void) name;
    (void) weight;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetMorphWeights(
    JNIEnv* env,
    jclass,
    jlong handleValue,
    jobjectArray names,
    jfloatArray weights
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle == nullptr || handle->viewer == nullptr || names == nullptr || weights == nullptr) {
        return;
    }
    const jsize nameCount = env->GetArrayLength(names);
    const jsize weightCount = env->GetArrayLength(weights);
    const jsize count = nameCount < weightCount ? nameCount : weightCount;
    std::vector<std::string> nameList;
    std::vector<float> weightList;
    nameList.reserve(count);
    weightList.reserve(count);
    std::vector<jfloat> weightBuffer(static_cast<size_t>(count));
    env->GetFloatArrayRegion(weights, 0, count, weightBuffer.data());
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        nameList.push_back(JStringToString(env, item));
        weightList.push_back(weightBuffer[static_cast<size_t>(i)]);
        if (item != nullptr) {
            env->DeleteLocalRef(item);
        }
    }
    handle->viewer->SetMorphWeights(nameList, weightList);
#else
    (void) handleValue;
    (void) names;
    (void) weights;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeClearMorphOverrides(JNIEnv*, jclass, jlong handleValue) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle != nullptr && handle->viewer != nullptr) {
        handle->viewer->ClearMorphOverrides();
    }
#else
    (void) handleValue;
#endif
}

// === Operit patch: bone (node) rotation control ===

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetNodeRotation(
    JNIEnv* env,
    jclass,
    jlong handleValue,
    jstring name,
    jfloat rx,
    jfloat ry,
    jfloat rz
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle == nullptr || handle->viewer == nullptr) {
        return;
    }
    handle->viewer->SetNodeRotation(JStringToString(env, name), rx, ry, rz);
#else
    (void) handleValue;
    (void) name;
    (void) rx;
    (void) ry;
    (void) rz;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeClearNodeRotations(JNIEnv*, jclass, jlong handleValue) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle != nullptr && handle->viewer != nullptr) {
        handle->viewer->ClearNodeRotations();
    }
#else
    (void) handleValue;
#endif
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetAutoGlance(
    JNIEnv*,
    jclass,
    jlong handleValue,
    jboolean enable
) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    auto* handle = FromHandle(handleValue);
    if (handle != nullptr && handle->viewer != nullptr) {
        handle->viewer->SetAutoGlance(enable == JNI_TRUE);
    }
#else
    (void) handleValue;
    (void) enable;
#endif
}

}  // extern "C"