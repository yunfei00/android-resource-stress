#pragma once

#include <android/native_window.h>
#include <vulkan/vulkan.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

class OnscreenVulkanRenderer final {
public:
    ~OnscreenVulkanRenderer();
    bool start(ANativeWindow* window, int targetLoadPercent);
    void stop();
    bool isRunning() const;
    double framesPerSecond() const;
    std::uint64_t frameTimeNanos() const;
    std::uint64_t frameCount() const;
    std::string lastError() const;

private:
    static constexpr std::size_t kFramesInFlight = 2;
    bool initialize();
    bool createInstanceAndSurface();
    bool selectDeviceAndQueue();
    bool createDevice();
    bool createSwapchain();
    bool createRenderPass();
    bool createPipeline();
    bool createFrameResources();
    bool drawFrame(float elapsedSeconds);
    void workerLoop();
    void release();
    bool fail(const std::string& operation, VkResult result = VK_SUCCESS);

    mutable std::mutex mutex_;
    std::thread worker_;
    std::atomic<bool> stopRequested_{true};
    std::atomic<bool> running_{false};
    std::atomic<double> fps_{0.0};
    std::atomic<std::uint64_t> frameTimeNanos_{0};
    std::atomic<std::uint64_t> frameCount_{0};
    std::string lastError_;
    int targetLoadPercent_ = 100;
    ANativeWindow* window_ = nullptr;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    std::uint32_t queueFamilyIndex_ = 0;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchainFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D extent_{};
    std::vector<VkImage> images_;
    std::vector<VkImageView> imageViews_;
    std::vector<VkFramebuffer> framebuffers_;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkShaderModule vertexShader_ = VK_NULL_HANDLE;
    VkShaderModule fragmentShader_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    std::array<VkCommandBuffer, kFramesInFlight> commandBuffers_{};
    std::array<VkSemaphore, kFramesInFlight> imageAvailable_{};
    std::array<VkSemaphore, kFramesInFlight> renderFinished_{};
    std::array<VkFence, kFramesInFlight> frameFences_{};
    std::vector<VkFence> imageFences_;
    std::size_t currentFrame_ = 0;
};
