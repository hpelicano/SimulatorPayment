package org.simulator.acquirer;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe generator for STAN (DE11, 000001..999999, wraps) and RRN (DE37, yDDDhh + STAN).
 */
public class SequenceGenerator {

    private static final DateTimeFormatter RRN_PREFIX = DateTimeFormatter.ofPattern("yDDDHH");

    private final AtomicInteger stan;
    private final Clock clock;

    public SequenceGenerator() {
        this(1, Clock.systemDefaultZone());
    }

    public SequenceGenerator(int start, Clock clock) {
        this.stan = new AtomicInteger(Math.max(0, start - 1));
        this.clock = clock;
    }

    public String nextStan() {
        int v = stan.updateAndGet(x -> x >= 999_999 ? 1 : x + 1);
        return String.format("%06d", v);
    }

    /** Builds an RRN from the given STAN: last digit of year, julian day, hour and STAN (12 chars). */
    public String rrn(String stanValue) {
        String prefix = ZonedDateTime.now(clock).format(RRN_PREFIX);
        prefix = prefix.substring(prefix.length() - 6);
        return prefix + stanValue;
    }

    public Clock clock() {
        return clock;
    }
}
