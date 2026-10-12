package org.simulator.ui.view;

import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.simulator.issuer.IssuerRules;
import org.simulator.issuer.ResponseRule;
import org.simulator.ui.util.Ui;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Form based editor for {@link IssuerRules}; the JSON editor remains the source for advanced options. */
public class RulesEditor extends BorderPane {

    private final ListView<ResponseRule> list = new ListView<>();
    private final TextField defRc = new TextField();
    private final TextField defDelay = new TextField();
    private final CheckBox authCode = new CheckBox("Generar código de autorización (DE38)");
    private final TextField name = new TextField();
    private final CheckBox enabled = new CheckBox("Habilitada");
    private final TextField mti = new TextField();
    private final TextField pc = new TextField();
    private final TextField bins = new TextField();
    private final TextField amountMin = new TextField();
    private final TextField amountMax = new TextField();
    private final TextField terminal = new TextField();
    private final TextField matchFields = new TextField();
    private final TextField rc = new TextField();
    private final TextField rcSeq = new TextField();
    private final TextField delay = new TextField();
    private final TextField jitter = new TextField();
    private final CheckBox noResponse = new CheckBox("Sin respuesta (forzar timeout)");
    private final TextField setFields = new TextField();
    private final TextField removeFields = new TextField();
    private IssuerRules rules = new IssuerRules();
    private ResponseRule editing;

    public RulesEditor() {
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ResponseRule r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : (r.isEnabled() ? "" : "(off) ") + r.getName());
            }
        });
        list.setPrefWidth(200);
        list.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> {
            try {
                commit();
            } catch (RuntimeException e) {
                Ui.error("Valor inválido en la regla: " + e.getMessage());
            }
            show(r);
        });
        bins.setPromptText("4540,5100 (coma)");
        matchFields.setPromptText("22=051;55=! (campo=valor, ! = ausente)");
        rcSeq.setPromptText("00,05,91 (rotativo)");
        setFields.setPromptText("38=${RANDOM_N:6};44=A");
        removeFields.setPromptText("54,55");

        VBox form = new VBox(6,
                Ui.form("RC por defecto", defRc, "Demora por defecto ms", defDelay), authCode,
                Ui.title("Regla seleccionada"),
                Ui.form("Nombre", name), enabled,
                new Label("Condición (vacío = cualquiera)"),
                Ui.form("MTI", mti, "Processing code", pc, "BIN prefijos", bins, "Importe mín.", amountMin,
                        "Importe máx.", amountMax, "Terminal", terminal, "Campos", matchFields),
                new Label("Acción"),
                Ui.form("RC", rc, "Secuencia RC", rcSeq, "Demora ms", delay, "Jitter ms", jitter,
                        "Setear campos", setFields, "Quitar campos", removeFields), noResponse);
        form.setPadding(new Insets(0, 0, 0, 10));

        VBox left = new VBox(6, list, Ui.bar(Ui.button("+", this::add), Ui.button("-", this::remove),
                Ui.button("↑", () -> move(-1)), Ui.button("↓", () -> move(1))));
        setLeft(left);
        setCenter(new javafx.scene.control.ScrollPane(form));
        show(null);
    }

    public void setRules(IssuerRules r) {
        editing = null;
        rules = r == null ? new IssuerRules() : r;
        defRc.setText(rules.getDefaultResponseCode());
        defDelay.setText(String.valueOf(rules.getDefaultDelayMs()));
        authCode.setSelected(rules.isGenerateAuthCode());
        list.getItems().setAll(rules.getRules());
        show(null);
    }

    /** Returns the edited rules (throws on invalid numbers). */
    public IssuerRules getRules() {
        commit();
        rules.setDefaultResponseCode(defRc.getText().trim());
        rules.setDefaultDelayMs(parseLong(defDelay.getText()));
        rules.setGenerateAuthCode(authCode.isSelected());
        rules.setRules(new java.util.ArrayList<>(list.getItems()));
        return rules;
    }

    private void add() {
        commit();
        ResponseRule r = new ResponseRule();
        r.setName("regla-" + (list.getItems().size() + 1));
        r.setEnabled(true);
        r.getAction().setResponseCode("00");
        list.getItems().add(r);
        list.getSelectionModel().select(r);
    }

    private void remove() {
        ResponseRule r = list.getSelectionModel().getSelectedItem();
        if (r != null) {
            editing = null;
            list.getItems().remove(r);
        }
    }

    private void move(int delta) {
        int i = list.getSelectionModel().getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= list.getItems().size()) {
            return;
        }
        commit();
        ResponseRule r = list.getItems().remove(i);
        list.getItems().add(j, r);
        list.getSelectionModel().select(j);
    }

    private void show(ResponseRule r) {
        editing = r;
        boolean off = r == null;
        for (var n : List.of(name, enabled, mti, pc, bins, amountMin, amountMax, terminal, matchFields, rc, rcSeq, delay,
                jitter, noResponse, setFields, removeFields)) {
            n.setDisable(off);
        }
        if (off) {
            return;
        }
        ResponseRule.Match m = r.getMatch();
        ResponseRule.Action a = r.getAction();
        name.setText(r.getName());
        enabled.setSelected(r.isEnabled());
        mti.setText(nz(m.getMti()));
        pc.setText(nz(m.getProcessingCode()));
        bins.setText(String.join(",", m.getBinPrefixes()));
        amountMin.setText(m.getAmountMin() == null ? "" : m.getAmountMin().toPlainString());
        amountMax.setText(m.getAmountMax() == null ? "" : m.getAmountMax().toPlainString());
        terminal.setText(nz(m.getTerminalId()));
        matchFields.setText(joinMap(m.getFields()));
        rc.setText(nz(a.getResponseCode()));
        rcSeq.setText(String.join(",", a.getResponseCodeSequence()));
        delay.setText(String.valueOf(a.getDelayMs()));
        jitter.setText(String.valueOf(a.getJitterMs()));
        noResponse.setSelected(a.isNoResponse());
        setFields.setText(joinMap(a.getSetFields()));
        removeFields.setText(a.getRemoveFields().stream().map(String::valueOf).collect(Collectors.joining(",")));
    }

    private void commit() {
        ResponseRule r = editing;
        if (r == null) {
            return;
        }
        ResponseRule.Match m = r.getMatch();
        ResponseRule.Action a = r.getAction();
        r.setName(name.getText().trim());
        r.setEnabled(enabled.isSelected());
        m.setMti(blank(mti.getText()));
        m.setProcessingCode(blank(pc.getText()));
        m.setBinPrefixes(split(bins.getText()));
        m.setAmountMin(blank(amountMin.getText()) == null ? null : new BigDecimal(amountMin.getText().trim()));
        m.setAmountMax(blank(amountMax.getText()) == null ? null : new BigDecimal(amountMax.getText().trim()));
        m.setTerminalId(blank(terminal.getText()));
        m.setFields(parseMap(matchFields.getText()));
        a.setResponseCode(blank(rc.getText()));
        a.setResponseCodeSequence(split(rcSeq.getText()));
        a.setDelayMs(parseLong(delay.getText()));
        a.setJitterMs(parseLong(jitter.getText()));
        a.setNoResponse(noResponse.isSelected());
        a.setSetFields(parseMap(setFields.getText()));
        a.setRemoveFields(split(removeFields.getText()).stream().map(Integer::parseInt).toList());
        list.refresh();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static long parseLong(String s) {
        return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
    }

    private static List<String> split(String s) {
        return s == null ? List.of() : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.toCollection(java.util.ArrayList::new));
    }

    private static String joinMap(Map<String, String> m) {
        return m.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(";"));
    }

    private static Map<String, String> parseMap(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s == null) {
            return out;
        }
        for (String part : s.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) {
                out.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
            }
        }
        return out;
    }
}
