package org.simulator.ui.util;

import javafx.scene.control.ButtonBase;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2OutlinedAL;
import org.kordamp.ikonli.material2.Material2OutlinedMZ;

import java.util.List;
import java.util.Locale;

/**
 * Material icons (Ikonli) and the automatic look of action buttons: the label decides the icon and the
 * color role (accent / success / danger), so every view gets a consistent style without per-view code.
 */
public final class Icons {

    /** Stylesheet applied to the main scene and to every dialog. */
    public static final String STYLESHEET = Icons.class.getResource("/org/simulator/ui/simulator.css").toExternalForm();

    private static final String ACCENT = "accent";
    private static final String SUCCESS = "success";
    private static final String DANGER = "danger";

    private record Rule(String prefix, Ikon icon, String role) {
    }

    /** First matching prefix (lower case) wins, so more specific prefixes go first. */
    private static final List<Rule> RULES = List.of(
            new Rule("detener", Material2OutlinedMZ.STOP, DANGER),
            new Rule("reproducir", Material2OutlinedMZ.PLAY_ARROW, ACCENT),
            new Rule("grabar", Material2OutlinedAL.FIBER_MANUAL_RECORD, DANGER),
            new Rule("eliminar", Material2OutlinedAL.DELETE, DANGER),
            new Rule("quitar", Material2OutlinedMZ.REMOVE_CIRCLE, DANGER),
            new Rule("purgar", Material2OutlinedAL.DELETE_SWEEP, DANGER),
            new Rule("desconectar", Material2OutlinedAL.LINK_OFF, DANGER),
            new Rule("conectar", Material2OutlinedAL.LINK, ACCENT),
            new Rule("enviar", Material2OutlinedMZ.SEND, ACCENT),
            new Rule("ejecutar", Material2OutlinedMZ.PLAY_ARROW, ACCENT),
            new Rule("iniciar", Material2OutlinedMZ.PLAY_ARROW, SUCCESS),
            new Rule("guardar", Material2OutlinedMZ.SAVE, SUCCESS),
            new Rule("aplicar", Material2OutlinedAL.CHECK, ACCENT),
            new Rule("activar", Material2OutlinedAL.CHECK_CIRCLE, ACCENT),
            new Rule("exportar", Material2OutlinedMZ.SAVE_ALT, null),
            new Rule("importar", Material2OutlinedAL.GET_APP, null),
            new Rule("abrir", Material2OutlinedAL.FOLDER_OPEN, null),
            new Rule("refrescar", Material2OutlinedMZ.REFRESH, null),
            new Rule("recargar", Material2OutlinedMZ.REFRESH, null),
            new Rule("reiniciar", Material2OutlinedAL.AUTORENEW, null),
            new Rule("limpiar", Material2OutlinedAL.CLEAR, null),
            new Rule("copiar", Material2OutlinedAL.CONTENT_COPY, null),
            new Rule("validar", Material2OutlinedAL.FACT_CHECK, null),
            new Rule("verificar", Material2OutlinedMZ.VERIFIED, null),
            new Rule("calcular", Material2OutlinedAL.CALCULATE, ACCENT),
            new Rule("kcv", Material2OutlinedAL.CALCULATE, null),
            new Rule("descifrar", Material2OutlinedMZ.NO_ENCRYPTION, null),
            new Rule("cifrar", Material2OutlinedAL.ENHANCED_ENCRYPTION, ACCENT),
            new Rule("desbloquear", Material2OutlinedAL.LOCK_OPEN, null),
            new Rule("backup", Material2OutlinedAL.BACKUP, null),
            new Rule("restaurar", Material2OutlinedMZ.RESTORE, null),
            new Rule("comparar", Material2OutlinedAL.COMPARE_ARROWS, ACCENT),
            new Rule("probar", Material2OutlinedMZ.NETWORK_CHECK, null),
            new Rule("health", Material2OutlinedMZ.NETWORK_CHECK, null),
            new Rule("echo", Material2OutlinedMZ.SYNC_ALT, null),
            new Rule("reversar", Material2OutlinedMZ.UNDO, null),
            new Rule("nuevo", Material2OutlinedAL.ADD, null),
            new Rule("nueva", Material2OutlinedAL.ADD, null),
            new Rule("agregar", Material2OutlinedAL.ADD, null),
            new Rule("simular", Material2OutlinedMZ.VISIBILITY, null),
            new Rule("vista previa", Material2OutlinedMZ.VISIBILITY, null),
            new Rule("ver ", Material2OutlinedMZ.VISIBILITY, null),
            new Rule("trace", Material2OutlinedMZ.TRACK_CHANGES, null),
            new Rule("construir", Material2OutlinedAL.BUILD, ACCENT),
            new Rule("convertir", Material2OutlinedMZ.SWAP_HORIZ, ACCENT),
            new Rule("hex", Material2OutlinedMZ.SWAP_HORIZ, null),
            new Rule("json", Material2OutlinedMZ.SWAP_HORIZ, null),
            new Rule("formatear", Material2OutlinedAL.FORMAT_ALIGN_LEFT, null),
            new Rule("tags", Material2OutlinedAL.LOCAL_OFFER, null),
            new Rule("por defecto", Material2OutlinedMZ.RESTORE, null),
            new Rule("cancelar", Material2OutlinedAL.CLOSE, null));

    private Icons() {
    }

    public static FontIcon of(Ikon ikon) {
        return new FontIcon(ikon);
    }

    public static FontIcon of(Ikon ikon, int size) {
        FontIcon i = new FontIcon(ikon);
        i.setIconSize(size);
        return i;
    }

    /**
     * Decorates a button from its label. Legacy glyph prefixes ("▶ ", "■ ", "● ") are dropped because the
     * icon replaces them; single symbol labels (+, -, arrows) become icon-only buttons.
     */
    public static void decorate(ButtonBase b) {
        String text = b.getText() == null ? "" : b.getText().trim();
        Ikon iconOnly = switch (text) {
            case "+" -> Material2OutlinedAL.ADD;
            case "-" -> Material2OutlinedMZ.REMOVE;
            case "↑" -> Material2OutlinedAL.ARROW_UPWARD;
            case "↓" -> Material2OutlinedAL.ARROW_DOWNWARD;
            default -> null;
        };
        if (iconOnly != null) {
            b.setText(null);
            b.setGraphic(of(iconOnly));
            b.getStyleClass().add("button-icon");
            return;
        }
        if (text.length() > 2 && "▶■●+".indexOf(text.charAt(0)) >= 0 && text.charAt(1) == ' ') {
            boolean plus = text.charAt(0) == '+';
            text = text.substring(2).trim();
            b.setText(text);
            if (plus) {
                b.setGraphic(of(Material2OutlinedAL.ADD));
                return;
            }
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (Rule r : RULES) {
            if (lower.startsWith(r.prefix())) {
                b.setGraphic(of(r.icon()));
                if (r.role() != null) {
                    b.getStyleClass().add(r.role());
                }
                return;
            }
        }
    }
}
