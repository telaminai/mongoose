/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Feed status: whether an event source has delivered everything currently available to it, and whether the processors
 * have dispatched it, so a host can tell deterministically that a feed has settled (docs/design/feed-caught-up.md).
 */
package com.telamin.mongoose.service.feedstatus;
