package qupath.ext.qpcat.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every in-app "Documentation" link must land on a section that exists.
 * <p>
 * A dead anchor does not fail, throw, or log: GitHub simply serves the top of
 * the page, so the link looks like it worked and the user is left scrolling a
 * 2,200-line guide. That is invisible in review and invisible at runtime, which
 * is exactly the kind of drift a test has to hold. Found for real: the Cellular
 * Neighborhoods dialog pointed at {@code #22-cellular-neighborhoods} while the
 * heading slug was {@code #22-finding-cellular-neighborhoods-spatial-niches}.
 * <p>
 * Anchors resolve against BOTH explicit {@code <a name="...">} tags and the
 * slugs GitHub derives from headings, because the guide uses both.
 */
class DocLinkAnchorsTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir"));
    private static final Path DOCS = REPO.resolve("documentation");

    /** Arguments of a QpcatDocLinks call. Each names its page, so the page is
     *  whichever argument ends in ".md" and the anchor is the one after it. */
    private static final Pattern DOC_LINKS =
            Pattern.compile("QpcatDocLinks\\.(?:page|linkBar|pageUrl)\\(([^)]*)\\)", Pattern.DOTALL);
    /** Page the results dialog appends its per-tab anchors to. */
    private static final String RESULTS_PAGE = "results.md";
    /** Page the algorithm "Learn more" links append their anchors to. */
    private static final String CLUSTERING_PAGE = "clustering.md";
    private static final Pattern WRAP_GUIDE =
            Pattern.compile("wrapWithGuide\\((.*?)\\);", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    @Test
    void everyInAppDocumentationLinkResolves() throws IOException {
        assertThat(DOCS).as("shipped documentation").exists();

        Map<String, String> broken = new TreeMap<>();
        try (Stream<Path> java = Files.walk(REPO.resolve("src/main/java"))) {
            for (Path p : java.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (p.getFileName().toString().equals("QpcatDocLinks.java")) {
                    continue;
                }
                String src = Files.readString(p, StandardCharsets.UTF_8);
                for (Map.Entry<String, String> ref : referencedLinks(src).entrySet()) {
                    String page = ref.getValue();
                    String anchor = ref.getKey();
                    Path target = DOCS.resolve(page);
                    if (!Files.exists(target)) {
                        broken.put(page + "#" + anchor, p.getFileName() + " (page missing)");
                        continue;
                    }
                    Set<String> anchors = anchorsIn(Files.readString(target, StandardCharsets.UTF_8));
                    if (!anchor.isEmpty() && !anchors.contains(anchor)) {
                        broken.put(page + "#" + anchor, p.getFileName().toString());
                    }
                }
            }
        }
        assertThat(broken)
                .as("in-app Documentation links with no matching section (page#anchor -> source "
                        + "file); fix the anchor or add the section")
                .isEmpty();
    }

    /**
     * Cross-references INSIDE the shipped docs must resolve too.
     * <p>
     * Same failure mode, and it accumulates faster: a renamed chapter leaves
     * every {@code ](#old-anchor)} pointing at the top of the page. Found for
     * real: the guide's own contents page still called chapter 10 "(Beta)"
     * after the heading became "[Experimental]", and a "report them" link
     * pointed at a section that had never been written.
     */
    @Test
    void everyInDocCrossReferenceResolves() throws IOException {
        Map<String, String> broken = new TreeMap<>();
        for (Path doc : shippedDocs()) {
            String md = Files.readString(doc, StandardCharsets.UTF_8);
            Set<String> anchors = anchorsIn(md);
            Matcher link = Pattern.compile("\\]\\(#([^)]+)\\)").matcher(md);
            while (link.find()) {
                if (!anchors.contains(link.group(1))) {
                    broken.put(doc.getFileName() + " -> #" + link.group(1), "unresolved");
                }
            }
        }
        assertThat(broken)
                .as("in-document links pointing at a section that does not exist")
                .isEmpty();
    }

    private static List<Path> shippedDocs() throws IOException {
        List<Path> out = new java.util.ArrayList<>();
        try (Stream<Path> docs = Files.list(REPO.resolve("documentation"))) {
            docs.filter(f -> f.toString().endsWith(".md")).forEach(out::add);
        }
        Path readme = REPO.resolve("README.md");
        if (Files.exists(readme)) {
            out.add(readme);
        }
        return out;
    }

    /**
     * Documentation links a source file asks for, as anchor -> page.
     * <p>
     * Two shapes. A {@code QpcatDocLinks} call names its page explicitly, so the
     * page is the argument ending in {@code .md} and the anchor is the next one;
     * that also stops a link's visible TEXT being mistaken for an anchor, which
     * "Auto-thresholding" otherwise is. The results dialog instead concatenates
     * a per-tab anchor onto a base URL, so those anchors are matched by shape and
     * resolved against the page that base points at.
     */
    private static Map<String, String> referencedLinks(String src) {
        Map<String, String> out = new TreeMap<>();
        Matcher m = DOC_LINKS.matcher(src);
        while (m.find()) {
            List<String> args = quoted(m.group(1));
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).endsWith(".md")) {
                    String anchor = (i + 1 < args.size()) ? args.get(i + 1) : "";
                    out.put(anchor, args.get(i));
                    break;
                }
            }
        }
        // Per-tab anchors, appended to a base URL at runtime.
        Matcher tab = Pattern.compile("\"([a-z0-9]+(?:-[a-z0-9]+)+)\"").matcher(src);
        while (tab.find()) {
            String anchor = tab.group(1);
            if (anchor.endsWith("-tab") || anchor.endsWith("-tabs")) {
                out.put(anchor, RESULTS_PAGE);
            } else if (anchor.startsWith("caution-")) {
                out.put(anchor, CLUSTERING_PAGE);
            }
        }
        return out;
    }

    private static void addIfAnchorLike(Set<String> out, String s) {
        if (s != null && !s.isBlank() && !s.contains(" ") && !s.startsWith("http")
                && s.contains("-")) {
            out.add(s);
        }
    }

    private static List<String> quoted(String args) {
        List<String> out = new java.util.ArrayList<>();
        Matcher q = QUOTED.matcher(args);
        while (q.find()) {
            out.add(q.group(1));
        }
        return out;
    }

    /** Explicit {@code <a name>} tags plus GitHub's heading slugs. */
    private static Set<String> anchorsIn(String markdown) {
        Set<String> out = new LinkedHashSet<>();
        Matcher named = Pattern.compile("<a\\s+name=\"([^\"]+)\"").matcher(markdown);
        while (named.find()) {
            out.add(named.group(1));
        }
        Matcher heading = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$", Pattern.MULTILINE)
                .matcher(markdown);
        while (heading.find()) {
            out.add(slug(heading.group(1)));
        }
        return out;
    }

    /** GitHub's heading-to-anchor rule: lower-case, drop punctuation, spaces to hyphens. */
    private static String slug(String heading) {
        // Underscores are KEPT -- GitHub strips punctuation but not '_', so a
        // heading like "Independent areas (`clustering.area_levels`)" slugs to
        // ...clusteringarea_levels. Stripping it here would report a working
        // link as broken, which is how a checker teaches people to ignore it.
        String s = heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 _-]", "")
                .trim()
                .replaceAll("\\s+", "-");
        return s.replaceAll("-+", "-");
    }
}
