package org.simulator.ui;

import org.junit.jupiter.api.Test;
import org.simulator.iso.MessageService;
import org.simulator.iso.codec.Tlv;
import org.simulator.iso.dialect.DialectRegistry;
import org.simulator.ui.util.ParserExamples;
import org.simulator.persistence.Database;
import org.simulator.persistence.DbAdminService;

import static org.assertj.core.api.Assertions.assertThat;

class ParserExamplesTest {

    @Test
    void everyExampleRoundTripsAndProvidesTlvDetails() {
        MessageService service = new MessageService(DialectRegistry.builtIn());
        assertThat(ParserExamples.all()).hasSize(4);
        for (var example : ParserExamples.all()) {
            var message = example.message();
            byte[] wire = service.pack(message);
            assertThat(service.unpack(message.getDialect(), wire).fields()).isEqualTo(message.fields());
            assertThat(service.fromJson(service.toJson(message)).fields()).isEqualTo(message.fields());
            var field = service.trace(message.getDialect(), wire).stream()
                    .filter(row -> row.element().equals("DE55")).findFirst().orElseThrow();
            assertThat(service.dialect(message.getDialect()).field(55).getSubFormat()).isEqualTo("BER_TLV");
            assertThat(Tlv.parseDetailed(field.value(), 55)).isNotEmpty();
        }
    }

    @Test
    void examplesCoverNestedScriptsLongLengthsAndRepeatedTags() {
        var examples = ParserExamples.all();
        assertThat(Tlv.parseDetailed(examples.get(1).data(), 55))
                .anyMatch(detail -> detail.path().equals("77/9F26"));
        assertThat(Tlv.parseDetailed(examples.get(2).data(), 55))
                .anyMatch(detail -> detail.path().equals("71/86"));
        var details = Tlv.parseDetailed(examples.get(3).data(), 55);
        assertThat(details.get(0).length()).isEqualTo(128);
        assertThat(details).extracting(Tlv.Detail::tag).containsExactly("DF01", "9F36", "9F36");
    }

    @Test
    void persistedExamplesMatchOriginalMessagesAndAreBrowsableBySuite() {
        MessageService service = new MessageService(DialectRegistry.builtIn());
        try (Database db = Database.inMemory("parser-examples-" + System.nanoTime())) {
            DbAdminService admin = new DbAdminService(db);
            assertThat(admin.messageTables()).contains("UNIT_TEST", "REGRESION", "STRESS_TEST", "AUDIT_LOG");
            for (var example : ParserExamples.all()) {
                var result = admin.tableRows("UNIT_TEST", example.dialect(), 1000);
                int name = result.columns().indexOf("NAME");
                int request = result.columns().indexOf("REQUEST");
                var row = result.rows().stream().filter(r -> example.name().equals(r.get(name)))
                        .findFirst().orElseThrow();
                var message = service.fromJson(row.get(request));
                assertThat(message.getDialect()).isEqualTo(example.dialect());
                assertThat(message.getMti()).isEqualTo(example.mti());
                assertThat(message.fields()).isEqualTo(example.message().fields());
                byte[] wire = service.pack(message);
                assertThat(service.unpack(message.getDialect(), wire).fields()).isEqualTo(message.fields());
                assertThat(Tlv.parseDetailed(message.get(55), 55)).isNotEmpty();
            }
            admin.createMessageTable("parser_custom");
            admin.insertMessage("parser_custom", "base24-pos", "Custom TLV",
                    service.toJson(ParserExamples.all().get(0).message()), "00", null, true);
            assertThat(admin.messageTables()).contains("PARSER_CUSTOM");
            assertThat(admin.tableRows("PARSER_CUSTOM", "base24-pos", 1000).rows()).hasSize(1);
            assertThat(admin.tableRows("PARSER_CUSTOM", "base24-atm", 1000).rows()).isEmpty();
        }
    }
}
