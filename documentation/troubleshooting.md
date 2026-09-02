# Troubleshooting

- [Common pitfalls](#common-pitfalls)
- [The Python console](#the-python-console)
- [Reporting a bug](#reporting-a-bug)
- [Removed features](#removed-features)

## Common pitfalls

### 1. Using raw (un-normalized) data

**Problem:** Markers with higher absolute intensity dominate the clustering, regardless of biological importance.
**Solution:** Always normalize. Z-score is the safest default.

### 2. Including too many irrelevant measurements

**Problem:** Morphological measurements, DAPI, and low-signal channels add noise.
**Solution:** Select only biologically relevant mean intensity measurements.

### 3. Over-clustering

**Problem:** Too many clusters that are not biologically distinct.
**Symptoms:** Heatmap rows look similar; many clusters have the same marker pattern.
**Solution:** Decrease Leiden resolution, decrease KMeans k, or merge clusters after the fact.

### 4. Under-clustering

**Problem:** Biologically distinct populations are lumped together.
**Symptoms:** Known cell types are not separated; embedding shows sub-structure within clusters.
**Solution:** Increase Leiden resolution, increase KMeans k, or run sub-clustering on specific
clusters (*Manage Clusters* -> select one cluster -> **Sub-cluster...**, which reopens the Run
Clustering dialog scoped to that class and labels the result `<name>.0`, `<name>.1`, ...).

### 5. Ignoring gate positions in phenotyping

**Problem:** Default gates may not match the actual positive/negative boundary for each marker.
**Solution:** Always check histograms. Use auto-thresholding as a starting point, then verify visually.

### 6. Rule order in phenotyping

**Problem:** Cells are classified as the wrong type because a less specific rule matched first.
**Solution:** Place more specific rules (more marker conditions) above more general rules.

### 7. Batch effects in multi-image analysis

**Problem:** Cells cluster by image source rather than biology.
**Solution:** Enable Harmony batch correction, or verify that technical variation is minimal before clustering without it.

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

**Summary and description.** Provide a **Summary** (used as the issue title) and a **description** --
there is a minimum length, because a one-line report is rarely actionable.

**Contact (optional).** To be notified when someone replies to your issue, add a **GitHub username**
and/or **image.sc forum username**. Both fields are optional -- leave them blank to stay anonymous.
The handles accept pasted profile URLs (e.g. `https://github.com/alice` or `https://forum.image.sc/u/alice`),
which are automatically cleaned up. Both handles appear publicly in the issue.

**Artifacts.** Choose what to attach:

| Checkbox | What it sends |
|---|---|
| **Include system info (versions, OS)** | QP-CAT, QuPath, Java and OS versions. Almost always worth sending; a surprising number of reports come down to a version mismatch. |
| **Include QP-CAT operation log** | QP-CAT's own audit trail (see [chapter 16](reproducibility.md#the-audit-log)): which tools ran, with which parameters, in what order. Greyed out with "(none yet)" if you have not run anything. |
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

---

<a name="10-explaining-clusters-with-an-llm-beta"></a>