/*
 * OkcarOS link stack — native half.
 *
 * Small on purpose: it gives the Java side a place to put code that must live
 * next to the kernel (the /dev/okcar role gate, and later the iAP2 bulk-endpoint
 * transport over the head unit's vendor interface), and it provides an
 * independent implementation of the iAP2 checksum so the self test can prove the
 * Java codec is right.
 *
 * Protocol reference: the head-unit capture notes §9.1 and the head unit's
 * libiPodForiAP2.so (exeed findings A §5.8.1).
 */

#include <jni.h>
#include <android/log.h>
#include <stdint.h>

#define LOG_TAG "OkCarLinkJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char kVersion[] = "okcarlink-jni/0.1 (session 6 skeleton)";

/* iAP2 checksum: two's complement of the 8-bit sum of a byte range. */
jint iap2Checksum(const uint8_t* data, jint offset, jint length) {
    uint32_t sum = 0;
    for (jint i = 0; i < length; ++i) {
        sum += data[offset + i];
    }
    return static_cast<jint>(static_cast<uint8_t>(0u - sum));
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_okcar_link_NativeBridge_nativeVersion(JNIEnv* env, jclass /* clazz */) {
    LOGI("nativeVersion()");
    return env->NewStringUTF(kVersion);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_okcar_link_NativeBridge_nativeChecksum(JNIEnv* env, jclass /* clazz */,
                                                jbyteArray array, jint offset, jint length) {
    if (array == nullptr || offset < 0 || length < 0) {
        return 0;
    }
    const jsize size = env->GetArrayLength(array);
    if (offset + length > size) {
        LOGI("nativeChecksum: range [%d,%d) exceeds array length %d", offset, offset + length,
             size);
        return 0;
    }
    jbyte* bytes = env->GetByteArrayElements(array, nullptr);
    if (bytes == nullptr) {
        return 0;
    }
    const jint result = iap2Checksum(reinterpret_cast<const uint8_t*>(bytes), offset, length);
    env->ReleaseByteArrayElements(array, bytes, JNI_ABORT);
    return result;
}
