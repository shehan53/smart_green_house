package com.greenhouse.buffer;

import com.greenhouse.sim.Reading;

/**
 * Author: Role 3 - Concurrency Engineer
 * Concepts: #9 Monitor bounded buffer (wait() in a while loop, notifyAll()), #8 synchronized methods
 * Purpose: Fixed-size circular buffer (size 10) between the 5 sensor threads (producers) and the
 *          controller's consumer thread. Written by hand as a Java monitor - no BlockingQueue.
 */
public class BoundedBuffer {

    private final Reading[] items;
    private int head;     // next item to take
    private int tail;     // next free slot
    private int count;    // number of items in the buffer
    private long dropped; // readings dropped because the buffer stayed full

    public BoundedBuffer(int capacity) {
        items = new Reading[capacity];
    }

    /**
     * Producer side. Waits while the buffer is full, but only up to timeoutMs so a sensor never
     * misses its own 500 ms period. Returns false if the reading had to be dropped.
     */
    // CONCEPT 9: monitor - synchronized + wait() in a WHILE loop (re-check after every wake-up) + notifyAll()
    public synchronized boolean put(Reading reading, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (count == items.length) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                dropped++;
                return false;
            }
            wait(remaining);
        }
        items[tail] = reading;
        tail = (tail + 1) % items.length;
        count++;
        notifyAll(); // wake up the consumer waiting for data
        return true;
    }

    /** Consumer side. Waits while the buffer is empty. */
    // CONCEPT 9: while (empty) wait();  - never "if", because of spurious wake-ups and other consumers
    public synchronized Reading take() throws InterruptedException {
        while (count == 0) {
            wait();
        }
        Reading reading = items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        notifyAll(); // wake up producers waiting for space
        return reading;
    }

    public synchronized void clear() {
        for (int i = 0; i < items.length; i++) items[i] = null;
        head = tail = count = 0;
        notifyAll();
    }

    public synchronized int size() { return count; }

    public synchronized long getDropped() { return dropped; }

    public int capacity() { return items.length; }
}
