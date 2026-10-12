package org.simulator.ui.view;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.simulator.common.util.Hex;
import org.simulator.config.model.CryptoConfig;
import org.simulator.crypto.Ciphers;
import org.simulator.crypto.CryptoService;
import org.simulator.crypto.Cvv;
import org.simulator.crypto.Emv;
import org.simulator.crypto.Mac;
import org.simulator.crypto.PinBlock;
import org.simulator.iso.IsoMessage;
import org.simulator.ui.AppContext;
import org.simulator.ui.util.Ui;

import java.nio.charset.StandardCharsets;

/** Software HSM: key configuration per environment and PIN/MAC/CVV/EMV calculators. */
public class CryptoView implements View {

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();

    private final CheckBox enabled = new CheckBox("Habilitar HSM de software en adquirente/emisor");
    private final TextField zpk = Ui.field("ZPK (hex o ENC(...))", "");
    private final TextField mak = Ui.field("MAK (hex o ENC(...))", "");
    private final TextField cvk = Ui.field("CVK A||B (hex o ENC(...))", "");
    private final TextField imk = Ui.field("IMK-AC (hex o ENC(...))", "");
    private final ChoiceBox<String> macAlg = new ChoiceBox<>();
    private final ChoiceBox<Integer> pinFormat = new ChoiceBox<>();
    private final ChoiceBox<String> sessionKey = new ChoiceBox<>();
    private final ChoiceBox<String> iadMode = new ChoiceBox<>();
    private final CheckBox genArqc = new CheckBox("Generar ARQC (9F26) en requests");
    private final CheckBox genArpc = new CheckBox("Generar ARPC (tag 91) en respuestas del emisor");
    private final CheckBox verArqc = new CheckBox("Verificar ARQC en el emisor");
    private final TextField failRc = Ui.field("RC si ARQC inválido (vacío = no cambiar)", "");
    private final CheckBox encrypt = new CheckBox("Cifrar claves al guardar (ENC)");
    private final TextArea out = Ui.code(false);

    public CryptoView(AppContext ctx) {
        this.ctx = ctx;
        macAlg.getItems().setAll("NONE", "X919", "ISO9797_ALG1", "AES_CMAC");
        pinFormat.getItems().setAll(0, 1, 3);
        sessionKey.getItems().setAll("NONE", "EMV_CSK");
        iadMode.getItems().setAll("CVR", "FULL", "NONE");
        encrypt.setSelected(true);

        var configPane = new VBox(10,
                new Label("Solo claves de TEST. Nunca cargar claves productivas en un simulador."),
                enabled,
                Ui.form("ZPK", zpk, "MAK", mak, "CVK", cvk, "IMK-AC", imk, "Algoritmo MAC", macAlg,
                        "Formato PIN block", pinFormat, "Session key EMV", sessionKey, "IAD en ARQC", iadMode,
                        "RC ARQC inválido", failRc),
                genArqc, genArpc, verArqc, encrypt,
                Ui.bar(Ui.button("Guardar", this::save), Ui.button("Recargar", this::load), Ui.button("KCV de claves", this::kcvs)));
        configPane.setPadding(new Insets(12));

        TabPane tabs = new TabPane(new Tab("Configuración", new ScrollPane(configPane)), new Tab("Calculadoras", tools()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        root.setTop(Ui.title("Criptografía (HSM de software)"));
        root.setCenter(tabs);
        root.setBottom(out);
        out.setPrefRowCount(8);
        root.setPadding(new Insets(12));
        load();
    }

    private Node tools() {
        TextField pin = Ui.field("PIN", "1234");
        TextField pan = Ui.field("PAN", "4761739001010010");
        TextField block = Ui.field("PIN block cifrado (hex)", "");
        TextField exp = Ui.field("Vencimiento YYMM", "2512");
        TextField svc = Ui.field("Service code", "201");
        TextField macData = Ui.field("Datos (texto o hex:...)", "hex:0200723A000108C18000");
        TextArea msg = Ui.code(true);
        msg.setPrefRowCount(8);
        msg.setPromptText("Mensaje JSON (dialecto, mti, fields) con DE55/DE52/DE64 para probar el pipeline completo");

        var box = new VBox(10,
                new Label("PIN block (usa ZPK y formato configurados)"),
                Ui.bar(new Label("PIN"), pin, new Label("PAN"), pan,
                        Ui.button("Cifrar", () -> run(() -> {
                            String enc = PinBlock.encrypt(ctx.crypto().key("zpk"), cfg().getPinBlockFormat(), pin.getText(), pan.getText());
                            block.setText(enc);
                            return "PIN block cifrado: " + enc;
                        }))),
                Ui.bar(new Label("Bloque"), block, Ui.button("Descifrar", () -> run(() ->
                        "PIN: " + PinBlock.decryptPin(ctx.crypto().key("zpk"), block.getText(), pan.getText())))),
                new Label("CVV / CVV2 / iCVV (usa CVK)"),
                Ui.bar(new Label("Venc."), exp, new Label("Service"), svc, Ui.button("Calcular", () -> run(() -> {
                    byte[] k = ctx.crypto().key("cvk");
                    return "CVV(" + svc.getText() + ")=" + Cvv.compute(k, pan.getText(), exp.getText(), svc.getText())
                            + "  CVV2=" + Cvv.compute(k, pan.getText(), exp.getText(), "000")
                            + "  iCVV=" + Cvv.compute(k, pan.getText(), exp.getText(), "999");
                }))),
                new Label("MAC (usa MAK y algoritmo configurados)"),
                Ui.bar(macData, Ui.button("Calcular MAC", () -> run(() -> {
                    String d = macData.getText();
                    byte[] data = d.startsWith("hex:") ? Hex.decode(d.substring(4).replace(" ", "")) : d.getBytes(StandardCharsets.US_ASCII);
                    Mac.Algorithm alg = Mac.Algorithm.valueOf(cfg().getMacAlgorithm());
                    return "MAC " + alg + " = " + Hex.encode(Mac.compute(alg, ctx.crypto().key("mak"), data));
                }))),
                new Label("Pipeline de mensaje: aplica PIN/ARQC/MAC como el adquirente y verifica como el emisor"),
                msg,
                Ui.bar(Ui.button("Aplicar (adquirente)", () -> run(() -> {
                    IsoMessage m = withCryptoForced(() -> ctx.crypto().prepareRequest(ctx.messages().fromJson(msg.getText())));
                    msg.setText(ctx.messages().toJson(m));
                    return "Request preparado. ARQC/MAC calculados.";
                })), Ui.button("Verificar (emisor)", () -> run(() -> {
                    IsoMessage m = ctx.messages().fromJson(msg.getText());
                    CryptoService.ArqcCheck c = ctx.crypto().verifyArqc(m);
                    StringBuilder sb = new StringBuilder();
                    sb.append("ARQC: ").append(c.present() ? (c.valid() ? "VÁLIDO" : "INVÁLIDO esperado=" + c.expected()) : "no presente");
                    if (m.has(64) || m.has(128)) sb.append("\nMAC: ").append(ctx.crypto().verifyMac(m) ? "VÁLIDA" : "INVÁLIDA");
                    if (m.has(52)) sb.append("\nPIN: ").append(ctx.crypto().decryptPin(m).replaceAll(".", "*")).append(" (descifrado OK)");
                    return sb.toString();
                })), Ui.button("Tags EMV", () -> run(() -> {
                    IsoMessage m = ctx.messages().fromJson(msg.getText());
                    return m.has(55) ? Ui.pretty(Emv.tags(m.get(55))) : "Sin DE55";
                }))));
        box.setPadding(new Insets(12));
        return new ScrollPane(box);
    }

    private IsoMessage withCryptoForced(java.util.function.Supplier<IsoMessage> s) {
        boolean was = cfg().isEnabled();
        cfg().setEnabled(true);
        try {
            return s.get();
        } finally {
            cfg().setEnabled(was);
        }
    }

    private void run(java.util.concurrent.Callable<String> c) {
        try {
            out.setText(c.call());
        } catch (Exception e) {
            out.setText("ERROR: " + Ui.root(e).getMessage());
        }
    }

    private CryptoConfig cfg() {
        return ctx.env().getCrypto();
    }

    private void load() {
        CryptoConfig c = cfg();
        enabled.setSelected(c.isEnabled());
        zpk.setText(nz(c.getZpk()));
        mak.setText(nz(c.getMak()));
        cvk.setText(nz(c.getCvk()));
        imk.setText(nz(c.getImkAc()));
        macAlg.setValue(nz(c.getMacAlgorithm()).isEmpty() ? "NONE" : c.getMacAlgorithm());
        pinFormat.setValue(c.getPinBlockFormat());
        sessionKey.setValue(nz(c.getEmvSessionKey()).isEmpty() ? "NONE" : c.getEmvSessionKey());
        iadMode.setValue(nz(c.getEmvIadMode()).isEmpty() ? "CVR" : c.getEmvIadMode());
        genArqc.setSelected(c.isGenerateArqc());
        genArpc.setSelected(c.isGenerateArpc());
        verArqc.setSelected(c.isVerifyArqc());
        failRc.setText(nz(c.getArqcFailureResponseCode()));
    }

    private void save() {
        try {
            CryptoConfig c = cfg();
            c.setEnabled(enabled.isSelected());
            c.setZpk(protect(zpk.getText()));
            c.setMak(protect(mak.getText()));
            c.setCvk(protect(cvk.getText()));
            c.setImkAc(protect(imk.getText()));
            c.setMacAlgorithm(macAlg.getValue());
            c.setPinBlockFormat(pinFormat.getValue());
            c.setEmvSessionKey(sessionKey.getValue());
            c.setEmvIadMode(iadMode.getValue());
            c.setGenerateArqc(genArqc.isSelected());
            c.setGenerateArpc(genArpc.isSelected());
            c.setVerifyArqc(verArqc.isSelected());
            c.setArqcFailureResponseCode(failRc.getText().trim());
            ctx.config().save();
            load();
            out.setText("Configuración cripto guardada para el entorno " + ctx.env().getName());
        } catch (Exception e) {
            Ui.error(e);
        }
    }

    private String protect(String v) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty() || s.startsWith("ENC(") || !encrypt.isSelected() || !ctx.ensureUnlocked()) return s.isEmpty() ? null : s;
        Ciphers.parseKey(s, "clave");
        return ctx.config().encryptSecret(s);
    }

    private void kcvs() {
        StringBuilder sb = new StringBuilder();
        for (String k : new String[]{"zpk", "mak", "cvk", "imkAc"}) {
            try {
                byte[] key = ctx.crypto().key(k);
                boolean aes = "mak".equals(k) && "AES_CMAC".equals(cfg().getMacAlgorithm());
                sb.append(k).append(": KCV ").append(Ciphers.kcv(key, aes)).append('\n');
            } catch (Exception e) {
                sb.append(k).append(": ").append(e.getMessage()).append('\n');
            }
        }
        out.setText(sb.toString());
    }

    private static String nz(String s) { return s == null ? "" : s; }

    @Override
    public String title() { return "Cripto / HSM"; }

    @Override
    public Node node() { return root; }
}
