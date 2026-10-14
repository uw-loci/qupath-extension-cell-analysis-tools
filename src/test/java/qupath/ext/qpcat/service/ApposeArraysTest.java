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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.apposed.appose.NDArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * Pins the {@link ApposeArrays} contract: axis order, element type, raw
 * values, and read-back. Allocates real shared memory through Appose.
 *
 * <p>COPIED BETWEEN REPOS together with {@link ApposeArrays}; see
 * {@code tools/check_appose_copies.py}.
 */
class ApposeArraysTest {

    private final List<NDArray> opened = new ArrayList<>();

    private NDArray track(NDArray array) {
        opened.add(array);
        return array;
    }

    @AfterEach
    void closeAll() {
        ApposeArrays.closeQuietly(opened.toArray(new NDArray[0]));
        opened.clear();
    }

    /** A 3 x 2 RGB image whose pixel (x, y) is (10x, 20y, 7). */
    private static BufferedImage rgb() {
        BufferedImage img = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                img.setRGB(x, y, ((10 * x) << 16) | ((20 * y) << 8) | 7);
            }
        }
        return img;
    }

    @Test
    void rgbIsChannelsLastRedFirst() {
        NDArray a = track(ApposeArrays.rgbUint8HWC(rgb()));
        assertArrayEquals(new int[] {2, 3, 3}, ApposeArrays.shape(a));
        assertEquals(NDArray.DType.UINT8, a.dType());
        byte[] b = ApposeArrays.toBytes(a);
        // pixel (x=2, y=1) sits at row 1, column 2: (20, 20, 7)
        int i = (1 * 3 + 2) * 3;
        assertEquals(20, b[i] & 0xFF);
        assertEquals(20, b[i + 1] & 0xFF);
        assertEquals(7, b[i + 2] & 0xFF);
        assertEquals(0, b[0] & 0xFF);
    }

    @Test
    void grayUint8AndUint16KeepRawValues() {
        BufferedImage g8 = new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_GRAY);
        g8.getRaster().setSample(1, 0, 0, 200);
        NDArray a8 = track(ApposeArrays.grayUint8HW(g8));
        assertArrayEquals(new int[] {2, 2}, ApposeArrays.shape(a8));
        assertArrayEquals(new int[] {0, 200, 0, 0}, ApposeArrays.toInts(a8));

        BufferedImage g16 = new BufferedImage(2, 2, BufferedImage.TYPE_USHORT_GRAY);
        g16.getRaster().setSample(0, 1, 0, 40000);
        NDArray a16 = track(ApposeArrays.grayUint16HW(g16));
        assertEquals(NDArray.DType.UINT16, a16.dType());
        assertArrayEquals(new int[] {0, 0, 40000, 0}, ApposeArrays.toInts(a16));
    }

    @Test
    void bandSelectionOrdersChannelsLastOrFirst() {
        BufferedImage img = rgb();
        // bands {2, 0}: blue then red
        NDArray hwc = track(ApposeArrays.bandsFloat32HWC(img, new int[] {2, 0}));
        assertArrayEquals(new int[] {2, 3, 2}, ApposeArrays.shape(hwc));
        float[] f = ApposeArrays.toFloats(hwc);
        int px = (1 * 3 + 2) * 2; // (x=2, y=1)
        assertEquals(7f, f[px]);
        assertEquals(20f, f[px + 1]);

        NDArray chw = track(ApposeArrays.bandsFloat32CHW(img, new int[] {2, 0}));
        assertArrayEquals(new int[] {2, 2, 3}, ApposeArrays.shape(chw));
        float[] c = ApposeArrays.toFloats(chw);
        assertEquals(7f, c[0 * 6 + 1 * 3 + 2]); // blue plane, (y=1, x=2)
        assertEquals(20f, c[1 * 6 + 1 * 3 + 2]); // red plane

        NDArray single = track(ApposeArrays.bandsFloat32HWC(img, new int[] {1}));
        assertArrayEquals(new int[] {2, 3}, ApposeArrays.shape(single));
        assertEquals(20f, ApposeArrays.toFloats(single)[3]); // (y=1, x=0): green = 20

        NDArray all = track(ApposeArrays.allBandsFloat32HWC(img));
        assertArrayEquals(new int[] {2, 3, 3}, ApposeArrays.shape(all));
    }

    @Test
    void roiMaskIs255InsideAtPixelCentres() {
        var roi = ROIs.createRectangleROI(10, 10, 2, 1, ImagePlane.getDefaultPlane());
        NDArray m = track(ApposeArrays.roiMaskUint8HW(roi, 9, 9, 4, 3));
        assertArrayEquals(new int[] {3, 4}, ApposeArrays.shape(m));
        int[] v = ApposeArrays.toInts(m);
        assertArrayEquals(new int[] {0, 0, 0, 0, 0, 255, 255, 0, 0, 0, 0, 0}, v);
        BufferedImage back = ApposeArrays.grayUint8ToImage(m);
        assertEquals(255, back.getRaster().getSample(1, 1, 0));
        assertEquals(0, back.getRaster().getSample(3, 1, 0));
    }

    @Test
    void rawBytesWidenWithoutScaling() {
        NDArray f = track(ApposeArrays.float32FromUint8Bytes(new byte[] {0, (byte) 255, (byte) 128, 1}, 2, 2));
        assertArrayEquals(new float[] {0f, 255f, 128f, 1f}, ApposeArrays.toFloats(f));

        ByteBuffer le = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        le.putFloat(1.5f).putFloat(-2f);
        NDArray g = track(ApposeArrays.float32FromLittleEndianBytes(le.array(), 2));
        assertArrayEquals(new float[] {1.5f, -2f}, ApposeArrays.toFloats(g));

        assertThrows(IllegalArgumentException.class, () -> ApposeArrays.float32FromUint8Bytes(new byte[3], 2, 2));
    }

    @Test
    void tablesRoundTrip() {
        double[][] rows = {{1.5, 2.0}, {3.0, 4.25}, {5.0, 6.0}};
        NDArray d = track(ApposeArrays.float64Matrix(rows, 3, 2));
        assertArrayEquals(new int[] {3, 2}, ApposeArrays.shape(d));
        assertArrayEquals(new double[] {1.5, 2.0, 3.0, 4.25, 5.0, 6.0}, ApposeArrays.toDoubles(d));

        NDArray f = track(ApposeArrays.float32Matrix(rows, 2, 2));
        assertArrayEquals(new float[] {1.5f, 2f, 3f, 4.25f}, ApposeArrays.toFloats(f));

        NDArray i = track(ApposeArrays.int32Vector(new int[] {7, -1, 300000}));
        assertArrayEquals(new int[] {7, -1, 300000}, ApposeArrays.toInts(i));
    }

    @Test
    void labelRasterReadsBackUnsigned() {
        NDArray labels = track(ApposeArrays.allocateLabelsUint16HW(2, 3));
        assertEquals(NDArray.DType.UINT16, labels.dType());
        ApposeArrays.bytes(labels).asShortBuffer().put(new short[] {0, 1, (short) 65535, 2, 0, 0});
        assertArrayEquals(new float[] {0f, 1f, 65535f, 2f, 0f, 0f}, ApposeArrays.labelsToFloat(labels, 2, 3));
    }

    @Test
    void shapeChecksAndTypeChecksFailLoudly() {
        NDArray a = track(ApposeArrays.allocate(NDArray.DType.UINT8, 2, 3));
        ApposeArrays.expectShape(a, 2, 3);
        assertThrows(IllegalArgumentException.class, () -> ApposeArrays.expectShape(a, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> ApposeArrays.toFloats(a));
        assertThrows(IllegalArgumentException.class, () -> ApposeArrays.toDoubles(a));
        ApposeArrays.closeQuietly((NDArray) null);
        ApposeArrays.closeQuietly((NDArray[]) null);
    }
}
