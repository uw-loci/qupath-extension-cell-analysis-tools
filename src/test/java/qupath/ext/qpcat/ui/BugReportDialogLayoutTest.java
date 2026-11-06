package qupath.ext.qpcat.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.lib.gui.QuPathGUI;

/**
 * Every wrapped note in the bug report dialog shows its whole text, at the size the window
 * opens at and after the user narrows or shortens it. Needs a display; skipped without one.
 */
class BugReportDialogLayoutTest {

    private static boolean fxUp;

    @BeforeAll
    static void startFx() throws Exception {
        try {
            CountDownLatch up = new CountDownLatch(1);
            Platform.startup(up::countDown);
            fxUp = up.await(20, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            fxUp = true;
        } catch (Throwable noDisplay) {
            fxUp = false;
        }
        if (fxUp) {
            Platform.setImplicitExit(false);
        }
    }

    @Test
    void wrappedNotesAreNeverCutShort() throws Exception {
        assumeTrue(fxUp, "no JavaFX display");
        List<String> problems = onFx(() -> {
            Constructor<BugReportDialog> c = BugReportDialog.class.getDeclaredConstructor(QuPathGUI.class);
            c.setAccessible(true);
            BugReportDialog dialog = c.newInstance((QuPathGUI) null);
            Field f = BugReportDialog.class.getDeclaredField("stage");
            f.setAccessible(true);
            Stage stage = (Stage) f.get(dialog);
            stage.show();
            List<String> out = new ArrayList<>();
            out.addAll(cutLabels(stage, "as opened " + (int) stage.getWidth() + "x" + (int) stage.getHeight()));
            Region root = (Region) stage.getScene().getRoot();
            if (root.prefWidth(-1) > 800) {
                out.add("the window opens " + (int) root.prefWidth(-1) + " px wide, as wide as a note on one line");
            }
            // Narrow and wide, roomy and short of height. The content is resized directly: a
            // stage resize reaches the scene a pulse later on some platforms, if at all.
            double[][] sizes = {{560, 2000}, {600, 2000}, {950, 2000}, {560, 500}, {950, 500}, {560, 300}};
            for (double[] s : sizes) {
                root.resize(s[0], s[1]);
                out.addAll(cutLabels(stage, "content at " + (int) s[0] + "x" + (int) s[1]));
            }
            stage.close();
            return out;
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static List<String> cutLabels(Stage stage, String when) {
        Region root = (Region) stage.getScene().getRoot();
        root.applyCss();
        root.layout();
        List<String> out = new ArrayList<>();
        for (Label l : labels(root)) {
            if (!l.isVisible()
                    || !l.isWrapText()
                    || l.getText() == null
                    || l.getText().isEmpty()) {
                continue;
            }
            for (Node n : l.getChildrenUnmodifiable()) {
                if (n instanceof Text t
                        && !t.getText()
                                .replace("\n", " ")
                                .replaceAll("\\s+", " ")
                                .trim()
                                .equals(l.getText().replaceAll("\\s+", " ").trim())) {
                    out.add(when + ": '"
                            + l.getText().substring(0, Math.min(40, l.getText().length()))
                            + "...' shows as '" + t.getText().replace("\n", "|") + "' (height "
                            + (int) l.getHeight() + ", pref " + (int) l.prefHeight(l.getWidth()) + ")");
                }
            }
        }
        return out;
    }

    private static List<Label> labels(Node n) {
        List<Label> out = new ArrayList<>();
        if (n instanceof Label l) {
            out.add(l);
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                out.addAll(labels(c));
            }
        }
        return out;
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(60, TimeUnit.SECONDS);
    }
}
