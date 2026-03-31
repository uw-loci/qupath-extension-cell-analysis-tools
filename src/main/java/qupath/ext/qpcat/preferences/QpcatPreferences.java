package qupath.ext.qpcat.preferences;

import javafx.beans.property.*;
import javafx.collections.ObservableList;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Persistent preferences for the QP-CAT extension.
 * All preferences are stored using QuPath's preference system and persist across sessions.
 */
public final class QpcatPreferences {

    // Categories match the menu item names so users can associate preferences with tools
    private static final String CATEGORY_VAE = "QP-CAT: [TEST] Autoencoder Classifier";
    private static final String CATEGORY_CLUSTERING = "QP-CAT: Run Clustering";
    private static final String CATEGORY_PHENOTYPING = "QP-CAT: Run Phenotyping";
    private static final String CATEGORY_FEATURES = "QP-CAT: Extract Foundation Model Features";
    private static final String CATEGORY_ZERO_SHOT = "QP-CAT: Zero-Shot Phenotyping";
    private static final String CATEGORY_GENERAL = "QP-CAT";

    private QpcatPreferences() {}

    // ==================== Autoencoder Training ====================

    private static final IntegerProperty aeLatentDim = PathPrefs.createPersistentPreference(
            "qpcat.ae.latentDim", 16);

    private static final IntegerProperty aeEpochs = PathPrefs.createPersistentPreference(
            "qpcat.ae.epochs", 100);

    private static final DoubleProperty aeLearningRate = PathPrefs.createPersistentPreference(
            "qpcat.ae.learningRate", 0.001);

    private static final IntegerProperty aeBatchSize = PathPrefs.createPersistentPreference(
            "qpcat.ae.batchSize", 128);

    private static final DoubleProperty aeSupervisionWeight = PathPrefs.createPersistentPreference(
            "qpcat.ae.supervisionWeight", 1.0);

    private static final DoubleProperty aeValSplit = PathPrefs.createPersistentPreference(
            "qpcat.ae.valSplit", 0.2);

    private static final IntegerProperty aeEarlyStopPatience = PathPrefs.createPersistentPreference(
            "qpcat.ae.earlyStopPatience", 15);

    private static final BooleanProperty aeClassWeights = PathPrefs.createPersistentPreference(
            "qpcat.ae.classWeights", true);

    private static final BooleanProperty aeAugmentation = PathPrefs.createPersistentPreference(
            "qpcat.ae.augmentation", true);

    private static final StringProperty aeInputMode = PathPrefs.createPersistentPreference(
            "qpcat.ae.inputMode", "measurements");

    private static final IntegerProperty aeTileSize = PathPrefs.createPersistentPreference(
            "qpcat.ae.tileSize", 32);

    private static final BooleanProperty aeIncludeMask = PathPrefs.createPersistentPreference(
            "qpcat.ae.includeMask", true);

    private static final DoubleProperty aeDownsample = PathPrefs.createPersistentPreference(
            "qpcat.ae.downsample", 1.0);

    private static final StringProperty aeNormalization = PathPrefs.createPersistentPreference(
            "qpcat.ae.normalization", "zscore");

    // ==================== Label Sources ====================

    private static final BooleanProperty aeLabelFromLockedAnnotations = PathPrefs.createPersistentPreference(
            "qpcat.ae.labelFromLockedAnnotations", true);

    private static final BooleanProperty aeLabelFromPoints = PathPrefs.createPersistentPreference(
            "qpcat.ae.labelFromPoints", true);

    private static final BooleanProperty aeLabelFromDetections = PathPrefs.createPersistentPreference(
            "qpcat.ae.labelFromDetections", false);

    private static final BooleanProperty aeCellsOnly = PathPrefs.createPersistentPreference(
            "qpcat.ae.cellsOnly", false);

    // ==================== Advanced VAE Training ====================

    private static final DoubleProperty aeKlBetaMax = PathPrefs.createPersistentPreference(
            "qpcat.ae.klBetaMax", 0.5);

    private static final IntegerProperty aeKlCycles = PathPrefs.createPersistentPreference(
            "qpcat.ae.klCycles", 4);

    private static final DoubleProperty aeKlRampFraction = PathPrefs.createPersistentPreference(
            "qpcat.ae.klRampFraction", 0.8);

    private static final DoubleProperty aeFreeBits = PathPrefs.createPersistentPreference(
            "qpcat.ae.freeBits", 0.25);

    private static final DoubleProperty aePretrainFraction = PathPrefs.createPersistentPreference(
            "qpcat.ae.pretrainFraction", 0.1);

    private static final DoubleProperty aeAugNoise = PathPrefs.createPersistentPreference(
            "qpcat.ae.augNoise", 0.02);

    private static final DoubleProperty aeAugScale = PathPrefs.createPersistentPreference(
            "qpcat.ae.augScale", 0.1);

    private static final DoubleProperty aeAugDropout = PathPrefs.createPersistentPreference(
            "qpcat.ae.augDropout", 0.1);

    // Tile-mode augmentation (same pattern as DL pixel classifier)
    private static final BooleanProperty aeAugFlipH = PathPrefs.createPersistentPreference(
            "qpcat.ae.augFlipH", true);

    private static final BooleanProperty aeAugFlipV = PathPrefs.createPersistentPreference(
            "qpcat.ae.augFlipV", true);

    private static final BooleanProperty aeAugRotation90 = PathPrefs.createPersistentPreference(
            "qpcat.ae.augRotation90", true);

    private static final BooleanProperty aeAugElastic = PathPrefs.createPersistentPreference(
            "qpcat.ae.augElastic", false);

    private static final DoubleProperty aeAugElasticAlpha = PathPrefs.createPersistentPreference(
            "qpcat.ae.augElasticAlpha", 120.0);

    private static final StringProperty aeAugIntensityMode = PathPrefs.createPersistentPreference(
            "qpcat.ae.augIntensityMode", "none");

    private static final DoubleProperty aeAugIntensityAmount = PathPrefs.createPersistentPreference(
            "qpcat.ae.augIntensityAmount", 0.2);

    private static final DoubleProperty aeAugGaussNoise = PathPrefs.createPersistentPreference(
            "qpcat.ae.augGaussNoise", 0.05);

    private static final DoubleProperty aeGradClipNorm = PathPrefs.createPersistentPreference(
            "qpcat.ae.gradClipNorm", 1.0);

    private static final DoubleProperty aeLrSchedulerFactor = PathPrefs.createPersistentPreference(
            "qpcat.ae.lrSchedulerFactor", 0.5);

    private static final IntegerProperty aeLrSchedulerPatience = PathPrefs.createPersistentPreference(
            "qpcat.ae.lrSchedulerPatience", 10);

    private static final IntegerProperty aePointMatchDistance = PathPrefs.createPersistentPreference(
            "qpcat.ae.pointMatchDistance", 50);

    private static final IntegerProperty aeTileBatchSize = PathPrefs.createPersistentPreference(
            "qpcat.ae.tileBatchSize", 500);

    // ==================== Run Clustering ====================

    private static final IntegerProperty clusterSpatialKnn = PathPrefs.createPersistentPreference(
            "qpcat.cluster.spatialKnn", 15);

    private static final DoubleProperty clusterTsnePerplexity = PathPrefs.createPersistentPreference(
            "qpcat.cluster.tsnePerplexity", 30.0);

    private static final IntegerProperty clusterHdbscanMinSamples = PathPrefs.createPersistentPreference(
            "qpcat.cluster.hdbscanMinSamples", 5);

    private static final IntegerProperty clusterMiniBatchSize = PathPrefs.createPersistentPreference(
            "qpcat.cluster.miniBatchKmeansBatchSize", 1024);

    private static final IntegerProperty clusterBanksyPcaDims = PathPrefs.createPersistentPreference(
            "qpcat.cluster.banksyPcaDims", 20);

    private static final IntegerProperty clusterPlotDpi = PathPrefs.createPersistentPreference(
            "qpcat.cluster.plotDpi", 150);

    // ==================== Run Phenotyping ====================

    private static final IntegerProperty phenoHistogramBins = PathPrefs.createPersistentPreference(
            "qpcat.pheno.histogramBins", 50);

    private static final IntegerProperty phenoMinValidValues = PathPrefs.createPersistentPreference(
            "qpcat.pheno.minValidValues", 10);

    private static final IntegerProperty phenoGmmMaxIter = PathPrefs.createPersistentPreference(
            "qpcat.pheno.gmmMaxIter", 200);

    private static final DoubleProperty phenoGammaStdMultiplier = PathPrefs.createPersistentPreference(
            "qpcat.pheno.gammaStdMultiplier", 1.0);

    private static final DoubleProperty phenoGateMax = PathPrefs.createPersistentPreference(
            "qpcat.pheno.gateMax", 5.0);

    // ==================== Extract Foundation Model Features ====================

    private static final IntegerProperty fmTileSize = PathPrefs.createPersistentPreference(
            "qpcat.fm.tileSize", 224);

    private static final IntegerProperty fmBatchSize = PathPrefs.createPersistentPreference(
            "qpcat.fm.batchSize", 32);

    // ==================== Zero-Shot Phenotyping ====================

    private static final IntegerProperty zsTileSize = PathPrefs.createPersistentPreference(
            "qpcat.zs.tileSize", 224);

    private static final IntegerProperty zsBatchSize = PathPrefs.createPersistentPreference(
            "qpcat.zs.batchSize", 32);

    private static final DoubleProperty zsMinSimilarity = PathPrefs.createPersistentPreference(
            "qpcat.zs.minSimilarity", 0.1);

    // ==================== General / Services ====================

    private static final IntegerProperty taskMaxRetries = PathPrefs.createPersistentPreference(
            "qpcat.service.taskMaxRetries", 3);

    private static final IntegerProperty taskRetrySleepMs = PathPrefs.createPersistentPreference(
            "qpcat.service.taskRetrySleepMs", 200);

    private static final IntegerProperty shutdownTimeoutMs = PathPrefs.createPersistentPreference(
            "qpcat.service.shutdownTimeoutMs", 5000);

    // ==================== Getters / Setters ====================

    public static int getAeLatentDim() { return aeLatentDim.get(); }
    public static void setAeLatentDim(int v) { aeLatentDim.set(v); }

    public static int getAeEpochs() { return aeEpochs.get(); }
    public static void setAeEpochs(int v) { aeEpochs.set(v); }

    public static double getAeLearningRate() { return aeLearningRate.get(); }
    public static void setAeLearningRate(double v) { aeLearningRate.set(v); }

    public static int getAeBatchSize() { return aeBatchSize.get(); }
    public static void setAeBatchSize(int v) { aeBatchSize.set(v); }

    public static double getAeSupervisionWeight() { return aeSupervisionWeight.get(); }
    public static void setAeSupervisionWeight(double v) { aeSupervisionWeight.set(v); }

    public static double getAeValSplit() { return aeValSplit.get(); }
    public static void setAeValSplit(double v) { aeValSplit.set(v); }

    public static int getAeEarlyStopPatience() { return aeEarlyStopPatience.get(); }
    public static void setAeEarlyStopPatience(int v) { aeEarlyStopPatience.set(v); }

    public static boolean isAeClassWeights() { return aeClassWeights.get(); }
    public static void setAeClassWeights(boolean v) { aeClassWeights.set(v); }

    public static boolean isAeAugmentation() { return aeAugmentation.get(); }
    public static void setAeAugmentation(boolean v) { aeAugmentation.set(v); }

    public static String getAeInputMode() { return aeInputMode.get(); }
    public static void setAeInputMode(String v) { aeInputMode.set(v); }

    public static int getAeTileSize() { return aeTileSize.get(); }
    public static void setAeTileSize(int v) { aeTileSize.set(v); }

    public static boolean isAeIncludeMask() { return aeIncludeMask.get(); }
    public static void setAeIncludeMask(boolean v) { aeIncludeMask.set(v); }

    public static double getAeDownsample() { return aeDownsample.get(); }
    public static void setAeDownsample(double v) { aeDownsample.set(v); }

    public static String getAeNormalization() { return aeNormalization.get(); }
    public static void setAeNormalization(String v) { aeNormalization.set(v); }

    public static boolean isAeLabelFromLockedAnnotations() { return aeLabelFromLockedAnnotations.get(); }
    public static void setAeLabelFromLockedAnnotations(boolean v) { aeLabelFromLockedAnnotations.set(v); }

    public static boolean isAeLabelFromPoints() { return aeLabelFromPoints.get(); }
    public static void setAeLabelFromPoints(boolean v) { aeLabelFromPoints.set(v); }

    public static boolean isAeLabelFromDetections() { return aeLabelFromDetections.get(); }
    public static void setAeLabelFromDetections(boolean v) { aeLabelFromDetections.set(v); }

    public static boolean isAeCellsOnly() { return aeCellsOnly.get(); }
    public static void setAeCellsOnly(boolean v) { aeCellsOnly.set(v); }

    /**
     * Saves all current dialog values to persistent preferences.
     */
    public static void saveFromDialog(int latentDim, int epochs, double learningRate,
                                       int batchSize, double supervisionWeight,
                                       double valSplit, int earlyStopPatience,
                                       boolean classWeights, boolean augmentation,
                                       String inputMode, int tileSize, double downsample,
                                       boolean includeMask, String normalization,
                                       boolean labelLocked, boolean labelPoints,
                                       boolean labelDetections, boolean cellsOnly) {
        setAeLatentDim(latentDim);
        setAeEpochs(epochs);
        setAeLearningRate(learningRate);
        setAeBatchSize(batchSize);
        setAeSupervisionWeight(supervisionWeight);
        setAeValSplit(valSplit);
        setAeEarlyStopPatience(earlyStopPatience);
        setAeClassWeights(classWeights);
        setAeAugmentation(augmentation);
        setAeInputMode(inputMode);
        setAeTileSize(tileSize);
        setAeDownsample(downsample);
        setAeIncludeMask(includeMask);
        setAeNormalization(normalization);
        setAeLabelFromLockedAnnotations(labelLocked);
        setAeLabelFromPoints(labelPoints);
        setAeLabelFromDetections(labelDetections);
        setAeCellsOnly(cellsOnly);
    }

    // ==================== Advanced VAE Getters ====================

    public static double getAeKlBetaMax() { return aeKlBetaMax.get(); }
    public static int getAeKlCycles() { return aeKlCycles.get(); }
    public static double getAeKlRampFraction() { return aeKlRampFraction.get(); }
    public static double getAeFreeBits() { return aeFreeBits.get(); }
    public static double getAePretrainFraction() { return aePretrainFraction.get(); }
    // Measurement-mode augmentation
    public static double getAeAugNoise() { return aeAugNoise.get(); }
    public static void setAeAugNoise(double v) { aeAugNoise.set(v); }
    public static double getAeAugScale() { return aeAugScale.get(); }
    public static void setAeAugScale(double v) { aeAugScale.set(v); }
    public static double getAeAugDropout() { return aeAugDropout.get(); }
    public static void setAeAugDropout(double v) { aeAugDropout.set(v); }

    // Tile-mode augmentation
    public static boolean isAeAugFlipH() { return aeAugFlipH.get(); }
    public static void setAeAugFlipH(boolean v) { aeAugFlipH.set(v); }
    public static boolean isAeAugFlipV() { return aeAugFlipV.get(); }
    public static void setAeAugFlipV(boolean v) { aeAugFlipV.set(v); }
    public static boolean isAeAugRotation90() { return aeAugRotation90.get(); }
    public static void setAeAugRotation90(boolean v) { aeAugRotation90.set(v); }
    public static boolean isAeAugElastic() { return aeAugElastic.get(); }
    public static void setAeAugElastic(boolean v) { aeAugElastic.set(v); }
    public static double getAeAugElasticAlpha() { return aeAugElasticAlpha.get(); }
    public static void setAeAugElasticAlpha(double v) { aeAugElasticAlpha.set(v); }
    public static String getAeAugIntensityMode() { return aeAugIntensityMode.get(); }
    public static void setAeAugIntensityMode(String v) { aeAugIntensityMode.set(v); }
    public static double getAeAugIntensityAmount() { return aeAugIntensityAmount.get(); }
    public static void setAeAugIntensityAmount(double v) { aeAugIntensityAmount.set(v); }
    public static double getAeAugGaussNoise() { return aeAugGaussNoise.get(); }
    public static void setAeAugGaussNoise(double v) { aeAugGaussNoise.set(v); }
    public static double getAeGradClipNorm() { return aeGradClipNorm.get(); }
    public static double getAeLrSchedulerFactor() { return aeLrSchedulerFactor.get(); }
    public static int getAeLrSchedulerPatience() { return aeLrSchedulerPatience.get(); }
    public static int getAePointMatchDistance() { return aePointMatchDistance.get(); }
    public static int getAeTileBatchSize() { return aeTileBatchSize.get(); }

    // Clustering getters
    public static int getClusterSpatialKnn() { return clusterSpatialKnn.get(); }
    public static double getClusterTsnePerplexity() { return clusterTsnePerplexity.get(); }
    public static int getClusterHdbscanMinSamples() { return clusterHdbscanMinSamples.get(); }
    public static int getClusterMiniBatchSize() { return clusterMiniBatchSize.get(); }
    public static int getClusterBanksyPcaDims() { return clusterBanksyPcaDims.get(); }
    public static int getClusterPlotDpi() { return clusterPlotDpi.get(); }

    // Phenotyping getters
    public static int getPhenoHistogramBins() { return phenoHistogramBins.get(); }
    public static int getPhenoMinValidValues() { return phenoMinValidValues.get(); }
    public static int getPhenoGmmMaxIter() { return phenoGmmMaxIter.get(); }
    public static double getPhenoGammaStdMultiplier() { return phenoGammaStdMultiplier.get(); }
    public static double getPhenoGateMax() { return phenoGateMax.get(); }

    // Feature extraction getters/setters
    public static int getFmTileSize() { return fmTileSize.get(); }
    public static void setFmTileSize(int v) { fmTileSize.set(v); }
    public static int getFmBatchSize() { return fmBatchSize.get(); }
    public static void setFmBatchSize(int v) { fmBatchSize.set(v); }

    // Zero-shot getters/setters
    public static int getZsTileSize() { return zsTileSize.get(); }
    public static void setZsTileSize(int v) { zsTileSize.set(v); }
    public static int getZsBatchSize() { return zsBatchSize.get(); }
    public static void setZsBatchSize(int v) { zsBatchSize.set(v); }
    public static double getZsMinSimilarity() { return zsMinSimilarity.get(); }
    public static void setZsMinSimilarity(double v) { zsMinSimilarity.set(v); }

    // Service getters
    public static int getTaskMaxRetries() { return taskMaxRetries.get(); }
    public static int getTaskRetrySleepMs() { return taskRetrySleepMs.get(); }
    public static int getShutdownTimeoutMs() { return shutdownTimeoutMs.get(); }

    // ==================== Preferences Pane ====================

    /**
     * Installs QP-CAT preferences into QuPath's Edit > Preferences dialog.
     */
    public static void installPreferences(QuPathGUI qupath) {
        if (qupath == null) return;

        ObservableList<org.controlsfx.control.PropertySheet.Item> items =
                qupath.getPreferencePane().getPropertySheet().getItems();

        // --- KL Annealing ---
        items.add(new PropertyItemBuilder<>(aeKlBetaMax, Double.class)
                .name("KL Beta Max")
                .category(CATEGORY_VAE)
                .description("Maximum KL divergence weight per annealing cycle (default: 0.5). "
                        + "Controls reconstruction vs regularization balance. "
                        + "Lower = better reconstruction, higher = smoother latent space. Range: 0.1-1.0.")
                .build());

        items.add(new PropertyItemBuilder<>(aeKlCycles, Integer.class)
                .name("KL Annealing Cycles")
                .category(CATEGORY_VAE)
                .description("Number of cyclical KL annealing cycles (default: 4). "
                        + "More cycles = more exploration before regularization. "
                        + "Set to 1 for monotonic annealing. Range: 1-10.")
                .build());

        items.add(new PropertyItemBuilder<>(aeKlRampFraction, Double.class)
                .name("KL Ramp Fraction")
                .category(CATEGORY_VAE)
                .description("Fraction of each cycle spent ramping KL from 0 to beta_max (default: 0.8). "
                        + "The remaining fraction holds at beta_max. Range: 0.5-1.0.")
                .build());

        items.add(new PropertyItemBuilder<>(aeFreeBits, Double.class)
                .name("Free Bits (nats)")
                .category(CATEGORY_VAE)
                .description("Minimum KL per latent dimension to prevent posterior collapse (default: 0.25). "
                        + "Higher = stronger anti-collapse but less smooth latent space. Range: 0.0-1.0.")
                .build());

        items.add(new PropertyItemBuilder<>(aePretrainFraction, Double.class)
                .name("Unsupervised Pre-train Fraction")
                .category(CATEGORY_VAE)
                .description("Fraction of epochs to train without classification loss (default: 0.1). "
                        + "Gives the latent space structure before labels are introduced. Range: 0.0-0.5.")
                .build());

        // Augmentation settings are in the dialog's collapsible section, not here.

        // --- Training ---
        items.add(new PropertyItemBuilder<>(aeGradClipNorm, Double.class)
                .name("Gradient Clip Max Norm")
                .category(CATEGORY_VAE)
                .description("Maximum gradient norm for clipping (default: 1.0). "
                        + "Prevents exploding gradients. Range: 0.5-5.0.")
                .build());

        items.add(new PropertyItemBuilder<>(aeLrSchedulerFactor, Double.class)
                .name("LR Scheduler Reduction Factor")
                .category(CATEGORY_VAE)
                .description("Factor to reduce learning rate on plateau (default: 0.5 = halve). "
                        + "Range: 0.1-0.9.")
                .build());

        items.add(new PropertyItemBuilder<>(aeLrSchedulerPatience, Integer.class)
                .name("LR Scheduler Patience")
                .category(CATEGORY_VAE)
                .description("Epochs without improvement before reducing LR (default: 10). "
                        + "Range: 5-50.")
                .build());

        // --- Operational ---
        items.add(new PropertyItemBuilder<>(aePointMatchDistance, Integer.class)
                .name("Point Annotation Match Distance (px)")
                .category(CATEGORY_VAE)
                .description("Maximum distance in pixels to match a point annotation to the nearest "
                        + "detection (default: 50). Increase for low-resolution images or large cells.")
                .build());

        items.add(new PropertyItemBuilder<>(aeTileBatchSize, Integer.class)
                .name("Tile I/O Batch Size")
                .category(CATEGORY_VAE)
                .description("Number of tiles read/written per batch during tile-mode training (default: 500). "
                        + "Higher values use more memory but fewer I/O operations. Range: 100-2000.")
                .build());

        // --- Run Clustering ---

        items.add(new PropertyItemBuilder<>(clusterSpatialKnn, Integer.class)
                .name("Spatial Smoothing K-NN")
                .category(CATEGORY_CLUSTERING)
                .description("Number of spatial neighbors for graph convolution smoothing (default: 15). "
                        + "Higher = more smoothing across nearby cells. Range: 5-50.")
                .build());

        items.add(new PropertyItemBuilder<>(clusterTsnePerplexity, Double.class)
                .name("t-SNE Perplexity")
                .category(CATEGORY_CLUSTERING)
                .description("Perplexity for t-SNE embedding (default: 30). "
                        + "Controls local vs global structure balance. Range: 5-100.")
                .build());

        items.add(new PropertyItemBuilder<>(clusterHdbscanMinSamples, Integer.class)
                .name("HDBSCAN Min Samples")
                .category(CATEGORY_CLUSTERING)
                .description("HDBSCAN min_samples parameter (default: 5). "
                        + "Lower = more clusters found, higher = denser clusters required. Range: 1-50.")
                .build());

        items.add(new PropertyItemBuilder<>(clusterMiniBatchSize, Integer.class)
                .name("MiniBatch KMeans Batch Size")
                .category(CATEGORY_CLUSTERING)
                .description("Batch size for MiniBatch KMeans algorithm (default: 1024). "
                        + "Larger = more accurate but slower. Range: 256-8192.")
                .build());

        items.add(new PropertyItemBuilder<>(clusterBanksyPcaDims, Integer.class)
                .name("BANKSY PCA Dimensions")
                .category(CATEGORY_CLUSTERING)
                .description("Number of PCA dimensions for BANKSY spatial clustering (default: 20). "
                        + "Higher captures more variance but slower. Range: 5-50.")
                .build());

        items.add(new PropertyItemBuilder<>(clusterPlotDpi, Integer.class)
                .name("Plot DPI")
                .category(CATEGORY_CLUSTERING)
                .description("Resolution for saved clustering plots in DPI (default: 150). "
                        + "Higher = larger files but sharper images. Range: 72-300.")
                .build());

        // --- Run Phenotyping ---

        items.add(new PropertyItemBuilder<>(phenoHistogramBins, Integer.class)
                .name("Histogram Bins")
                .category(CATEGORY_PHENOTYPING)
                .description("Number of bins for marker histograms and threshold computation (default: 50). "
                        + "More bins = finer resolution but noisier for small datasets. Range: 20-200.")
                .build());

        items.add(new PropertyItemBuilder<>(phenoMinValidValues, Integer.class)
                .name("Min Valid Values for Threshold")
                .category(CATEGORY_PHENOTYPING)
                .description("Minimum non-zero values per marker to compute auto-threshold (default: 10). "
                        + "Markers with fewer values are skipped. Range: 2-100.")
                .build());

        items.add(new PropertyItemBuilder<>(phenoGmmMaxIter, Integer.class)
                .name("GMM Max Iterations")
                .category(CATEGORY_PHENOTYPING)
                .description("Maximum iterations for Gaussian Mixture Model threshold fitting (default: 200). "
                        + "Increase if GMM fails to converge. Range: 50-1000.")
                .build());

        items.add(new PropertyItemBuilder<>(phenoGammaStdMultiplier, Double.class)
                .name("Gamma Threshold Std Multiplier")
                .category(CATEGORY_PHENOTYPING)
                .description("Threshold = mode + N*std for gamma distribution method (default: 1.0). "
                        + "Higher = stricter positive threshold. Range: 0.5-3.0.")
                .build());

        items.add(new PropertyItemBuilder<>(phenoGateMax, Double.class)
                .name("Gate Threshold Max")
                .category(CATEGORY_PHENOTYPING)
                .description("Maximum value for per-marker gate threshold spinners (default: 5.0). "
                        + "Increase if your normalized values exceed this range.")
                .build());

        // --- Extract Foundation Model Features ---

        items.add(new PropertyItemBuilder<>(fmTileSize, Integer.class)
                .name("Tile Size")
                .category(CATEGORY_FEATURES)
                .description("Tile size in pixels for foundation model input (default: 224). "
                        + "Most models expect 224. Only change if using a model with different input size.")
                .build());

        items.add(new PropertyItemBuilder<>(fmBatchSize, Integer.class)
                .name("Batch Size")
                .category(CATEGORY_FEATURES)
                .description("Number of tiles per GPU batch for feature extraction (default: 32). "
                        + "Reduce if running out of GPU memory. Range: 1-128.")
                .build());

        // --- Zero-Shot Phenotyping ---

        items.add(new PropertyItemBuilder<>(zsTileSize, Integer.class)
                .name("Tile Size")
                .category(CATEGORY_ZERO_SHOT)
                .description("Tile size in pixels for BiomedCLIP input (default: 224). "
                        + "BiomedCLIP expects 224. Only change for different vision-language models.")
                .build());

        items.add(new PropertyItemBuilder<>(zsBatchSize, Integer.class)
                .name("Batch Size")
                .category(CATEGORY_ZERO_SHOT)
                .description("Number of tiles per GPU batch for zero-shot inference (default: 32). "
                        + "Reduce if running out of GPU memory. Range: 1-128.")
                .build());

        items.add(new PropertyItemBuilder<>(zsMinSimilarity, Double.class)
                .name("Min Similarity Threshold")
                .category(CATEGORY_ZERO_SHOT)
                .description("Minimum cosine similarity for phenotype assignment (default: 0.1). "
                        + "Cells below this threshold are classified as 'Unknown'. Range: 0.0-1.0.")
                .build());

        // --- General ---

        items.add(new PropertyItemBuilder<>(taskMaxRetries, Integer.class)
                .name("Task Max Retries")
                .category(CATEGORY_GENERAL)
                .description("Maximum retry attempts on Appose 'thread death' errors (default: 3). "
                        + "Increase if thread death errors persist. Range: 1-10.")
                .build());

        items.add(new PropertyItemBuilder<>(taskRetrySleepMs, Integer.class)
                .name("Task Retry Sleep (ms)")
                .category(CATEGORY_GENERAL)
                .description("Milliseconds to wait between task retries (default: 200). "
                        + "Increase if retries fail. Range: 100-2000.")
                .build());

        items.add(new PropertyItemBuilder<>(shutdownTimeoutMs, Integer.class)
                .name("Python Shutdown Timeout (ms)")
                .category(CATEGORY_GENERAL)
                .description("Milliseconds to wait for Python service shutdown (default: 5000). "
                        + "Increase if Python tasks take longer to stop gracefully.")
                .build());
    }
}
