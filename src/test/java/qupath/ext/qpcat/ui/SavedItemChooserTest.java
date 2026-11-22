package qupath.ext.qpcat.ui;

import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The chooser behind "Load Config..." and "Load Rules...": a list of saved
 * names with a Delete button that asks first and then re-reads the list.
 * Built on the FX thread, never shown.
 */
class SavedItemChooserTest {

    @BeforeAll
    static void needsFx() {
        assumeTrue(FxHarness.available(), "no display: FX tests skipped");
    }

    /** An in-memory store standing in for a manager's folder. */
    private static final class Store {
        final List<String> names = new ArrayList<>(List.of("alpha", "beta", "gamma"));
        final List<String> prompts = new ArrayList<>();
        boolean answer = true;

        SavedItemChooser chooser() throws Exception {
            SavedItemChooser c = new SavedItemChooser(null, "Load Clustering Config",
                    "Select a saved configuration:", "configuration", "Delete Clustering Config",
                    () -> new ArrayList<>(names), names::remove);
            c.setConfirm((title, message) -> {
                prompts.add(title + " | " + message);
                return answer;
            });
            return c;
        }
    }

    private static <T> T fx(java.util.concurrent.Callable<T> work) {
        return FxHarness.onFxGet(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void deleteIsEnabledOnlyWithASelection() {
        boolean[] disabled = fx(() -> {
            SavedItemChooser c = new Store().chooser();
            boolean withFirstSelected = c.deleteButton().isDisabled();
            c.list().getSelectionModel().clearSelection();
            boolean withNone = c.deleteButton().isDisabled();
            c.list().getSelectionModel().select("beta");
            return new boolean[] {withFirstSelected, withNone, c.deleteButton().isDisabled()};
        });
        assertThat(disabled).containsExactly(false, true, false);
    }

    @Test
    void deleteAsksByNameThenRemovesTheItemAndRefreshesTheList() {
        Store store = new Store();
        List<String> shown = fx(() -> {
            SavedItemChooser c = store.chooser();
            c.list().getSelectionModel().select("beta");
            c.deleteButton().fire();
            List<String> out = new ArrayList<>(c.list().getItems());
            out.add("selected=" + c.list().getSelectionModel().getSelectedItem());
            return out;
        });
        assertThat(store.prompts).containsExactly("Delete Clustering Config | "
                + "Delete the saved configuration 'beta'? This cannot be undone.");
        assertThat(store.names).containsExactly("alpha", "gamma");
        assertThat(shown).containsExactly("alpha", "gamma", "selected=gamma");
    }

    @Test
    void answeringNoDeletesNothing() {
        Store store = new Store();
        store.answer = false;
        List<String> shown = fx(() -> {
            SavedItemChooser c = store.chooser();
            c.list().getSelectionModel().select("alpha");
            c.deleteButton().fire();
            return new ArrayList<>(c.list().getItems());
        });
        assertThat(store.prompts).hasSize(1);
        assertThat(store.names).containsExactly("alpha", "beta", "gamma");
        assertThat(shown).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void deletingTheLastItemLeavesAnEmptyListWithLoadAndDeleteDisabled() {
        Store store = new Store();
        store.names.retainAll(List.of("alpha"));
        boolean[] state = fx(() -> {
            SavedItemChooser c = store.chooser();
            c.deleteButton().fire();
            Button load = (Button) c.dialog().getDialogPane().lookupButton(
                    c.dialog().getDialogPane().getButtonTypes().get(0));
            return new boolean[] {
                c.list().getItems().isEmpty(), c.deleteButton().isDisabled(), load.isDisabled()
            };
        });
        assertThat(state).containsExactly(true, true, true);
    }

    @Test
    void theChooserKeepsItsOwnTitleAndTheLoadButton() {
        String[] text = fx(() -> {
            SavedItemChooser c = new Store().chooser();
            Button load = (Button) c.dialog().getDialogPane().lookupButton(
                    c.dialog().getDialogPane().getButtonTypes().get(0));
            return new String[] {c.dialog().getTitle(), load.getText(), c.deleteButton().getText()};
        });
        assertThat(text).containsExactly("Load Clustering Config", "Load", "Delete");
    }
}
