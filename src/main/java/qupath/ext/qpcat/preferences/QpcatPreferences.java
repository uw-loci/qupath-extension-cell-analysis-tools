package qupath.ext.qpcat.preferences;

import javafx.beans.property.*;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Persistent preferences for the QP-CAT extension.
 * All preferences are stored using QuPath's preference system and persist across sessions.
 */
public final class QpcatPreferences {

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

    private static final StringProperty aeNormalization = PathPrefs.createPersistentPreference(
            "qpcat.ae.normalization", "zscore");

    // ==================== Label Sources ====================

    private static final BooleanProperty aeLabelFromLockedAnnotations = PathPrefs.createPersistentPreference(
            "qpcat.ae.labelFromLockedAnnotations", true);

    private static final BooleanProperty aeLabelFromPoints = PathPrefs.createPersistentPreference(
            "qpcat.ae.labelFromPoints", true);

    private static final BooleanProperty aeLabelFromDetections = PathPrefs.createPersistentPreference(
            "qpcat.ae.labelFromDetections", false);

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

    public static String getAeNormalization() { return aeNormalization.get(); }
    public static void setAeNormalization(String v) { aeNormalization.set(v); }

    public static boolean isAeLabelFromLockedAnnotations() { return aeLabelFromLockedAnnotations.get(); }
    public static void setAeLabelFromLockedAnnotations(boolean v) { aeLabelFromLockedAnnotations.set(v); }

    public static boolean isAeLabelFromPoints() { return aeLabelFromPoints.get(); }
    public static void setAeLabelFromPoints(boolean v) { aeLabelFromPoints.set(v); }

    public static boolean isAeLabelFromDetections() { return aeLabelFromDetections.get(); }
    public static void setAeLabelFromDetections(boolean v) { aeLabelFromDetections.set(v); }

    /**
     * Saves all current dialog values to persistent preferences.
     */
    public static void saveFromDialog(int latentDim, int epochs, double learningRate,
                                       int batchSize, double supervisionWeight,
                                       double valSplit, int earlyStopPatience,
                                       boolean classWeights, boolean augmentation,
                                       String inputMode, int tileSize, boolean includeMask,
                                       String normalization,
                                       boolean labelLocked, boolean labelPoints,
                                       boolean labelDetections) {
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
        setAeIncludeMask(includeMask);
        setAeNormalization(normalization);
        setAeLabelFromLockedAnnotations(labelLocked);
        setAeLabelFromPoints(labelPoints);
        setAeLabelFromDetections(labelDetections);
    }
}
