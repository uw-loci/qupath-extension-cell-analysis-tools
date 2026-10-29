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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.apposed.appose.Builder;
import org.apposed.appose.Environment;
import org.junit.jupiter.api.Test;

/**
 * COPIED BETWEEN REPOS together with {@link ApposeGpu}; see
 * {@code tools/check_appose_copies.py}.
 */
class ApposeGpuTest {

    private static final String TWO_CARDS = String.join(
            "\n",
            "0, GPU-aaaa, NVIDIA T400, 7.5, 2048, 1500",
            "1, GPU-bbbb, NVIDIA GeForce RTX 5090, 12.0, 32607, 31800");

    @Test
    void parsesNvidiaSmiCsvAndSkipsJunk() {
        List<ApposeGpu.Gpu> g = ApposeGpu.parse(TWO_CARDS + "\n[Not Supported]\n\ngarbage line\n", true);
        assertEquals(2, g.size());
        assertEquals("GPU-bbbb", g.get(1).uuid());
        assertEquals(120, g.get(1).capabilityValue());
        assertEquals(32607, g.get(1).memoryMiB());
        assertEquals(31800, g.get(1).freeMiB());
        List<ApposeGpu.Gpu> old = ApposeGpu.parse("0, GPU-cccc, Quadro K2200, 4096, [N/A]", false);
        assertEquals(1, old.size());
        assertEquals(-1, old.get(0).capabilityValue());
        assertEquals(-1, old.get(0).freeMiB());
    }

    @Test
    void autoPicksTheComputeCardNotTheDisplayCard() {
        var gpus = ApposeGpu.parse(TWO_CARDS, true);
        assertEquals(
                "GPU-bbbb", ApposeGpu.select(gpus, "auto", null).orElseThrow().uuid());
        assertEquals("GPU-bbbb", ApposeGpu.select(gpus, "", "").orElseThrow().uuid());
    }

    @Test
    void anExplicitIndexOrUuidWins() {
        var gpus = ApposeGpu.parse(TWO_CARDS, true);
        assertEquals("GPU-aaaa", ApposeGpu.select(gpus, "0", null).orElseThrow().uuid());
        assertEquals(
                "GPU-aaaa",
                ApposeGpu.select(gpus, "gpu-AAAA", null).orElseThrow().uuid());
        // explicit beats a scheduler's mask
        assertEquals("GPU-aaaa", ApposeGpu.select(gpus, "0", "1").orElseThrow().uuid());
        // an unknown preference falls back to automatic
        assertEquals("GPU-bbbb", ApposeGpu.select(gpus, "7", null).orElseThrow().uuid());
    }

    @Test
    void aSchedulerAllocationIsKeptOnAuto() {
        var gpus = ApposeGpu.parse(TWO_CARDS, true);
        assertTrue(ApposeGpu.select(gpus, "auto", "0").isEmpty());
        assertTrue(ApposeGpu.select(List.of(), "auto", null).isEmpty());
    }

    @Test
    void tiesBreakOnCapabilityThenIndex() {
        // free memory differing by a few MiB is a tie
        var gpus = ApposeGpu.parse(
                "0, GPU-a, A, 8.6, 24576, 24000\n1, GPU-b, B, 8.9, 24576, 23990\n2, GPU-c, C, 8.9, 24576, 24010", true);
        assertEquals("GPU-b", ApposeGpu.select(gpus, "auto", null).orElseThrow().uuid());
    }

    @Test
    void onAServerWithIdenticalCardsAutoPicksTheLeastLoaded() {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            int free = i == 5 ? 79000 : i == 2 ? 60000 : 9000; // card 0 is the busy one
            csv.append(i)
                    .append(", GPU-")
                    .append(i)
                    .append(", NVIDIA A100, 8.0, 81920, ")
                    .append(free)
                    .append('\n');
        }
        var gpus = ApposeGpu.parse(csv.toString(), true);
        assertEquals("GPU-5", ApposeGpu.select(gpus, "auto", null).orElseThrow().uuid());
        // all idle: lowest index, stable from run to run
        var idle = ApposeGpu.parse("0, GPU-0, A, 8.0, 81920, 81000\n1, GPU-1, A, 8.0, 81920, 81003", true);
        assertEquals("GPU-0", ApposeGpu.select(idle, "auto", null).orElseThrow().uuid());
    }

    @Test
    void unknownFreeMemoryRanksByTotal() {
        var gpus = ApposeGpu.parse("0, GPU-a, A, 8.6, 8192, [N/A]\n1, GPU-b, B, 8.6, 24576, [N/A]", true);
        assertEquals("GPU-b", ApposeGpu.select(gpus, "auto", null).orElseThrow().uuid());
    }

    @Test
    void pinningUsesUuidAndPciOrderAndKeepsTheEnvironmentsOwnVariables() {
        var gpu = ApposeGpu.parse(TWO_CARDS, true).get(1);
        Map<String, String> vars = ApposeGpu.pinVariables(gpu);
        assertEquals("PCI_BUS_ID", vars.get("CUDA_DEVICE_ORDER"));
        assertEquals("GPU-bbbb", vars.get("CUDA_VISIBLE_DEVICES"));

        Environment base = new Environment() {
            public String base() {
                return "/env";
            }

            public List<String> binPaths() {
                return List.of("/env/bin");
            }

            public List<String> launchArgs() {
                return List.of();
            }

            public Map<String, String> envVars() {
                return Map.of("PIXI_X", "1", "CUDA_VISIBLE_DEVICES", "0");
            }

            public Builder<?> builder() {
                return null;
            }
        };
        Environment pinned = new ApposeGpu.PinnedEnvironment(base, vars);
        assertEquals("/env", pinned.base());
        assertEquals("1", pinned.envVars().get("PIXI_X"));
        assertEquals("GPU-bbbb", pinned.envVars().get("CUDA_VISIBLE_DEVICES"));
    }

    private static final java.nio.file.Path PREFIX =
            java.nio.file.Path.of("appose", "env", ".pixi", "envs", "default");

    /** QuPath launched inside Apptainer by a script that sets up DJL from another PyTorch env. */
    @Test
    void anotherEnvironmentsLibrariesAreDroppedAndTheDriverIsKept() {
        String inherited = String.join(":",
                "/home/u/miniconda3/envs/qupath-pytorch/lib/python3.13/site-packages/nvidia/cudnn/lib",
                "/home/u/miniconda3/envs/qupath-pytorch/lib/python3.13/site-packages/torch/lib",
                "/.singularity.d/libs");
        Map<String, String> vars = ApposeGpu.isolationVariables(
                Map.of("LD_LIBRARY_PATH", inherited, "PYTHONPATH", "/home/u/other"), PREFIX, true,
                dir -> dir.equals("/.singularity.d/libs"));
        assertEquals(PREFIX.resolve("lib") + ":/.singularity.d/libs", vars.get("LD_LIBRARY_PATH"));
        assertEquals("", vars.get("PYTHONPATH"));
        assertEquals("1", vars.get("PYTHONNOUSERSITE"));
        assertTrue(!vars.containsKey("PYTHONHOME"), "only emptied when QuPath had it");
    }

    @Test
    void withNothingInheritedTheEnvironmentsLibComesFirst() {
        Map<String, String> vars = ApposeGpu.isolationVariables(Map.of(), PREFIX, true, dir -> false);
        assertEquals(PREFIX.resolve("lib").toString(), vars.get("LD_LIBRARY_PATH"));
    }

    @Test
    void libraryPathIsLeftAloneOffLinuxOrWithoutAPrefix() {
        Map<String, String> vars = ApposeGpu.isolationVariables(
                Map.of("LD_LIBRARY_PATH", "/x"), PREFIX, false, dir -> false);
        assertTrue(!vars.containsKey("LD_LIBRARY_PATH"));
        assertEquals("1", vars.get("PYTHONNOUSERSITE"));
        assertTrue(!ApposeGpu.isolationVariables(Map.of("LD_LIBRARY_PATH", "/x"), null, true, dir -> false)
                .containsKey("LD_LIBRARY_PATH"));
    }

    @Test
    void thePrefixIsTheParentOfTheBinFolder() {
        Environment env = new Environment() {
            public String base() {
                return "/env";
            }

            public List<String> binPaths() {
                return List.of(PREFIX.resolve("bin").toString());
            }

            public List<String> launchArgs() {
                return List.of();
            }

            public Map<String, String> envVars() {
                return Map.of();
            }

            public Builder<?> builder() {
                return null;
            }
        };
        assertEquals(PREFIX, ApposeGpu.envPrefix(env));
    }
}
