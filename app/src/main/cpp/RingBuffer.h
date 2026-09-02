#ifndef SOUNDVISUALIZER_RINGBUFFER_H
#define SOUNDVISUALIZER_RINGBUFFER_H

#include <atomic>
#include <vector>
#include <stdexcept>
#include <cstring>

// A simple lock-free single-producer single-consumer ring buffer for audio PCM data.
template<typename T>
class RingBuffer {
public:
    explicit RingBuffer(size_t capacity) : buffer_(capacity), capacity_(capacity), head_(0), tail_(0) {
        if (capacity == 0) {
            throw std::invalid_argument("Capacity must be greater than 0");
        }
    }

    // Producer writes to the buffer
    bool push(const T* data, size_t count) {
        size_t current_tail = tail_.load(std::memory_order_relaxed);
        size_t current_head = head_.load(std::memory_order_acquire);
        
        size_t available_space = capacity_ - (current_tail - current_head);
        if (available_space < count) {
            return false; // Not enough space
        }

        for (size_t i = 0; i < count; ++i) {
            buffer_[(current_tail + i) % capacity_] = data[i];
        }

        tail_.store(current_tail + count, std::memory_order_release);
        return true;
    }

    // Consumer reads from the buffer
    size_t pop(T* out_data, size_t count) {
        size_t current_head = head_.load(std::memory_order_relaxed);
        size_t current_tail = tail_.load(std::memory_order_acquire);
        
        size_t available_data = current_tail - current_head;
        size_t elements_to_read = std::min(available_data, count);

        if (elements_to_read == 0) {
            return 0;
        }

        for (size_t i = 0; i < elements_to_read; ++i) {
            out_data[i] = buffer_[(current_head + i) % capacity_];
        }

        head_.store(current_head + elements_to_read, std::memory_order_release);
        return elements_to_read;
    }

    // Get available data size without popping
    size_t size() const {
        size_t current_head = head_.load(std::memory_order_acquire);
        size_t current_tail = tail_.load(std::memory_order_acquire);
        return current_tail - current_head;
    }

private:
    std::vector<T> buffer_;
    size_t capacity_;
    alignas(64) std::atomic<size_t> head_;
    alignas(64) std::atomic<size_t> tail_;
};

#endif // SOUNDVISUALIZER_RINGBUFFER_H
