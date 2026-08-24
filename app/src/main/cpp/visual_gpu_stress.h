#pragma once

#include <vulkan/vulkan.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

enum class VisualGpuStatus : int {
    Stopped = 0,
    Running = 1,
    Unsupported = 2,
    Error = 3,
    Starting = 4,
    Stopping = 5,
};

class VisualGpuStress final {
public:
    ~VisualGpuStress();

    bool start(int targetLoadPercent);
    void stop();
    VisualGpuStatus status() const;
    std::uint64_t frameCount() const;
    std::uint64_t lastFrameWorkNanos() const;
    std::string lastError() const;

private:
    bool initialize(int targetLoadPercent);
    bool createImage();
    bool createRenderPass();
    bool createPipeline();
    bool createCommands(int targetLoadPercent);
    bool submitFrame();
    void workerLoop();
    void release();
    bool fail(const std::string& message, VkResult result = VK_SUCCESS);
    std::uint32_t findMemoryType(std::uint32_t typeBits, VkMemoryPropertyFlags flags, bool* found);

    mutable std::mutex mutex_;
    std::condition_variable wake_;
    std::thread worker_;
    std::atomic<bool> stopRequested_{true};
    std::atomic<VisualGpuStatus> status_{VisualGpuStatus::Stopped};
    std::atomic<std::uint64_t> frameCount_{0};
    std::atomic<std::uint64_t> lastFrameWorkNanos_{0};
    std::string lastError_;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    std::uint32_t queueFamilyIndex_ = 0;
    VkImage image_ = VK_NULL_HANDLE;
    VkDeviceMemory imageMemory_ = VK_NULL_HANDLE;
    VkImageView imageView_ = VK_NULL_HANDLE;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkFramebuffer framebuffer_ = VK_NULL_HANDLE;
    VkShaderModule vertexShader_ = VK_NULL_HANDLE;
    VkShaderModule fragmentShader_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
};
