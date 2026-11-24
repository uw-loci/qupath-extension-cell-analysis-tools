package qupath.ext.qpcat.scripting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Replay scripts quote result names safely, and YAML text is never taken for a path (H45). */
class GeneratedScriptQuotingTest {

    @Test
    void theReplayScriptNamesTheResultInASingleQuotedLiteral() {
        String script = SpatialConnectionsScripts.buildPushWorkflowScript("run $1 \"a\"\nb\\c");
        assertThat(script).contains("    'run $1 \"a\"\\nb\\\\c'\n");
        // No GString: '$' must not interpolate when the step is replayed.
        assertThat(script).doesNotContain("\"run");
    }

    @Test
    void anyStringWithALineBreakIsYamlText() {
        assertThat(YamlBatchScripts.isInlineYaml("# my batch\nversion: '1.0'\n")).isTrue();
        assertThat(YamlBatchScripts.isInlineYaml("scope: {}\r\n")).isTrue();
        assertThat(YamlBatchScripts.isInlineYaml("  version: '1.0'")).isTrue();
        assertThat(YamlBatchScripts.isInlineYaml("/data/batch.yaml")).isFalse();
        assertThat(YamlBatchScripts.isInlineYaml("C:\\batch\\run.yaml")).isFalse();
    }
}
