package com.telamin.mongoose.spike.replay;

/** SPIKE: a service interface a processor exports, called by a typed invoke strategy after the queue. */
public interface QuoteControl {
    void onQuoteControl(String command);
}
