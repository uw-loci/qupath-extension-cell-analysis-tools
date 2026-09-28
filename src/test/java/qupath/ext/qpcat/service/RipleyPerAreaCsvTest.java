package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.RipleyResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run partitioned into areas has no single pooled curve, so its CSV has to
 * carry the area as a column rather than leaving the reader to guess which
 * image a row came from.
 */
class RipleyPerAreaCsvTest {

    private static RipleyResult curves(String cluster, double l0, double l1) {
        RipleyResult r = new RipleyResult();
        r.setRadii(new double[] {10, 20});
        r.setClusterNames(List.of(cluster));
        r.setLValues(new double[][] {{l0, l1}});
        r.setKUnavailable(true);
        // Each area carries its OWN null, because the null depends on that area's
        // geometry. A shared one would be the pooled reference this avoids.
        r.setPoissonL(new double[] {0.0, 0.0});
        return r;
    }

    @Test
    void everyAreaAppearsWithItsOwnRows() {
        Map<String, RipleyResult> byArea = new LinkedHashMap<>();
        byArea.put("tme_00.tiff", curves("0", 1.0, 2.0));
        byArea.put("tme_01.tiff", curves("0", 3.0, 4.0));

        String csv = SpatialStatsCsv.ripleyCsvPerArea(byArea, null);
        String[] lines = csv.strip().split("\n");

        assertEquals("area,cluster,radius,k,l", lines[0]);
        assertTrue(csv.contains("tme_00.tiff,0,10"), csv);
        assertTrue(csv.contains("tme_01.tiff,0,10"), csv);
        // Two areas x (one cluster x two radii + that area's two Poisson rows).
        assertEquals(8, lines.length - 1, csv);
        assertEquals(2, csv.lines().filter(l -> l.startsWith("tme_00.tiff,Poisson null")).count());
        assertEquals(2, csv.lines().filter(l -> l.startsWith("tme_01.tiff,Poisson null")).count());
    }

    @Test
    void areaNamesWithCommasAreQuoted() {
        Map<String, RipleyResult> byArea = new LinkedHashMap<>();
        byArea.put("slide, section 2", curves("0", 1.0, 2.0));
        String csv = SpatialStatsCsv.ripleyCsvPerArea(byArea, null);
        assertTrue(csv.contains("\"slide, section 2\""), csv);
    }

    @Test
    void aNullMapIsJustAHeader() {
        assertEquals("area,cluster,radius,k,l", SpatialStatsCsv.ripleyCsvPerArea(null, null).strip());
    }
}
