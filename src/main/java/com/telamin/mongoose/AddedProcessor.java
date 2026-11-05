/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose;

import com.telamin.mongoose.control.ControlReply;

import java.util.concurrent.CompletionStage;

/**
 * What {@link MongooseServer#addProcessor} returns (design "Agent threads and replies"): the handle of exactly the
 * registration this add admitted, built before the add was sent, so a stop through it can never reach a later processor
 * under the same name; and the add's outcome. The handle is null when the add was refused before admission.
 */
public record AddedProcessor(ProcessorHandle handle, CompletionStage<ControlReply> reply) {
}
