/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.delivery;

/**
 * Registered by every server under {@link #SERVICE_NAME}, so a processor can take a {@link ProcessingCheckpoint} through
 * {@code @ServiceRegistered} (docs/design/feed-delivery.md, section 5).
 */
public interface ProcessingCheckpointService {

    String SERVICE_NAME = "mongoose.processingCheckpoints";

    /**
     * Hold acknowledgements of {@code feedName}'s items on {@code processorGroup}'s queue until
     * {@link ProcessingCheckpoint#durableThrough} says so. Take it before the feed starts delivering (in a processor's
     * {@code init} or {@code start}); items acknowledged before it was taken stay acknowledged. Taking it again returns
     * the same hold, held again.
     */
    ProcessingCheckpoint hold(String feedName, String processorGroup);
}
