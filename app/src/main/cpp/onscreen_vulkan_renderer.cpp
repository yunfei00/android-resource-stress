#include "onscreen_vulkan_renderer.h"

#include "onscreen_fragment_shader_spv.h"
#include "onscreen_vertex_shader_spv.h"

#include <android/log.h>

#include <algorithm>
#include <array>
#include <limits>
#include <vector>

namespace {
constexpr char kLogTag[] = "ResourceStress";
constexpr std::uint64_t kFenceTimeoutNanos = 5ULL * 1000ULL * 1000ULL * 1000ULL;

struct PushConstants {
    float time;
    float target;
    float aspect;
    float padding;
};

VkShaderModule createShader(VkDevice device, const std::uint8_t* bytes, std::size_t size) {
    VkShaderModule module = VK_NULL_HANDLE;
    VkShaderModuleCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    info.codeSize = size;
    info.pCode = reinterpret_cast<const std::uint32_t*>(bytes);
    if (vkCreateShaderModule(device, &info, nullptr, &module) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }
    return module;
}
}

OnscreenVulkanRenderer::~OnscreenVulkanRenderer() {
    stop();
}

bool OnscreenVulkanRenderer::start(ANativeWindow* window, int targetLoadPercent) {
    if (window == nullptr || targetLoadPercent < 25 || targetLoadPercent > 100 ||
        targetLoadPercent % 25 != 0) {
        if (window != nullptr) ANativeWindow_release(window);
        return false;
    }
    stop();
    std::lock_guard<std::mutex> lock(mutex_);
    window_ = window;
    targetLoadPercent_ = targetLoadPercent;
    lastError_.clear();
    fps_.store(0.0, std::memory_order_relaxed);
    frameTimeNanos_.store(0, std::memory_order_relaxed);
    frameCount_.store(0, std::memory_order_relaxed);
    stopRequested_.store(false, std::memory_order_release);
    try {
        worker_ = std::thread(&OnscreenVulkanRenderer::workerLoop, this);
    } catch (...) {
        stopRequested_.store(true, std::memory_order_release);
        ANativeWindow_release(window_);
        window_ = nullptr;
        lastError_ = "Unable to create onscreen Vulkan worker";
        return false;
    }
    return true;
}

void OnscreenVulkanRenderer::stop() {
    std::thread worker;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stopRequested_.store(true, std::memory_order_release);
        if (worker_.joinable()) worker = std::move(worker_);
    }
    if (worker.joinable()) worker.join();
    running_.store(false, std::memory_order_release);
}

bool OnscreenVulkanRenderer::isRunning() const {
    return running_.load(std::memory_order_acquire);
}

double OnscreenVulkanRenderer::framesPerSecond() const {
    return fps_.load(std::memory_order_relaxed);
}

std::uint64_t OnscreenVulkanRenderer::frameTimeNanos() const {
    return frameTimeNanos_.load(std::memory_order_relaxed);
}

std::uint64_t OnscreenVulkanRenderer::frameCount() const {
    return frameCount_.load(std::memory_order_relaxed);
}

std::string OnscreenVulkanRenderer::lastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return lastError_;
}

void OnscreenVulkanRenderer::workerLoop() {
    if (!initialize()) {
        running_.store(false, std::memory_order_release);
        release();
        return;
    }
    running_.store(true, std::memory_order_release);
    const auto started = std::chrono::steady_clock::now();
    auto previousFrame = started;
    auto fpsWindow = started;
    std::uint64_t windowFrames = 0;
    while (!stopRequested_.load(std::memory_order_acquire)) {
        const auto before = std::chrono::steady_clock::now();
        const float elapsed = std::chrono::duration<float>(before - started).count();
        if (!drawFrame(elapsed)) break;
        const auto now = std::chrono::steady_clock::now();
        const auto frameNanos = static_cast<std::uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(now - previousFrame).count());
        previousFrame = now;
        frameTimeNanos_.store(frameNanos, std::memory_order_relaxed);
        frameCount_.fetch_add(1, std::memory_order_relaxed);
        ++windowFrames;
        const auto windowNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
            now - fpsWindow).count();
        if (windowNanos >= 1'000'000'000LL) {
            fps_.store(
                static_cast<double>(windowFrames) * 1'000'000'000.0 /
                    static_cast<double>(windowNanos),
                std::memory_order_relaxed);
            fpsWindow = now;
            windowFrames = 0;
        }
    }
    running_.store(false, std::memory_order_release);
    release();
}

bool OnscreenVulkanRenderer::initialize() {
    return createInstanceAndSurface() && selectDeviceAndQueue() && createDevice() &&
        createSwapchain() && createRenderPass() && createPipeline() && createFrameResources();
}

bool OnscreenVulkanRenderer::createInstanceAndSurface() {
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "Android Resource Stress Onscreen";
    app.applicationVersion = VK_MAKE_VERSION(0, 5, 0);
    app.pEngineName = "ResourceStressVulkanSurface";
    app.engineVersion = VK_MAKE_VERSION(0, 5, 0);
    app.apiVersion = VK_API_VERSION_1_0;
    const std::array<const char*, 2> extensions{
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };
    VkInstanceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    info.pApplicationInfo = &app;
    info.enabledExtensionCount = static_cast<std::uint32_t>(extensions.size());
    info.ppEnabledExtensionNames = extensions.data();
    VkResult result = vkCreateInstance(&info, nullptr, &instance_);
    if (result != VK_SUCCESS) return fail("vkCreateInstance(onscreen)", result);

    VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
    surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceInfo.window = window_;
    result = vkCreateAndroidSurfaceKHR(instance_, &surfaceInfo, nullptr, &surface_);
    return result == VK_SUCCESS || fail("vkCreateAndroidSurfaceKHR", result);
}

bool OnscreenVulkanRenderer::selectDeviceAndQueue() {
    std::uint32_t count = 0;
    VkResult result = vkEnumeratePhysicalDevices(instance_, &count, nullptr);
    if (result != VK_SUCCESS || count == 0) return fail("No Vulkan presentation device", result);
    std::vector<VkPhysicalDevice> devices(count);
    result = vkEnumeratePhysicalDevices(instance_, &count, devices.data());
    if (result != VK_SUCCESS) return fail("vkEnumeratePhysicalDevices(onscreen)", result);
    for (VkPhysicalDevice device : devices) {
        std::uint32_t queueCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, nullptr);
        std::vector<VkQueueFamilyProperties> queues(queueCount);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, queues.data());
        for (std::uint32_t index = 0; index < queueCount; ++index) {
            VkBool32 present = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(device, index, surface_, &present);
            if (queues[index].queueCount > 0 && present == VK_TRUE &&
                (queues[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
                physicalDevice_ = device;
                queueFamilyIndex_ = index;
                return true;
            }
        }
    }
    return fail("No graphics queue can present to the Android Surface");
}

bool OnscreenVulkanRenderer::createDevice() {
    const float priority = 1.0F;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = queueFamilyIndex_;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    const char* extension = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
    VkDeviceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    info.queueCreateInfoCount = 1;
    info.pQueueCreateInfos = &queueInfo;
    info.enabledExtensionCount = 1;
    info.ppEnabledExtensionNames = &extension;
    VkResult result = vkCreateDevice(physicalDevice_, &info, nullptr, &device_);
    if (result != VK_SUCCESS) return fail("vkCreateDevice(onscreen)", result);
    vkGetDeviceQueue(device_, queueFamilyIndex_, 0, &queue_);
    return queue_ != VK_NULL_HANDLE || fail("Onscreen graphics queue is null");
}

bool OnscreenVulkanRenderer::createSwapchain() {
    VkSurfaceCapabilitiesKHR capabilities{};
    VkResult result = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
        physicalDevice_, surface_, &capabilities);
    if (result != VK_SUCCESS) return fail("vkGetPhysicalDeviceSurfaceCapabilitiesKHR", result);
    std::uint32_t formatCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, nullptr);
    if (formatCount == 0) return fail("Android Surface has no Vulkan formats");
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, formats.data());
    VkSurfaceFormatKHR chosen = formats.front();
    for (const auto& candidate : formats) {
        if (candidate.format == VK_FORMAT_R8G8B8A8_UNORM ||
            candidate.format == VK_FORMAT_B8G8R8A8_UNORM) {
            chosen = candidate;
            break;
        }
    }
    swapchainFormat_ = chosen.format;
    if (capabilities.currentExtent.width != std::numeric_limits<std::uint32_t>::max()) {
        extent_ = capabilities.currentExtent;
    } else {
        extent_.width = std::clamp(
            static_cast<std::uint32_t>(std::max(1, ANativeWindow_getWidth(window_))),
            capabilities.minImageExtent.width,
            capabilities.maxImageExtent.width);
        extent_.height = std::clamp(
            static_cast<std::uint32_t>(std::max(1, ANativeWindow_getHeight(window_))),
            capabilities.minImageExtent.height,
            capabilities.maxImageExtent.height);
    }
    std::uint32_t imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0) imageCount = std::min(imageCount, capabilities.maxImageCount);
    VkSwapchainCreateInfoKHR info{};
    info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    info.surface = surface_;
    info.minImageCount = imageCount;
    info.imageFormat = swapchainFormat_;
    info.imageColorSpace = chosen.colorSpace;
    info.imageExtent = extent_;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.preTransform = capabilities.currentTransform;
    info.compositeAlpha = (capabilities.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
        ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
        : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    info.clipped = VK_TRUE;
    result = vkCreateSwapchainKHR(device_, &info, nullptr, &swapchain_);
    if (result != VK_SUCCESS) return fail("vkCreateSwapchainKHR", result);
    vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, nullptr);
    images_.resize(imageCount);
    vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, images_.data());
    imageViews_.resize(images_.size());
    for (std::size_t index = 0; index < images_.size(); ++index) {
        VkImageViewCreateInfo view{};
        view.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        view.image = images_[index];
        view.viewType = VK_IMAGE_VIEW_TYPE_2D;
        view.format = swapchainFormat_;
        view.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        view.subresourceRange.levelCount = 1;
        view.subresourceRange.layerCount = 1;
        result = vkCreateImageView(device_, &view, nullptr, &imageViews_[index]);
        if (result != VK_SUCCESS) return fail("vkCreateImageView(onscreen)", result);
    }
    return true;
}

bool OnscreenVulkanRenderer::createRenderPass() {
    VkAttachmentDescription attachment{};
    attachment.format = swapchainFormat_;
    attachment.samples = VK_SAMPLE_COUNT_1_BIT;
    attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference reference{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &reference;
    VkSubpassDependency dependency{};
    dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    dependency.dstSubpass = 0;
    dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    info.attachmentCount = 1;
    info.pAttachments = &attachment;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    info.dependencyCount = 1;
    info.pDependencies = &dependency;
    VkResult result = vkCreateRenderPass(device_, &info, nullptr, &renderPass_);
    if (result != VK_SUCCESS) return fail("vkCreateRenderPass(onscreen)", result);
    framebuffers_.resize(imageViews_.size());
    for (std::size_t index = 0; index < imageViews_.size(); ++index) {
        VkFramebufferCreateInfo framebuffer{};
        framebuffer.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebuffer.renderPass = renderPass_;
        framebuffer.attachmentCount = 1;
        framebuffer.pAttachments = &imageViews_[index];
        framebuffer.width = extent_.width;
        framebuffer.height = extent_.height;
        framebuffer.layers = 1;
        result = vkCreateFramebuffer(device_, &framebuffer, nullptr, &framebuffers_[index]);
        if (result != VK_SUCCESS) return fail("vkCreateFramebuffer(onscreen)", result);
    }
    return true;
}

bool OnscreenVulkanRenderer::createPipeline() {
    vertexShader_ = createShader(device_, kOnscreenVertexShaderSpirv, kOnscreenVertexShaderSpirvSize);
    fragmentShader_ = createShader(device_, kOnscreenFragmentShaderSpirv, kOnscreenFragmentShaderSpirvSize);
    if (vertexShader_ == VK_NULL_HANDLE || fragmentShader_ == VK_NULL_HANDLE) {
        return fail("Unable to create onscreen shader modules");
    }
    std::array<VkPipelineShaderStageCreateInfo, 2> stages{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertexShader_;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragmentShader_;
    stages[1].pName = "main";
    VkPipelineVertexInputStateCreateInfo vertexInput{};
    vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo assembly{};
    assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo viewport{};
    viewport.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewport.viewportCount = 1;
    viewport.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo raster{};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0F;
    VkPipelineMultisampleStateCreateInfo multisample{};
    multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState colorAttachment{};
    colorAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
        VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo colorBlend{};
    colorBlend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    colorBlend.attachmentCount = 1;
    colorBlend.pAttachments = &colorAttachment;
    const std::array<VkDynamicState, 2> dynamicStates{
        VK_DYNAMIC_STATE_VIEWPORT,
        VK_DYNAMIC_STATE_SCISSOR,
    };
    VkPipelineDynamicStateCreateInfo dynamic{};
    dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamic.dynamicStateCount = static_cast<std::uint32_t>(dynamicStates.size());
    dynamic.pDynamicStates = dynamicStates.data();
    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    pushRange.size = sizeof(PushConstants);
    VkPipelineLayoutCreateInfo layout{};
    layout.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layout.pushConstantRangeCount = 1;
    layout.pPushConstantRanges = &pushRange;
    VkResult result = vkCreatePipelineLayout(device_, &layout, nullptr, &pipelineLayout_);
    if (result != VK_SUCCESS) return fail("vkCreatePipelineLayout(onscreen)", result);
    VkGraphicsPipelineCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    info.stageCount = static_cast<std::uint32_t>(stages.size());
    info.pStages = stages.data();
    info.pVertexInputState = &vertexInput;
    info.pInputAssemblyState = &assembly;
    info.pViewportState = &viewport;
    info.pRasterizationState = &raster;
    info.pMultisampleState = &multisample;
    info.pColorBlendState = &colorBlend;
    info.pDynamicState = &dynamic;
    info.layout = pipelineLayout_;
    info.renderPass = renderPass_;
    result = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &info, nullptr, &pipeline_);
    return result == VK_SUCCESS || fail("vkCreateGraphicsPipelines(onscreen)", result);
}

bool OnscreenVulkanRenderer::createFrameResources() {
    VkCommandPoolCreateInfo pool{};
    pool.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool.queueFamilyIndex = queueFamilyIndex_;
    VkResult result = vkCreateCommandPool(device_, &pool, nullptr, &commandPool_);
    if (result != VK_SUCCESS) return fail("vkCreateCommandPool(onscreen)", result);
    VkCommandBufferAllocateInfo allocation{};
    allocation.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocation.commandPool = commandPool_;
    allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocation.commandBufferCount = static_cast<std::uint32_t>(commandBuffers_.size());
    result = vkAllocateCommandBuffers(device_, &allocation, commandBuffers_.data());
    if (result != VK_SUCCESS) return fail("vkAllocateCommandBuffers(onscreen)", result);
    for (std::size_t index = 0; index < kFramesInFlight; ++index) {
        VkSemaphoreCreateInfo semaphore{};
        semaphore.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        VkFenceCreateInfo fence{};
        fence.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fence.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vkCreateSemaphore(device_, &semaphore, nullptr, &imageAvailable_[index]) != VK_SUCCESS ||
            vkCreateSemaphore(device_, &semaphore, nullptr, &renderFinished_[index]) != VK_SUCCESS ||
            vkCreateFence(device_, &fence, nullptr, &frameFences_[index]) != VK_SUCCESS) {
            return fail("Unable to create onscreen frame synchronization");
        }
    }
    imageFences_.assign(images_.size(), VK_NULL_HANDLE);
    return true;
}

bool OnscreenVulkanRenderer::drawFrame(float elapsedSeconds) {
    VkFence fence = frameFences_[currentFrame_];
    VkResult result = vkWaitForFences(device_, 1, &fence, VK_TRUE, kFenceTimeoutNanos);
    if (result != VK_SUCCESS) return fail("vkWaitForFences(onscreen)", result);
    std::uint32_t imageIndex = 0;
    result = vkAcquireNextImageKHR(
        device_, swapchain_, kFenceTimeoutNanos, imageAvailable_[currentFrame_],
        VK_NULL_HANDLE, &imageIndex);
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        return fail("vkAcquireNextImageKHR", result);
    }
    if (imageFences_[imageIndex] != VK_NULL_HANDLE) {
        result = vkWaitForFences(
            device_, 1, &imageFences_[imageIndex], VK_TRUE, kFenceTimeoutNanos);
        if (result != VK_SUCCESS) return fail("vkWaitForFences(image)", result);
    }
    imageFences_[imageIndex] = fence;
    vkResetFences(device_, 1, &fence);
    VkCommandBuffer command = commandBuffers_[currentFrame_];
    vkResetCommandBuffer(command, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    result = vkBeginCommandBuffer(command, &begin);
    if (result != VK_SUCCESS) return fail("vkBeginCommandBuffer(onscreen)", result);
    const VkClearValue clear{{{0.004F, 0.008F, 0.025F, 1.0F}}};
    VkRenderPassBeginInfo render{};
    render.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    render.renderPass = renderPass_;
    render.framebuffer = framebuffers_[imageIndex];
    render.renderArea.extent = extent_;
    render.clearValueCount = 1;
    render.pClearValues = &clear;
    vkCmdBeginRenderPass(command, &render, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport viewport{0.0F, 0.0F, static_cast<float>(extent_.width),
        static_cast<float>(extent_.height), 0.0F, 1.0F};
    VkRect2D scissor{{0, 0}, extent_};
    vkCmdSetViewport(command, 0, 1, &viewport);
    vkCmdSetScissor(command, 0, 1, &scissor);
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    const PushConstants push{
        elapsedSeconds,
        static_cast<float>(targetLoadPercent_) / 100.0F,
        static_cast<float>(extent_.width) / static_cast<float>(std::max(1U, extent_.height)),
        0.0F,
    };
    vkCmdPushConstants(
        command, pipelineLayout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(push), &push);
    vkCmdDraw(command, 3, 1, 0, 0);
    vkCmdEndRenderPass(command);
    result = vkEndCommandBuffer(command);
    if (result != VK_SUCCESS) return fail("vkEndCommandBuffer(onscreen)", result);
    const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &imageAvailable_[currentFrame_];
    submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &renderFinished_[currentFrame_];
    result = vkQueueSubmit(queue_, 1, &submit, fence);
    if (result != VK_SUCCESS) return fail("vkQueueSubmit(onscreen)", result);
    VkPresentInfoKHR present{};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &renderFinished_[currentFrame_];
    present.swapchainCount = 1;
    present.pSwapchains = &swapchain_;
    present.pImageIndices = &imageIndex;
    result = vkQueuePresentKHR(queue_, &present);
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        return fail("vkQueuePresentKHR", result);
    }
    currentFrame_ = (currentFrame_ + 1) % kFramesInFlight;
    return true;
}

void OnscreenVulkanRenderer::release() {
    if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
    for (std::size_t index = 0; index < kFramesInFlight; ++index) {
        if (frameFences_[index] != VK_NULL_HANDLE) vkDestroyFence(device_, frameFences_[index], nullptr);
        if (renderFinished_[index] != VK_NULL_HANDLE) vkDestroySemaphore(device_, renderFinished_[index], nullptr);
        if (imageAvailable_[index] != VK_NULL_HANDLE) vkDestroySemaphore(device_, imageAvailable_[index], nullptr);
        frameFences_[index] = VK_NULL_HANDLE;
        renderFinished_[index] = VK_NULL_HANDLE;
        imageAvailable_[index] = VK_NULL_HANDLE;
    }
    if (commandPool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, commandPool_, nullptr);
    commandPool_ = VK_NULL_HANDLE;
    if (pipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline_, nullptr);
    if (pipelineLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
    if (vertexShader_ != VK_NULL_HANDLE) vkDestroyShaderModule(device_, vertexShader_, nullptr);
    if (fragmentShader_ != VK_NULL_HANDLE) vkDestroyShaderModule(device_, fragmentShader_, nullptr);
    for (VkFramebuffer framebuffer : framebuffers_) vkDestroyFramebuffer(device_, framebuffer, nullptr);
    if (renderPass_ != VK_NULL_HANDLE) vkDestroyRenderPass(device_, renderPass_, nullptr);
    for (VkImageView view : imageViews_) vkDestroyImageView(device_, view, nullptr);
    if (swapchain_ != VK_NULL_HANDLE) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
    if (device_ != VK_NULL_HANDLE) vkDestroyDevice(device_, nullptr);
    if (surface_ != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance_, surface_, nullptr);
    if (instance_ != VK_NULL_HANDLE) vkDestroyInstance(instance_, nullptr);
    if (window_ != nullptr) ANativeWindow_release(window_);
    window_ = nullptr;
    instance_ = VK_NULL_HANDLE;
    surface_ = VK_NULL_HANDLE;
    physicalDevice_ = VK_NULL_HANDLE;
    device_ = VK_NULL_HANDLE;
    queue_ = VK_NULL_HANDLE;
    swapchain_ = VK_NULL_HANDLE;
    renderPass_ = VK_NULL_HANDLE;
    vertexShader_ = VK_NULL_HANDLE;
    fragmentShader_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    pipeline_ = VK_NULL_HANDLE;
    framebuffers_.clear();
    imageViews_.clear();
    images_.clear();
    imageFences_.clear();
    currentFrame_ = 0;
}

bool OnscreenVulkanRenderer::fail(const std::string& operation, VkResult result) {
    std::lock_guard<std::mutex> lock(mutex_);
    lastError_ = result == VK_SUCCESS
        ? operation
        : operation + " result=" + std::to_string(result);
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", lastError_.c_str());
    return false;
}
