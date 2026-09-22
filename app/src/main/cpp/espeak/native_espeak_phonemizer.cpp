#include <jni.h>

#include <espeak-ng/speak_lib.h>
#include "translate.h"

#include <mutex>
#include <string>

namespace {
std::mutex g_espeak_mutex;
bool g_initialized = false;

std::string to_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring error_string(JNIEnv* env, const char* message) {
    return env->NewStringUTF(message);
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_operit_api_voice_EspeakPhonemizer_nativeInitialize(
        JNIEnv* env, jobject, jstring data_parent_path) {
    const std::string data_path = to_utf8(env, data_parent_path);
    if (data_path.empty()) {
        return error_string(env, "eSpeak-ng data path is empty");
    }

    std::lock_guard<std::mutex> lock(g_espeak_mutex);
    if (g_initialized) {
        return nullptr;
    }

    const int sample_rate = espeak_Initialize(
            AUDIO_OUTPUT_SYNCHRONOUS,
            0,
            data_path.c_str(),
            espeakINITIALIZE_DONT_EXIT);
    if (sample_rate <= 0) {
        const std::string message =
                "Unable to initialize eSpeak-ng data at " + data_path;
        return error_string(env, message.c_str());
    }

    g_initialized = true;
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_operit_api_voice_EspeakPhonemizer_nativePhonemize(
        JNIEnv* env, jobject, jstring text, jstring voice) {
    const std::string input = to_utf8(env, text);
    const std::string voice_name = to_utf8(env, voice);
    if (input.empty() || voice_name.empty()) {
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_espeak_mutex);
    if (!g_initialized || espeak_SetVoiceByName(voice_name.c_str()) != EE_OK) {
        return nullptr;
    }

    const char* cursor = input.c_str();
    std::string phonemes;
    while (cursor != nullptr) {
        int terminator = 0;
        const char* clause = espeak_TextToPhonemesWithTerminator(
                reinterpret_cast<const void**>(&cursor),
                espeakCHARS_UTF8,
                0x02,
                &terminator);
        if (clause == nullptr) {
            return nullptr;
        }
        phonemes.append(clause);

        const int punctuation = terminator & 0x000FFFFF;
        if (punctuation == CLAUSE_PERIOD) {
            phonemes.push_back('.');
        } else if (punctuation == CLAUSE_QUESTION) {
            phonemes.push_back('?');
        } else if (punctuation == CLAUSE_EXCLAMATION) {
            phonemes.push_back('!');
        } else if (punctuation == CLAUSE_COMMA) {
            phonemes.append(", ");
        } else if (punctuation == CLAUSE_COLON) {
            phonemes.append(": ");
        } else if (punctuation == CLAUSE_SEMICOLON) {
            phonemes.append("; ");
        }
    }

    return env->NewStringUTF(phonemes.c_str());
}