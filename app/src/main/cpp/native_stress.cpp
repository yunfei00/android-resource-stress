#include <jni.h>

#include <cstdint>
#include <string>

#include "cpu_stress.h"
#include "gpu_stress.h"
#include "memory_stress.h"

namespace {
CpuStress gCpuStress;
MemoryStress gMemoryStress;
GpuStress gGpuStress;

jstring toJavaString(JNIEnv* environment, const std::string& value) {
    return environment->NewStringUTF(value.c_str());
}

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

jlong startMemoryStressSafely(
        JNIEnv*, jclass, jlong targetBytes, jlong minimumAvailableBytes) {
    return static_cast<jlong>(gMemoryStress.start(
        static_cast<std::int64_t>(targetBytes),
        static_cast<std::int64_t>(minimumAvailableBytes)));
}

void stopMemoryStress(JNIEnv*, jclass) {
    gMemoryStress.stop();
}

jboolean isMemoryStressRunning(JNIEnv*, jclass) {
    return gMemoryStress.isRunning() ? JNI_TRUE : JNI_FALSE;
}

jlong getAllocatedMemoryBytes(JNIEnv*, jclass) {
    return static_cast<jlong>(gMemoryStress.allocatedBytes());
}

jlong getProcessedMemoryBytes(JNIEnv*, jclass) {
    return static_cast<jlong>(gMemoryStress.processedBytes());
}

jboolean initializeGpu(JNIEnv*, jclass) {
    return gGpuStress.initialize() ? JNI_TRUE : JNI_FALSE;
}

jboolean isGpuStressSupported(JNIEnv*, jclass) {
    return gGpuStress.capabilities().supported ? JNI_TRUE : JNI_FALSE;
}

jstring getGpuDeviceName(JNIEnv* environment, jclass) {
    return toJavaString(environment, gGpuStress.capabilities().deviceName);
}

jint getGpuApiVersion(JNIEnv*, jclass) {
    return static_cast<jint>(gGpuStress.capabilities().apiVersion);
}

jlong getGpuVendorId(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.capabilities().vendorId);
}

jlong getGpuDeviceId(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.capabilities().deviceId);
}

jboolean isGpuComputeQueueSupported(JNIEnv*, jclass) {
    return gGpuStress.capabilities().computeQueueSupported ? JNI_TRUE : JNI_FALSE;
}

jlong getGpuMaxWorkGroupCountX(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.capabilities().maxWorkGroupCount[0]);
}

jlong getGpuMaxWorkGroupCountY(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.capabilities().maxWorkGroupCount[1]);
}

jlong getGpuMaxWorkGroupCountZ(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.capabilities().maxWorkGroupCount[2]);
}

jlong getGpuMaxWorkGroupSizeX(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.capabilities().maxWorkGroupSize[0]);
}

jlong getGpuMaxWorkGroupSizeY(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.capabilities().maxWorkGroupSize[1]);
}

jlong getGpuMaxWorkGroupSizeZ(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.capabilities().maxWorkGroupSize[2]);
}

jlong getGpuMaxWorkGroupInvocations(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.capabilities().maxWorkGroupInvocations);
}

jboolean isGpuTimestampSupported(JNIEnv*, jclass) {
    return gGpuStress.capabilities().timestampSupported ? JNI_TRUE : JNI_FALSE;
}

jlong getGpuBufferBytes(JNIEnv*, jclass) {
    return static_cast<jlong>(VulkanContext::kStorageBufferBytes);
}

jboolean startGpuStress(JNIEnv*, jclass, jint targetLoadPercent) {
    return gGpuStress.start(targetLoadPercent) ? JNI_TRUE : JNI_FALSE;
}

void stopGpuStress(JNIEnv*, jclass) {
    gGpuStress.stop();
}

void shutdownGpu(JNIEnv*, jclass) {
    gGpuStress.shutdown();
}

jint getGpuStressStatus(JNIEnv*, jclass) {
    return static_cast<jint>(gGpuStress.status());
}

jlong getGpuDispatchCount(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.dispatchCount());
}

jlong getGpuWorkGroupCount(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.workGroupCount());
}

jlong getGpuWorkTimeNanos(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.lastGpuWorkNanos());
}

jlong getGpuOutputChecksum(JNIEnv*, jclass) {
    return static_cast<jlong>(gGpuStress.outputChecksum());
}

jstring getGpuLastError(JNIEnv* environment, jclass) {
    return toJavaString(environment, gGpuStress.lastError());
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
    {const_cast<char*>("startMemoryStressSafely"), const_cast<char*>("(JJ)J"),
     reinterpret_cast<void*>(startMemoryStressSafely)},
    {const_cast<char*>("stopMemoryStress"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(stopMemoryStress)},
    {const_cast<char*>("isMemoryStressRunning"), const_cast<char*>("()Z"),
     reinterpret_cast<void*>(isMemoryStressRunning)},
    {const_cast<char*>("getAllocatedMemoryBytes"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getAllocatedMemoryBytes)},
    {const_cast<char*>("getProcessedMemoryBytes"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getProcessedMemoryBytes)},
    {const_cast<char*>("initializeGpu"), const_cast<char*>("()Z"),
     reinterpret_cast<void*>(initializeGpu)},
    {const_cast<char*>("isGpuStressSupported"), const_cast<char*>("()Z"),
     reinterpret_cast<void*>(isGpuStressSupported)},
    {const_cast<char*>("getGpuDeviceName"), const_cast<char*>("()Ljava/lang/String;"),
     reinterpret_cast<void*>(getGpuDeviceName)},
    {const_cast<char*>("getGpuApiVersion"), const_cast<char*>("()I"),
     reinterpret_cast<void*>(getGpuApiVersion)},
    {const_cast<char*>("getGpuVendorId"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuVendorId)},
    {const_cast<char*>("getGpuDeviceId"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuDeviceId)},
    {const_cast<char*>("isGpuComputeQueueSupported"), const_cast<char*>("()Z"),
     reinterpret_cast<void*>(isGpuComputeQueueSupported)},
    {const_cast<char*>("getGpuMaxWorkGroupCountX"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuMaxWorkGroupCountX)},
    {const_cast<char*>("getGpuMaxWorkGroupCountY"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuMaxWorkGroupCountY)},
    {const_cast<char*>("getGpuMaxWorkGroupCountZ"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuMaxWorkGroupCountZ)},
    {const_cast<char*>("getGpuMaxWorkGroupSizeX"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuMaxWorkGroupSizeX)},
    {const_cast<char*>("getGpuMaxWorkGroupSizeY"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuMaxWorkGroupSizeY)},
    {const_cast<char*>("getGpuMaxWorkGroupSizeZ"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuMaxWorkGroupSizeZ)},
    {const_cast<char*>("getGpuMaxWorkGroupInvocations"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuMaxWorkGroupInvocations)},
    {const_cast<char*>("isGpuTimestampSupported"), const_cast<char*>("()Z"),
     reinterpret_cast<void*>(isGpuTimestampSupported)},
    {const_cast<char*>("getGpuBufferBytes"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuBufferBytes)},
    {const_cast<char*>("startGpuStress"), const_cast<char*>("(I)Z"),
     reinterpret_cast<void*>(startGpuStress)},
    {const_cast<char*>("stopGpuStress"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(stopGpuStress)},
    {const_cast<char*>("shutdownGpu"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(shutdownGpu)},
    {const_cast<char*>("getGpuStressStatus"), const_cast<char*>("()I"),
     reinterpret_cast<void*>(getGpuStressStatus)},
    {const_cast<char*>("getGpuDispatchCount"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuDispatchCount)},
    {const_cast<char*>("getGpuWorkGroupCount"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuWorkGroupCount)},
    {const_cast<char*>("getGpuWorkTimeNanos"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuWorkTimeNanos)},
    {const_cast<char*>("getGpuOutputChecksum"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(getGpuOutputChecksum)},
    {const_cast<char*>("getGpuLastError"), const_cast<char*>("()Ljava/lang/String;"),
     reinterpret_cast<void*>(getGpuLastError)},
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
    gGpuStress.shutdown();
}
