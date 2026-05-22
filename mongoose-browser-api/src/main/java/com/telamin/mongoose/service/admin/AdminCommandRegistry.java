/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.admin;

/**
 * Signature-only Java 8 stub of {@code com.telamin.mongoose.service.admin.AdminCommandRegistry}.
 * <p>
 * Scoped to the playground data generator's usage — {@link #registerCommand}
 * only. The real registry also exposes {@code processAdminCommandRequest} and
 * {@code commandList}; add them here only if an example starts using them.
 */
public interface AdminCommandRegistry {

    <OUT, ERR> void registerCommand(String name, AdminFunction<OUT, ERR> command);
}
