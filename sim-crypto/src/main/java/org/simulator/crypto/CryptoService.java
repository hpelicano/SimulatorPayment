package org.simulator.crypto;

import org.simulator.common.util.Hex;
import org.simulator.config.model.CryptoConfig;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.iso.codec.FieldTrace;
import org.simulator.iso.codec.Tlv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Software HSM applied to ISO 8583 messages: PIN block encryption (DE52 marker {@code PIN:1234}),
 * ARQC generation (DE55 tag 9F26), ARQC verification + ARPC (tag 91) and MAC (DE64/DE128).
 */
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final String ZERO_MAC = "0000000000000000";

    public record ArqcCheck(boolean present, boolean valid, String expected, String received) {}

    private final Supplier<CryptoConfig> config;
    private final UnaryOperator<String> secrets;
    private final MessageService messages;

    /**
     * @param config   current environment crypto config (re-read on every call)
     * @param secrets  resolves {@code ENC(...)} values to clear hex
     */
    public CryptoService(Supplier<CryptoConfig> config, UnaryOperator<String> secrets, MessageService messages) {
        this.config = config;
        this.secrets = secrets;
        this.messages = messages;
    }

    public boolean enabled() {
        CryptoConfig c = config.get();
        return c != null && c.isEnabled();
    }

    public CryptoConfig config() { return config.get(); }

    public byte[] key(String name) {
        CryptoConfig c = config.get();
        String v = switch (name) {
            case "zpk" -> c.getZpk();
            case "mak" -> c.getMak();
            case "cvk" -> c.getCvk();
            case "imkAc" -> c.getImkAc();
            default -> throw new IllegalArgumentException(name);
        };
        return Ciphers.parseKey(v == null ? null : secrets.apply(v), name);
    }

    /** Acquirer side: called right before a request is packed and sent. */
    public IsoMessage prepareRequest(IsoMessage req) {
        if (!enabled()) return req;
        CryptoConfig c = config.get();
        IsoMessage m = req.copy();
        String de52 = m.get(52);
        if (de52 != null && de52.toUpperCase(Locale.ROOT).startsWith("PIN:")) {
            m.set(52, PinBlock.encrypt(key("zpk"), c.getPinBlockFormat(), de52.substring(4).trim(), pan(m)));
        }
        if (c.isGenerateArqc() && m.has(55) && present(c.getImkAc())) {
            Map<String, String> tags = Emv.tags(m.get(55));
            if (tags.containsKey("9F26") && tags.containsKey("9F36")) {
                String arqc = Emv.arqc(cardKey(m, tags), tags, sessionMode(), iadMode());
                m.set(55, replaceTag(m.get(55), "9F26", arqc));
            }
        }
        applyMac(m);
        return m;
    }

    /** Issuer side: verifies the request ARQC, adds the ARPC (tag 91) and the response MAC. */
    public IsoMessage processResponse(IsoMessage req, IsoMessage resp) {
        if (!enabled() || resp == null) return resp;
        CryptoConfig c = config.get();
        IsoMessage r = resp.copy();
        if (req.has(55) && present(c.getImkAc())) {
            ArqcCheck check = verifyArqc(req);
            if (check.present()) {
                if (c.isVerifyArqc() && !check.valid()) {
                    log.warn("ARQC inválido: esperado={} recibido={}", check.expected(), check.received());
                    if (present(c.getArqcFailureResponseCode())) r.set(39, c.getArqcFailureResponseCode());
                }
                if (c.isGenerateArpc()) {
                    Map<String, String> tags = Emv.tags(req.get(55));
                    String rc = r.get(39) == null ? "00" : r.get(39);
                    String arc = Hex.encode(rc.getBytes(StandardCharsets.US_ASCII));
                    String arpc = Emv.arpc(cardKey(req, tags), check.received(), arc);
                    r.set(55, Tlv.build(List.of(new Tlv.Entry("91", 10, arpc + arc))));
                }
            }
        }
        if (req.has(64) || req.has(128)) {
            if (!r.has(64) && !r.has(128)) r.set(req.has(128) ? 128 : 64, ZERO_MAC);
            applyMac(r);
        }
        return r;
    }

    public ArqcCheck verifyArqc(IsoMessage req) {
        if (!req.has(55)) return new ArqcCheck(false, false, null, null);
        Map<String, String> tags = Emv.tags(req.get(55));
        String received = tags.get("9F26");
        if (received == null || !tags.containsKey("9F36")) return new ArqcCheck(false, false, null, received);
        String expected = Emv.arqc(cardKey(req, tags), tags, sessionMode(), iadMode());
        return new ArqcCheck(true, expected.equalsIgnoreCase(received), expected, received);
    }

    /** Recomputes the MAC of a received message and compares it with DE64/DE128. */
    public boolean verifyMac(IsoMessage m) {
        int f = m.has(128) ? 128 : 64;
        String got = m.get(f);
        if (got == null || macAlgorithm() == Mac.Algorithm.NONE) return false;
        IsoMessage copy = m.copy();
        copy.set(f, ZERO_MAC);
        applyMac(copy);
        return got.equalsIgnoreCase(copy.get(f));
    }

    public String decryptPin(IsoMessage m) {
        return PinBlock.decryptPin(key("zpk"), m.get(52), pan(m));
    }

    private void applyMac(IsoMessage m) {
        Mac.Algorithm alg = macAlgorithm();
        if (alg == Mac.Algorithm.NONE || !(m.has(64) || m.has(128))) return;
        int f = m.has(128) ? 128 : 64;
        m.set(f, ZERO_MAC);
        byte[] wire = messages.pack(m);
        List<FieldTrace> trace = messages.trace(m.getDialect(), wire);
        int start = 0, end = wire.length;
        for (FieldTrace t : trace) {
            if ("MTI".equals(t.element())) start = t.offset();
            if (("DE" + f).equals(t.element())) end = t.offset();
        }
        byte[] data = java.util.Arrays.copyOfRange(wire, start, end);
        m.set(f, Hex.encode(Mac.compute(alg, key("mak"), data)));
    }

    private byte[] cardKey(IsoMessage m, Map<String, String> tags) {
        String psn = tags.get("5F34");
        if (psn == null && m.get(23) != null) {
            String d = m.get(23);
            psn = d.substring(Math.max(0, d.length() - 2));
        }
        return Emv.cardKey(key("imkAc"), pan(m), psn, tags.get("9F36"), sessionMode());
    }

    private Emv.SessionKey sessionMode() {
        return Emv.SessionKey.valueOf(orDefault(config.get().getEmvSessionKey(), "NONE"));
    }

    private Emv.IadMode iadMode() {
        return Emv.IadMode.valueOf(orDefault(config.get().getEmvIadMode(), "CVR"));
    }

    private Mac.Algorithm macAlgorithm() {
        return Mac.Algorithm.valueOf(orDefault(config.get().getMacAlgorithm(), "NONE"));
    }

    static String pan(IsoMessage m) {
        if (m.get(2) != null) return m.get(2);
        String t2 = m.get(35);
        if (t2 != null) {
            int i = t2.indexOf('=');
            if (i < 0) i = t2.toUpperCase(Locale.ROOT).indexOf('D');
            return i > 0 ? t2.substring(0, i) : t2;
        }
        throw new IllegalArgumentException("Mensaje sin PAN (DE2/DE35)");
    }

    static String replaceTag(String tlvHex, String tag, String value) {
        List<Tlv.Entry> out = new ArrayList<>();
        for (Tlv.Entry e : Tlv.parse(tlvHex)) {
            out.add(e.tag().equalsIgnoreCase(tag) ? new Tlv.Entry(e.tag(), value.length() / 2, value) : e);
        }
        return Tlv.build(out);
    }

    private static boolean present(String s) { return s != null && !s.isBlank(); }

    private static String orDefault(String s, String d) { return present(s) ? s.trim().toUpperCase(Locale.ROOT) : d; }
}
