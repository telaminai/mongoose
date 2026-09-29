package com.telamin.mongoose.replay;

import java.util.List;

/** Each processor's entries, in its input order. */
public interface ReplayStore {
    void append(String processor, ReplayEntry entry);

    List<ReplayEntry> entries(String processor);
}
