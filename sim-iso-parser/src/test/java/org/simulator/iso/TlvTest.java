package org.simulator.iso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simulator.iso.codec.IsoParseException;
import org.simulator.iso.codec.Tlv;
import org.simulator.iso.dialect.DialectRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TlvTest {

    @Test
    void detailedViewPreservesNestedPathsOffsetsAndDuplicateTags() {
        String hex = "770B9F270180E1059F360200019F270140";
        List<Tlv.Detail> details = Tlv.parseDetailed(hex, 55);
        assertThat(details).extracting(Tlv.Detail::tag).containsExactly("77", "9F27", "E1", "9F36", "9F27");
        assertThat(details).extracting(Tlv.Detail::path)
                .containsExactly("77", "77/9F27", "77/E1", "77/E1/9F36", "9F27");
        assertThat(details).extracting(Tlv.Detail::offset).containsExactly(0, 2, 6, 8, 13);
        assertThat(details).extracting(Tlv.Detail::constructed).containsExactly(true, false, true, false, false);
        assertThat(details.get(3).value()).isEqualTo("0001");
        assertThat(details.get(3).description()).contains("Application Transaction Counter");
        assertThat(details.get(2).description()).contains("no catalogado");
        // The existing crypto API must still return only top-level entries.
        assertThat(Tlv.parse(hex)).extracting(Tlv.Entry::tag).containsExactly("77", "9F27");
        assertThat(Tlv.build(Tlv.parse(hex))).isEqualTo(hex);
    }

    @Test
    void longLengthsPaddingAndUnknownTags() {
        String value = "AB".repeat(128);
        String hex = "00FFDF018180" + value + "00";
        List<Tlv.Detail> details = Tlv.parseDetailed(hex, 48);
        assertThat(details).hasSize(1);
        assertThat(details.get(0).tag()).isEqualTo("DF01");
        assertThat(details.get(0).length()).isEqualTo(128);
        assertThat(details.get(0).offset()).isEqualTo(2);
        assertThat(details.get(0).value()).isEqualTo(value);
        assertThat(Tlv.build(Tlv.parse(hex))).isEqualTo("DF018180" + value);
        assertThat(Tlv.parseDetailed("9F3600", 55).get(0).value()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9F", "9F81", "9F26", "9F2681", "9F268201",
            "9F2680", "9F2685", "9F2684FFFFFFFF", "9F2602AB"})
    void malformedDataHasExplicitFieldAndOffset(String hex) {
        assertThatThrownBy(() -> Tlv.parseDetailed(hex, 48))
                .isInstanceOf(IsoParseException.class).hasMessageContaining("[DE48]").hasMessageContaining("offset");
        assertThatThrownBy(() -> Tlv.parse(hex)).isInstanceOf(IsoParseException.class);
    }

    @Test
    void nestedLengthCannotConsumeNextSibling() {
        assertThatThrownBy(() -> Tlv.parseDetailed("77039F36020001", 55))
                .isInstanceOf(IsoParseException.class).hasMessageContaining("value truncated");
    }

    @Test
    void excessiveNestingIsReported() {
        String hex = "9F270180";
        for (int i = 0; i < 34; i++) {
            hex = Tlv.build(List.of(new Tlv.Entry("E1", hex.length() / 2, hex)));
        }
        String nested = hex;
        assertThatThrownBy(() -> Tlv.parseDetailed(nested, 55))
                .isInstanceOf(IsoParseException.class).hasMessageContaining("nesting");
    }

    @Test
    void invalidHexIsRejected() {
        assertThatThrownBy(() -> Tlv.parseDetailed("9F2", 55)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tlv.parseDetailed("ZZ", 55)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Tlv.parseDetailed("", 55)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"iso87-ascii", "iso87-binary", "base24-pos", "base24-atm", "visa-base1", "mastercard-cis"})
    void detailUsesCanonicalTraceValueNotWireLengthPrefix(String dialect) {
        MessageService service = new MessageService(DialectRegistry.builtIn());
        IsoMessage message = new IsoMessage(dialect, "0200")
                .set(55, "9F2608A1B2C3D4E5F601029F270180");
        var trace = service.trace(dialect, service.pack(message)).stream()
                .filter(row -> row.element().equals("DE55")).findFirst().orElseThrow();
        assertThat(service.dialect(dialect).field(55).getSubFormat()).isEqualTo("BER_TLV");
        assertThat(Tlv.parseDetailed(trace.value(), 55)).extracting(Tlv.Detail::tag)
                .containsExactly("9F26", "9F27");
    }
}
