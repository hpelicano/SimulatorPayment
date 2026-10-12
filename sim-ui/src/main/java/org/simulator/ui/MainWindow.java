package org.simulator.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2OutlinedAL;
import org.kordamp.ikonli.material2.Material2OutlinedMZ;
import org.simulator.stats.StatsSnapshot;
import org.simulator.ui.util.Icons;
import org.simulator.ui.view.AcquirerView;
import org.simulator.ui.view.Base24View;
import org.simulator.ui.view.ConfigView;
import org.simulator.ui.view.CryptoView;
import org.simulator.ui.view.DashboardView;
import org.simulator.ui.view.DatabaseView;
import org.simulator.ui.view.FormatsView;
import org.simulator.ui.view.IssuerView;
import org.simulator.ui.view.LogsView;
import org.simulator.ui.view.ParserView;
import org.simulator.ui.view.RegressionView;
import org.simulator.ui.view.ReplayView;
import org.simulator.ui.view.StressView;
import org.simulator.ui.view.View;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Main layout: dark sidebar navigation on the left, active view in the center, status bar at the bottom. */
public class MainWindow {

    private static final String VERSION = "v1.3";
    private static Node themedRoot;

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final StackPane content = new StackPane();
    private final List<View> views;
    private final Map<View, Node> wrappers = new HashMap<>();
    private final ToggleGroup navGroup = new ToggleGroup();
    private final Label envBadge = new Label();
    private final Button themeButton = new Button();
    private final Label statusUser = chip(Material2OutlinedMZ.PERSON);
    private final Label statusEnv = chip(Material2OutlinedAL.DNS);
    private final Label statusAcq = chip(Material2OutlinedAL.LINK);
    private final Label statusIss = chip(Material2OutlinedAL.CALL_RECEIVED);
    private final Label statusRec = chip(Material2OutlinedAL.FIBER_MANUAL_RECORD);
    private final Label statusTps = chip(Material2OutlinedMZ.SHOW_CHART);
    private final Timeline timer;

    public MainWindow(AppContext ctx) {
        this.ctx = ctx;
        themedRoot = root;
        root.getStyleClass().add("app-root");
        applyTheme(ctx.config().user().getTheme());
        views = List.of(new DashboardView(ctx), new AcquirerView(ctx), new IssuerView(ctx),
                new ParserView(ctx), new FormatsView(ctx), new CryptoView(ctx), new RegressionView(ctx),
                new ReplayView(ctx), new StressView(ctx), new Base24View(ctx), new DatabaseView(ctx),
                new ConfigView(ctx, t -> {
                    applyTheme(t);
                    updateThemeButton();
                }), new LogsView());

        root.setLeft(buildSidebar());
        content.getStyleClass().add("content-area");
        content.setPadding(new Insets(14));
        root.setCenter(content);
        root.setBottom(buildStatusBar());

        show(views.get(0));
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshStatus()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        refreshStatus();
    }

    private Node buildSidebar() {
        StackPane logo = new StackPane(Icons.of(Material2OutlinedMZ.PAYMENTS, 22));
        logo.getStyleClass().add("brand-logo");
        Label title = new Label("SimulatorPayment");
        title.getStyleClass().add("brand-title");
        Label subtitle = new Label("Base24 Transaction Lab");
        subtitle.getStyleClass().add("brand-subtitle");
        HBox brand = new HBox(10, logo, new VBox(1, title, subtitle));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("brand");

        envBadge.setGraphic(Icons.of(Material2OutlinedAL.FIBER_MANUAL_RECORD, 10));
        envBadge.getStyleClass().add("env-badge");
        VBox.setMargin(envBadge, new Insets(0, 14, 2, 14));

        VBox nav = new VBox(2);
        nav.getStyleClass().add("nav-box");
        String lastSection = null;
        for (View v : views) {
            String section = section(v);
            if (!section.equals(lastSection)) {
                Label s = new Label(section);
                s.getStyleClass().add("nav-section");
                nav.getChildren().add(s);
                lastSection = section;
            }
            ToggleButton item = new ToggleButton(v.title(), Icons.of(icon(v), 18));
            item.getStyleClass().add("nav-item");
            item.setToggleGroup(navGroup);
            item.setUserData(v);
            item.setMaxWidth(Double.MAX_VALUE);
            item.setAlignment(Pos.CENTER_LEFT);
            item.setFocusTraversable(false);
            nav.getChildren().add(item);
        }
        navGroup.selectedToggleProperty().addListener((obs, old, sel) -> {
            if (sel == null) {
                navGroup.selectToggle(old); // prevent deselection
            } else if (sel != old) {
                show((View) sel.getUserData());
            }
        });
        navGroup.selectToggle(navGroup.getToggles().get(0));

        ScrollPane navScroll = new ScrollPane(nav);
        navScroll.setFitToWidth(true);
        navScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        navScroll.getStyleClass().add("nav-scroll");
        VBox.setVgrow(navScroll, Priority.ALWAYS);

        themeButton.getStyleClass().add("sidebar-button");
        themeButton.setMaxWidth(Double.MAX_VALUE);
        themeButton.setAlignment(Pos.CENTER_LEFT);
        themeButton.setOnAction(e -> toggleTheme());
        updateThemeButton();
        Label version = new Label(VERSION);
        version.getStyleClass().add("version-label");
        VBox footer = new VBox(6, themeButton, version);
        footer.getStyleClass().add("sidebar-footer");

        VBox sidebar = new VBox(10, brand, envBadge, navScroll, footer);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(230);
        sidebar.setMinWidth(210);
        return sidebar;
    }

    private Node buildStatusBar() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        statusTps.getStyleClass().add("metrics");
        HBox bar = new HBox(8, statusUser, statusEnv, statusAcq, statusIss, statusRec, spacer, statusTps);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private static Label chip(Ikon icon) {
        Label l = new Label("", Icons.of(icon, 13));
        l.getStyleClass().add("status-chip");
        return l;
    }

    private static String section(View v) {
        return switch (v.getClass().getSimpleName()) {
            case "DashboardView" -> "MONITOREO";
            case "AcquirerView", "IssuerView" -> "TRANSACCIONES";
            case "ParserView", "FormatsView", "CryptoView" -> "MENSAJERÍA";
            case "RegressionView", "ReplayView", "StressView" -> "PRUEBAS";
            case "Base24View" -> "BASE24";
            default -> "SISTEMA";
        };
    }

    private static Ikon icon(View v) {
        return switch (v.getClass().getSimpleName()) {
            case "DashboardView" -> Material2OutlinedAL.DASHBOARD;
            case "AcquirerView" -> Material2OutlinedMZ.SEND;
            case "IssuerView" -> Material2OutlinedAL.CALL_RECEIVED;
            case "ParserView" -> Material2OutlinedMZ.TRANSFORM;
            case "FormatsView" -> Material2OutlinedAL.CODE;
            case "CryptoView" -> Material2OutlinedMZ.VPN_KEY;
            case "RegressionView" -> Material2OutlinedAL.FACT_CHECK;
            case "ReplayView" -> Material2OutlinedMZ.REPLAY;
            case "StressView" -> Material2OutlinedMZ.SPEED;
            case "Base24View" -> Material2OutlinedAL.DNS;
            case "DatabaseView" -> Material2OutlinedMZ.STORAGE;
            case "ConfigView" -> Material2OutlinedMZ.SETTINGS;
            case "LogsView" -> Material2OutlinedAL.ARTICLE;
            default -> Material2OutlinedAL.CHEVRON_RIGHT;
        };
    }

    private void toggleTheme() {
        String next = isDark(ctx.config().user().getTheme()) ? "LIGHT" : "DARK";
        ctx.config().user().setTheme(next);
        try {
            ctx.config().save();
        } catch (Exception ignored) {
            // theme preference is best effort
        }
        applyTheme(next);
        updateThemeButton();
    }

    private void updateThemeButton() {
        boolean dark = isDark(ctx.config().user().getTheme());
        themeButton.setText(dark ? "Tema claro" : "Tema oscuro");
        themeButton.setGraphic(Icons.of(dark ? Material2OutlinedMZ.WB_SUNNY : Material2OutlinedAL.BEDTIME, 16));
    }

    private static boolean isDark(String theme) {
        return "dark".equalsIgnoreCase(theme);
    }

    private void show(View view) {
        Node node = wrappers.computeIfAbsent(view, this::scrollable);
        content.getChildren().setAll(node);
        view.onShow();
    }

    /** Wraps a view so it scrolls instead of overflowing on small screens, decorating it as cards. */
    private Node scrollable(View view) {
        Node node = view.node();
        node.getStyleClass().add("view");
        if (node instanceof BorderPane bp) {
            decorate(bp);
        }
        ScrollPane sp = new ScrollPane(node);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.getStyleClass().add("view-scroll");
        if (node instanceof Region r) {
            r.setMinSize(760, 480);
        }
        return sp;
    }

    private static void decorate(BorderPane bp) {
        if (bp.getTop() != null && !bp.getTop().getStyleClass().contains("no-card")) {
            bp.getTop().getStyleClass().add("view-header");
        }
        card(bp.getCenter());
        card(bp.getLeft());
        card(bp.getRight());
        card(bp.getBottom());
        margin(bp.getCenter(), new Insets(12, 0, 0, 0));
        margin(bp.getLeft(), new Insets(12, 12, 0, 0));
        margin(bp.getRight(), new Insets(12, 0, 0, 12));
        margin(bp.getBottom(), new Insets(12, 0, 0, 0));
    }

    private static void card(Node n) {
        if (n == null || n.getStyleClass().contains("no-card")) {
            return;
        }
        if (n instanceof TabPane) {
            n.getStyleClass().addAll("card", "floating");
        } else if (n instanceof TextInputControl || n instanceof TableView || n instanceof ListView) {
            n.getStyleClass().add("card-plain");
        } else {
            n.getStyleClass().add("card");
        }
    }

    private static void margin(Node n, Insets insets) {
        if (n != null && BorderPane.getMargin(n) == null) {
            BorderPane.setMargin(n, insets);
        }
    }

    private void refreshStatus() {
        String env = ctx.config().activeEnvironment() == null ? "-" : ctx.config().activeEnvironment().getName();
        boolean unlocked = ctx.config().isUnlocked();
        statusUser.setText(ctx.user());
        statusEnv.setText("Entorno: " + env);
        envBadge.setText(env + (unlocked ? "" : "  ·  secretos bloqueados"));
        envBadge.setTooltip(new Tooltip(unlocked ? "Secretos desbloqueados"
                : "Los secretos se desbloquean al primer uso"));
        toggle(envBadge, "locked", !unlocked);

        boolean acq = ctx.acquirer().isConnected();
        statusAcq.setText(acq ? "Adquirente conectado" : "Adquirente desconectado");
        state(statusAcq, acq ? "ok" : "off");
        boolean iss = ctx.issuer().isRunning();
        statusIss.setText(iss ? "Emisor :" + ctx.issuer().port() : "Emisor detenido");
        state(statusIss, iss ? "ok" : "off");
        boolean rec = ctx.recorder().isRecording();
        statusRec.setText(rec ? "Grabando" : "Sin grabación");
        state(statusRec, rec ? "rec" : "off");

        StatsSnapshot s = ctx.stats().snapshot();
        String metrics = ctx.exporter().isRunning() ? "  ·  /metrics :" + ctx.exporter().port() : "";
        statusTps.setText(String.format("TPS %.1f  ·  Total %d  ·  Aprob. %.1f%%%s",
                s.currentTps(), s.total(), s.approvalRate(), metrics));
    }

    private static void state(Label l, String cls) {
        l.getStyleClass().removeAll("ok", "off", "warn", "rec");
        l.getStyleClass().add(cls);
    }

    private static void toggle(Node n, String cls, boolean on) {
        if (on) {
            if (!n.getStyleClass().contains(cls)) {
                n.getStyleClass().add(cls);
            }
        } else {
            n.getStyleClass().remove(cls);
        }
    }

    public javafx.scene.Parent root() {
        return root;
    }

    public void dispose() {
        timer.stop();
        views.forEach(View::dispose);
    }

    public static void applyTheme(String theme) {
        boolean dark = isDark(theme);
        Application.setUserAgentStylesheet(dark
                ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet());
        if (themedRoot != null) {
            toggle(themedRoot, "theme-dark", dark);
        }
    }
}
