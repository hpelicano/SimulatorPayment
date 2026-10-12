package org.simulator.ui.util;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Logback appender that keeps the last log lines in memory for the Logs view.
 * Configure it in logback.xml as {@code org.simulator.ui.util.UiLogAppender}.
 */
public class UiLogAppender extends AppenderBase<ILoggingEvent> {

    public record Line(Instant at, String level, String logger, String message) {
        private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

        @Override
        public String toString() {
            return F.format(at) + " " + String.format("%-5s", level) + " " + logger + " - " + message;
        }
    }

    private static final int CAPACITY = 5000;
    private static final Deque<Line> BUFFER = new ArrayDeque<>();
    private static final List<Consumer<Line>> LISTENERS = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent e) {
        String logger = e.getLoggerName();
        int dot = logger.lastIndexOf('.');
        String msg = e.getFormattedMessage();
        if (e.getThrowableProxy() != null) {
            msg += " | " + e.getThrowableProxy().getClassName() + ": " + e.getThrowableProxy().getMessage();
        }
        Line line = new Line(Instant.ofEpochMilli(e.getTimeStamp()), e.getLevel().toString(),
                dot >= 0 ? logger.substring(dot + 1) : logger, msg);
        synchronized (BUFFER) {
            BUFFER.addLast(line);
            while (BUFFER.size() > CAPACITY) {
                BUFFER.removeFirst();
            }
        }
        for (Consumer<Line> l : LISTENERS) {
            l.accept(line);
        }
    }

    public static List<Line> snapshot() {
        synchronized (BUFFER) {
            return new ArrayList<>(BUFFER);
        }
    }

    public static void clear() {
        synchronized (BUFFER) {
            BUFFER.clear();
        }
    }

    public static void addListener(Consumer<Line> l) {
        LISTENERS.add(l);
    }

    public static void removeListener(Consumer<Line> l) {
        LISTENERS.remove(l);
    }
}
