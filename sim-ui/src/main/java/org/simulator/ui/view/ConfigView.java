package org.simulator.ui.view;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.simulator.common.json.Json;
import org.simulator.config.ConfigService;
import org.simulator.config.model.AppConfig;
import org.simulator.config.model.EnvironmentConfig;
import org.simulator.ui.AppContext;
import org.simulator.ui.MasterPasswordDialog;
import org.simulator.ui.util.Ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

/** User settings, Base24 environments (hosts/ports/TLS/SSH/Pathway) and secret encryption. */
public class ConfigView implements View {

    private final AppContext ctx;
    private final Consumer<String> themeChanger;
    private final BorderPane root = new BorderPane();
    private final ChoiceBox<String> env = new ChoiceBox<>();
    private final TextArea envJson = Ui.code(true);
    private final TextField username = new TextField();
    private final TextField displayName = new TextField();
    private final ChoiceBox<String> theme = new ChoiceBox<>(FXCollections.observableArrayList("LIGHT", "DARK"));
    private final TextField retention = new TextField();
    private final PasswordField plain = new PasswordField();
    private final TextField encrypted = new TextField();
    private final Label lock = new Label();
    private final javafx.scene.control.CheckBox metrics = new javafx.scene.control.CheckBox("Exponer métricas Prometheus al iniciar");
    private final TextField metricsPort = new TextField();

    public ConfigView(AppContext ctx, Consumer<String> themeChanger) {
        this.ctx = ctx;
        this.themeChanger = themeChanger;
        encrypted.setEditable(false);

        var user = Ui.form("Usuario", username, "Nombre", displayName, "Tema", theme, "Retención log (días)", retention,
                "Puerto métricas", metricsPort);
        VBox userBox = new VBox(6, Ui.title("Usuario"), user, metrics,
                Ui.bar(Ui.button("Guardar usuario", this::saveUser)),
                new Label(" "), Ui.title("Cifrado de secretos (AES-GCM)"), lock,
                Ui.button("Desbloquear", () -> {
                    MasterPasswordDialog.unlock(ctx.config());
                    refreshLock();
                }),
                Ui.form("Texto plano", plain, "Cifrado", encrypted),
                Ui.bar(Ui.button("Cifrar", this::encrypt), Ui.button("Copiar", () -> {
                    ClipboardContent c = new ClipboardContent();
                    c.putString(encrypted.getText());
                    Clipboard.getSystemClipboard().setContent(c);
                })),
                new Label("Pegue el valor ENC(...) en los campos password / apiKey /\nbearerToken / keyStorePassword del entorno."),
                new Label(" "), new Label("Directorio de configuración:"), new Label(ctx.config().getConfigDir().toString()));
        userBox.setPrefWidth(360);

        VBox envBox = new VBox(6, Ui.bar(Ui.title("Entornos Base24"), Ui.spacer(), new Label("Activo:"), env,
                        Ui.button("Activar", this::activate)),
                Ui.bar(Ui.button("Validar", this::validate), Ui.button("Guardar entornos", this::saveEnvironments),
                        Ui.button("Recargar", this::load)),
                envJson,
                new Label("Incluye por entorno: acquirer/issuer (host, port, framing, dialect, timeouts, TLS), api (baseUrl, auth,"
                        + " TLS/HTTP1.1),\nguardian (SSH host/port/usuario/clave/known_hosts) y pathway (mode SOCKET|SSH, host, port, pathmon, verbos)."));
        VBox.setVgrow(envJson, Priority.ALWAYS);

        root.setLeft(userBox);
        root.setCenter(envBox);
        BorderPane.setMargin(envBox, new Insets(0, 0, 0, 16));
        root.setPadding(new Insets(12));
        load();
    }

    private void load() {
        ConfigService c = ctx.config();
        username.setText(c.user().getUsername());
        displayName.setText(c.user().getDisplayName());
        theme.setValue(c.user().getTheme() == null ? "LIGHT" : c.user().getTheme().toUpperCase());
        retention.setText(String.valueOf(c.user().getLogRetentionDays()));
        metrics.setSelected(c.user().isMetricsEnabled());
        metricsPort.setText(String.valueOf(c.user().getMetricsPort()));
        env.getItems().setAll(c.app().getEnvironments().stream().map(EnvironmentConfig::getName).toList());
        env.setValue(c.app().getActiveEnvironment());
        envJson.setText(Ui.pretty(c.app()));
        refreshLock();
    }

    private void refreshLock() {
        lock.setText(ctx.config().isUnlocked() ? "Estado: desbloqueado" : "Estado: BLOQUEADO (los secretos ENC(...) no se pueden usar)");
    }

    private void saveUser() {
        try {
            var u = ctx.config().user();
            u.setUsername(username.getText().trim());
            u.setDisplayName(displayName.getText().trim());
            u.setTheme(theme.getValue());
            u.setLogRetentionDays(Integer.parseInt(retention.getText().trim()));
            u.setMetricsEnabled(metrics.isSelected());
            u.setMetricsPort(Integer.parseInt(metricsPort.getText().trim()));
            ctx.config().save();
            themeChanger.accept(theme.getValue());
            Ui.info("Configuración de usuario guardada");
        } catch (Exception e) {
            Ui.error(e);
        }
    }

    private void encrypt() {
        if (!ctx.ensureUnlocked()) {
            Ui.error("Desbloquee la configuración con la contraseña maestra");
            return;
        }
        encrypted.setText(ctx.config().encryptSecret(plain.getText()));
        plain.clear();
    }

    private AppConfig parse() {
        return Json.read(envJson.getText(), AppConfig.class);
    }

    private void validate() {
        try {
            parse();
            List<String> errors = validateText();
            Ui.info(errors.isEmpty() ? "Configuración válida" : "Errores:\n- " + String.join("\n- ", errors));
        } catch (Exception e) {
            Ui.error("JSON inválido: " + Ui.root(e).getMessage());
        }
    }

    /** Validates the edited text using a temporary ConfigService so the live configuration is untouched. */
    private List<String> validateText() throws Exception {
        var tmp = Files.createTempDirectory("simcfg");
        try {
            Files.writeString(tmp.resolve(ConfigService.ENV_FILE), envJson.getText(), StandardCharsets.UTF_8);
            Files.copy(ctx.config().getConfigDir().resolve(ConfigService.USER_FILE), tmp.resolve(ConfigService.USER_FILE));
            return new ConfigService(tmp).load().validate();
        } finally {
            try (var s = Files.walk(tmp)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private void saveEnvironments() {
        try {
            parse();
            List<String> errors = validateText();
            if (!errors.isEmpty()) {
                Ui.error("No se guardó. Errores:\n- " + String.join("\n- ", errors));
                return;
            }
            Files.writeString(ctx.config().getConfigDir().resolve(ConfigService.ENV_FILE), envJson.getText(), StandardCharsets.UTF_8);
            ctx.config().load();
            ctx.environmentChanged();
            load();
            Ui.info("Entornos guardados. Las conexiones remotas se recrearán con la nueva configuración.");
        } catch (Exception e) {
            Ui.error(e);
        }
    }

    private void activate() {
        String name = env.getValue();
        if (name == null) {
            return;
        }
        ctx.config().app().setActiveEnvironment(name);
        ctx.config().save();
        ctx.environmentChanged();
        load();
        Ui.info("Entorno activo: " + name + ". Reconecte los canales abiertos.");
    }

    @Override
    public String title() {
        return "Configuración";
    }

    @Override
    public Node node() {
        return root;
    }
}
