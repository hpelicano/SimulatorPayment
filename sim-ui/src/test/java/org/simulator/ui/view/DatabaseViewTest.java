package org.simulator.ui.view;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseViewTest {

    @Test
    void hidesInternalTablesInAllCasesWithoutFilteringTransactionTables() {
        assertThat(DatabaseView.visibleTables(List.of("AUDIT_LOG", "system_audit_log", "BASE24_SNAPSHOT",
                "flyway_schema_history", "UNIT_TEST", "REGRESION", "STRESS_TEST", "TRANSACTION_LOG", "CUSTOM_TEST")))
                .containsExactly("UNIT_TEST", "REGRESION", "STRESS_TEST", "TRANSACTION_LOG", "CUSTOM_TEST");
    }

    @Test
    void multilineJsonIsDisplayedOnOneLineWithoutChangingItsStoredValue() {
        String json = "{\r\n\t\"mti\": \"0200\"\n}";
        assertThat(DatabaseView.singleLine(json)).isEqualTo("{   \"mti\": \"0200\" }");
        assertThat(json).contains("\r\n", "\t");
    }
}
