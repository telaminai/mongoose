package com.telamin.mongoose.service.admin;

import java.util.List;

/**
 * What a lambda admin command's audit record names (spec-replay-recording 3d): the command and its arguments. The
 * invoker opens the processor's audit record with it, runs the command, and closes the record, so the command's
 * {@code auditLog} writes land in a record of their own instead of splicing into the next event's.
 */
public record AdminCommandEvent(String command, List<String> args) { }
