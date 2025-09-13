/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.admin;

import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Represents a request to execute an administrative command. The request contains
 * the name of the command to be executed, a list of arguments, and handlers for
 * processing the command's output and error output.
 * <p>
 * This class can be used in conjunction with administrative command processing
 * systems to encapsulate the details of the request and manage the flow of output data.
 */
@Data
public class AdminCommandRequest {

    /**
     * Create an empty AdminCommandRequest.
     */
    public AdminCommandRequest() {
    }

    private String command;
    private List<String> arguments = new ArrayList<>();
    @ToString.Exclude
    private Consumer<Object> output;
    @ToString.Exclude
    private Consumer<Object> errOutput;
}
