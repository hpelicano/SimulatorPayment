package org.simulator.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Minimal synchronous, thread-safe publish/subscribe bus used to decouple transaction producers
 * (acquirer, issuer, stress) from consumers (statistics, UI, logging).
 */
public final class EventBus {

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<?>, List<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> Subscription subscribe(Class<T> type, Consumer<? super T> listener) {
        Consumer<Object> wrapper = e -> ((Consumer<Object>) listener).accept(e);
        subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(wrapper);
        return () -> subscribers.getOrDefault(type, List.of()).remove(wrapper);
    }

    public void publish(Object event) {
        subscribers.forEach((type, listeners) -> {
            if (type.isInstance(event)) {
                for (Consumer<Object> l : listeners) {
                    try {
                        l.accept(event);
                    } catch (RuntimeException ex) {
                        LOG.warn("Event listener failed for {}", event.getClass().getSimpleName(), ex);
                    }
                }
            }
        });
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
