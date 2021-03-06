/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.videobridge.util;

import org.jetbrains.annotations.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.stats.*;
import org.json.simple.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Implements a byte array pool based on a number of independent partitions.
 * Buffers are requested and returned to a random partition, which helps with
 * contention.
 *
 * @author Brian Baldino
 * @author Boris Grozev
 */
class PartitionedByteBufferPool
{
    /**
     * The number of partitions.
     */
    private static final int NUM_PARTITIONS = 8;

    /**
     * How many buffers to pre-allocate in each partition.
     */
    private static final int INITIAL_SIZE = 10;

    /**
     * Whether to accept small buffers (<1500) that are returned.
     */
    private static final boolean ACCEPT_SMALL_BUFFERS = false;

    /**
     * The {@link Logger}
     */
    private static final Logger logger
            = Logger.getLogger(PartitionedByteBufferPool.class);

    /**
     * Used to select a partition at random.
     */
    private static final Random random = new Random();

    /**
     * The partitions.
     */
    private final Partition[] partitions = new Partition[NUM_PARTITIONS];

    /**
     * Whether to keep track of request/return rates and other basic statistics.
     * As opposed to {@link ByteBufferPool#ENABLE_BOOKKEEPING} this has a
     * relatively low overhead and can be kept on in production if necessary.
     */
    private boolean enableStatistics = false;

    private final int defaultBufferSize;

    /**
     * Initializes a new {@link PartitionedByteBufferPool} instance with
     * a given initial size for each partition.
     */
    PartitionedByteBufferPool(int defaultBufferSize)
    {
        this.defaultBufferSize = defaultBufferSize;
        for (int i = 0; i < NUM_PARTITIONS; ++i)
        {
            partitions[i] = new Partition(i, INITIAL_SIZE);
        }
        logger.info("Initialized a new " + getClass().getSimpleName()
                + " with " + NUM_PARTITIONS + " partitions.");
    }

    /**
     * Enables or disables tracking of statistics.
     * @param enable whether to enable or disable.
     */
    void enableStatistics(boolean enable)
    {
        enableStatistics = enable;
    }

    /**
     * Returns a random partition.
     */
    private Partition getPartition()
    {
        return partitions[random.nextInt(NUM_PARTITIONS)];
    }

    /**
     * {@inheritDoc}
     */
    byte[] getBuffer(int size)
    {
        return getPartition().getBuffer(size);
    }

    /**
     * {@inheritDoc}
     */
    void returnBuffer(byte[] buf)
    {
        getPartition().returnBuffer(buf);
    }

    /**
     * Adds statistics for this pool to the given JSON object.
     * @param stats the JSON object to add stats to.
     */
    JSONObject getStats()
    {
        JSONObject stats = new JSONObject();
        stats.put("default_size", defaultBufferSize);
        JSONArray partitionStats = new JSONArray();
        for (Partition p : partitions)
        {
            partitionStats.add(p.getStatsJson());
        }
        stats.put("partitions", partitionStats);
        return stats;
    }

    /**
     * Gets the total number of times a new byte[] was allocated.
     * @return
     */
    long getNumAllocations()
    {
        long allocations = 0;
        for (int i = 0; i < NUM_PARTITIONS; i++)
        {
            allocations += partitions[i].numAllocations.get();
        }

        return allocations;
    }

    /**
     * A byte array pool with a single {@link LinkedBlockingQueue}.
     */
    private class Partition
    {
        /**
         * The queue in which we store available packets.
         */
        private final LinkedBlockingQueue<byte[]> pool
                = new LinkedBlockingQueue<>();

        /**
         * The ID of the partition (used for debugging).
         */
        private final int id;

        /**
         * The number of times a request was satisfied with a buffer from the
         * pool.
         */
        private final AtomicLong numNoAllocationNeeded = new AtomicLong(0);

        /**
         * The number of times a new {@code byte[]} had to be allocated.
         */
        private final AtomicLong numAllocations = new AtomicLong(0);

        /**
         * The number of times a new {@code byte[]} had to be allocated because
         * the pool was empty.
         */
        private final AtomicLong numEmptyPoolAllocations = new AtomicLong(0);

        /**
         * The number of times a new {@code byte[]} had to be allocated because
         * the pool did not have a buffer with the required size (but it was
         * not empty).
         */
        private final AtomicLong numWrongSizeAllocations = new AtomicLong(0);

        /**
         * Total number of requests.
         */
        private final AtomicLong numRequests = new AtomicLong(0);

        /**
         * Total number of returned buffers.
         */
        private final AtomicLong numReturns = new AtomicLong(0);

        /**
         * The number of times a small buffer (<1500 bytes) was returned.
         */
        private final AtomicLong numSmallReturns = new AtomicLong(0);

        /**
         * The number of times a buffer from the pool was discarded because it
         * was too small to satisfy a request.
         */
        private final AtomicLong numSmallBuffersDiscarded = new AtomicLong(0);

        /**
         * The number of times a large buffer (>1500 bytes) was requested.
         */
        private final AtomicLong numLargeRequests = new AtomicLong(0);

        /**
         * Request rate in requests per second over the last 10 seconds.
         */
        private final RateStatistics requestRate
            = new RateStatistics(10000, 1000);

        /**
         * Return rate in requests per second over the last 10 seconds.
         */
        private final RateStatistics returnRate
            = new RateStatistics(1000, 1000);

        /**
         * Initializes a new partition.
         * @param id
         * @param initialSize
         */
        Partition(int id, int initialSize)
        {
            this.id = id;
            for (int i = 0; i < initialSize; ++i)
            {
                pool.add(new byte[defaultBufferSize]);
            }
        }

        /**
         * Returns a buffer from this pool (allocates a new one if necessary).
         * @param requiredSize the minimum size.
         *
         * @return a {@code byte[]} with size at least {@code requiredSize}.
         */
        private byte[] getBuffer(int requiredSize)
        {
            if (ByteBufferPool.ENABLE_BOOKKEEPING)
            {
                logger.info("partition " + id + " request number "
                        + (numRequests.get() + 1) + ", pool has size "
                        + pool.size());
            }

            if (enableStatistics)
            {
                numRequests.incrementAndGet();
                requestRate.update(1, System.currentTimeMillis());
                if (requiredSize > defaultBufferSize)
                {
                    numLargeRequests.incrementAndGet();
                }
            }

            byte[] buf = pool.poll();
            if (buf == null)
            {
                buf = new byte[Math.max(defaultBufferSize, requiredSize)];
                numAllocations.incrementAndGet();
                if (enableStatistics)
                {
                    numEmptyPoolAllocations.incrementAndGet();
                }
            }
            else if (buf.length < requiredSize)
            {
                if (ByteBufferPool.ENABLE_BOOKKEEPING)
                {
                    logger.info("Needed buffer of size " + requiredSize
                            + ", got size " + buf.length + " retrying");
                }

                // This is unusual and shouldn't happen often, so we will just
                // allocate a new buffer.

                // If the size is smaller than the default AND we just witnessed
                // the buffer to be too small in practice, we'll throw it away.
                // This makes sure that if someone returns a small buffer (in
                // the practical sense) it will not get stuck in the pool.
                if (buf.length >= defaultBufferSize)
                {
                    pool.offer(buf);
                }
                else
                {
                    numSmallBuffersDiscarded.incrementAndGet();
                }

                buf = new byte[Math.max(defaultBufferSize, requiredSize)];
                numAllocations.incrementAndGet();
                if (enableStatistics)
                {
                    numWrongSizeAllocations.incrementAndGet();
                }
            }
            else if (enableStatistics)
            {
                numNoAllocationNeeded.incrementAndGet();
            }

            if (ByteBufferPool.ENABLE_BOOKKEEPING)
            {
                System.out.println("got buffer " + System.identityHashCode(buf)
                        + " from thread " + Thread.currentThread().getId()
                        + ", partition " + id + " now has size " + pool.size());
            }
            return buf;
        }

        /**
         * Returns a buffer to this pool.
         * @param buf
         */
        private void returnBuffer(@NotNull byte[] buf)
        {
            if (ByteBufferPool.ENABLE_BOOKKEEPING)
            {
                System.out.println("returned buffer " + System.identityHashCode(buf) +
                        " from thread " + Thread.currentThread().getId() + ", partition " + id +
                        " now has size " + pool.size());

            }

            if (enableStatistics)
            {
                numReturns.incrementAndGet();
                returnRate.update(1, System.currentTimeMillis());
            }

            if (buf.length < defaultBufferSize)
            {
                numSmallReturns.incrementAndGet();
                if (ACCEPT_SMALL_BUFFERS)
                {
                    pool.offer(buf);
                }
            }
            else
            {
                pool.offer(buf);
            }
        }

        /**
         * Gets a snapshot of the statistics of this partition in JSON format.
         */
        private JSONObject getStatsJson()
        {
            long now = System.currentTimeMillis();
            JSONObject stats = new JSONObject();
            stats.put("id", id);

            stats.put("num_requests", numRequests.get());
            stats.put("num_returns", numReturns.get());
            stats.put("requests_rate_rps", requestRate.getRate(now));
            stats.put("returns_rate_rps", returnRate.getRate(now));
            stats.put("current_size", pool.size());

            stats.put("num_allocations", numAllocations.get());
            stats.put("num_allocations_empty_pool", numEmptyPoolAllocations.get());
            stats.put("num_allocations_wrong_size", numWrongSizeAllocations.get());
            stats.put("num_no_allocation_needed", numNoAllocationNeeded.get());
            stats.put(
                    "allocation_percent",
                    100D * numAllocations.get() / Math.max(1, numRequests.get()));
            stats.put("num_small_returns", numSmallReturns.get());
            stats.put("num_large_requests", numLargeRequests.get());
            stats.put("num_small_discarded", numSmallBuffersDiscarded.get());

            return stats;
        }
    }
}
