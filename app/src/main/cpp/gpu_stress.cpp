#include "gpu_stress.h"

#include <android/log.h>

#include <chrono>
#include <utility>

namespace {
constexpr char kLogTag[] = "ResourceStress";
constexpr auto kDutyCycle = std::chrono::milliseconds(100);
}  // namespace

GpuStress::~GpuStress() {
    shutdown();
}

bool GpuStress::initialize() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (context_.capabilities().supported) {
        if (status_.load(std::memory_order_acquire) == GpuStressStatus::Checking) {
            status_.store(GpuStressStatus::Stopped, std::memory_order_release);
        }
        return true;
    }

    status_.store(GpuStressStatus::Checking, std::memory_order_release);
    if (!context_.initialize()) {
        const bool unsupported =
            context_.lastError().find("unavailable") != std::string::npos ||
            context_.lastError().find("No Vulkan") != std::string::npos;
        status_.store(
            unsupported ? GpuStressStatus::Unsupported : GpuStressStatus::Error,
            std::memory_order_release);
        return false;
    }
    status_.store(GpuStressStatus::Stopped, std::memory_order_release);
    return true;
}

bool GpuStress::start(int targetLoadPercent) {
    if (targetLoadPercent != 25 && targetLoadPercent != 50 &&
        targetLoadPercent != 75 && targetLoadPercent != 100) {
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_ || worker_.joinable() ||
        status_.load(std::memory_order_acquire) == GpuStressStatus::Running ||
        status_.load(std::memory_order_acquire) == GpuStressStatus::Starting) {
        return false;
    }

    status_.store(GpuStressStatus::Starting, std::memory_order_release);
    if (!context_.initialize() || !context_.prepareStressResources()) {
        status_.store(
            context_.capabilities().supported
                ? GpuStressStatus::Error
                : GpuStressStatus::Unsupported,
            std::memory_order_release);
        return false;
    }

    dispatchCount_.store(0, std::memory_order_relaxed);
    workGroupCount_.store(0, std::memory_order_relaxed);
    lastGpuWorkNanos_.store(0, std::memory_order_relaxed);
    outputChecksum_.store(0, std::memory_order_relaxed);
    stopRequested_.store(false, std::memory_order_release);
    try {
        worker_ = std::thread(&GpuStress::workerLoop, this, targetLoadPercent);
    } catch (...) {
        stopRequested_.store(true, std::memory_order_release);
        context_.releaseStressResources();
        status_.store(GpuStressStatus::Error, std::memory_order_release);
        return false;
    }
    status_.store(GpuStressStatus::Running, std::memory_order_release);
    return true;
}

void GpuStress::stop() {
    std::thread workerToJoin;
    {
        std::unique_lock<std::mutex> lock(mutex_);
        if (stopping_) {
            stateChanged_.wait(lock, [this] { return !stopping_; });
            return;
        }
        stopping_ = true;
        stopRequested_.store(true, std::memory_order_release);
        const GpuStressStatus current = status_.load(std::memory_order_acquire);
        if (current == GpuStressStatus::Running || current == GpuStressStatus::Starting) {
            status_.store(GpuStressStatus::Stopping, std::memory_order_release);
        }
        if (worker_.joinable()) {
            workerToJoin = std::move(worker_);
        }
    }
    workerWake_.notify_all();

    if (workerToJoin.joinable()) {
        workerToJoin.join();
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        context_.releaseStressResources();
        dispatchCount_.store(0, std::memory_order_relaxed);
        workGroupCount_.store(0, std::memory_order_relaxed);
        lastGpuWorkNanos_.store(0, std::memory_order_relaxed);
        outputChecksum_.store(0, std::memory_order_relaxed);
        if (context_.capabilities().supported) {
            status_.store(GpuStressStatus::Stopped, std::memory_order_release);
        } else if (status_.load(std::memory_order_acquire) != GpuStressStatus::Error) {
            status_.store(GpuStressStatus::Unsupported, std::memory_order_release);
        }
        stopping_ = false;
    }
    stateChanged_.notify_all();
}

void GpuStress::shutdown() {
    stop();
    std::lock_guard<std::mutex> lock(mutex_);
    context_.shutdown();
    status_.store(GpuStressStatus::Checking, std::memory_order_release);
}

VulkanCapabilities GpuStress::capabilities() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return context_.capabilities();
}

GpuStressStatus GpuStress::status() const {
    return status_.load(std::memory_order_acquire);
}

std::uint64_t GpuStress::dispatchCount() const {
    return dispatchCount_.load(std::memory_order_relaxed);
}

std::uint64_t GpuStress::workGroupCount() const {
    return workGroupCount_.load(std::memory_order_relaxed);
}

std::uint64_t GpuStress::lastGpuWorkNanos() const {
    return lastGpuWorkNanos_.load(std::memory_order_relaxed);
}

std::uint64_t GpuStress::outputChecksum() const {
    return outputChecksum_.load(std::memory_order_relaxed);
}

std::string GpuStress::lastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return context_.lastError();
}

void GpuStress::workerLoop(int targetLoadPercent) {
    while (!stopRequested_.load(std::memory_order_acquire)) {
        const auto cycleStarted = std::chrono::steady_clock::now();
        const auto activeDeadline = targetLoadPercent == 100
            ? std::chrono::steady_clock::time_point::max()
            : cycleStarted + kDutyCycle * targetLoadPercent / 100;

        do {
            std::uint64_t gpuWorkNanos = 0;
            std::uint64_t checksum = 0;
            std::uint32_t workGroups = 0;
            if (!context_.submitAndWait(&gpuWorkNanos, &checksum, &workGroups)) {
                status_.store(GpuStressStatus::Error, std::memory_order_release);
                stopRequested_.store(true, std::memory_order_release);
                __android_log_print(
                    ANDROID_LOG_ERROR,
                    kLogTag,
                    "GPU stress worker stopped: %s",
                    context_.lastError().c_str());
                break;
            }
            dispatchCount_.fetch_add(1, std::memory_order_relaxed);
            workGroupCount_.fetch_add(workGroups, std::memory_order_relaxed);
            lastGpuWorkNanos_.store(gpuWorkNanos, std::memory_order_relaxed);
            outputChecksum_.store(checksum, std::memory_order_relaxed);
        } while (!stopRequested_.load(std::memory_order_acquire) &&
                 std::chrono::steady_clock::now() < activeDeadline);

        if (targetLoadPercent < 100 &&
            !stopRequested_.load(std::memory_order_acquire)) {
            std::unique_lock<std::mutex> waitLock(mutex_);
            workerWake_.wait_until(
                waitLock,
                cycleStarted + kDutyCycle,
                [this] { return stopRequested_.load(std::memory_order_acquire); });
        }
    }
}
