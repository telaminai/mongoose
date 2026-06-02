/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.mongoose.browser.Stub;

/**
 * Compile-only stub of {@code com.telamin.mongoose.plugin.svc.adminweb.WebAdminService}.
 * Lets playground source reference the admin-console wiring; can't actually
 * run a Javalin server under CheerpJ.
 */
public class WebAdminService {

    public WebAdminService() {
    }

    public void setListenPort(int port) {
        throw Stub.notRunnable();
    }

    public void setBindAddress(String bindAddress) {
        throw Stub.notRunnable();
    }

    public void setInstanceLabel(String label) {
        throw Stub.notRunnable();
    }
}
