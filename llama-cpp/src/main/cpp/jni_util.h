#pragma once

#include <jni.h>
#include <string>
#include <exception>

inline void throwJava(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

// GetStringUTFChars returns JNI's Modified UTF-8 (CESU-8), not standard UTF-8: a
// supplementary-plane character comes out as a pair of three-byte surrogate encodings, and an
// embedded NUL comes out as the two-byte C0 80 overlong form. That is fine for a value this code
// never hands to a strict UTF-8 consumer, such as a filesystem path, but wrong for arbitrary
// text such as user chat content. Do not reuse this for that; carry it as a jbyteArray instead
// (see byteArrayToUtf8 / utf8ToByteArray below).
inline std::string jstringToUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return std::string();
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return std::string();
    }
    // Releases the JVM-owned buffer even if constructing the std::string below throws,
    // since the destructor still runs during stack unwinding.
    struct ReleaseGuard {
        JNIEnv *env;
        jstring value;
        const char *chars;
        ~ReleaseGuard() { env->ReleaseStringUTFChars(value, chars); }
    } guard{env, value, chars};
    return std::string(chars);
}

// Copies a Java byte[] holding UTF-8 text into a std::string. Unlike jstringToUtf8, this is
// standard UTF-8 exactly as the caller encoded it: no CESU-8, no overlong forms, safe to hand to
// a strict UTF-8 parser such as nlohmann::json.
inline std::string byteArrayToUtf8(JNIEnv *env, jbyteArray value) {
    if (value == nullptr) {
        return std::string();
    }
    const jsize len = env->GetArrayLength(value);
    std::string result(static_cast<size_t>(len), '\0');
    if (len > 0) {
        env->GetByteArrayRegion(value, 0, len, reinterpret_cast<jbyte *>(result.data()));
    }
    return result;
}

// Copies a std::string holding UTF-8 text into a new Java byte[]. The symmetric return path:
// NewStringUTF requires Modified UTF-8, which standard UTF-8 (what nlohmann::json::dump
// produces) is not in general, so text that may contain supplementary-plane characters must
// cross this boundary as bytes, not as a jstring.
inline jbyteArray utf8ToByteArray(JNIEnv *env, const std::string &value) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result != nullptr && !value.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()),
                                 reinterpret_cast<const jbyte *>(value.data()));
    }
    return result;
}

// Wraps a JNI body so no C++ exception can cross the boundary.
#define JNI_GUARD(env, failValue, body)                          \
    try {                                                        \
        body                                                     \
    } catch (const std::exception &e) {                          \
        throwJava(env, e.what());                                \
        return failValue;                                        \
    } catch (...) {                                              \
        throwJava(env, "unknown native error");                  \
        return failValue;                                        \
    }

// Same as JNI_GUARD, for void-returning entry points where there is no failValue.
#define JNI_GUARD_VOID(env, body)                                \
    try {                                                        \
        body                                                     \
    } catch (const std::exception &e) {                          \
        throwJava(env, e.what());                                \
    } catch (...) {                                              \
        throwJava(env, "unknown native error");                  \
    }
