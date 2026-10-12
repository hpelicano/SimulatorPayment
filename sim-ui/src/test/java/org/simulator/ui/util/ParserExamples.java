package org.simulator.ui.util;

import org.simulator.iso.IsoMessage;
import org.simulator.iso.codec.Tlv;

import java.util.List;

/** Expected contents of the persisted V7 examples, used to verify the migration. */
public final class ParserExamples {

    private ParserExamples() {
    }

    public record Example(String name, String dialect, String mti, String data) {
        public IsoMessage message() {
            IsoMessage message = new IsoMessage(dialect, mti)
                    .set(3, "000000").set(4, "000000010000")
                    .set(7, "1003120000").set(11, "000001")
                    .set(22, "051").set(37, "000000000001")
                    .set(41, "TERM0001").set(49, "032").set(55, data);
            if ("0210".equals(mti)) {
                message.set(38, "123456").set(39, "00");
            }
            return message;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static List<Example> all() {
        String emv = "9F2608A1B2C3D4E5F601029F2701809F100706010A03A0B800"
                + "9F360200019F3704112233449F02060000000100005F2A020032"
                + "9A032610039C010095050000000000820218009F1A0200329F34031E0300";
        String nested = Tlv.build(List.of(new Tlv.Entry("77", emv.length() / 2, emv)));
        String script = Tlv.build(List.of(new Tlv.Entry("71", 7, "86058400000000")));
        String proprietary = Tlv.build(List.of(new Tlv.Entry("DF01", 128, "AB".repeat(128)),
                new Tlv.Entry("9F36", 2, "0001"), new Tlv.Entry("9F36", 2, "0002")));
        return List.of(
                new Example("Base24 POS - Compra EMV (DE55)", "base24-pos", "0200", emv),
                new Example("Visa - Tags anidados (77)", "visa-base1", "0200", nested),
                new Example("Mastercard - Respuesta y script (71)", "mastercard-cis", "0210",
                        "8A02303091081122334455667788" + script),
                new Example("ISO87 - Tag propietario, longitud larga y repetidos", "iso87-ascii", "0200", proprietary));
    }
}
