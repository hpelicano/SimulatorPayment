package org.simulator.ui.view;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocBuilderPaneTest {

    @Test
    void selectsOnlyNonEmptyManualCommandsInExecutionOrder() {
        assertThat(DocBuilderPane.requestedQueries("INFO FILE", "AUDIT PROCESS"))
                .containsExactly("PTLF", "AUDIT");
        assertThat(DocBuilderPane.requestedQueries("INFO FILE", "  "))
                .containsExactly("PTLF");
        assertThat(DocBuilderPane.requestedQueries("", "AUDIT PROCESS"))
                .containsExactly("AUDIT");
    }

    @Test
    void reportsMissingInputWhenBothCommandsAreEmpty() {
        assertThatThrownBy(() -> DocBuilderPane.requestedQueries(" ", ""))
                .hasMessageContaining("Faltan datos");
    }
}
