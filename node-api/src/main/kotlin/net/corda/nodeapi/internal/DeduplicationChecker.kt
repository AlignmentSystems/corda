/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.nodeapi.internal

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A class allowing the deduplication of a strictly incrementing sequence number.
 */
class DeduplicationChecker(cacheExpiry: Duration) {
    // dedupe identity -> watermark cache
    private val watermarkCache = CacheBuilder.newBuilder()
            .expireAfterAccess(cacheExpiry.toNanos(), TimeUnit.NANOSECONDS)
            .build(WatermarkCacheLoader)

    private object WatermarkCacheLoader : CacheLoader<Any, AtomicLong>() {
        override fun load(key: Any) = AtomicLong(-1)
    }

    /**
     * @param identity the identity that generates the sequence numbers.
     * @param sequenceNumber the sequence number to check.
     * @return true if the message is unique, false if it's a duplicate.
     */
    fun checkDuplicateMessageId(identity: Any, sequenceNumber: Long): Boolean {
        return watermarkCache[identity].getAndUpdate { maxOf(sequenceNumber, it) } >= sequenceNumber
    }
}
