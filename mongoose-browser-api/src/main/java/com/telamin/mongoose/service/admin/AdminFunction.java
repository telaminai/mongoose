/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.admin;

import java.util.List;
import java.util.function.Consumer;

/**
 * Signature-only Java 8 stub of {@code com.telamin.mongoose.service.admin.AdminFunction}.
 * <p>
 * The functional shape of an admin command — what a processor passes to
 * {@link AdminCommandRegistry#registerCommand}. Lets the playground's
 * {@code DataGenerator} type-check its {@code datagen.*} command handlers.
 */
@FunctionalInterface
public interface AdminFunction<OUT, ERR> {

    void processAdminCommand(List<String> commands, Consumer<OUT> output, Consumer<ERR> errOutput);
}
