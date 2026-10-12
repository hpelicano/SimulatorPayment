package org.simulator.iso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simulator.common.util.Hex;
import org.simulator.iso.codec.Base24Tokens;
import org.simulator.iso.codec.FieldTrace;
import org.simulator.iso.codec.IsoParseException;
import org.simulator.iso.codec.Tlv;
import org.simulator.iso.dialect.DialectRegistry;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageServiceTest {

    static final MessageService SERVICE = new MessageService(DialectRegistry.builtIn());

    static IsoMessage purchase(String dialect) {
        return new IsoMessage(dialect, "0200")
                .set(2, "4761739001010119")
                .set(3, "000000")
                .set(4, "000000012345")
                .set(7, "0926141500")
                .set(11, "000123")
                .set(12, "141500")
                .set(13, "0926")
                .set(22, "051")
                .set(35, "4761739001010119D25122011143804400000")
                .set(37, "000000123456")
                .set(41, "TERM0001")
                .set(42, "MERCHANT0000001")
                .set(49, "032")
                .set(55, "9F2608A1B2C3D4E5F601029F2701809F100706010A03A0B800")
                .set(102, "0011223344");
    }

    @ParameterizedTest
    @ValueSource(strings = {"iso87-ascii", "iso87-binary", "base24-pos", "base24-atm", "visa-base1", "mastercard-cis", "hpdh", "bnet"})
    void roundTripAllDialects(String dialect) {
        IsoMessage msg = purchase(dialect);
        byte[] wire = SERVICE.pack(msg);
        IsoMessage back = SERVICE.unpack(dialect, wire);
        assertThat(back.getMti()).isEqualTo("0200");
        assertThat(back.fields()).isEqualTo(msg.fields());
        assertThat(SERVICE.pack(back)).isEqualTo(wire);
    }

    @Test
    void base24PosGoldenLayout() {
        IsoMessage msg = new IsoMessage("base24-pos", "0800").set(7, "0926141500").set(11, "000001").set(70, "301");
        String wire = new String(SERVICE.pack(msg), StandardCharsets.US_ASCII);
        assertThat(wire).isEqualTo("ISO026000050" + "0800" + "8220000000000000" + "0400000000000000"
                + "0926141500" + "000001" + "301");
        assertThat(SERVICE.responseHeader("base24-pos", "ISO026000050")).isEqualTo("ISO026000055");
    }

    @Test
    void visaHeaderCarriesTotalLengthAndBcdOddPan() {
        IsoMessage msg = new IsoMessage("visa-base1", "0100").set(2, "476173900101011").set(11, "000001");
        byte[] wire = SERVICE.pack(msg);
        assertThat(((wire[3] & 0xFF) << 8) | (wire[4] & 0xFF)).isEqualTo(wire.length);
        // header(22) + MTI BCD(2) + bitmap(8) + DE2: len byte 0x0F + 8 BCD bytes + DE11 3 bytes
        assertThat(wire.length).isEqualTo(22 + 2 + 8 + 1 + 8 + 3);
        assertThat(SERVICE.unpack("visa-base1", wire).get(2)).isEqualTo("476173900101011");
    }

    @Test
    void hpdhTpduSwapped() {
        assertThat(SERVICE.responseHeader("hpdh", "6000010002")).isEqualTo("6000020001");
    }

    @Test
    void jsonRoundTripAndConversion() {
        IsoMessage msg = purchase("base24-pos");
        String json = SERVICE.toJson(msg);
        IsoMessage fromJson = SERVICE.fromJson(json);
        assertThat(fromJson.fields()).isEqualTo(msg.fields());

        IsoMessage visa = SERVICE.convert(msg, "visa-base1");
        IsoMessage visaBack = SERVICE.unpack("visa-base1", SERVICE.pack(visa));
        assertThat(visaBack.get(4)).isEqualTo("000000012345");
        assertThat(SERVICE.fromJson("{\"dialect\":\"iso87-ascii\",\"mti\":\"0800\",\"fields\":{\"DE11\":\"1\",\"70\":\"301\"}}").get(11))
                .isEqualTo("1");
    }

    @Test
    void loggingAndReplayKeepPanWithoutPinOrSecurityControlData() {
        IsoMessage message = purchase("base24-pos").set(52, "ABCDEF0123456789").set(53, "2600000000000000")
                .set(45, "%B4761739001010119^TEST/NAME^2512101?")
                .set(55, "5A084761739001010119570A4761739001010119D251"
                        + "9F6B0A4761739001010119D251"
                        + "5613" + Hex.encode("%B4761739001010119^".getBytes(StandardCharsets.US_ASCII)));
        IsoMessage log = message.redactedCopy();
        assertThat(log.get(2)).isEqualTo(message.get(2));
        assertThat(log.get(35)).startsWith("4761739001010119D").doesNotContain("2512201");
        assertThat(log.get(45)).startsWith("%B4761739001010119^").doesNotContain("TEST/NAME");
        assertThat(log.get(55)).isEqualTo(message.get(55));
        assertThat(log.get(52)).isEqualTo("[REMOVED]");
        assertThat(log.get(53)).isEqualTo("[REMOVED]");
        assertThat(message.toString()).contains("DE2=4761739001010119", "DE35=4761739001010119D")
                .doesNotContain("ABCDEF0123456789", "2600000000000000", "TEST/NAME");
        IsoMessage replay = message.replayCopy();
        assertThat(replay.get(2)).isEqualTo(message.get(2));
        assertThat(replay.get(35)).isEqualTo(message.get(35));
        assertThat(replay.get(55)).isEqualTo(message.get(55));
        assertThat(replay.has(52)).isFalse();
        assertThat(replay.has(53)).isFalse();
        assertThat(message.get(52)).isEqualTo("ABCDEF0123456789");
    }

    @Test
    void traceAndErrors() {
        byte[] wire = SERVICE.pack(purchase("base24-pos"));
        List<FieldTrace> trace = SERVICE.trace("base24-pos", wire);
        assertThat(trace).extracting(FieldTrace::element).startsWith("HEADER", "MTI", "BITMAP", "BITMAP", "DE2");

        byte[] truncated = java.util.Arrays.copyOf(wire, wire.length - 3);
        assertThatThrownBy(() -> SERVICE.unpack("base24-pos", truncated)).isInstanceOf(IsoParseException.class)
                .hasMessageContaining("DE102");
        assertThatThrownBy(() -> SERVICE.pack(new IsoMessage("iso87-ascii", "0200").set(3, "1234567")))
                .isInstanceOf(IsoParseException.class).hasMessageContaining("DE3");
    }

    @Test
    void tlvAndBase24Tokens() {
        List<Tlv.Entry> tlv = Tlv.parse("9F2608A1B2C3D4E5F601029F270180");
        assertThat(tlv).extracting(Tlv.Entry::tag).containsExactly("9F26", "9F27");
        assertThat(Tlv.build(tlv)).isEqualTo("9F2608A1B2C3D4E5F601029F270180");

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("C0", "12345678901234567890123456");
        tokens.put("B2", "ABC");
        String field = Base24Tokens.build(tokens);
        assertThat(field).startsWith("& 00003");
        assertThat(Base24Tokens.parse(field)).isEqualTo(tokens);
    }

    @Test
    void visaDefaultHeaderIs22Bytes() {
        assertThat(Hex.decode(SERVICE.dialect("visa-base1").getHeader().getDefaultValue())).hasSize(22);
        assertThat(SERVICE.registry().ids()).contains("iso93-ascii");
    }
}
