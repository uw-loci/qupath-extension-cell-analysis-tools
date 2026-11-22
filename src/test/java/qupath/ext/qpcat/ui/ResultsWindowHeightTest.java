package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The results window opened 650 px tall, which left the 3D View's class list five
 * visible rows above 12 classes. It now asks for 840 px where the screen allows it,
 * and stays under 900 px as a window on a 1080 px screen with a taskbar.
 */
class ResultsWindowHeightTest {

    @Test
    void aFullHdScreenGetsTheTallerWindow() {
        // 1080 px minus a 40 px Windows taskbar.
        assertThat(ClusteringDialog.resultsSceneHeight(1040)).isEqualTo(840);
        // Scene plus the margin kept for the frame stays under 900.
        assertThat(840 + ClusteringDialog.RESULTS_SCENE_SCREEN_MARGIN).isLessThanOrEqualTo(900);
    }

    @Test
    void aShorterScreenGetsAShorterWindow() {
        // A 768 px laptop screen with a 40 px taskbar: the window must still fit.
        double h = ClusteringDialog.resultsSceneHeight(728);
        assertThat(h).isLessThan(728);
        assertThat(h).isEqualTo(728 - ClusteringDialog.RESULTS_SCENE_SCREEN_MARGIN);
    }

    @Test
    void neverShrinksBelowAUsableHeight() {
        assertThat(ClusteringDialog.resultsSceneHeight(300)).isEqualTo(500);
    }

    @Test
    void tallerThanTheOldDefault() {
        assertThat(ClusteringDialog.RESULTS_SCENE_HEIGHT).isGreaterThan(650);
    }
}
