#include "cpu_stress.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>

namespace {
constexpr auto kDutyCycle = std::chrono::milliseconds(100);
constexpr int kOperationsPerBatch = 4096;

std::uint64_t rotateLeft(std::uint64_t value, unsigned int count) {
    return (value << count) | (value >> (64U - count));
}
}  // namespace

CpuStress::~CpuStress() {
    stop();
}

bool CpuStress::start(int threadCount, int targetLoadPercent) {
    if (threadCount <= 0 || threadCount > 256 || targetLoadPercent < 1 ||
        targetLoadPercent > 100) {
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (running_.load(std::memory_order_acquire) || !workers_.empty()) {
            return false;
        }
        running_.store(true, std::memory_order_release);
        try {
            workers_.reserve(static_cast<std::size_t>(threadCount));
            for (int index = 0; index < threadCount; ++index) {
                workers_.emplace_back(
                    &CpuStress::workerLoop,
                    this,
                    static_cast<std::uint32_t>(index),
                    targetLoadPercent);
            }
        } catch (...) {
            running_.store(false, std::memory_order_release);
        }
    }

    if (!running_.load(std::memory_order_acquire)) {
        stop();
        return false;
    }
    return true;
}

void CpuStress::stop() {
    std::vector<std::thread> workersToJoin;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        running_.store(false, std::memory_order_release);
        workersToJoin.swap(workers_);
    }
    for (std::thread& worker : workersToJoin) {
        if (worker.joinable()) {
            worker.join();
        }
    }
}

int CpuStress::threadCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return static_cast<int>(workers_.size());
}

void CpuStress::workerLoop(std::uint32_t workerIndex, int targetLoadPercent) {
    std::uint64_t integerState =
        0x9E3779B97F4A7C15ULL ^ (static_cast<std::uint64_t>(workerIndex) + 1ULL);
    double floatingState = 1.0 + static_cast<double>(workerIndex) * 0.03125;
    std::array<double, 32> values{};
    for (std::size_t index = 0; index < values.size(); ++index) {
        values[index] = floatingState + static_cast<double>(index) * 0.125;
    }

    while (running_.load(std::memory_order_acquire)) {
        const auto cycleStarted = std::chrono::steady_clock::now();
        const auto computeDeadline = targetLoadPercent == 100
            ? std::chrono::steady_clock::time_point::max()
            : cycleStarted + kDutyCycle * targetLoadPercent / 100;

        do {
            for (int operation = 0; operation < kOperationsPerBatch; ++operation) {
                integerState ^= integerState >> 12U;
                integerState ^= integerState << 25U;
                integerState ^= integerState >> 27U;
                integerState *= 0x2545F4914F6CDD1DULL;
                integerState = rotateLeft(integerState, 17U) ^
                    (0xD6E8FEB86659FD93ULL + static_cast<std::uint64_t>(operation));

                const std::size_t index = integerState & (values.size() - 1U);
                const std::size_t other = (index + 11U) & (values.size() - 1U);
                floatingState = std::fma(
                    floatingState,
                    1.0000001192092896,
                    values[other] * 0.0000009536743164);
                values[index] = std::sqrt(std::abs(floatingState) + 1.0) +
                    values[index] * 0.99991;
                floatingState = values[index] +
                    static_cast<double>(integerState & 0xFFFFU) * 0.000001;
            }

            std::uint64_t floatingBits = 0;
            static_assert(sizeof(floatingBits) == sizeof(floatingState));
            std::memcpy(&floatingBits, &floatingState, sizeof(floatingBits));
            sink_.fetch_xor(integerState ^ floatingBits, std::memory_order_relaxed);
        } while (running_.load(std::memory_order_acquire) &&
                 std::chrono::steady_clock::now() < computeDeadline);

        if (targetLoadPercent < 100 && running_.load(std::memory_order_acquire)) {
            std::this_thread::sleep_until(cycleStarted + kDutyCycle);
        }
    }

    sink_.fetch_xor(integerState, std::memory_order_relaxed);
}
