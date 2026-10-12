package org.simulator.ui.view;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.ChoiceBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.converter.DefaultStringConverter;
import org.simulator.common.json.Json;
import org.simulator.iso.dialect.Encoding;
import org.simulator.iso.dialect.FieldDef;
import org.simulator.iso.dialect.FieldType;
import org.simulator.iso.dialect.LengthType;
import org.simulator.iso.dialect.MessageDialect;
import org.simulator.ui.AppContext;
import org.simulator.ui.util.Ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Visual editor for message dialects (ISO8583, HPDH, BNET, VISA...). Edits the resolved field table and saves
 * a standalone dialect to {@code config/formats/<id>.json}, which overrides the built-in with the same id.
 */
public class FormatsView implements View {

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final ChoiceBox<String> dialect = new ChoiceBox<>();
    private final TextField id = new TextField();
    private final TextField name = new TextField();
    private final TextField description = new TextField();
    private final ChoiceBox<Encoding> mtiEnc = enc();
    private final ChoiceBox<Encoding> bitmapEnc = enc();
    private final ChoiceBox<Encoding> numericEnc = enc();
    private final ChoiceBox<Encoding> textEnc = enc();
    private final ChoiceBox<Encoding> lengthEnc = enc();
    private final TableView<FieldDef> table = new TableView<>();
    private MessageDialect current;

    public FormatsView(AppContext ctx) {
        this.ctx = ctx;
        table.setEditable(true);
        TableColumn<FieldDef, String> num = text("Campo", 60, f -> String.valueOf(f.getNumber()), (f, v) -> f.setNumber(Integer.parseInt(v.trim())));
        table.getColumns().setAll(java.util.List.of(num,
                text("Nombre", 200, FieldDef::getName, FieldDef::setName),
                choice("Tipo", 70, FieldType.values(), FieldDef::getType, FieldDef::setType),
                choice("Longitud", 90, LengthType.values(), FieldDef::getLengthType, FieldDef::setLengthType),
                text("Máx.", 60, f -> String.valueOf(f.getLength()), (f, v) -> f.setLength(Integer.parseInt(v.trim()))),
                choice("Encoding", 100, Encoding.values(), FieldDef::getEncoding, FieldDef::setEncoding),
                choice("Enc. largo", 100, Encoding.values(), FieldDef::getLengthEncoding, FieldDef::setLengthEncoding),
                text("Sub-formato", 110, FieldDef::getSubFormat, (f, v) -> f.setSubFormat(v.isBlank() ? null : v.trim())),
                text("Pad", 50, FieldDef::getPadNibble, (f, v) -> f.setPadNibble(v.isBlank() ? null : v.trim())),
                choice("Sensible", 80, new Boolean[]{Boolean.TRUE, Boolean.FALSE}, FieldDef::getSensitive, FieldDef::setSensitive)));

        dialect.getSelectionModel().selectedItemProperty().addListener((o, a, v) -> load(v));
        VBox top = new VBox(6,
                Ui.bar(Ui.title("Editor de formatos"), Ui.spacer(), new Label("Dialecto:"), dialect,
                        Ui.button("Recargar", this::reloadList)),
                Ui.form("Id (guardar como)", id, "Nombre", name, "Descripción", description),
                Ui.bar(new Label("MTI"), mtiEnc, new Label("Bitmap"), bitmapEnc, new Label("Numérico"), numericEnc,
                        new Label("Texto"), textEnc, new Label("Largo"), lengthEnc),
                table,
                Ui.bar(Ui.button("Agregar campo", this::addField), Ui.button("Quitar campo", () -> {
                            FieldDef f = table.getSelectionModel().getSelectedItem();
                            if (f != null) {
                                table.getItems().remove(f);
                            }
                        }), Ui.spacer(), Ui.button("Ver JSON", () -> Ui.saveText(Ui.window(root), "Exportar dialecto",
                                id.getText().trim() + ".json", Json.write(build()))),
                        Ui.button("Guardar y aplicar", this::save)),
                new Label("Se guarda en config/formats/<id>.json como dialecto autónomo (sin herencia) y reemplaza al built-in con el mismo id."));
        VBox.setVgrow(table, Priority.ALWAYS);
        root.setCenter(top);
        root.setPadding(new Insets(12));
        reloadList();
    }

    private static ChoiceBox<Encoding> enc() {
        return new ChoiceBox<>(FXCollections.observableArrayList(Encoding.values()));
    }

    private TableColumn<FieldDef, String> text(String title, double w, Function<FieldDef, String> get, BiConsumer<FieldDef, String> set) {
        TableColumn<FieldDef, String> c = new TableColumn<>(title);
        c.setPrefWidth(w);
        c.setCellValueFactory(d -> new SimpleStringProperty(get.apply(d.getValue())));
        c.setCellFactory(TextFieldTableCell.forTableColumn(new DefaultStringConverter()));
        c.setOnEditCommit(e -> {
            try {
                set.accept(e.getRowValue(), e.getNewValue() == null ? "" : e.getNewValue());
            } catch (Exception ex) {
                Ui.error("Valor inválido: " + e.getNewValue());
            }
            table.refresh();
        });
        return c;
    }

    private <T> TableColumn<FieldDef, T> choice(String title, double w, T[] values, Function<FieldDef, T> get, BiConsumer<FieldDef, T> set) {
        TableColumn<FieldDef, T> c = new TableColumn<>(title);
        c.setPrefWidth(w);
        c.setCellValueFactory(d -> new SimpleObjectProperty<>(get.apply(d.getValue())));
        c.setCellFactory(ChoiceBoxTableCell.forTableColumn(values));
        c.setOnEditCommit(e -> set.accept(e.getRowValue(), e.getNewValue()));
        return c;
    }

    private void reloadList() {
        String sel = dialect.getValue();
        dialect.getItems().setAll(ctx.messages().registry().ids().stream().sorted().toList());
        dialect.setValue(sel != null && dialect.getItems().contains(sel) ? sel : dialect.getItems().isEmpty() ? null : dialect.getItems().get(0));
    }

    private void load(String dialectId) {
        if (dialectId == null) {
            return;
        }
        current = ctx.messages().dialect(dialectId);
        id.setText(current.getId());
        name.setText(current.getName());
        description.setText(current.getDescription());
        mtiEnc.setValue(current.getMtiEncoding());
        bitmapEnc.setValue(current.getBitmapEncoding());
        numericEnc.setValue(current.getNumericEncoding());
        textEnc.setValue(current.getTextEncoding());
        lengthEnc.setValue(current.getLengthEncoding());
        table.getItems().setAll(current.resolvedFields().values().stream().map(FieldDef::copy).toList());
    }

    private void addField() {
        int next = table.getItems().stream().mapToInt(FieldDef::getNumber).max().orElse(1) + 1;
        Ui.prompt("Número de campo (2-128)", String.valueOf(Math.min(next, 128))).ifPresent(v -> {
            try {
                int n = Integer.parseInt(v.trim());
                if (n < 2 || n > 128 || table.getItems().stream().anyMatch(f -> f.getNumber() == n)) {
                    Ui.error("Número inválido o ya definido");
                    return;
                }
                table.getItems().add(new FieldDef(n, "Campo " + n, FieldType.ANS, LengthType.LLVAR, 99));
                table.getItems().sort(Comparator.comparingInt(FieldDef::getNumber));
            } catch (NumberFormatException e) {
                Ui.error("Número inválido");
            }
        });
    }

    private MessageDialect build() {
        MessageDialect d = new MessageDialect();
        d.setId(id.getText().trim());
        d.setName(name.getText().trim());
        d.setDescription(description.getText().trim());
        if (current != null) {
            d.setHeader(current.getHeader());
            d.setFraming(current.getFraming());
        }
        d.setMtiEncoding(mtiEnc.getValue());
        d.setBitmapEncoding(bitmapEnc.getValue());
        d.setNumericEncoding(numericEnc.getValue());
        d.setTextEncoding(textEnc.getValue());
        d.setLengthEncoding(lengthEnc.getValue());
        Map<String, FieldDef> fields = new LinkedHashMap<>();
        table.getItems().stream().sorted(Comparator.comparingInt(FieldDef::getNumber))
                .forEach(f -> fields.put(String.valueOf(f.getNumber()), f.copy()));
        d.setFields(fields);
        return d;
    }

    private void save() {
        String newId = id.getText().trim();
        if (!newId.matches("[A-Za-z0-9._-]{1,60}")) {
            Ui.error("Id inválido (letras, números, . _ -)");
            return;
        }
        try {
            MessageDialect d = build();
            Path dir = ctx.config().getConfigDir().resolve("formats");
            Files.createDirectories(dir);
            Path file = dir.resolve(newId + ".json");
            if (Files.exists(file) && !Ui.confirm("Sobrescribir", "Ya existe " + file.getFileName() + ". ¿Reemplazar?")) {
                return;
            }
            Files.writeString(file, Json.write(d), StandardCharsets.UTF_8);
            ctx.messages().registry().register(d);
            ctx.messages().registry().resolveAll();
            ctx.messages().clearCache();
            reloadList();
            dialect.setValue(newId);
            Ui.info("Dialecto '" + newId + "' guardado y aplicado:\n" + file);
        } catch (Exception e) {
            Ui.error(e);
        }
    }

    @Override
    public String title() {
        return "Formatos";
    }

    @Override
    public Node node() {
        return root;
    }
}
