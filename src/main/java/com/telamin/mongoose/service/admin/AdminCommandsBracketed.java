package com.telamin.mongoose.service.admin;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

/**
 * Declared by a processor that implements {@code DataFlow.runInEventCycle} but wants its LAMBDA admin commands run in the
 * audit bracket instead (the documented weaker form: its own audit record, no event cycle). #48 review, finding 2: the
 * decision is made from this declaration BEFORE the command is invoked. It used to be inferred from any
 * UnsupportedOperationException thrown inside runInEventCycle, so a supported processor whose cycle failed while setting
 * up (its clock, a buffered calculation) had its command silently run by the weaker route. Now, for a processor that
 * implements runInEventCycle and does not declare this, anything that fails before the command runs refuses it by name.
 * A processor that inherits the refusing default (generated before fluxtion runtime 1.1.0) is bracketed without it.
 */
@Experimental
public interface AdminCommandsBracketed {
}
