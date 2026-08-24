#include "visual_gpu_stress.h"

#include "visual_fragment_shader_spv.h"
#include "visual_vertex_shader_spv.h"

#include <android/log.h>

#include <array>
#include <chrono>
#include <vector>

namespace {
constexpr char kLogTag[] = "ResourceStress";
constexpr std::uint32_t kRenderWidth = 640;
constexpr std::uint32_t kRenderHeight = 640;
constexpr std::uint64_t kFenceTimeoutNanos = 5ULL * 1000ULL * 1000ULL * 1000ULL;
constexpr auto kFrameInterval = std::chrono::microseconds(16667);
}

VisualGpuStress::~VisualGpuStress() {
    stop();
}

bool VisualGpuStress::start(int targetLoadPercent) {
    if (targetLoadPercent != 25 && targetLoadPercent != 50 &&
        targetLoadPercent != 75 && targetLoadPercent != 100) {
        return false;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if (worker_.joinable() || status_.load(std::memory_order_acquire) == VisualGpuStatus::Running) {
        return false;
    }
    status_.store(VisualGpuStatus::Starting, std::memory_order_release);
    lastError_.clear();
    frameCount_.store(0, std::memory_order_relaxed);
    lastFrameWorkNanos_.store(0, std::memory_order_relaxed);
    if (!initialize(targetLoadPercent)) {
        release();
        status_.store(VisualGpuStatus::Error, std::memory_order_release);
        return false;
    }
    stopRequested_.store(false, std::memory_order_release);
    try {
        worker_ = std::thread(&VisualGpuStress::workerLoop, this);
    } catch (...) {
        stopRequested_.store(true, std::memory_order_release);
        release();
        status_.store(VisualGpuStatus::Error, std::memory_order_release);
        lastError_ = "Unable to create Vulkan visual worker";
        return false;
    }
    status_.store(VisualGpuStatus::Running, std::memory_order_release);
    return true;
}

void VisualGpuStress::stop() {
    std::thread worker;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stopRequested_.store(true, std::memory_order_release);
        const VisualGpuStatus current = status_.load(std::memory_order_acquire);
        if (current == VisualGpuStatus::Running || current == VisualGpuStatus::Starting) {
            status_.store(VisualGpuStatus::Stopping, std::memory_order_release);
        }
        if (worker_.joinable()) worker = std::move(worker_);
    }
    wake_.notify_all();
    if (worker.joinable()) worker.join();
    std::lock_guard<std::mutex> lock(mutex_);
    release();
    frameCount_.store(0, std::memory_order_relaxed);
    lastFrameWorkNanos_.store(0, std::memory_order_relaxed);
    if (status_.load(std::memory_order_acquire) != VisualGpuStatus::Error) {
        status_.store(VisualGpuStatus::Stopped, std::memory_order_release);
    }
}

VisualGpuStatus VisualGpuStress::status() const {
    return status_.load(std::memory_order_acquire);
}

std::uint64_t VisualGpuStress::frameCount() const {
    return frameCount_.load(std::memory_order_relaxed);
}

std::uint64_t VisualGpuStress::lastFrameWorkNanos() const {
    return lastFrameWorkNanos_.load(std::memory_order_relaxed);
}

std::string VisualGpuStress::lastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return lastError_;
}

bool VisualGpuStress::initialize(int targetLoadPercent) {
    VkApplicationInfo applicationInfo{};
    applicationInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    applicationInfo.pApplicationName = "Android Resource Stress Visual";
    applicationInfo.applicationVersion = VK_MAKE_VERSION(0, 4, 0);
    applicationInfo.pEngineName = "ResourceStressVisual";
    applicationInfo.engineVersion = VK_MAKE_VERSION(0, 4, 0);
    applicationInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &applicationInfo;
    VkResult result = vkCreateInstance(&instanceInfo, nullptr, &instance_);
    if (result != VK_SUCCESS) return fail("vkCreateInstance(visual)", result);

    std::uint32_t deviceCount = 0;
    result = vkEnumeratePhysicalDevices(instance_, &deviceCount, nullptr);
    if (result != VK_SUCCESS || deviceCount == 0) {
        return fail("No Vulkan graphics device is available", result);
    }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    result = vkEnumeratePhysicalDevices(instance_, &deviceCount, devices.data());
    if (result != VK_SUCCESS) return fail("vkEnumeratePhysicalDevices(visual)", result);

    bool foundQueue = false;
    for (VkPhysicalDevice candidate : devices) {
        std::uint32_t queueCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueCount, nullptr);
        std::vector<VkQueueFamilyProperties> queues(queueCount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueCount, queues.data());
        for (std::uint32_t index = 0; index < queueCount; ++index) {
            if (queues[index].queueCount > 0 &&
                (queues[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
                physicalDevice_ = candidate;
                queueFamilyIndex_ = index;
                foundQueue = true;
                break;
            }
        }
        if (foundQueue) break;
    }
    if (!foundQueue) return fail("Vulkan graphics queue is unavailable");

    const float priority = 1.0F;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = queueFamilyIndex_;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    result = vkCreateDevice(physicalDevice_, &deviceInfo, nullptr, &device_);
    if (result != VK_SUCCESS) return fail("vkCreateDevice(visual)", result);
    vkGetDeviceQueue(device_, queueFamilyIndex_, 0, &queue_);
    if (queue_ == VK_NULL_HANDLE) return fail("Visual graphics queue is null");

    return createImage() && createRenderPass() && createPipeline() &&
        createCommands(targetLoadPercent);
}

bool VisualGpuStress::createImage() {
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {kRenderWidth, kRenderHeight, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkResult result = vkCreateImage(device_, &imageInfo, nullptr, &image_);
    if (result != VK_SUCCESS) return fail("vkCreateImage(visual)", result);

    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(device_, image_, &requirements);
    bool found = false;
    const std::uint32_t memoryType = findMemoryType(
        requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        &found);
    if (!found) return fail("No device-local memory for visual image");
    VkMemoryAllocateInfo allocation{};
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = memoryType;
    result = vkAllocateMemory(device_, &allocation, nullptr, &imageMemory_);
    if (result != VK_SUCCESS) return fail("vkAllocateMemory(visual)", result);
    result = vkBindImageMemory(device_, image_, imageMemory_, 0);
    if (result != VK_SUCCESS) return fail("vkBindImageMemory(visual)", result);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = image_;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    result = vkCreateImageView(device_, &viewInfo, nullptr, &imageView_);
    return result == VK_SUCCESS || fail("vkCreateImageView(visual)", result);
}

bool VisualGpuStress::createRenderPass() {
    VkAttachmentDescription attachment{};
    attachment.format = VK_FORMAT_R8G8B8A8_UNORM;
    attachment.samples = VK_SAMPLE_COUNT_1_BIT;
    attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachment.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
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
    if (result != VK_SUCCESS) return fail("vkCreateRenderPass(visual)", result);

    VkFramebufferCreateInfo framebufferInfo{};
    framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    framebufferInfo.renderPass = renderPass_;
    framebufferInfo.attachmentCount = 1;
    framebufferInfo.pAttachments = &imageView_;
    framebufferInfo.width = kRenderWidth;
    framebufferInfo.height = kRenderHeight;
    framebufferInfo.layers = 1;
    result = vkCreateFramebuffer(device_, &framebufferInfo, nullptr, &framebuffer_);
    return result == VK_SUCCESS || fail("vkCreateFramebuffer(visual)", result);
}

bool VisualGpuStress::createPipeline() {
    auto createShader = [this](const std::uint8_t* bytes, std::size_t size, VkShaderModule* output) {
        VkShaderModuleCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        info.codeSize = size;
        info.pCode = reinterpret_cast<const std::uint32_t*>(bytes);
        return vkCreateShaderModule(device_, &info, nullptr, output);
    };
    VkResult result = createShader(kVisualVertexShaderSpirv, kVisualVertexShaderSpirvSize, &vertexShader_);
    if (result != VK_SUCCESS) return fail("vkCreateShaderModule(vertex)", result);
    result = createShader(kVisualFragmentShaderSpirv, kVisualFragmentShaderSpirvSize, &fragmentShader_);
    if (result != VK_SUCCESS) return fail("vkCreateShaderModule(fragment)", result);

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
    VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
    inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkViewport viewport{0.0F, 0.0F, static_cast<float>(kRenderWidth), static_cast<float>(kRenderHeight), 0.0F, 1.0F};
    VkRect2D scissor{{0, 0}, {kRenderWidth, kRenderHeight}};
    VkPipelineViewportStateCreateInfo viewportState{};
    viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewportState.viewportCount = 1;
    viewportState.pViewports = &viewport;
    viewportState.scissorCount = 1;
    viewportState.pScissors = &scissor;
    VkPipelineRasterizationStateCreateInfo rasterization{};
    rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rasterization.polygonMode = VK_POLYGON_MODE_FILL;
    rasterization.cullMode = VK_CULL_MODE_NONE;
    rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rasterization.lineWidth = 1.0F;
    VkPipelineMultisampleStateCreateInfo multisample{};
    multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState blendAttachment{};
    blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
        VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo blend{};
    blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blend.attachmentCount = 1;
    blend.pAttachments = &blendAttachment;
    VkPipelineLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    result = vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &pipelineLayout_);
    if (result != VK_SUCCESS) return fail("vkCreatePipelineLayout(visual)", result);

    VkGraphicsPipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pipelineInfo.stageCount = static_cast<std::uint32_t>(stages.size());
    pipelineInfo.pStages = stages.data();
    pipelineInfo.pVertexInputState = &vertexInput;
    pipelineInfo.pInputAssemblyState = &inputAssembly;
    pipelineInfo.pViewportState = &viewportState;
    pipelineInfo.pRasterizationState = &rasterization;
    pipelineInfo.pMultisampleState = &multisample;
    pipelineInfo.pColorBlendState = &blend;
    pipelineInfo.layout = pipelineLayout_;
    pipelineInfo.renderPass = renderPass_;
    pipelineInfo.subpass = 0;
    result = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline_);
    return result == VK_SUCCESS || fail("vkCreateGraphicsPipelines(visual)", result);
}

bool VisualGpuStress::createCommands(int targetLoadPercent) {
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamilyIndex_;
    VkResult result = vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_);
    if (result != VK_SUCCESS) return fail("vkCreateCommandPool(visual)", result);
    VkCommandBufferAllocateInfo allocation{};
    allocation.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocation.commandPool = commandPool_;
    allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocation.commandBufferCount = 1;
    result = vkAllocateCommandBuffers(device_, &allocation, &commandBuffer_);
    if (result != VK_SUCCESS) return fail("vkAllocateCommandBuffers(visual)", result);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT;
    result = vkBeginCommandBuffer(commandBuffer_, &begin);
    if (result != VK_SUCCESS) return fail("vkBeginCommandBuffer(visual)", result);
    const VkClearValue clear{{{0.015F, 0.025F, 0.055F, 1.0F}}};
    VkRenderPassBeginInfo renderBegin{};
    renderBegin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    renderBegin.renderPass = renderPass_;
    renderBegin.framebuffer = framebuffer_;
    renderBegin.renderArea.extent = {kRenderWidth, kRenderHeight};
    renderBegin.clearValueCount = 1;
    renderBegin.pClearValues = &clear;
    vkCmdBeginRenderPass(commandBuffer_, &renderBegin, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    const std::uint32_t layers = static_cast<std::uint32_t>(targetLoadPercent / 25) * 2U;
    vkCmdDraw(commandBuffer_, 3, layers, 0, 0);
    vkCmdEndRenderPass(commandBuffer_);
    result = vkEndCommandBuffer(commandBuffer_);
    if (result != VK_SUCCESS) return fail("vkEndCommandBuffer(visual)", result);
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    result = vkCreateFence(device_, &fenceInfo, nullptr, &fence_);
    return result == VK_SUCCESS || fail("vkCreateFence(visual)", result);
}

bool VisualGpuStress::submitFrame() {
    VkResult result = vkResetFences(device_, 1, &fence_);
    if (result != VK_SUCCESS) return fail("vkResetFences(visual)", result);
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &commandBuffer_;
    const auto started = std::chrono::steady_clock::now();
    result = vkQueueSubmit(queue_, 1, &submit, fence_);
    if (result != VK_SUCCESS) return fail("vkQueueSubmit(visual)", result);
    result = vkWaitForFences(device_, 1, &fence_, VK_TRUE, kFenceTimeoutNanos);
    if (result != VK_SUCCESS) return fail("vkWaitForFences(visual)", result);
    const auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - started).count();
    lastFrameWorkNanos_.store(static_cast<std::uint64_t>(elapsed), std::memory_order_relaxed);
    frameCount_.fetch_add(1, std::memory_order_relaxed);
    return true;
}

void VisualGpuStress::workerLoop() {
    while (!stopRequested_.load(std::memory_order_acquire)) {
        const auto frameStarted = std::chrono::steady_clock::now();
        if (!submitFrame()) {
            status_.store(VisualGpuStatus::Error, std::memory_order_release);
            stopRequested_.store(true, std::memory_order_release);
            break;
        }
        std::unique_lock<std::mutex> lock(mutex_);
        wake_.wait_until(lock, frameStarted + kFrameInterval, [this] {
            return stopRequested_.load(std::memory_order_acquire);
        });
    }
}

void VisualGpuStress::release() {
    if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
    if (fence_ != VK_NULL_HANDLE) vkDestroyFence(device_, fence_, nullptr);
    if (commandPool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, commandPool_, nullptr);
    if (pipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline_, nullptr);
    if (pipelineLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
    if (fragmentShader_ != VK_NULL_HANDLE) vkDestroyShaderModule(device_, fragmentShader_, nullptr);
    if (vertexShader_ != VK_NULL_HANDLE) vkDestroyShaderModule(device_, vertexShader_, nullptr);
    if (framebuffer_ != VK_NULL_HANDLE) vkDestroyFramebuffer(device_, framebuffer_, nullptr);
    if (renderPass_ != VK_NULL_HANDLE) vkDestroyRenderPass(device_, renderPass_, nullptr);
    if (imageView_ != VK_NULL_HANDLE) vkDestroyImageView(device_, imageView_, nullptr);
    if (image_ != VK_NULL_HANDLE) vkDestroyImage(device_, image_, nullptr);
    if (imageMemory_ != VK_NULL_HANDLE) vkFreeMemory(device_, imageMemory_, nullptr);
    if (device_ != VK_NULL_HANDLE) vkDestroyDevice(device_, nullptr);
    if (instance_ != VK_NULL_HANDLE) vkDestroyInstance(instance_, nullptr);
    instance_ = VK_NULL_HANDLE;
    physicalDevice_ = VK_NULL_HANDLE;
    device_ = VK_NULL_HANDLE;
    queue_ = VK_NULL_HANDLE;
    image_ = VK_NULL_HANDLE;
    imageMemory_ = VK_NULL_HANDLE;
    imageView_ = VK_NULL_HANDLE;
    renderPass_ = VK_NULL_HANDLE;
    framebuffer_ = VK_NULL_HANDLE;
    vertexShader_ = VK_NULL_HANDLE;
    fragmentShader_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    pipeline_ = VK_NULL_HANDLE;
    commandPool_ = VK_NULL_HANDLE;
    commandBuffer_ = VK_NULL_HANDLE;
    fence_ = VK_NULL_HANDLE;
}

bool VisualGpuStress::fail(const std::string& message, VkResult result) {
    lastError_ = result == VK_SUCCESS ? message : message + " result=" + std::to_string(result);
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", lastError_.c_str());
    return false;
}

std::uint32_t VisualGpuStress::findMemoryType(
        std::uint32_t typeBits,
        VkMemoryPropertyFlags flags,
        bool* found) {
    VkPhysicalDeviceMemoryProperties properties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &properties);
    for (std::uint32_t index = 0; index < properties.memoryTypeCount; ++index) {
        if ((typeBits & (1U << index)) != 0 &&
            (properties.memoryTypes[index].propertyFlags & flags) == flags) {
            *found = true;
            return index;
        }
    }
    *found = false;
    return 0;
}
