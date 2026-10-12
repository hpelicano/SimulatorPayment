package org.simulator.validator;

import org.junit.jupiter.api.Test;
import org.simulator.common.json.Json;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.dialect.DialectRegistry;
import org.simulator.iso.dialect.MessageDialect;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MessageValidatorTest {

    final MessageDialect dialect = DialectRegistry.builtIn().get("base24-pos");
    final MessageValidator validator = new MessageValidator();

    IsoMessage request() {
        return new IsoMessage("base24-pos", "0200").set(2, "4111111111111111").set(3, "000000").set(4, "000000001000")
                .set(11, "000001").set(37, "000000000001").set(41, "TERM0001");
    }

    @Test
    void approvedExchangePasses() {
        IsoMessage req = request();
        IsoMessage resp = req.copy().setMti("0210").set(39, "00").set(38, "123456");
        ValidationProfile p = ValidationProfile.expecting("00|10");
        p.getRequestRules().add(FieldRule.of(2, FieldRule.Type.LUHN));
        assertThat(validator.validateExchange(p, req, resp, dialect).passed()).isTrue();
    }

    @Test
    void detectsBadEchoRcMtiAndStructure() {
        IsoMessage req = request().set(3, "00A000");
        IsoMessage resp = request().setMti("0200").set(4, "000000009999").set(39, "05");
        ValidationResult r = validator.validateExchange(ValidationProfile.expecting("00"), req, resp, dialect);
        assertThat(r.passed()).isFalse();
        assertThat(r.issues()).extracting(ValidationIssue::rule).contains("TYPE", "MTI", "ECHO", "RESPONSE_CODE");
    }

    @Test
    void rulesFromJsonAndCompare() {
        ValidationProfile p = Json.read("""
                {"expectedResponseCode":"51","responseRules":[
                  {"field":39,"type":"IN","values":["51","61"]},
                  {"field":38,"type":"ABSENT"},
                  {"field":4,"type":"RANGE","min":1,"max":5000}]}""", ValidationProfile.class);
        IsoMessage req = request();
        IsoMessage resp = req.copy().setMti("0210").set(39, "51");
        assertThat(validator.validateExchange(p, req, resp, dialect).passed()).isTrue();

        IsoMessage other = resp.copy().set(11, "999999").set(39, "00");
        ValidationResult cmp = validator.compare(resp, other, Set.of(11));
        assertThat(cmp.issues()).singleElement().extracting(ValidationIssue::field).isEqualTo(39);
    }
}
