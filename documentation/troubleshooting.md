# Troubleshooting

- [Common pitfalls](#common-pitfalls)
- [Messages you may see](#messages-you-may-see)
- [The Python console](#the-python-console)
- [Reporting a bug](#reporting-a-bug)
- [Removed features](#removed-features)

## Common pitfalls

### 1. Using raw (un-normalized) data

**Problem:** Markers with higher absolute intensity dominate the clustering, regardless of biological importance.
**Solution:** Normalize before clustering markers; Z-score is the safest default. The
exception is clustering on embedding coordinates (pitfall 5), where **None (raw values)** keeps
the embedding's geometry. For phenotyping gates, use Min-Max or Percentile (see
[Phenotyping](phenotyping.md)).

### 2. Including too many irrelevant measurements

**Problem:** Morphological measurements, DAPI, and low-signal channels add noise.
**Solution:** Select only biologically relevant mean intensity measurements.

### 3. Over-clustering

**Problem:** Too many clusters that are not biologically distinct.
**Symptoms:** Heatmap rows look similar; many clusters have the same marker pattern.
**Solution:** Lower Leiden's **resolution** or KMeans' **n_clusters** (Clustering Algorithm
section; GMM and Agglomerative have the same **n_components** / **n_clusters** spinners), or
merge clusters after the fact.

### 4. Under-clustering

**Problem:** Biologically distinct populations are lumped together.
**Symptoms:** Known cell types are not separated; embedding shows sub-structure within clusters.
**Solution:** Raise Leiden's **resolution** or KMeans' **n_clusters**, or run sub-clustering on specific
clusters (*Results & populations > Modify cell populations (rename, merge, split,
sub-cluster)...* -> select one cluster -> **Sub-cluster...**, which reopens the clustering
dialog scoped to that class and labels the result `<name>.0`, `<name>.1`, ...).

### 5. HDBSCAN returns one giant cluster and almost no noise

**Problem:** A run returns two or three clusters, one of which holds 80% or more of the cells, and
under 5% of cells are noise. This is common when clustering on embedding coordinates, where
the groups are visibly separated on the plot.

**Symptoms:** The quality banner says "One cluster holds N% of the clustered cells" while the
noise count is small. In the viewer the slide is one color.

This is a different failure from the one in [Clustering: HDBSCAN](clustering.md#caution-hdbscan),
where no density gap exists and each population's sparse fringe is written off as noise; that
one leaves **a lot** of noise, spread evenly. Little noise means no boundary was cut *anywhere*.
Raising `min_cluster_size`, the usual first move, makes both worse.

**Solution:** it is the cluster-selection setting, not the data.

1. **Cluster selection -> Leaf.** The default, *Excess of mass*, is scikit-learn's and keeps
   the most *persistent* clusters, which favors large ones: where the lobes of a space differ
   in density it returns their common parent instead of the lobes. *Leaf* takes the leaves of
   the density tree -- scikit-learn's own wording is "the most fine grained and homogeneous
   clusters".
2. **min_samples -> 0 (auto).** Auto follows scikit-learn and uses `min_cluster_size`. A small
   fixed value beside a large `min_cluster_size` measures density over a handful of points
   while demanding clusters of hundreds.
3. **Normalization -> None, if the input is an embedding.** Z-scoring each axis separately
   stretches the embedding along one axis and squashes it along another, and the geometry is
   the only thing an embedding carries.

Measured: a 3D UMAP of 107,282 cells with plainly separated lobes returned 4 clusters, one
holding 97.0% of them, with 0.9% noise, on the defaults. See the full walkthrough in
[Recipes: cluster on a UMAP / t-SNE embedding](recipes.md).

### 6. Ignoring gate positions in phenotyping

**Problem:** Default gates may not match the actual positive/negative boundary for each marker.
**Solution:** Always check histograms. Use auto-thresholding as a starting point, then verify visually.

### 7. Rule order in phenotyping

**Problem:** Cells are classified as the wrong type because a less specific rule matched first.
**Solution:** Place more specific rules (more marker conditions) above more general rules.

### 8. Batch effects in multi-image analysis

**Problem:** Cells cluster by image source rather than biology.
**Symptoms:** In the results, **Composition by image** shows each cluster sitting almost entirely
in one image.
**Solution:** Tick **Batch correction (Harmony) - for multi-image clustering** in the analysis
options (it needs several images or independent areas in scope), or check the
[Composition by image](results.md#composition-by-image) tab to confirm technical variation is
minimal before clustering without it.

## Messages you may see

| Message | Where | Cause | Fix |
|---|---|---|---|
| "The open image has no detections. Run cell detection first." | Clustering dialog | no cells on the open image | run cell detection |
| "No image is open." | Quick clustering presets | the presets use the open image only | open an image, or use the full dialog with a project scope |
| "No mean-intensity measurements found on these detections (...)" | Quick clustering presets | the presets select only "Mean" measurements | use **Find cell populations (clustering)...** and pick measurements |
| "Compute thresholds first before applying." | Phenotyping | **Apply to All Markers** before **Compute Thresholds** | click **Compute Thresholds** |
| "Select an auto-threshold method (Triangle, GMM, or Gamma) in the Histogram & Auto-Thresholding panel, then Apply to All. 'Manual' has no computed value to apply." | Phenotyping | **Method** still on **Manual** | pick a method |
| "Spatial statistics are off because this run is restricted to chosen classifications. ..." | Clustering dialog | a classification subset is ticked | see [Spatial statistics are unavailable while subsetting](clustering.md#spatial-statistics-are-unavailable-while-subsetting) |
| "Could not install harmonypy 2.1.0 for Harmony (it needs internet access the first time). ... pip said: ..." | Clustering run (Harmony version 2.1.0) | the first run that asks for harmonypy 2.1.0 could not download it from PyPI | check the internet connection and run again, or choose **harmonypy 0.2.0** in **Harmony version** |

---
## The Python console

Monitor Python-side output in real time.

1. **Extensions > QP-CAT > Setup & help > Python Console**
2. The console shows timestamped debug messages from the Python environment
3. **Auto-scroll** toggle: keeps the view at the latest output
4. **Clear**: empties the console
5. **Save Log...**: exports the console contents to a text file

Useful for diagnosing errors, monitoring long operations, and seeing detailed Python output.

---
## Reporting a bug

**Menu: Extensions > QP-CAT > Setup & help > "Report a Bug..."**

Files a GitHub issue against
[qupath-extension-cell-analysis-tools](https://github.com/uw-loci/qupath-extension-cell-analysis-tools/issues)
without leaving QuPath, so a report arrives with the context that makes it reproducible
instead of "clustering failed". **Reports are filed anonymously** -- the issue is created by
a shared reporting service, not from your GitHub account, so you will not be notified of
replies unless you add your contact information below. No GitHub account is needed.

**Summary and description.** Provide a **Summary** (used as the issue title, 8 to 80 characters)
and a **description** of at least 20 characters ("Describe the bug (minimum 20 characters):").

**Contact (optional).** To be notified when someone replies to your issue, add a **GitHub username**
and/or **image.sc forum username**. Both fields are optional -- leave them blank to stay anonymous.
The handles accept pasted profile URLs (e.g. `https://github.com/alice` or `https://forum.image.sc/u/alice`),
which are automatically cleaned up. Both handles appear publicly in the issue.

**Artifacts.** Choose what to attach:

| Checkbox | What it sends |
|---|---|
| **Include system info (versions, OS)** | QP-CAT, QuPath, Java and OS versions. Almost always worth sending; a surprising number of reports come down to a version mismatch. |
| **Include QP-CAT operation log** | QP-CAT's own audit trail (see [Reproducibility](reproducibility.md#the-audit-log)): which tools ran, with which parameters, in what order. Grayed out with "(none yet)" if you have not run anything. |
| **Include QuPath log** | QuPath's log as captured in memory this session. QuPath writes no log file by default, so this checkbox is the only way to get a stack trace attached. Shows "(none captured this session)" when there is nothing to send. |
| **Include a screenshot of the QuPath window** | The window as it looks now. |

**What is redacted, and what is not.** Text attachments have your home directory
replaced with `~` so the report does not carry your username. The **screenshot is not
redacted at all** -- it shows whatever is on screen, including open slides. The dialog
says so and gives you a preview before sending. On patient material, close anything
sensitive first, and check the operation log too: it records image *names*.

**Opening the issue.** **"Open the issue in my browser after submitting"** is checked by default;
uncheck it if you would rather not have a browser window open. Either way the success dialog gives
you the issue number and a link you can follow later. Commenting on the issue -- the only way to
follow up if you left the contact fields blank -- does need a GitHub account.

**If the dialog says bug reporting is not set up**, the relay URL was not configured in
this build. File the issue on GitHub directly; the text you have typed is still on
screen to copy. A failed submission likewise leaves your text in place and shows the
error rather than discarding it.

---
## Removed features

Capabilities that shipped in earlier versions but were removed to keep the tool
focused. Whether the code is still in the repository varies -- each entry below
says. Where it has been deleted, treat bringing the capability back as new work
rather than as re-enabling something that already works. If you want one of
these, open an issue or use **Report a Bug** to ask.

### Foundation-model feature extraction (removed in v0.7.0, deleted in 0.11.1)

**"Extract Foundation Model Features..."** extracted morphological embeddings from
pretrained pathology vision foundation models and stored them as per-cell `FM_*`
measurements, which could then be selected in the clustering dialog to cluster cells by
appearance rather than marker expression.

It was unwired from the menu in v0.7.0 and the code has now been deleted. Two reasons, and
the second is the decisive one: it saw effectively no use and pulled a heavy model-download
dependency (`timm`, `huggingface-hub`) that every user installed; and **the Java half was
never run end to end by anyone.** The Python extraction logic had been exercised against a
single ungated model, but the full path -- QuPath detections to tiles, shared-memory
transfer, `FM_*` measurements written back onto cells -- was never executed. It is not a
working feature that was set aside; it is an unvalidated one.

There is therefore nothing here to reinstate. Anyone wanting appearance-based clustering
should treat it as new work rather than as a revival.
