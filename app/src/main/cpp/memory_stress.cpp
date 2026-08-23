#include "memory_stress.h"

#include <android/log.h>
#include <sys/mman.h>
#include <sys/prctl.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <new>
#include <utility>

namespace {
constexpr std::size_t kAllocationBlockBytes = 8U * 1024U * 1024U;
constexpr std::size_t kTouchStrideBytes = 4096U;
constexpr std::size_t kCopyChunkBytes = 1024U * 1024U;
constexpr std::int64_t kMaximumTargetBytes = 1536LL * 1024LL * 1024LL;
constexpr char kLogTag[] = "ResourceStress";
constexpr char kNativeMappingName[] = "libc_malloc";

std::int64_t readAvailableMemoryBytes() {
    FILE* file = std::fopen("/proc/meminfo", "r");
    if (file == nullptr) {
        return -1;
    }
    char label[64]{};
    unsigned long long valueKiB = 0;
    char unit[16]{};
    std::int64_t availableBytes = -1;
    while (std::fscanf(file, "%63s %llu %15s", label, &valueKiB, unit) == 3) {
        if (std::strcmp(label, "MemAvailable:") == 0) {
            availableBytes = static_cast<std::int64_t>(valueKiB * 1024ULL);
            break;
        }
    }
    std::fclose(file);
    return availableBytes;
}

#ifndef PR_SET_VMA
constexpr int PR_SET_VMA = 0x53564d41;
#endif
#ifndef PR_SET_VMA_ANON_NAME
constexpr int PR_SET_VMA_ANON_NAME = 0;
#endif
}  // namespace

MemoryStress::~MemoryStress() {
    stop();
}

std::int64_t MemoryStress::start(
    std::int64_t targetBytes,
    std::int64_t minimumAvailableBytes) {
    if (targetBytes <= 0 || targetBytes > kMaximumTargetBytes ||
        minimumAvailableBytes < 0) {
        return 0;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (active_ || allocating_ || stopping_ || worker_.joinable() || !blocks_.empty()) {
            return 0;
        }
        active_ = true;
        allocating_ = true;
        stopRequested_.store(false, std::memory_order_release);
        workerRunning_.store(false, std::memory_order_release);
        allocatedBytes_.store(0, std::memory_order_relaxed);
        processedBytes_.store(0, std::memory_order_relaxed);
    }

    std::int64_t remainingBytes = targetBytes;
    while (remainingBytes > 0 && !stopRequested_.load(std::memory_order_acquire)) {
        const std::int64_t availableBytes = readAvailableMemoryBytes();
        if (minimumAvailableBytes > 0 && availableBytes >= 0 &&
            availableBytes < minimumAvailableBytes) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "Memory allocation stopped at safety reserve: available=%lld reserve=%lld",
                static_cast<long long>(availableBytes),
                static_cast<long long>(minimumAvailableBytes));
            break;
        }
        const std::size_t blockSize = static_cast<std::size_t>(
            std::min<std::int64_t>(remainingBytes, static_cast<std::int64_t>(kAllocationBlockBytes)));
        void* mapping = mmap(
            nullptr,
            blockSize,
            PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS,
            -1,
            0);
        if (mapping == MAP_FAILED) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "Memory mapping stopped after %lld bytes",
                static_cast<long long>(allocatedBytes_.load(std::memory_order_relaxed)));
            break;
        }
        if (prctl(
                PR_SET_VMA,
                PR_SET_VMA_ANON_NAME,
                mapping,
                blockSize,
                kNativeMappingName) != 0) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "Unable to classify %zu-byte stress mapping as native memory",
                blockSize);
        }
        auto* data = static_cast<std::uint8_t*>(mapping);

        for (std::size_t offset = 0; offset < blockSize; offset += kTouchStrideBytes) {
            data[offset] = static_cast<std::uint8_t>((offset / kTouchStrideBytes) ^ blockSize);
            if (stopRequested_.load(std::memory_order_acquire)) {
                break;
            }
        }
        if (blockSize > 0) {
            data[blockSize - 1U] ^= 0xA5U;
        }

        if (stopRequested_.load(std::memory_order_acquire)) {
            munmap(data, blockSize);
            break;
        }

        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!active_ || stopRequested_.load(std::memory_order_acquire)) {
                munmap(data, blockSize);
                break;
            }
            try {
                blocks_.push_back(Block{data, blockSize});
            } catch (...) {
                munmap(data, blockSize);
                break;
            }
            allocatedBytes_.fetch_add(
                static_cast<std::int64_t>(blockSize),
                std::memory_order_relaxed);
        }
        remainingBytes -= static_cast<std::int64_t>(blockSize);
    }

    bool workerCreationFailed = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        allocating_ = false;
        if (active_ && !stopRequested_.load(std::memory_order_acquire) && !blocks_.empty()) {
            try {
                workerRunning_.store(true, std::memory_order_release);
                worker_ = std::thread(&MemoryStress::workerLoop, this);
            } catch (...) {
                active_ = false;
                stopRequested_.store(true, std::memory_order_release);
                workerRunning_.store(false, std::memory_order_release);
                workerCreationFailed = true;
            }
        } else if (blocks_.empty()) {
            active_ = false;
        }
    }
    stateChanged_.notify_all();

    if (workerCreationFailed) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Memory worker creation failed");
        stop();
        return 0;
    }
    return allocatedBytes_.load(std::memory_order_relaxed);
}

void MemoryStress::stop() {
    std::thread workerToJoin;
    {
        std::unique_lock<std::mutex> lock(mutex_);
        if (stopping_) {
            stateChanged_.wait(lock, [this] { return !stopping_; });
            return;
        }
        stopping_ = true;
        active_ = false;
        stopRequested_.store(true, std::memory_order_release);
        stateChanged_.wait(lock, [this] { return !allocating_; });
        if (worker_.joinable()) {
            workerToJoin = std::move(worker_);
        }
    }

    if (workerToJoin.joinable()) {
        workerToJoin.join();
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        freeBlocksLocked();
        allocatedBytes_.store(0, std::memory_order_relaxed);
        processedBytes_.store(0, std::memory_order_relaxed);
        workerRunning_.store(false, std::memory_order_release);
        stopping_ = false;
    }
    stateChanged_.notify_all();
}

bool MemoryStress::isRunning() const {
    return workerRunning_.load(std::memory_order_acquire);
}

std::int64_t MemoryStress::allocatedBytes() const {
    return allocatedBytes_.load(std::memory_order_relaxed);
}

std::int64_t MemoryStress::processedBytes() const {
    return processedBytes_.load(std::memory_order_relaxed);
}

void MemoryStress::workerLoop() {
    std::vector<Block> blocks;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        blocks = blocks_;
    }

    std::vector<std::uint8_t> copyBuffer;
    try {
        copyBuffer.resize(kCopyChunkBytes);
    } catch (const std::bad_alloc&) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Memory copy buffer allocation failed");
        stopRequested_.store(true, std::memory_order_release);
        workerRunning_.store(false, std::memory_order_release);
        return;
    }

    std::uint64_t checksum = 0;
    while (!stopRequested_.load(std::memory_order_acquire)) {
        for (const Block& block : blocks) {
            for (std::size_t offset = 0;
                 offset < block.size && !stopRequested_.load(std::memory_order_acquire);
                 offset += copyBuffer.size()) {
                const std::size_t length = std::min(copyBuffer.size(), block.size - offset);
                std::memcpy(copyBuffer.data(), block.data + offset, length);
                for (std::size_t index = 0; index < length; index += 64U) {
                    checksum = (checksum * 0x100000001B3ULL) ^ copyBuffer[index];
                    copyBuffer[index] ^= static_cast<std::uint8_t>(checksum >> 17U);
                }
                std::memcpy(block.data + offset, copyBuffer.data(), length);
                processedBytes_.fetch_add(
                    static_cast<std::int64_t>(length) * 2LL,
                    std::memory_order_relaxed);
            }
        }
        sink_.store(checksum, std::memory_order_relaxed);
    }
    sink_.fetch_xor(checksum, std::memory_order_relaxed);
    workerRunning_.store(false, std::memory_order_release);
}

void MemoryStress::freeBlocksLocked() {
    for (const Block& block : blocks_) {
        if (munmap(block.data, block.size) != 0) {
            __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "munmap failed for %zu-byte stress block",
                block.size);
        }
    }
    blocks_.clear();
}
