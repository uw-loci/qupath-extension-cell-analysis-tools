package qupath.ext.qpcat.service;

import groovy.lang.GroovyShell;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Names pasted into generated Groovy cannot break or alter the script (H45). */
class GroovyTextTest {

    private static Object eval(String script) {
        return new GroovyShell().evaluate(script);
    }

    @Test
    void aLiteralEvaluatesBackToExactlyTheName() {
        String[] names = {"plain", "it's", "a\\b", "cost $5 ${x}", "two\nlines\r\n", "tab\there",
                "\"quoted\"", "caf\u00e9", "bell\u0007"};
        for (String n : names) {
            String lit = GroovyText.literal(n);
            assertThat(lit).matches("\\p{ASCII}*");
            assertThat(eval(lit)).as(lit).isEqualTo(n);
        }
        assertThat(GroovyText.literal(null)).isEqualTo("null");
    }

    @Test
    void aCommentStaysOneLine() {
        assertThat(GroovyText.commentSafe("x\nprintln 'pwned'")).isEqualTo("x println 'pwned'");
        assertThat(GroovyText.commentSafe("a\rb\u2028c")).isEqualTo("a b c");
        assertThat(GroovyText.commentSafe(null)).isEmpty();
        // As a comment, nothing after the name runs.
        assertThat(eval("// " + GroovyText.commentSafe("x\nreturn 42") + "\nreturn 1")).isEqualTo(1);
    }
}
