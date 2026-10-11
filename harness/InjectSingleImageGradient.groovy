/*
 * Add a measurement with a KNOWN spatial answer to every cell of a project.
 *
 *   qp-script harness/InjectSingleImageGradient.groovy <projectDir> [imageIndex] [name] [seed]
 *
 * "<name>" (default "Nucleus: Probe mean") is i.i.d. Gaussian noise in every
 * image except image [imageIndex] (default 3), where it is the cell's x
 * coordinate (0..1 across the image) plus the same noise. So the ground truth
 * is: structured in exactly ONE image, nothing anywhere else.
 *
 * This is the case the Spatial Autocorrelation tab's consistency count exists
 * for. Pooled over the project the measurement has a clearly positive Moran's I
 * and a negligible q, which a p-value reads as a cohort finding; the per-image
 * values show one image at I ~ 0.5 and the rest at ~0. A staining or
 * illumination gradient on one slide looks exactly like this.
 *
 * Deterministic for a given seed. Re-running overwrites the measurement.
 */
import qupath.lib.projects.ProjectIO
import qupath.lib.images.servers.ImageServer
import java.awt.image.BufferedImage

def projectDir = new File(args[0])
int gradientIndex = args.length > 1 ? Integer.parseInt(args[1]) : 3
String name = args.length > 2 ? args[2] : "Nucleus: Probe mean"
long seed = args.length > 3 ? Long.parseLong(args[3]) : 20261011L

def project = ProjectIO.loadProject(new File(projectDir, "project.qpproj"), BufferedImage)
def rng = new Random(seed)
def entries = project.getImageList()
println "INJECT ${name} into ${entries.size()} image(s); gradient in image index ${gradientIndex}"

entries.eachWithIndex { entry, i ->
    def imageData = entry.readImageData()
    def server = imageData.getServer()
    double width = server.getWidth()
    def cells = imageData.getHierarchy().getDetectionObjects()
    int n = 0
    cells.each { cell ->
        double noise = rng.nextGaussian() * 0.3
        double value = (i == gradientIndex)
                ? cell.getROI().getCentroidX() / width + noise
                : noise
        cell.getMeasurementList().put(name, value)
        n++
    }
    entry.saveImageData(imageData)
    imageData.getServer().close()
    println "IMG ${entry.getImageName()} cells=${n} ${i == gradientIndex ? 'GRADIENT' : 'noise'}"
}
project.syncChanges()
println "INJECT_DONE"
