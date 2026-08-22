#include "vulkan_context.h"

#include "stress_shader_spv.h"

#include <android/log.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <limits>
#include <sstream>
#include <vector>

namespace {
constexpr char kLogTag[] = "ResourceStress";
constexpr VkDeviceSize kReadbackBytes = 16;
constexpr std::uint64_t kFenceTimeoutNanos = 10ULL * 1000ULL * 1000ULL * 1000ULL;
constexpr std::uint32_t kShaderLocalSizeX = 256;

const char* resultName(VkResult result) {
    switch (result) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_NOT_READY: return "VK_NOT_READY";
        case VK_TIMEOUT: return "VK_TIMEOUT";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
        case VK_ERROR_MEMORY_MAP_FAILED: return "VK_ERROR_MEMORY_MAP_FAILED";
        case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
        case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
        default: return "VK_ERROR_UNKNOWN";
    }
}
}  // namespace

VulkanContext::~VulkanContext() {
    shutdown();
}

bool VulkanContext::initialize() {
    if (device_ != VK_NULL_HANDLE) {
        return capabilities_.supported;
    }

    lastError_.clear();
    capabilities_ = VulkanCapabilities{};

    const VkApplicationInfo applicationInfo{
        VK_STRUCTURE_TYPE_APPLICATION_INFO,
        nullptr,
        "Android Resource Stress",
        VK_MAKE_VERSION(0, 2, 0),
        "ResourceStress",
        VK_MAKE_VERSION(0, 2, 0),
        VK_API_VERSION_1_0,
    };
    const VkInstanceCreateInfo instanceCreateInfo{
        VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        nullptr,
        0,
        &applicationInfo,
        0,
        nullptr,
        0,
        nullptr,
    };
    VkResult result = vkCreateInstance(&instanceCreateInfo, nullptr, &instance_);
    if (result != VK_SUCCESS) {
        return fail("vkCreateInstance", result);
    }

    std::uint32_t deviceCount = 0;
    result = vkEnumeratePhysicalDevices(instance_, &deviceCount, nullptr);
    if (result != VK_SUCCESS) {
        shutdown();
        return fail("vkEnumeratePhysicalDevices", result);
    }
    if (deviceCount == 0) {
        shutdown();
        return fail("No Vulkan physical device is available");
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    result = vkEnumeratePhysicalDevices(instance_, &deviceCount, devices.data());
    if (result != VK_SUCCESS) {
        shutdown();
        return fail("vkEnumeratePhysicalDevices(list)", result);
    }

    VkPhysicalDeviceProperties selectedProperties{};
    VkQueueFamilyProperties selectedQueueProperties{};
    bool selected = false;
    for (VkPhysicalDevice candidate : devices) {
        std::uint32_t queueFamilyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueFamilyCount, nullptr);
        if (queueFamilyCount == 0) {
            continue;
        }
        std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(
            candidate,
            &queueFamilyCount,
            queueFamilies.data());
        for (std::uint32_t index = 0; index < queueFamilyCount; ++index) {
            if ((queueFamilies[index].queueFlags & VK_QUEUE_COMPUTE_BIT) == 0 ||
                queueFamilies[index].queueCount == 0) {
                continue;
            }
            physicalDevice_ = candidate;
            computeQueueFamilyIndex_ = index;
            selectedQueueProperties = queueFamilies[index];
            vkGetPhysicalDeviceProperties(candidate, &selectedProperties);
            selected = true;
            break;
        }
        if (selected) {
            break;
        }
    }

    if (!selected) {
        shutdown();
        return fail("Vulkan compute queue is unavailable");
    }

    capabilities_.apiVersion = selectedProperties.apiVersion;
    capabilities_.vendorId = selectedProperties.vendorID;
    capabilities_.deviceId = selectedProperties.deviceID;
    capabilities_.deviceName = selectedProperties.deviceName;
    capabilities_.computeQueueSupported = true;
    capabilities_.maxWorkGroupCount = {
        selectedProperties.limits.maxComputeWorkGroupCount[0],
        selectedProperties.limits.maxComputeWorkGroupCount[1],
        selectedProperties.limits.maxComputeWorkGroupCount[2],
    };
    capabilities_.maxWorkGroupSize = {
        selectedProperties.limits.maxComputeWorkGroupSize[0],
        selectedProperties.limits.maxComputeWorkGroupSize[1],
        selectedProperties.limits.maxComputeWorkGroupSize[2],
    };
    capabilities_.maxWorkGroupInvocations =
        selectedProperties.limits.maxComputeWorkGroupInvocations;
    timestampValidBits_ = selectedQueueProperties.timestampValidBits;
    timestampPeriodNanos_ = selectedProperties.limits.timestampPeriod;
    capabilities_.timestampSupported =
        selectedProperties.limits.timestampComputeAndGraphics == VK_TRUE &&
        timestampValidBits_ > 0 &&
        timestampPeriodNanos_ > 0.0F;

    const float queuePriority = 1.0F;
    const VkDeviceQueueCreateInfo queueCreateInfo{
        VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        nullptr,
        0,
        computeQueueFamilyIndex_,
        1,
        &queuePriority,
    };
    const VkDeviceCreateInfo deviceCreateInfo{
        VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        nullptr,
        0,
        1,
        &queueCreateInfo,
        0,
        nullptr,
        0,
        nullptr,
        nullptr,
    };
    result = vkCreateDevice(physicalDevice_, &deviceCreateInfo, nullptr, &device_);
    if (result != VK_SUCCESS) {
        shutdown();
        return fail("vkCreateDevice", result);
    }
    vkGetDeviceQueue(device_, computeQueueFamilyIndex_, 0, &computeQueue_);
    if (computeQueue_ == VK_NULL_HANDLE) {
        shutdown();
        return fail("vkGetDeviceQueue returned a null compute queue");
    }

    capabilities_.supported = true;
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "Vulkan compute ready: %s API %u.%u.%u",
        capabilities_.deviceName.c_str(),
        VK_VERSION_MAJOR(capabilities_.apiVersion),
        VK_VERSION_MINOR(capabilities_.apiVersion),
        VK_VERSION_PATCH(capabilities_.apiVersion));
    return true;
}

bool VulkanContext::prepareStressResources() {
    if (!initialize()) {
        return false;
    }
    if (pipeline_ != VK_NULL_HANDLE) {
        return true;
    }

    lastError_.clear();
    if (!createStorageResources() ||
        !createDescriptorResources() ||
        !createPipelineResources() ||
        !createCommandResources() ||
        !initializeStorageBuffer() ||
        !recordStressCommandBuffer()) {
        releaseStressResources();
        return false;
    }
    return true;
}

bool VulkanContext::submitAndWait(
    std::uint64_t* gpuWorkNanos,
    std::uint64_t* outputChecksum,
    std::uint32_t* workGroupCount) {
    if (device_ == VK_NULL_HANDLE || pipeline_ == VK_NULL_HANDLE ||
        commandBuffer_ == VK_NULL_HANDLE || fence_ == VK_NULL_HANDLE) {
        return fail("Vulkan stress resources are not initialized");
    }

    VkResult result = vkResetFences(device_, 1, &fence_);
    if (result != VK_SUCCESS) {
        return fail("vkResetFences", result);
    }
    const VkSubmitInfo submitInfo{
        VK_STRUCTURE_TYPE_SUBMIT_INFO,
        nullptr,
        0,
        nullptr,
        nullptr,
        1,
        &commandBuffer_,
        0,
        nullptr,
    };
    result = vkQueueSubmit(computeQueue_, 1, &submitInfo, fence_);
    if (result != VK_SUCCESS) {
        return fail("vkQueueSubmit", result);
    }
    result = vkWaitForFences(device_, 1, &fence_, VK_TRUE, kFenceTimeoutNanos);
    if (result != VK_SUCCESS) {
        return fail("vkWaitForFences", result);
    }

    std::uint64_t measuredNanos = 0;
    if (capabilities_.timestampSupported && queryPool_ != VK_NULL_HANDLE) {
        std::array<std::uint64_t, 2> timestamps{};
        result = vkGetQueryPoolResults(
            device_,
            queryPool_,
            0,
            static_cast<std::uint32_t>(timestamps.size()),
            sizeof(timestamps),
            timestamps.data(),
            sizeof(std::uint64_t),
            VK_QUERY_RESULT_64_BIT);
        if (result != VK_SUCCESS) {
            return fail("vkGetQueryPoolResults", result);
        }
        const std::uint64_t mask = timestampValidBits_ >= 64
            ? std::numeric_limits<std::uint64_t>::max()
            : (1ULL << timestampValidBits_) - 1ULL;
        const std::uint64_t ticks = (timestamps[1] - timestamps[0]) & mask;
        measuredNanos = static_cast<std::uint64_t>(
            static_cast<double>(ticks) * static_cast<double>(timestampPeriodNanos_));
    }

    if (gpuWorkNanos != nullptr) {
        *gpuWorkNanos = measuredNanos;
    }
    if (outputChecksum != nullptr) {
        *outputChecksum = readOutputChecksum();
    }
    if (workGroupCount != nullptr) {
        *workGroupCount = dispatchWorkGroupCount_;
    }
    return true;
}

void VulkanContext::releaseStressResources() {
    if (device_ == VK_NULL_HANDLE) {
        return;
    }
    vkDeviceWaitIdle(device_);

    if (readbackMapping_ != nullptr) {
        vkUnmapMemory(device_, readbackMemory_);
        readbackMapping_ = nullptr;
    }
    if (queryPool_ != VK_NULL_HANDLE) {
        vkDestroyQueryPool(device_, queryPool_, nullptr);
        queryPool_ = VK_NULL_HANDLE;
    }
    if (fence_ != VK_NULL_HANDLE) {
        vkDestroyFence(device_, fence_, nullptr);
        fence_ = VK_NULL_HANDLE;
    }
    if (commandPool_ != VK_NULL_HANDLE) {
        vkDestroyCommandPool(device_, commandPool_, nullptr);
        commandPool_ = VK_NULL_HANDLE;
        commandBuffer_ = VK_NULL_HANDLE;
    }
    if (pipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device_, pipeline_, nullptr);
        pipeline_ = VK_NULL_HANDLE;
    }
    if (pipelineLayout_ != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
        pipelineLayout_ = VK_NULL_HANDLE;
    }
    if (shaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device_, shaderModule_, nullptr);
        shaderModule_ = VK_NULL_HANDLE;
    }
    if (descriptorPool_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
        descriptorPool_ = VK_NULL_HANDLE;
        descriptorSet_ = VK_NULL_HANDLE;
    }
    if (descriptorSetLayout_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(device_, descriptorSetLayout_, nullptr);
        descriptorSetLayout_ = VK_NULL_HANDLE;
    }
    if (readbackBuffer_ != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, readbackBuffer_, nullptr);
        readbackBuffer_ = VK_NULL_HANDLE;
    }
    if (readbackMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device_, readbackMemory_, nullptr);
        readbackMemory_ = VK_NULL_HANDLE;
    }
    if (storageBuffer_ != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, storageBuffer_, nullptr);
        storageBuffer_ = VK_NULL_HANDLE;
    }
    if (storageMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device_, storageMemory_, nullptr);
        storageMemory_ = VK_NULL_HANDLE;
    }
    dispatchWorkGroupCount_ = 0;
}

void VulkanContext::shutdown() {
    releaseStressResources();
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
    }
    computeQueue_ = VK_NULL_HANDLE;
    physicalDevice_ = VK_NULL_HANDLE;
    if (instance_ != VK_NULL_HANDLE) {
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }
    capabilities_ = VulkanCapabilities{};
    timestampValidBits_ = 0;
    timestampPeriodNanos_ = 0.0F;
}

const VulkanCapabilities& VulkanContext::capabilities() const {
    return capabilities_;
}

const std::string& VulkanContext::lastError() const {
    return lastError_;
}

bool VulkanContext::fail(const char* operation, VkResult result) {
    std::ostringstream message;
    message << operation << " failed: " << resultName(result) << " (" << result << ')';
    return fail(message.str());
}

bool VulkanContext::fail(const std::string& message) {
    lastError_ = message;
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", lastError_.c_str());
    return false;
}

std::uint32_t VulkanContext::findMemoryType(
    std::uint32_t typeBits,
    VkMemoryPropertyFlags required,
    VkMemoryPropertyFlags preferred,
    bool* found) const {
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memoryProperties);
    std::uint32_t fallback = 0;
    bool hasFallback = false;
    for (std::uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
        if ((typeBits & (1U << index)) == 0) {
            continue;
        }
        const VkMemoryPropertyFlags flags = memoryProperties.memoryTypes[index].propertyFlags;
        if ((flags & required) != required) {
            continue;
        }
        if ((flags & preferred) == preferred) {
            *found = true;
            return index;
        }
        if (!hasFallback) {
            fallback = index;
            hasFallback = true;
        }
    }
    *found = hasFallback;
    return fallback;
}

bool VulkanContext::createBuffer(
    VkDeviceSize size,
    VkBufferUsageFlags usage,
    VkMemoryPropertyFlags required,
    VkMemoryPropertyFlags preferred,
    VkBuffer* buffer,
    VkDeviceMemory* memory,
    bool* hostCoherent) {
    const VkBufferCreateInfo bufferCreateInfo{
        VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        nullptr,
        0,
        size,
        usage,
        VK_SHARING_MODE_EXCLUSIVE,
        0,
        nullptr,
    };
    VkResult result = vkCreateBuffer(device_, &bufferCreateInfo, nullptr, buffer);
    if (result != VK_SUCCESS) {
        return fail("vkCreateBuffer", result);
    }

    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(device_, *buffer, &requirements);
    bool found = false;
    const std::uint32_t memoryTypeIndex = findMemoryType(
        requirements.memoryTypeBits,
        required,
        preferred,
        &found);
    if (!found) {
        return fail("No compatible Vulkan memory type is available");
    }

    const VkMemoryAllocateInfo allocateInfo{
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        nullptr,
        requirements.size,
        memoryTypeIndex,
    };
    result = vkAllocateMemory(device_, &allocateInfo, nullptr, memory);
    if (result != VK_SUCCESS) {
        return fail("vkAllocateMemory", result);
    }
    result = vkBindBufferMemory(device_, *buffer, *memory, 0);
    if (result != VK_SUCCESS) {
        return fail("vkBindBufferMemory", result);
    }

    if (hostCoherent != nullptr) {
        VkPhysicalDeviceMemoryProperties memoryProperties{};
        vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memoryProperties);
        *hostCoherent =
            (memoryProperties.memoryTypes[memoryTypeIndex].propertyFlags &
             VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    }
    return true;
}

bool VulkanContext::createStorageResources() {
    if (!createBuffer(
            kStorageBufferBytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            &storageBuffer_,
            &storageMemory_)) {
        return false;
    }
    if (!createBuffer(
            kReadbackBytes,
            VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            &readbackBuffer_,
            &readbackMemory_,
            &readbackHostCoherent_)) {
        return false;
    }
    const VkResult result = vkMapMemory(
        device_,
        readbackMemory_,
        0,
        kReadbackBytes,
        0,
        &readbackMapping_);
    if (result != VK_SUCCESS) {
        return fail("vkMapMemory(readback)", result);
    }
    return true;
}

bool VulkanContext::createDescriptorResources() {
    const VkDescriptorSetLayoutBinding binding{
        0,
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
        1,
        VK_SHADER_STAGE_COMPUTE_BIT,
        nullptr,
    };
    const VkDescriptorSetLayoutCreateInfo layoutCreateInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        nullptr,
        0,
        1,
        &binding,
    };
    VkResult result = vkCreateDescriptorSetLayout(
        device_,
        &layoutCreateInfo,
        nullptr,
        &descriptorSetLayout_);
    if (result != VK_SUCCESS) {
        return fail("vkCreateDescriptorSetLayout", result);
    }

    const VkDescriptorPoolSize poolSize{
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
        1,
    };
    const VkDescriptorPoolCreateInfo poolCreateInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        nullptr,
        0,
        1,
        1,
        &poolSize,
    };
    result = vkCreateDescriptorPool(device_, &poolCreateInfo, nullptr, &descriptorPool_);
    if (result != VK_SUCCESS) {
        return fail("vkCreateDescriptorPool", result);
    }

    const VkDescriptorSetAllocateInfo allocateInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        nullptr,
        descriptorPool_,
        1,
        &descriptorSetLayout_,
    };
    result = vkAllocateDescriptorSets(device_, &allocateInfo, &descriptorSet_);
    if (result != VK_SUCCESS) {
        return fail("vkAllocateDescriptorSets", result);
    }

    const VkDescriptorBufferInfo bufferInfo{
        storageBuffer_,
        0,
        kStorageBufferBytes,
    };
    const VkWriteDescriptorSet descriptorWrite{
        VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        nullptr,
        descriptorSet_,
        0,
        0,
        1,
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
        nullptr,
        &bufferInfo,
        nullptr,
    };
    vkUpdateDescriptorSets(device_, 1, &descriptorWrite, 0, nullptr);
    return true;
}

bool VulkanContext::createPipelineResources() {
    if (kStressShaderSpirvSize == 0 || kStressShaderSpirvSize % sizeof(std::uint32_t) != 0) {
        return fail("Embedded SPIR-V has an invalid size");
    }
    const VkShaderModuleCreateInfo shaderCreateInfo{
        VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        nullptr,
        0,
        kStressShaderSpirvSize,
        reinterpret_cast<const std::uint32_t*>(kStressShaderSpirv),
    };
    VkResult result = vkCreateShaderModule(
        device_,
        &shaderCreateInfo,
        nullptr,
        &shaderModule_);
    if (result != VK_SUCCESS) {
        return fail("vkCreateShaderModule", result);
    }

    const VkPipelineLayoutCreateInfo pipelineLayoutCreateInfo{
        VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        nullptr,
        0,
        1,
        &descriptorSetLayout_,
        0,
        nullptr,
    };
    result = vkCreatePipelineLayout(
        device_,
        &pipelineLayoutCreateInfo,
        nullptr,
        &pipelineLayout_);
    if (result != VK_SUCCESS) {
        return fail("vkCreatePipelineLayout", result);
    }

    const VkPipelineShaderStageCreateInfo stageCreateInfo{
        VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
        nullptr,
        0,
        VK_SHADER_STAGE_COMPUTE_BIT,
        shaderModule_,
        "main",
        nullptr,
    };
    const VkComputePipelineCreateInfo pipelineCreateInfo{
        VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        nullptr,
        0,
        stageCreateInfo,
        pipelineLayout_,
        VK_NULL_HANDLE,
        0,
    };
    result = vkCreateComputePipelines(
        device_,
        VK_NULL_HANDLE,
        1,
        &pipelineCreateInfo,
        nullptr,
        &pipeline_);
    if (result != VK_SUCCESS) {
        return fail("vkCreateComputePipelines", result);
    }
    return true;
}

bool VulkanContext::createCommandResources() {
    const VkCommandPoolCreateInfo commandPoolCreateInfo{
        VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        nullptr,
        VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        computeQueueFamilyIndex_,
    };
    VkResult result = vkCreateCommandPool(
        device_,
        &commandPoolCreateInfo,
        nullptr,
        &commandPool_);
    if (result != VK_SUCCESS) {
        return fail("vkCreateCommandPool", result);
    }

    const VkCommandBufferAllocateInfo commandBufferAllocateInfo{
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        nullptr,
        commandPool_,
        VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        1,
    };
    result = vkAllocateCommandBuffers(device_, &commandBufferAllocateInfo, &commandBuffer_);
    if (result != VK_SUCCESS) {
        return fail("vkAllocateCommandBuffers", result);
    }

    const VkFenceCreateInfo fenceCreateInfo{
        VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
        nullptr,
        0,
    };
    result = vkCreateFence(device_, &fenceCreateInfo, nullptr, &fence_);
    if (result != VK_SUCCESS) {
        return fail("vkCreateFence", result);
    }

    if (capabilities_.timestampSupported) {
        const VkQueryPoolCreateInfo queryPoolCreateInfo{
            VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
            nullptr,
            0,
            VK_QUERY_TYPE_TIMESTAMP,
            2,
            0,
        };
        result = vkCreateQueryPool(device_, &queryPoolCreateInfo, nullptr, &queryPool_);
        if (result != VK_SUCCESS) {
            capabilities_.timestampSupported = false;
            queryPool_ = VK_NULL_HANDLE;
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "Timestamp query disabled: %s",
                resultName(result));
        }
    }
    return true;
}

bool VulkanContext::initializeStorageBuffer() {
    VkResult result = vkResetCommandBuffer(commandBuffer_, 0);
    if (result != VK_SUCCESS) {
        return fail("vkResetCommandBuffer(init)", result);
    }
    const VkCommandBufferBeginInfo beginInfo{
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        nullptr,
        VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
        nullptr,
    };
    result = vkBeginCommandBuffer(commandBuffer_, &beginInfo);
    if (result != VK_SUCCESS) {
        return fail("vkBeginCommandBuffer(init)", result);
    }
    vkCmdFillBuffer(commandBuffer_, storageBuffer_, 0, kStorageBufferBytes, 0x3F000000U);
    const VkBufferMemoryBarrier barrier{
        VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        nullptr,
        VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
        VK_QUEUE_FAMILY_IGNORED,
        VK_QUEUE_FAMILY_IGNORED,
        storageBuffer_,
        0,
        kStorageBufferBytes,
    };
    vkCmdPipelineBarrier(
        commandBuffer_,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0,
        nullptr,
        1,
        &barrier,
        0,
        nullptr);
    result = vkEndCommandBuffer(commandBuffer_);
    if (result != VK_SUCCESS) {
        return fail("vkEndCommandBuffer(init)", result);
    }

    const VkSubmitInfo submitInfo{
        VK_STRUCTURE_TYPE_SUBMIT_INFO,
        nullptr,
        0,
        nullptr,
        nullptr,
        1,
        &commandBuffer_,
        0,
        nullptr,
    };
    result = vkResetFences(device_, 1, &fence_);
    if (result != VK_SUCCESS) {
        return fail("vkResetFences(init)", result);
    }
    result = vkQueueSubmit(computeQueue_, 1, &submitInfo, fence_);
    if (result != VK_SUCCESS) {
        return fail("vkQueueSubmit(init)", result);
    }
    result = vkWaitForFences(device_, 1, &fence_, VK_TRUE, kFenceTimeoutNanos);
    if (result != VK_SUCCESS) {
        return fail("vkWaitForFences(init)", result);
    }
    return true;
}

bool VulkanContext::recordStressCommandBuffer() {
    const std::uint64_t elementCount = kStorageBufferBytes / (sizeof(float) * 4ULL);
    const std::uint64_t workGroupCount =
        (elementCount + kShaderLocalSizeX - 1ULL) / kShaderLocalSizeX;
    if (workGroupCount == 0 ||
        workGroupCount > capabilities_.maxWorkGroupCount[0] ||
        workGroupCount > std::numeric_limits<std::uint32_t>::max()) {
        return fail("Storage buffer requires an unsupported compute workgroup count");
    }
    dispatchWorkGroupCount_ = static_cast<std::uint32_t>(workGroupCount);

    VkResult result = vkResetCommandBuffer(commandBuffer_, 0);
    if (result != VK_SUCCESS) {
        return fail("vkResetCommandBuffer(stress)", result);
    }
    const VkCommandBufferBeginInfo beginInfo{
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        nullptr,
        VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT,
        nullptr,
    };
    result = vkBeginCommandBuffer(commandBuffer_, &beginInfo);
    if (result != VK_SUCCESS) {
        return fail("vkBeginCommandBuffer(stress)", result);
    }

    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdResetQueryPool(commandBuffer_, queryPool_, 0, 2);
        vkCmdWriteTimestamp(
            commandBuffer_,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            queryPool_,
            0);
    }

    const VkBufferMemoryBarrier preComputeBarrier{
        VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        nullptr,
        VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT,
        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
        VK_QUEUE_FAMILY_IGNORED,
        VK_QUEUE_FAMILY_IGNORED,
        storageBuffer_,
        0,
        kStorageBufferBytes,
    };
    vkCmdPipelineBarrier(
        commandBuffer_,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0,
        nullptr,
        1,
        &preComputeBarrier,
        0,
        nullptr);

    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(
        commandBuffer_,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        pipelineLayout_,
        0,
        1,
        &descriptorSet_,
        0,
        nullptr);
    vkCmdDispatch(commandBuffer_, dispatchWorkGroupCount_, 1, 1);

    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(
            commandBuffer_,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            queryPool_,
            1);
    }

    const VkBufferMemoryBarrier readbackBarrier{
        VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        nullptr,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_TRANSFER_READ_BIT,
        VK_QUEUE_FAMILY_IGNORED,
        VK_QUEUE_FAMILY_IGNORED,
        storageBuffer_,
        0,
        kReadbackBytes,
    };
    vkCmdPipelineBarrier(
        commandBuffer_,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        0,
        nullptr,
        1,
        &readbackBarrier,
        0,
        nullptr);
    const VkBufferCopy copyRegion{0, 0, kReadbackBytes};
    vkCmdCopyBuffer(commandBuffer_, storageBuffer_, readbackBuffer_, 1, &copyRegion);

    result = vkEndCommandBuffer(commandBuffer_);
    if (result != VK_SUCCESS) {
        return fail("vkEndCommandBuffer(stress)", result);
    }
    return true;
}

std::uint64_t VulkanContext::readOutputChecksum() {
    if (readbackMapping_ == nullptr) {
        return 0;
    }
    if (!readbackHostCoherent_) {
        const VkMappedMemoryRange range{
            VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
            nullptr,
            readbackMemory_,
            0,
            VK_WHOLE_SIZE,
        };
        if (vkInvalidateMappedMemoryRanges(device_, 1, &range) != VK_SUCCESS) {
            return 0;
        }
    }

    std::array<std::uint32_t, 4> words{};
    std::memcpy(words.data(), readbackMapping_, sizeof(words));
    std::uint64_t hash = 1469598103934665603ULL;
    for (std::uint32_t word : words) {
        hash ^= word;
        hash *= 1099511628211ULL;
    }
    return hash;
}
