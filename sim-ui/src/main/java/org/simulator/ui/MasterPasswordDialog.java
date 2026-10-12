package org.simulator.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import org.simulator.config.ConfigService;

import java.util.Arrays;

/** Asks for the master password used to decrypt the ENC(...) secrets of the configuration. */
public final class MasterPasswordDialog {

    private MasterPasswordDialog() {
    }

    /** @return true when the configuration was unlocked; false if the user chose to continue locked */
    public static boolean unlock(ConfigService config) {
        boolean firstTime = config.user().getMasterPasswordCheck() == null;
        String error = null;
        while (true) {
            Dialog<ButtonType> d = new Dialog<>();
            d.setTitle("SimulatorPayment");
            d.setHeaderText(firstTime ? "Defina la contraseña maestra (cifra las credenciales con AES-GCM)"
                    : "Ingrese la contraseña maestra");
            PasswordField pwd = new PasswordField();
            PasswordField confirm = new PasswordField();
            pwd.setPromptText("Contraseña maestra");
            confirm.setPromptText("Repetir contraseña");
            VBox box = new VBox(8, new Label("Contraseña"), pwd);
            if (firstTime) {
                box.getChildren().addAll(new Label("Confirmación"), confirm);
            }
            if (error != null) {
                Label err = new Label(error);
                err.setStyle("-fx-text-fill: -color-danger-fg;");
                box.getChildren().add(err);
            }
            box.setPadding(new Insets(10));
            d.getDialogPane().setContent(box);
            ButtonType skip = new ButtonType("Continuar sin desbloquear", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
            d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, skip);
            org.simulator.ui.util.Ui.styleDialog(d.getDialogPane());
            javafx.application.Platform.runLater(pwd::requestFocus);
            ButtonType result = d.showAndWait().orElse(skip);
            if (result != ButtonType.OK) {
                return false;
            }
            char[] p = pwd.getText().toCharArray();
            try {
                if (p.length < 8) {
                    error = "La contraseña debe tener al menos 8 caracteres";
                    continue;
                }
                if (firstTime && !pwd.getText().equals(confirm.getText())) {
                    error = "Las contraseñas no coinciden";
                    continue;
                }
                if (config.unlock(p)) {
                    return true;
                }
                error = "Contraseña incorrecta";
            } finally {
                Arrays.fill(p, '\0');
            }
        }
    }
}
