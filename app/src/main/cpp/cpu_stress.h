#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

class CpuStress final {
public:
    CpuStress() = default;
    ~CpuStress();

    CpuStress(const CpuStress&) = delete;
    CpuStress& operator=(const CpuStress&) = delete;

    bool start(int threadCount, int targetLoadPercent);
    void stop();
    int threadCount() const;

private:
    void workerLoop(std::uint32_t workerIndex, int targetLoadPercent);

    mutable std::mutex mutex_;
    std::vector<std::thread> workers_;
    std::atomic<bool> running_{false};
    std::atomic<std::uint64_t> sink_{0};
};
