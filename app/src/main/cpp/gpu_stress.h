#pragma once

#include "vulkan_context.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

enum class GpuStressStatus : int {
    Stopped = 0,
    Running = 1,
    Unsupported = 2,
    Error = 3,
    Starting = 4,
    Stopping = 5,
    Checking = 6,
};

class GpuStress final {
public:
    GpuStress() = default;
    ~GpuStress();

    GpuStress(const GpuStress&) = delete;
    GpuStress& operator=(const GpuStress&) = delete;

    bool initialize();
    bool start(int targetLoadPercent);
    void stop();
    void shutdown();

    VulkanCapabilities capabilities() const;
    GpuStressStatus status() const;
    std::uint64_t dispatchCount() const;
    std::uint64_t workGroupCount() const;
    std::uint64_t lastGpuWorkNanos() const;
    std::uint64_t outputChecksum() const;
    std::string lastError() const;

private:
    void workerLoop(int targetLoadPercent);

    mutable std::mutex mutex_;
    std::condition_variable workerWake_;
    std::condition_variable stateChanged_;
    VulkanContext context_;
    std::thread worker_;
    bool stopping_ = false;
    std::atomic<bool> stopRequested_{true};
    std::atomic<GpuStressStatus> status_{GpuStressStatus::Checking};
    std::atomic<std::uint64_t> dispatchCount_{0};
    std::atomic<std::uint64_t> workGroupCount_{0};
    std::atomic<std::uint64_t> lastGpuWorkNanos_{0};
    std::atomic<std::uint64_t> outputChecksum_{0};
};
