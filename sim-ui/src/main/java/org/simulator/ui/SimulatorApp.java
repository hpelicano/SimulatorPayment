package org.simulator.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.simulator.config.ConfigService;
import org.simulator.persistence.Database;
import org.simulator.ui.util.Ui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * JavaFX entry point. Configuration dir can be overridden with {@code -Dsimulator.config=<dir>}
 * and the H2 data dir with {@code -Dsimulator.data=<dir>}.
 */
public class SimulatorApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(SimulatorApp.class);
    private static final double PREF_WIDTH = 1440;
    private static final double PREF_HEIGHT = 900;
    private static final double MIN_WIDTH = 900;
    private static final double MIN_HEIGHT = 600;

    private AppContext ctx;
    private MainWindow window;

    @Override
    public void start(Stage stage) {
        try {
            String cfgDir = System.getProperty("simulator.config");
            String dataDir = System.getProperty("simulator.data");
            ConfigService config = new ConfigService(cfgDir != null ? Path.of(cfgDir) : ConfigService.defaultDir()).load();
            MainWindow.applyTheme(config.user().getTheme());
            List<String> errors = config.validate();
            if (!errors.isEmpty()) {
                Ui.error("La configuración tiene errores:\n- " + String.join("\n- ", errors)
                        + "\n\nCorríjalos en la vista Configuración.");
            }
            String envPwd = System.getenv("SIM_MASTER_PASSWORD");
            if (envPwd != null && !config.unlock(envPwd.toCharArray())) {
                log.warn("SIM_MASTER_PASSWORD is incorrect: configuration stays locked");
            }
            Database db = Database.file(dataDir != null ? Path.of(dataDir) : Database.defaultDir());
            ctx = new AppContext(config, db);
            ctx.setUnlockPrompt(() -> onFxThread(() -> MasterPasswordDialog.unlock(config)));
            if (config.user().isMetricsEnabled()) {
                try {
                    ctx.startMetrics();
                } catch (Exception e) {
                    log.warn("Cannot start Prometheus endpoint: {}", e.getMessage());
                }
            }
            window = new MainWindow(ctx);
            Rectangle2D screen = Screen.getPrimary().getVisualBounds();
            double width = Math.min(PREF_WIDTH, screen.getWidth());
            double height = Math.min(PREF_HEIGHT, screen.getHeight());
            Scene scene = new Scene(window.root(), width, height);
            scene.getStylesheets().add(org.simulator.ui.util.Icons.STYLESHEET);
            stage.setTitle("SimulatorPayment - Simulador transaccional Base24  [" + ctx.env().getName() + "]");
            stage.setScene(scene);
            stage.setMinWidth(Math.min(MIN_WIDTH, screen.getWidth()));
            stage.setMinHeight(Math.min(MIN_HEIGHT, screen.getHeight()));
            stage.setX(screen.getMinX() + (screen.getWidth() - width) / 2);
            stage.setY(screen.getMinY() + (screen.getHeight() - height) / 2);
            if (screen.getWidth() < PREF_WIDTH || screen.getHeight() < PREF_HEIGHT) {
                stage.setMaximized(true);
            }
            stage.setOnCloseRequest(e -> shutdown());
            stage.show();
            log.info("SimulatorPayment started. Config: {}, DB: {}", config.getConfigDir(), db.url());
        } catch (Exception e) {
            log.error("Startup failed", e);
            Ui.error("No se pudo iniciar el simulador: " + Ui.root(e).getMessage());
            Platform.exit();
        }
    }

    /** Runs a dialog on the FX thread and waits for its result when called from a background task. */
    private static boolean onFxThread(java.util.function.BooleanSupplier dialog) {
        if (Platform.isFxApplicationThread()) {
            return dialog.getAsBoolean();
        }
        java.util.concurrent.FutureTask<Boolean> task = new java.util.concurrent.FutureTask<>(dialog::getAsBoolean);
        Platform.runLater(task);
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("Unlock dialog failed: {}", e.getCause().toString());
            return false;
        }
    }
    private void shutdown() {
        if (window != null) {
            window.dispose();
            window = null;
        }
        if (ctx != null) {
            try {
                ctx.close();
            } catch (Exception e) {
                log.warn("Error on shutdown: {}", e.toString());
            }
            ctx = null;
        }
        Ui.shutdown();
    }

    @Override
    public void stop() {
        shutdown();
    }

    public static void main(String[] args) {
        launch(SimulatorApp.class, args);
    }
}
