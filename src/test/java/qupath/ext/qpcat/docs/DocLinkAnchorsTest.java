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
    private static final Path GUIDE = REPO.resolve("documentation/HOW_TO_GUIDE.md");

    /** Anchor passed to QpcatDocLinks.howToGuide/linkBar, or as wrapWithGuide's docAnchor. */
    private static final Pattern DOC_LINKS =
            Pattern.compile("QpcatDocLinks\\.(?:howToGuide|linkBar)\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern WRAP_GUIDE =
            Pattern.compile("wrapWithGuide\\((.*?)\\);", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    @Test
    void everyInAppDocumentationLinkResolves() throws IOException {
        assertThat(GUIDE).as("shipped guide").exists();
        Set<String> anchors = anchorsIn(Files.readString(GUIDE, StandardCharsets.UTF_8));

        Map<String, String> broken = new TreeMap<>();
        try (Stream<Path> java = Files.walk(REPO.resolve("src/main/java"))) {
            for (Path p : java.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                for (String anchor : referencedAnchors(src)) {
                    if (!anchors.contains(anchor)) {
                        broken.put(anchor, p.getFileName().toString());
                    }
                }
            }
        }
        assertThat(broken)
                .as("in-app Documentation links with no matching section in HOW_TO_GUIDE.md "
                        + "(anchor -> source file); fix the anchor or add the section")
                .isEmpty();
    }

    /**
     * Anchors a source file asks for. Deliberately conservative: only the LAST
     * string argument of wrapWithGuide is an anchor (the earlier ones are guide
     * prose), and only single-token strings are considered, so a link's visible
     * TEXT is never mistaken for an anchor.
     */
    private static Set<String> referencedAnchors(String src) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = DOC_LINKS.matcher(src);
        while (m.find()) {
            // The anchor is always the LAST string argument: howToGuide(anchor),
            // howToGuide(text, anchor) and linkBar(anchor) all put it there. An
            // earlier argument is the link's visible TEXT, and "Auto-thresholding"
            // looks exactly like an anchor if you do not make that distinction.
            List<String> args = quoted(m.group(1));
            if (!args.isEmpty()) {
                addIfAnchorLike(out, args.get(args.size() - 1));
            }
        }
        m = WRAP_GUIDE.matcher(src);
        while (m.find()) {
            List<String> args = quoted(m.group(1));
            if (!args.isEmpty()) {
                addIfAnchorLike(out, args.get(args.size() - 1));
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
        String s = heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 -]", "")
                .trim()
                .replaceAll("\\s+", "-");
        return s.replaceAll("-+", "-");
    }
}
