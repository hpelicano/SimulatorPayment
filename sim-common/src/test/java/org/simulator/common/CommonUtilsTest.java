package org.simulator.common;

import org.junit.jupiter.api.Test;
import org.simulator.common.event.EventBus;
import org.simulator.common.util.Bcd;
import org.simulator.common.util.Hex;
import org.simulator.common.util.Luhn;
import org.simulator.common.util.Masking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommonUtilsTest {

    @Test
    void bcdRoundTripOddAndEven() {
        assertThat(Hex.encode(Bcd.encode("123"))).isEqualTo("0123");
        assertThat(Bcd.decode(Bcd.encode("123"), 0, 3)).isEqualTo("123");
        assertThat(Bcd.decode(Bcd.encode("1234"), 0, 4)).isEqualTo("1234");
        assertThat(Hex.encode(Bcd.encodeRightPadded("4761=25", 'F'))).isEqualTo("4761D25F");
        assertThat(Bcd.decodeRightPadded(Hex.decode("4761D25F"), 0, 7)).isEqualTo("4761D25");
    }

    @Test
    void luhn() {
        assertThat(Luhn.isValid("4111111111111111")).isTrue();
        assertThat(Luhn.isValid("4111111111111112")).isFalse();
        assertThat(Luhn.complete("411111111111111")).isEqualTo("4111111111111111");
    }

    @Test
    void masking() {
        assertThat(Masking.maskPan("4111111111111111")).isEqualTo("4111111111111111");
        assertThat(Masking.maskPan("1234567")).isEqualTo("1234567");
        assertThat(Masking.maskPan(null)).isNull();
        assertThat(Masking.maskTrack("4111111111111111=2512101")).isEqualTo("4111111111111111=*******");
        assertThat(Masking.maskTrack("4111111111111111D2512101")).isEqualTo("4111111111111111D*******");
        assertThat(Masking.maskTrack(";4111111111111111=2512101?")).isEqualTo(";4111111111111111=********");
        assertThat(Masking.maskTrack("%B4111111111111111^TEST/NAME^2512101?"))
                .isEqualTo("%B4111111111111111^" + "*".repeat(18));
        assertThat(Masking.maskTrack("4111111111111111")).isEqualTo("4111111111111111");
        assertThat(Masking.maskTrack(null)).isNull();
        assertThat(Masking.maskTrack("not-a-track")).isEqualTo("***********");
        String tlv = "5A084111111111111111570A4111111111111111D251"
                + "9F6B0A4111111111111111D251"
                + "5613" + Hex.encode("%B4111111111111111^".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(Masking.maskFields(Map.of(2, "4111111111111111", 35, "4111111111111111=2512101",
                45, "%B4111111111111111^TEST/NAME^2512101?", 55, tlv, 52, "ABCDEF0123456789", 53, "2600000000000000")))
                .containsEntry(2, "4111111111111111")
                .containsEntry(35, "4111111111111111=*******")
                .containsEntry(45, "%B4111111111111111^" + "*".repeat(18))
                .containsEntry(55, tlv)
                .containsEntry(52, "[REMOVED]")
                .containsEntry(53, "[REMOVED]");
    }

    @Test
    void eventBusDeliversByType() {
        EventBus bus = new EventBus();
        List<Object> got = new ArrayList<>();
        try (EventBus.Subscription s = bus.subscribe(String.class, got::add)) {
            bus.publish("hello");
            bus.publish(42);
        }
        bus.publish("after-close");
        assertThat(got).containsExactly("hello");
    }
}
