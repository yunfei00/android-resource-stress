#pragma once

#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>

struct VulkanCapabilities {
    bool supported = false;
    bool computeQueueSupported = false;
    bool timestampSupported = false;
    std::uint32_t apiVersion = 0;
    std::uint32_t vendorId = 0;
    std::uint32_t deviceId = 0;
    std::string deviceName = "Unavailable";
    std::array<std::uint32_t, 3> maxWorkGroupCount{};
    std::array<std::uint32_t, 3> maxWorkGroupSize{};
    std::uint32_t maxWorkGroupInvocations = 0;
};

class VulkanContext final {
public:
    static constexpr VkDeviceSize kStorageBufferBytes = 64ULL * 1024ULL * 1024ULL;
    static constexpr std::size_t kInFlightBatchCount = 3;

    VulkanContext() = default;
    ~VulkanContext();

    VulkanContext(const VulkanContext&) = delete;
    VulkanContext& operator=(const VulkanContext&) = delete;

    bool initialize();
    bool prepareStressResources();
    bool submitAndWait(
        std::uint64_t* gpuWorkNanos,
        std::uint64_t* outputChecksum,
        std::uint32_t* workGroupCount);
    void releaseStressResources();
    void shutdown();

    const VulkanCapabilities& capabilities() const;
    const std::string& lastError() const;

private:
    bool fail(const char* operation, VkResult result);
    bool fail(const std::string& message);
    std::uint32_t findMemoryType(
        std::uint32_t typeBits,
        VkMemoryPropertyFlags required,
        VkMemoryPropertyFlags preferred,
        bool* found) const;
    bool createBuffer(
        VkDeviceSize size,
        VkBufferUsageFlags usage,
        VkMemoryPropertyFlags required,
        VkMemoryPropertyFlags preferred,
        VkBuffer* buffer,
        VkDeviceMemory* memory,
        bool* hostCoherent = nullptr);
    bool createStorageResources();
    bool createDescriptorResources();
    bool createPipelineResources();
    bool createCommandResources();
    bool initializeStorageBuffer();
    bool recordStressCommandBuffers();
    bool recordStressCommandBuffer(std::size_t slot);
    bool calibrateWorkload();
    bool submitSlot(std::size_t slot);
    bool waitSlot(std::size_t slot, std::uint64_t* measuredNanos);
    std::uint64_t readOutputChecksum();

    VulkanCapabilities capabilities_;
    std::string lastError_;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue computeQueue_ = VK_NULL_HANDLE;
    std::uint32_t computeQueueFamilyIndex_ = 0;
    std::uint32_t timestampValidBits_ = 0;
    float timestampPeriodNanos_ = 0.0F;

    VkBuffer storageBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory storageMemory_ = VK_NULL_HANDLE;
    VkBuffer readbackBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory readbackMemory_ = VK_NULL_HANDLE;
    void* readbackMapping_ = nullptr;
    bool readbackHostCoherent_ = false;

    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VkShaderModule shaderModule_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    std::array<VkCommandBuffer, kInFlightBatchCount> commandBuffers_{};
    std::array<VkFence, kInFlightBatchCount> fences_{};
    std::array<VkQueryPool, kInFlightBatchCount> queryPools_{};
    std::uint32_t dispatchWorkGroupCount_ = 0;
    std::uint32_t dispatchRepetitions_ = 1;
    std::size_t nextCompletedSlot_ = 0;
    bool inFlightPrimed_ = false;
};
