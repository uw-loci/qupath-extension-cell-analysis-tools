package qupath.ext.qpcat.ui;

import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.MeasurementNames;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The marker of a measurement name is bold in the views that list many of them. */
class MeasurementNameTextTest {

    private static final List<String> NAMES = List.of(
            "Nucleus: CD3 mean", "Cell: PCNA: Median", "CD8: Membrane: Std.Dev.", "Cell: Area");

    @BeforeEach
    void requireToolkit() {
        assumeTrue(FxHarness.available(), "no display: JavaFX toolkit unavailable");
    }

    private static boolean isBold(Text t) {
        return t.getFont().getStyle().toLowerCase().contains("bold");
    }

    @Test
    void onlyTheMarkerIsBold() {
        List<String> bold = FxHarness.onFxGet(() -> {
            TextFlow flow = MeasurementNameText.flow(MeasurementNames.split("Nucleus: CD3 std dev"));
            FxHarness.layOutOffscreen(flow);
            return flow.getChildren().stream().map(n -> (Text) n)
                    .filter(MeasurementNameTextTest::isBold).map(Text::getText)
                    .collect(Collectors.toList());
        });
        assertThat(bold).containsExactly("CD3");
    }

    @Test
    void onlyTheMarkerIsBoldEvenUnderABoldParent() {
        // A table column header styles its contents bold; the phenotyping headers sit there.
        List<String> bold = FxHarness.onFxGet(() -> {
            TextFlow flow = MeasurementNameText.flow(MeasurementNames.split("Cell: PCNA: Mean"));
            javafx.scene.layout.StackPane parent = new javafx.scene.layout.StackPane(flow);
            parent.setStyle("-fx-font-weight: bold;");
            FxHarness.layOutOffscreen(parent);
            return flow.getChildren().stream().map(n -> (Text) n)
                    .filter(MeasurementNameTextTest::isBold).map(Text::getText)
                    .collect(Collectors.toList());
        });
        assertThat(bold).containsExactly("PCNA");
    }

    @Test
    void pickerRowsKeepTheirCheckboxAndHeightAndBoldTheMarker() {
        record Row(boolean checkbox, List<String> bold, String text, double height) {}
        List<Row> rows = FxHarness.onFxGet(() -> {
            MeasurementSelectionPane pane = new MeasurementSelectionPane();
            pane.setMeasurements(NAMES, n -> n.contains("CD3"));
            Scene scene = FxHarness.layOutOffscreen(pane);
            scene.getRoot().layout();
            return pane.lookupAll(".list-cell").stream()
                    .map(n -> (ListCell<?>) n)
                    .filter(c -> !c.isEmpty())
                    .map(c -> {
                        HBox g = (HBox) c.getGraphic();
                        TextFlow flow = (TextFlow) g.getChildren().get(1);
                        List<Text> texts = flow.getChildren().stream().map(n -> (Text) n).toList();
                        return new Row(g.getChildren().get(0) instanceof CheckBox,
                                texts.stream().filter(MeasurementNameTextTest::isBold)
                                        .map(Text::getText).toList(),
                                texts.stream().map(Text::getText).collect(Collectors.joining()),
                                c.getHeight());
                    })
                    .toList();
        });
        double plainHeight = FxHarness.onFxGet(() -> {
            ListView<String> plain = new ListView<>();
            plain.getItems().setAll(NAMES);
            plain.setCellFactory(CheckBoxListCell.forListView(s -> new SimpleBooleanProperty()));
            FxHarness.layOutOffscreen(plain);
            return plain.lookupAll(".list-cell").stream()
                    .map(n -> (ListCell<?>) n).filter(c -> !c.isEmpty())
                    .mapToDouble(ListCell::getHeight).max().orElse(-1);
        });

        assertThat(rows).hasSize(NAMES.size());
        assertThat(rows).allMatch(Row::checkbox);
        assertThat(rows.stream().map(Row::text)).containsExactlyElementsOf(NAMES);
        assertThat(rows.stream().map(Row::bold))
                .containsExactly(List.of("CD3"), List.of("PCNA"), List.of("CD8"), List.of("Area"));
        assertThat(rows).allSatisfy(r -> assertThat(r.height()).isCloseTo(plainHeight,
                org.assertj.core.data.Offset.offset(0.5)));
    }

    @Test
    void canvasDrawingCentresTheWholeNameAndRestoresTheContext() {
        double[] out = FxHarness.onFxGet(() -> {
            Font font = Font.font("System", 10);
            MeasurementNames.Parts p = MeasurementNames.split("Nucleus: CD3 mean");
            Canvas canvas = new Canvas(300, 40);
            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.setFont(font);
            gc.setTextAlign(TextAlignment.RIGHT);
            MeasurementNameText.fill(gc, p, 150, 20, TextAlignment.CENTER);
            Text plain = new Text(p.text());
            plain.setFont(font);
            return new double[] {
                MeasurementNameText.width(p, font),
                plain.getLayoutBounds().getWidth(),
                gc.getFont().equals(font) ? 1 : 0,
                gc.getTextAlign() == TextAlignment.RIGHT ? 1 : 0
            };
        });
        assertThat(out[0]).as("bold marker is wider than the plain name").isGreaterThan(out[1]);
        assertThat(out[2]).as("font restored").isEqualTo(1);
        assertThat(out[3]).as("alignment restored").isEqualTo(1);
    }
}
