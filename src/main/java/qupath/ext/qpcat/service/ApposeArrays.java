/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.qpcat.service;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import org.apposed.appose.NDArray;
import qupath.lib.roi.interfaces.ROI;

/**
 * Every conversion between QuPath's image and table types and Appose
 * {@link NDArray}s, under one contract.
 *
 * <h2>COPIED BETWEEN REPOS -- keep the copies identical</h2>
 *
 * <p>Same arrangement as {@link ApposeSupport}: the file is copied into each
 * Appose extension, {@code tools/check_appose_copies.py} keeps the copies
 * together, and {@code claude-reports/design/appose/SHARED_COPIES.md} records
 * why each change was made. Not every extension uses every method; the point
 * is that when one needs a conversion, it already exists and means the same
 * thing everywhere.
 *
 * <h2>The contract</h2>
 * <ul>
 *   <li><b>Axis order is in the method name.</b> {@code HW} is (rows, columns),
 *       {@code HWC} is channels-last, {@code CHW} is channels-first. Every shape
 *       is C order, so the last axis is the fastest-varying, matching numpy's
 *       default and what {@code ndarray()} returns on the Python side.</li>
 *   <li><b>The element type is in the method name</b> ({@code Uint8},
 *       {@code Uint16}, {@code Float32}, {@code Float64}, {@code Int32}) and is
 *       never inferred from the image.</li>
 *   <li><b>Byte order is native</b> on both sides: numpy reads the shared
 *       segment as native order, and the buffer Appose hands out is native
 *       order already. {@link #bytes} makes that explicit and rewinds.</li>
 *   <li><b>Values are raw.</b> No scaling, no normalisation, no gamma: a uint8
 *       value of 200 arrives as 200.0f in a float32 array. Normalise in Python,
 *       where the model's expectations are.</li>
 *   <li><b>The caller owns every array returned</b> and must close it, on
 *       every path. Shared memory does not go away with the Java object.
 *       {@link #closeQuietly} exists for finally blocks.</li>
 *   <li><b>Allocation runs under the extension classloader.</b> Appose finds
 *       its shared-memory implementation through {@code ServiceLoader}, which
 *       reads the thread context classloader, and a tile-rendering or FX thread
 *       does not carry the extension's.</li>
 * </ul>
 */
public final class ApposeArrays {

    private ApposeArrays() {}

    // ==================== Allocation and access ====================

    /**
     * A zeroed C-order array of the given type and shape, allocated under the
     * extension classloader.
     */
    public static NDArray allocate(NDArray.DType dtype, int... shape) {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());
        try {
            return new NDArray(dtype, new NDArray.Shape(NDArray.Shape.Order.C_ORDER, shape));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /** The array's bytes in native order, positioned at the start. */
    public static ByteBuffer bytes(NDArray array) {
        ByteBuffer buffer = array.buffer();
        buffer.order(ByteOrder.nativeOrder());
        buffer.rewind();
        return buffer;
    }

    /** The array's dimensions in C order. */
    public static int[] shape(NDArray array) {
        return array.shape().toIntArray(NDArray.Shape.Order.C_ORDER);
    }

    /**
     * Fails loudly when an array is not the shape the caller is about to read
     * it as. A silently wrong shape reads as plausible garbage.
     */
    public static void expectShape(NDArray array, int... expected) {
        int[] actual = shape(array);
        if (!Arrays.equals(actual, expected)) {
            throw new IllegalArgumentException("Expected an array of shape " + Arrays.toString(expected) + " but got "
                    + Arrays.toString(actual) + " (" + array.dType() + ")");
        }
    }

    /** Closes each non-null array, ignoring failures; for finally blocks. */
    public static void closeQuietly(NDArray... arrays) {
        if (arrays == null) {
            return;
        }
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());
        try {
            for (NDArray array : arrays) {
                if (array != null) {
                    try {
                        array.close();
                    } catch (RuntimeException e) {
                        // nothing more to do
                    }
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    // ==================== Images to arrays ====================

    /**
     * An RGB image as (H, W, 3) uint8, red first, read through
     * {@link BufferedImage#getRGB}, so any colour model is converted to sRGB.
     */
    public static NDArray rgbUint8HWC(BufferedImage img) {
        int h = img.getHeight();
        int w = img.getWidth();
        NDArray array = allocate(NDArray.DType.UINT8, h, w, 3);
        ByteBuffer buf = bytes(array);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = img.getRGB(x, y);
                buf.put((byte) ((pixel >> 16) & 0xFF));
                buf.put((byte) ((pixel >> 8) & 0xFF));
                buf.put((byte) (pixel & 0xFF));
            }
        }
        return array;
    }

    /** Band 0 of an image as (H, W) uint8; for masks and 8-bit grayscale. */
    public static NDArray grayUint8HW(BufferedImage img) {
        int h = img.getHeight();
        int w = img.getWidth();
        NDArray array = allocate(NDArray.DType.UINT8, h, w);
        ByteBuffer buf = bytes(array);
        Raster raster = img.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buf.put((byte) raster.getSample(x, y, 0));
            }
        }
        return array;
    }

    /** Band 0 of an image as (H, W) uint16; for 16-bit grayscale. */
    public static NDArray grayUint16HW(BufferedImage img) {
        int h = img.getHeight();
        int w = img.getWidth();
        NDArray array = allocate(NDArray.DType.UINT16, h, w);
        ShortBuffer buf = bytes(array).asShortBuffer();
        Raster raster = img.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buf.put((short) raster.getSample(x, y, 0));
            }
        }
        return array;
    }

    /**
     * Every band of an image as (H, W, nBands) float32, band i at channel i,
     * values as the raster holds them (8-bit, 16-bit or float).
     */
    public static NDArray allBandsFloat32HWC(BufferedImage img) {
        int nb = img.getRaster().getNumBands();
        int[] bands = new int[nb];
        for (int i = 0; i < nb; i++) {
            bands[i] = i;
        }
        return allocateBandsHWC(img, bands, nb > 1);
    }

    /**
     * The given bands (0-based, in the given order) as float32, channels-last:
     * (H, W, k) for more than one band, (H, W) for exactly one. The two-axis
     * form for a single band is deliberate: libraries that take an optional
     * channel axis (Cellpose among them) treat a trailing axis of length 1 as a
     * channel and change behaviour.
     */
    public static NDArray bandsFloat32HWC(BufferedImage img, int[] bands) {
        return allocateBandsHWC(img, bands, bands.length > 1);
    }

    private static NDArray allocateBandsHWC(BufferedImage img, int[] bands, boolean channelAxis) {
        int h = img.getHeight();
        int w = img.getWidth();
        int k = bands.length;
        NDArray array = channelAxis ? allocate(NDArray.DType.FLOAT32, h, w, k) : allocate(NDArray.DType.FLOAT32, h, w);
        FloatBuffer buf = bytes(array).asFloatBuffer();
        Raster raster = img.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int b = 0; b < k; b++) {
                    buf.put(raster.getSampleFloat(x, y, bands[b]));
                }
            }
        }
        return array;
    }

    /**
     * The given bands (0-based, in the given order) as (k, H, W) float32,
     * channels-first, always three axes even for one band.
     */
    public static NDArray bandsFloat32CHW(BufferedImage img, int[] bands) {
        int h = img.getHeight();
        int w = img.getWidth();
        int k = bands.length;
        NDArray array = allocate(NDArray.DType.FLOAT32, k, h, w);
        FloatBuffer buf = bytes(array).asFloatBuffer();
        Raster raster = img.getRaster();
        for (int b = 0; b < k; b++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    buf.put(raster.getSampleFloat(x, y, bands[b]));
                }
            }
        }
        return array;
    }

    /**
     * A ROI rasterised as an (H, W) uint8 mask over the region starting at
     * ({@code x}, {@code y}) in full-resolution image coordinates: 255 where
     * the pixel centre is inside the ROI, 0 elsewhere.
     */
    public static NDArray roiMaskUint8HW(ROI roi, int x, int y, int w, int h) {
        NDArray array = allocate(NDArray.DType.UINT8, h, w);
        ByteBuffer buf = bytes(array);
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                buf.put(roi.contains(x + col + 0.5, y + row + 0.5) ? (byte) 255 : (byte) 0);
            }
        }
        return array;
    }

    /**
     * Raw uint8 samples, already in the order of {@code shape}, widened to
     * float32 without scaling (200 becomes 200.0f).
     */
    public static NDArray float32FromUint8Bytes(byte[] raw, int... shape) {
        NDArray array = allocate(NDArray.DType.FLOAT32, shape);
        expectElements(array, raw.length);
        FloatBuffer buf = bytes(array).asFloatBuffer();
        for (byte b : raw) {
            buf.put((float) (b & 0xFF));
        }
        return array;
    }

    /**
     * Little-endian float32 samples (QuPath's tile encoders write them this
     * way), already in the order of {@code shape}, copied into a float32 array.
     */
    public static NDArray float32FromLittleEndianBytes(byte[] raw, int... shape) {
        NDArray array = allocate(NDArray.DType.FLOAT32, shape);
        expectElements(array, raw.length / Float.BYTES);
        FloatBuffer src = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        bytes(array).asFloatBuffer().put(src);
        return array;
    }

    private static void expectElements(NDArray array, long provided) {
        long expected = array.shape().numElements();
        if (expected != provided) {
            array.close();
            throw new IllegalArgumentException("Shape " + Arrays.toString(shape(array)) + " holds " + expected
                    + " elements but " + provided + " were provided");
        }
    }

    // ==================== Tables to arrays ====================

    /** A 1-D int32 array, e.g. labels or indices, one element per value. */
    public static NDArray int32Vector(int[] values) {
        NDArray array = allocate(NDArray.DType.INT32, values.length);
        bytes(array).asIntBuffer().put(values);
        return array;
    }

    /**
     * An (nRows, nCols) float32 matrix from row arrays, narrowing each value;
     * the caller decides whether float32 is exact enough.
     */
    public static NDArray float32Matrix(double[][] rows, int nRows, int nCols) {
        NDArray array = allocate(NDArray.DType.FLOAT32, nRows, nCols);
        FloatBuffer buf = bytes(array).asFloatBuffer();
        for (int i = 0; i < nRows; i++) {
            double[] row = rows[i];
            for (int j = 0; j < nCols; j++) {
                buf.put((float) row[j]);
            }
        }
        return array;
    }

    /** An (nRows, nCols) float64 matrix from row arrays. */
    public static NDArray float64Matrix(double[][] rows, int nRows, int nCols) {
        NDArray array = allocate(NDArray.DType.FLOAT64, nRows, nCols);
        DoubleBuffer buf = bytes(array).asDoubleBuffer();
        for (int i = 0; i < nRows; i++) {
            double[] row = rows[i];
            for (int j = 0; j < nCols; j++) {
                buf.put(row[j]);
            }
        }
        return array;
    }

    // ==================== Arrays back to Java ====================

    /**
     * A zeroed (H, W) uint16 label raster for a Python task to fill in place.
     * uint16 holds 65535 labels per tile; a script that writes more wraps
     * silently, so the caller must check the label count the script reports.
     */
    public static NDArray allocateLabelsUint16HW(int h, int w) {
        return allocate(NDArray.DType.UINT16, h, w);
    }

    /**
     * An (H, W) uint16 label raster as a row-major {@code float[]} of length
     * {@code h * w}, suitable for {@code SimpleImages.createFloatImage}; label 0
     * is background.
     */
    public static float[] labelsToFloat(NDArray labels, int h, int w) {
        ShortBuffer buf = bytes(labels).asShortBuffer();
        float[] out = new float[h * w];
        int n = Math.min(out.length, buf.remaining());
        for (int i = 0; i < n; i++) {
            out[i] = buf.get(i) & 0xFFFF;
        }
        return out;
    }

    /** All elements of a uint8 array as bytes, in C order. */
    public static byte[] toBytes(NDArray array) {
        ByteBuffer buf = bytes(array);
        byte[] out = new byte[(int) array.shape().numElements() * array.dType().bytesPerElement()];
        buf.get(out, 0, Math.min(out.length, buf.remaining()));
        return out;
    }

    /**
     * All elements of an integer array as {@code int[]}, in C order. uint8 and
     * uint16 are widened without sign; uint32 is reinterpreted, so values above
     * {@code Integer.MAX_VALUE} come back negative.
     */
    public static int[] toInts(NDArray array) {
        int n = (int) array.shape().numElements();
        int[] out = new int[n];
        ByteBuffer buf = bytes(array);
        switch (array.dType()) {
            case UINT8:
            case INT8:
                for (int i = 0; i < n; i++) {
                    out[i] = array.dType() == NDArray.DType.UINT8 ? buf.get(i) & 0xFF : buf.get(i);
                }
                break;
            case UINT16:
            case INT16: {
                ShortBuffer sb = buf.asShortBuffer();
                for (int i = 0; i < n; i++) {
                    out[i] = array.dType() == NDArray.DType.UINT16 ? sb.get(i) & 0xFFFF : sb.get(i);
                }
                break;
            }
            case INT32:
            case UINT32: {
                IntBuffer ib = buf.asIntBuffer();
                ib.get(out, 0, Math.min(n, ib.remaining()));
                break;
            }
            default:
                throw new IllegalArgumentException("Not an integer array: " + array.dType());
        }
        return out;
    }

    /** All elements of a float32 array, in C order. */
    public static float[] toFloats(NDArray array) {
        if (array.dType() != NDArray.DType.FLOAT32) {
            throw new IllegalArgumentException("Not a float32 array: " + array.dType());
        }
        FloatBuffer buf = bytes(array).asFloatBuffer();
        float[] out = new float[(int) array.shape().numElements()];
        buf.get(out, 0, Math.min(out.length, buf.remaining()));
        return out;
    }

    /** All elements of a float64 array, in C order. */
    public static double[] toDoubles(NDArray array) {
        if (array.dType() != NDArray.DType.FLOAT64) {
            throw new IllegalArgumentException("Not a float64 array: " + array.dType());
        }
        DoubleBuffer buf = bytes(array).asDoubleBuffer();
        double[] out = new double[(int) array.shape().numElements()];
        buf.get(out, 0, Math.min(out.length, buf.remaining()));
        return out;
    }

    /** An (H, W) uint8 array as a {@code TYPE_BYTE_GRAY} image. */
    public static BufferedImage grayUint8ToImage(NDArray array) {
        int[] dims = shape(array);
        if (dims.length != 2 || array.dType() != NDArray.DType.UINT8) {
            throw new IllegalArgumentException(
                    "Expected an (H, W) uint8 array, got " + Arrays.toString(dims) + " " + array.dType());
        }
        int h = dims[0];
        int w = dims[1];
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        byte[] pixels = toBytes(array);
        img.getRaster().setDataElements(0, 0, w, h, pixels);
        return img;
    }
}
