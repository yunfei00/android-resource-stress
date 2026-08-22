#include <jni.h>

#include <cstdint>

#include "cpu_stress.h"
#include "memory_stress.h"

namespace {
CpuStress gCpuStress;
MemoryStress gMemoryStress;

jboolean startCpuStress(JNIEnv*, jclass, jint threadCount, jint targetLoadPercent) {
    return gCpuStress.start(threadCount, targetLoadPercent) ? JNI_TRUE : JNI_FALSE;
}

void stopCpuStress(JNIEnv*, jclass) {
    gCpuStress.stop();
}

jint getCpuStressThreadCount(JNIEnv*, jclass) {
    return static_cast<jint>(gCpuStress.threadCount());
}

jlong startMemoryStress(JNIEnv*, jclass, jlong targetBytes) {
    return static_cast<jlong>(
        gMemoryStress.start(static_cast<std::int64_t>(targetBytes)));
}

void stopMemoryStress(JNIEnv*, jclass) {
    gMemoryStress.stop();
}

jlong getAllocatedMemoryBytes(JNIEnv*, jclass) {
    return static_cast<jlong>(gMemoryStress.allocatedBytes());
}

jlong getProcessedMemoryBytes(JNIEnv*, jclass) {
    return static_cast<jlong>(gMemoryStress.processedBytes());
}

JNINativeMethod kMethods[] = {
    {const_cast<char*>("startCpuStress"), const_cast<char*>("(II)Z"),
     reinterpret_cast<void*>(startCpuStress)},
    {const_cast<char*>("stopCpuStress"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(stopCpuStress)},
    {const_cast<char*>("getCpuStressThreadCount"), const_cast<char*>("()I"),
     reinterpret_cast<void*>(getCpuStressThreadCount)},
    {const_cast<char*>("startMemoryStress"), const_cast<char*>("(J)J"),
     reinterpret_cast<void*>(startMemoryStress)},
    {const_cast<char*>("stopMemoryStress"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(stopMemoryStress)},
    {const_cast<char*>("getAllocatedMemoryBytes"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getAllocatedMemoryBytes)},
    {const_cast<char*>("getProcessedMemoryBytes"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getProcessedMemoryBytes)},
};
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* virtualMachine, void*) {
    JNIEnv* environment = nullptr;
    if (virtualMachine->GetEnv(
            reinterpret_cast<void**>(&environment),
            JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass nativeStressClass = environment->FindClass(
        "com/androidresourcestress/NativeStress");
    if (nativeStressClass == nullptr) {
        return JNI_ERR;
    }
    const jint methodCount = static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0]));
    if (environment->RegisterNatives(nativeStressClass, kMethods, methodCount) != JNI_OK) {
        environment->DeleteLocalRef(nativeStressClass);
        return JNI_ERR;
    }
    environment->DeleteLocalRef(nativeStressClass);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    gCpuStress.stop();
    gMemoryStress.stop();
}
