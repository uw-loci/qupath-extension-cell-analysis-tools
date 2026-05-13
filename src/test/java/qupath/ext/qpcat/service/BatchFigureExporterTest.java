package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpcat.model.OutputFormat;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for the file-write path inside
 * {@link BatchFigureExporter}. The full project-scoped happy path
 * requires a {@code QuPathGUI} singleton (not available headless), so
 * here we exercise the static {@link BatchFigureExporter#writeWithFormat}
 * helper which is the load-bearing transcode entry point.
 */
class BatchFigureExporterTest {

    private Path createSamplePng(Path dir, String name) throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 64, 64);
            g.setColor(Color.BLUE);
            g.fillRect(10, 10, 44, 44);
        } finally {
            g.dispose();
        }
        Path file = dir.resolve(name);
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    @Test
    void writePngIsACopy(@TempDir Path dir) throws Exception {
        Path src = createSamplePng(dir, "src.png");
        Path target = dir.resolve("out.png");
        BatchFigureExporter.writeWithFormat(src, target, OutputFormat.PNG);
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.size(target)).isEqualTo(Files.size(src));
    }

    @Test
    void writeTiffTranscodesFromPng(@TempDir Path dir) throws Exception {
        Path src = createSamplePng(dir, "src.png");
        Path target = dir.resolve("out.tif");
        BatchFigureExporter.writeWithFormat(src, target, OutputFormat.TIFF);
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.size(target)).isGreaterThan(0);
        // Re-read it to confirm it's a valid image
        BufferedImage roundtrip = ImageIO.read(target.toFile());
        assertThat(roundtrip).isNotNull();
        assertThat(roundtrip.getWidth()).isEqualTo(64);
        assertThat(roundtrip.getHeight()).isEqualTo(64);
    }
}
