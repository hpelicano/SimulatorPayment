package org.simulator.acquirer;

import org.simulator.common.SimulatorException;
import org.simulator.common.event.EventBus;
import org.simulator.common.event.TransactionEvent;
import org.simulator.common.event.TransactionEvent.Outcome;
import org.simulator.common.util.Hex;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.iso.dialect.MessageDialect;
import org.simulator.persistence.repo.TransactionLogRecord;
import org.simulator.persistence.repo.TransactionLogRepository;
import org.simulator.transport.Exchange;
import org.simulator.transport.TransportException;
import org.simulator.validator.MessageValidator;
import org.simulator.validator.ValidationProfile;
import org.simulator.validator.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import java.util.List;

/**
 * Acquirer module: builds, sends and evaluates transactions against Base24 (or any host).
 * <p>
 * Every completed transaction is validated (when a profile is supplied), published as a
 * {@link TransactionEvent} and appended (plain PAN, redacted track details/PIN) to the transaction log.
 */
public class AcquirerService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AcquirerService.class);
    private static final DateTimeFormatter MMDDHHMMSS = DateTimeFormatter.ofPattern("MMddHHmmss");

    private final MessageService messages;
    private final MessageValidator validator;
    private final EventBus bus;
    private final TransactionLogRepository txLog;
    private final SequenceGenerator sequences;
    private final TemplateEngine templates;

    private volatile TransactionChannel channel;
    private volatile String environment = "DEV";
    private volatile boolean autoReversalOnTimeout;
    private volatile boolean logPayloads = true;
    private volatile UnaryOperator<IsoMessage> requestInterceptor;
    private final List<BiConsumer<TransactionOutcome, String>> outcomeListeners = new CopyOnWriteArrayList<>();

    public AcquirerService(MessageService messages, MessageValidator validator, EventBus bus,
                           TransactionLogRepository txLog) {
        this(messages, validator, bus, txLog, new SequenceGenerator());
    }

    public AcquirerService(MessageService messages, MessageValidator validator, EventBus bus,
                           TransactionLogRepository txLog, SequenceGenerator sequences) {
        this.messages = messages;
        this.validator = validator;
        this.bus = bus;
        this.txLog = txLog;
        this.sequences = sequences;
        this.templates = new TemplateEngine(sequences);
    }

    // ------------------------------------------------------------------ channel

    public synchronized void useChannel(TransactionChannel newChannel) {
        TransactionChannel old = this.channel;
        this.channel = newChannel;
        if (old != null && old != newChannel) {
            old.close();
        }
    }

    public synchronized void disconnect() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    public boolean isConnected() {
        TransactionChannel c = channel;
        return c != null && c.isConnected();
    }

    public TransactionChannel channel() {
        return channel;
    }

    // ------------------------------------------------------------------ building

    public IsoMessage build(TransactionTemplate template, Map<String, String> variables) {
        String dialect = channel != null ? channel.dialect() : null;
        IsoMessage msg = templates.build(template, dialect, variables);
        if (msg.getDialect() == null) {
            throw new SimulatorException("No dialect: set it in the template or connect a channel first");
        }
        return msg;
    }

    public CompletableFuture<TransactionOutcome> send(TransactionTemplate template, Map<String, String> variables,
                                                      String source) {
        IsoMessage req = build(template, variables);
        return send(req, template.getValidation(), source);
    }

    public CompletableFuture<TransactionOutcome> echoTest() {
        TransactionChannel c = requireChannel();
        String stan = sequences.nextStan();
        IsoMessage m = MessageBuilders.networkManagement(c.dialect(), "301", stan, transmissionDateTime());
        return send(m, ValidationProfile.expecting("00"), "ACQUIRER");
    }

    public CompletableFuture<TransactionOutcome> reverse(TransactionOutcome original, String mti) {
        IsoMessage rev = MessageBuilders.reversal(original.request(), original.response(), mti,
                sequences.nextStan(), original.status() == Outcome.TIMEOUT ? "68" : null);
        return send(rev, null, "ACQUIRER");
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends a request and evaluates the result. The returned future never completes exceptionally:
     * timeouts and errors are reported in {@link TransactionOutcome#status()}.
     */
    public CompletableFuture<TransactionOutcome> send(IsoMessage original, ValidationProfile profile, String source) {
        TransactionChannel c = requireChannel();
        IsoMessage request = original;
        UnaryOperator<IsoMessage> interceptor = requestInterceptor;
        if (interceptor != null) {
            try {
                request = interceptor.apply(original);
            } catch (RuntimeException e) {
                return CompletableFuture.completedFuture(onFailure(original, e, 0, source));
            }
        }
        final IsoMessage req = request;
        int timeout = c.defaultTimeoutMs();
        long start = System.nanoTime();
        CompletableFuture<Exchange> f;
        try {
            f = c.send(req, timeout);
        } catch (RuntimeException e) {
            f = CompletableFuture.failedFuture(e);
        }
        return f.handle((ex, err) -> err == null
                        ? onResponse(ex, profile, source)
                        : onFailure(req, unwrap(err), (System.nanoTime() - start) / 1000, source))
                .thenCompose(outcome -> {
                    if (autoReversalOnTimeout && outcome.status() == Outcome.TIMEOUT
                            && isReversible(req.getMti())) {
                        log.info("Timeout on STAN {} - sending automatic reversal", req.get(11));
                        return reverse(outcome, "0420").handle((r, e) -> outcome);
                    }
                    return CompletableFuture.completedFuture(outcome);
                });
    }

    private TransactionOutcome onResponse(Exchange ex, ValidationProfile profile, String source) {
        IsoMessage req = ex.request();
        IsoMessage resp = ex.response();
        String rc = resp.get(39);
        Outcome status = TransactionEvent.outcomeFor(rc);
        if (rc == null && resp.getMti() != null && resp.getMti().startsWith("08")) {
            status = Outcome.APPROVED;
        }
        ValidationResult validation = null;
        if (profile != null) {
            MessageDialect d = messages.dialect(resp.getDialect() != null ? resp.getDialect() : req.getDialect());
            validation = validator.validateExchange(profile, req, resp, d);
        }
        TransactionOutcome o = new TransactionOutcome(req, resp, ex.rawRequest(), ex.rawResponse(), status, rc,
                ex.latencyMicros(), validation, null);
        record(o, source);
        return o;
    }

    private TransactionOutcome onFailure(IsoMessage req, Throwable err, long micros, String source) {
        boolean timeout = err instanceof TransportException te && te.isTimeout();
        Outcome status = timeout ? Outcome.TIMEOUT : Outcome.ERROR;
        byte[] raw = null;
        try {
            raw = messages.pack(req);
        } catch (RuntimeException ignored) {
            // the request itself may be what failed to encode
        }
        TransactionOutcome o = new TransactionOutcome(req, null, raw, null, status, null, micros, null,
                err.getMessage());
        record(o, source);
        return o;
    }

    private void record(TransactionOutcome o, String source) {
        IsoMessage req = o.request();
        String pan = req.get(2);
        String bin = pan != null && pan.length() >= 6 ? pan.substring(0, 6) : null;
        bus.publish(new TransactionEvent(source, req.getMti(), req.get(3), bin, o.responseCode(), o.status(),
                o.latencyMicros(), Instant.now()));
        for (BiConsumer<TransactionOutcome, String> l : outcomeListeners) {
            try {
                l.accept(o, source);
            } catch (RuntimeException e) {
                log.warn("Outcome listener failed: {}", e.getMessage());
            }
        }
        if (txLog == null) {
            return;
        }
        try {
            txLog.append(new TransactionLogRecord(null, source, environment, req.getDialect(), req.getMti(),
                    req.get(3), req.get(11), req.get(37), req.get(41), pan, req.get(4),
                    o.responseCode(), o.status().name(), o.latencyMicros(),
                    logPayloads ? maskedJson(req) : null,
                    logPayloads && o.response() != null ? maskedJson(o.response()) : null,
                    null, null, o.error(), Instant.now()));
        } catch (RuntimeException e) {
            log.warn("Cannot log transaction: {}", e.getMessage());
        }
    }

    /** JSON with plain PAN and redacted non-PAN sensitive data; raw hex may contain PIN/security data. */
    public String maskedJson(IsoMessage msg) {
        return messages.toJson(msg.redactedCopy());
    }

    /** Transforms every outgoing request (e.g. software HSM: PIN block, ARQC, MAC). */
    public void setRequestInterceptor(UnaryOperator<IsoMessage> interceptor) {
        this.requestInterceptor = interceptor;
    }

    /** Receives every completed transaction with its source (recorder, exporters...). */
    public void addOutcomeListener(BiConsumer<TransactionOutcome, String> listener) {
        outcomeListeners.add(listener);
    }

    public void removeOutcomeListener(BiConsumer<TransactionOutcome, String> listener) {
        outcomeListeners.remove(listener);
    }

    public String hex(byte[] raw) {
        return raw == null ? "" : Hex.encode(raw);
    }

    private TransactionChannel requireChannel() {
        TransactionChannel c = channel;
        if (c == null) {
            throw new SimulatorException("Acquirer is not connected");
        }
        return c;
    }

    private static boolean isReversible(String mti) {
        return mti != null && (mti.startsWith("01") || mti.startsWith("02"));
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public String transmissionDateTime() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(MMDDHHMMSS);
    }

    public SequenceGenerator sequences() { return sequences; }
    public TemplateEngine templateEngine() { return templates; }
    public MessageService messages() { return messages; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getEnvironment() { return environment; }
    public void setAutoReversalOnTimeout(boolean v) { this.autoReversalOnTimeout = v; }
    public boolean isAutoReversalOnTimeout() { return autoReversalOnTimeout; }
    public void setLogPayloads(boolean v) { this.logPayloads = v; }

    @Override
    public void close() {
        disconnect();
    }
}
