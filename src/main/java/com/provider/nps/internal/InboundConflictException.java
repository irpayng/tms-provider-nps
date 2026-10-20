package com.provider.nps.internal;

/** The same NIBSS identity was reused with different business data. */
public class InboundConflictException extends IllegalArgumentException {
    public InboundConflictException() {
        super("Message identity conflicts with previously received business data");
    }
}
