package org.simulator.crypto;

import org.junit.jupiter.api.Test;
import org.simulator.common.util.Hex;
import org.simulator.config.model.CryptoConfig;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.iso.codec.Tlv;
import org.simulator.iso.dialect.DialectRegistry;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoTest {

    static final String KEY2 = "0123456789ABCDEFFEDCBA9876543210";

    @Test
    void visaCvvKnownVector() {
        assertThat(Cvv.compute(Hex.decode(KEY2), "4123456789012345", "8701", "101")).isEqualTo("561");
    }

    @Test
    void aesCmacRfc4493Vectors() {
        byte[] k = Hex.decode("2b7e151628aed2a6abf7158809cf4f3c");
        assertThat(Hex.encode(Mac.aesCmac(k, new byte[0]))).isEqualToIgnoringCase("bb1d6929e95937287fa37d129b756746");
        assertThat(Hex.encode(Mac.aesCmac(k, Hex.decode("6bc1bee22e409f96e93d7e117393172a"))))
                .isEqualToIgnoringCase("070a16b46b4d4144f79bdd9dd04a287c");
        assertThat(Hex.encode(Mac.aesCmac(k, Hex.decode("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411"))))
                .isEqualToIgnoringCase("dfa66747de9ae63030ca32611497c827");
    }

    @Test
    void desCbcMacFips113Vector() {
        byte[] data = "Now is the time for all ".getBytes(StandardCharsets.US_ASCII);
        byte[] k = Hex.decode("0123456789ABCDEF");
        assertThat(Hex.encode(Mac.retail(k, Mac.pad1(data)))).isEqualToIgnoringCase("70A30640CC76DD8B");
        assertThat(Hex.encode(Mac.alg1(k, Mac.pad1(data)))).isEqualToIgnoringCase("70A30640CC76DD8B");
    }

    @Test
    void pinBlockFormat0() {
        assertThat(PinBlock.clear(0, "1234", "4111111111111111")).isEqualToIgnoringCase("041225EEEEEEEEEE");
        byte[] zpk = Hex.decode(KEY2);
        for (int f : new int[]{0, 1, 3}) {
            String enc = PinBlock.encrypt(zpk, f, "987654", "4761739001010010");
            assertThat(PinBlock.decryptPin(zpk, enc, "4761739001010010")).isEqualTo("987654");
        }
    }

    @Test
    void kcvOfTestKey() {
        assertThat(Ciphers.kcv(Hex.decode(KEY2), false)).isEqualToIgnoringCase("08D7B4");
    }

    @Test
    void endToEndEmvPinAndMac() {
        CryptoConfig cfg = new CryptoConfig();
        cfg.setEnabled(true);
        cfg.setZpk(KEY2);
        cfg.setMak(KEY2);
        cfg.setImkAc("ENC(imk)");
        cfg.setMacAlgorithm("X919");
        cfg.setEmvSessionKey("EMV_CSK");
        cfg.setArqcFailureResponseCode("05");
        UnaryOperator<String> secrets = s -> s.equals("ENC(imk)") ? "1111222233334444AAAABBBBCCCCDDDD" : s;
        MessageService ms = new MessageService(DialectRegistry.builtIn());
        CryptoService hsm = new CryptoService(() -> cfg, secrets, ms);

        String de55 = Tlv.build(List.of(
                new Tlv.Entry("9F26", 8, "0000000000000000"), new Tlv.Entry("9F02", 6, "000000010000"),
                new Tlv.Entry("9F03", 6, "000000000000"), new Tlv.Entry("9F1A", 2, "0032"),
                new Tlv.Entry("95", 5, "0000000000"), new Tlv.Entry("5F2A", 2, "0032"),
                new Tlv.Entry("9A", 3, "250101"), new Tlv.Entry("9C", 1, "00"),
                new Tlv.Entry("9F37", 4, "12345678"), new Tlv.Entry("82", 2, "3C00"),
                new Tlv.Entry("9F36", 2, "0001"), new Tlv.Entry("9F10", 7, "06010A03A00000")));
        IsoMessage req = new IsoMessage("iso87-ascii", "0200")
                .set(2, "4761739001010010").set(3, "000000").set(4, "000000010000").set(11, "000001")
                .set(23, "001").set(41, "TERM0001").set(52, "PIN:1234").set(55, de55).set(64, "0000000000000000");

        IsoMessage out = hsm.prepareRequest(req);
        assertThat(out.get(52)).hasSize(16).isNotEqualTo("PIN:1234");
        assertThat(hsm.decryptPin(out)).isEqualTo("1234");
        assertThat(Emv.tags(out.get(55)).get("9F26")).isNotEqualTo("0000000000000000");
        assertThat(hsm.verifyArqc(out).valid()).isTrue();
        assertThat(hsm.verifyMac(out)).isTrue();

        IsoMessage tampered = out.copy().set(4, "000000099999");
        assertThat(hsm.verifyMac(tampered)).isFalse();

        IsoMessage resp = new IsoMessage("iso87-ascii", "0210").set(11, "000001").set(39, "00");
        IsoMessage signed = hsm.processResponse(out, resp);
        String tag91 = Emv.tags(signed.get(55)).get("91");
        assertThat(tag91).hasSize(20).endsWith("3030");
        assertThat(hsm.verifyMac(signed)).isTrue();

        IsoMessage badArqc = out.copy().set(55, CryptoService.replaceTag(out.get(55), "9F26", "1122334455667788"));
        assertThat(hsm.verifyArqc(badArqc).valid()).isFalse();
        assertThat(hsm.processResponse(badArqc, resp).get(39)).isEqualTo("05");
    }
}
