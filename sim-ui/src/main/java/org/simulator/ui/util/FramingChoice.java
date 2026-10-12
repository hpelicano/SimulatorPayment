package org.simulator.ui.util;

import javafx.collections.FXCollections;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Tooltip;
import javafx.util.StringConverter;
import org.simulator.transport.Framing;

/** Selector of the TCP length-prefix framing, initialised from the endpoint configuration. */
public final class FramingChoice {

    private FramingChoice() {
    }

    public static ChoiceBox<Framing> create(String configured) {
        ChoiceBox<Framing> box = new ChoiceBox<>(FXCollections.observableArrayList(Framing.values()));
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(Framing f) {
                return f == null ? "" : f.name() + " - " + f.label();
            }

            @Override
            public Framing fromString(String s) {
                return null;
            }
        });
        try {
            box.setValue(Framing.parse(configured));
        } catch (IllegalArgumentException e) {
            box.setValue(Framing.LEN2_BINARY);
        }
        box.setTooltip(new Tooltip("""
                Prefijo de longitud de cada mensaje en el stream TCP:
                LEN2_BINARY: 2 bytes binarios, no se incluye a sí mismo (BASE24)
                LEN2_BINARY_INCLUSIVE: 2 bytes binarios, incluye los 2 bytes
                LEN2_BINARY_HEADER: 2 bytes + 2 reservados 0x0000 (VISA)
                LEN4_BINARY: 4 bytes binarios
                LEN4_ASCII: 4 dígitos ASCII (máx. 9999)
                LEN4_ASCII_INCLUSIVE: 4 dígitos ASCII, incluye los 4 dígitos
                NONE: sin prefijo (cada lectura TCP = 1 mensaje)"""));
        return box;
    }
}
