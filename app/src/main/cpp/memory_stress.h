#pragma once

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

class MemoryStress final {
public:
    MemoryStress() = default;
    ~MemoryStress();

    MemoryStress(const MemoryStress&) = delete;
    MemoryStress& operator=(const MemoryStress&) = delete;

    std::int64_t start(std::int64_t targetBytes, std::int64_t minimumAvailableBytes = 0);
    void stop();
    bool isRunning() const;
    std::int64_t allocatedBytes() const;
    std::int64_t processedBytes() const;

private:
    struct Block {
        std::uint8_t* data;
        std::size_t size;
    };

    void workerLoop();
    void freeBlocksLocked();

    mutable std::mutex mutex_;
    std::condition_variable stateChanged_;
    std::vector<Block> blocks_;
    std::thread worker_;
    bool active_ = false;
    bool allocating_ = false;
    bool stopping_ = false;
    std::atomic<bool> stopRequested_{true};
    std::atomic<bool> workerRunning_{false};
    std::atomic<std::int64_t> allocatedBytes_{0};
    std::atomic<std::int64_t> processedBytes_{0};
    std::atomic<std::uint64_t> sink_{0};
};
