package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tested invariant for the LLM Cluster Explainer (Phase 2):
 * <p>
 * The API key MUST be scrubbed from any payload that the
 * {@code OperationLogger} writes to the on-disk audit log. The Python
 * error classifier in {@code run_llm_explainer.py} is the primary
 * scrubber; {@link LlmAuditScrubber} is the Java-side defense in depth.
 * <p>
 * These tests exercise the Java scrubber against shapes that mirror what
 * an Anthropic SDK exception looks like when the provider returns a 401
 * with the request headers echoed back into the error message. If the
 * regex set is weakened in future, the test fails -- preventing a silent
 * regression that would dump bearer tokens to a per-project plaintext
 * log file shared across collaborators.
 */
class LlmKeyRedactionTest {

    private static final String FAKE_KEY =
            "sk-ant-api03-Abc123Def456Ghi789Jkl012Mno345Pqr678Stu901Vwx234Yza567";

    @Test
    void anthropicKeyIsRedactedFromAuthorizationHeader() {
        String payload = "401 Unauthorized: request failed with headers "
                + "Authorization: Bearer " + FAKE_KEY + " sent to /v1/messages";

        String scrubbed = LlmAuditScrubber.scrub(payload);

        assertThat(scrubbed).doesNotContain(FAKE_KEY);
        assertThat(scrubbed).contains("[REDACTED]");
    }

    @Test
    void bareAnthropicKeyInBodyIsRedacted() {
        // Some SDK errors include the body verbatim, where the key may
        // appear in a json field rather than a header.
        String payload = "{\"error\": {\"type\": \"authentication_error\","
                + " \"message\": \"invalid api key " + FAKE_KEY + "\"}}";

        String scrubbed = LlmAuditScrubber.scrub(payload);

        assertThat(scrubbed).doesNotContain(FAKE_KEY);
        assertThat(scrubbed).doesNotContain("sk-ant-");
        assertThat(scrubbed).contains("[REDACTED]");
    }

    @Test
    void xApiKeyHeaderIsRedacted() {
        String payload = "Sent headers: x-api-key: " + FAKE_KEY + ", content-type: json";

        String scrubbed = LlmAuditScrubber.scrub(payload);

        assertThat(scrubbed).doesNotContain(FAKE_KEY);
        assertThat(scrubbed).contains("[REDACTED]");
    }

    @Test
    void genericBearerTokenIsRedacted() {
        String payload = "401 returned -- presented Bearer "
                + "ya29.A0AeXRPp7notARealKeyJustLooksLikeOne_abc-def-ghi";

        String scrubbed = LlmAuditScrubber.scrub(payload);

        assertThat(scrubbed).doesNotContain("ya29.A0AeXRPp7notARealKey");
        assertThat(scrubbed).contains("[REDACTED]");
    }

    @Test
    void scrubIsIdempotent() {
        String payload = "Authorization: Bearer " + FAKE_KEY;
        String once = LlmAuditScrubber.scrub(payload);
        String twice = LlmAuditScrubber.scrub(once);

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void nullInputReturnsEmptyString() {
        assertThat(LlmAuditScrubber.scrub(null)).isEqualTo("");
    }

    @Test
    void cleanPayloadIsUnchanged() {
        String payload = "Cluster 3 -- 5 markers ranked by Wilcoxon score.";

        String scrubbed = LlmAuditScrubber.scrub(payload);

        assertThat(scrubbed).isEqualTo(payload);
    }

    @Test
    void synthetic401WithKeyInPayloadIsScrubbed() {
        // The canonical Phase 2 invariant: a 401 response body that echoes
        // the request's Authorization header end-to-end MUST come out the
        // far side with the key replaced and no `sk-ant-` substring left.
        String synthetic = "HTTP/1.1 401 Unauthorized\n"
                + "Authorization: Bearer " + FAKE_KEY + "\n"
                + "{\"error\": {\"type\": \"authentication_error\", "
                + "\"message\": \"Invalid API key supplied: " + FAKE_KEY + "\"}}";

        String scrubbed = LlmAuditScrubber.scrub(synthetic);

        assertThat(scrubbed).doesNotContain(FAKE_KEY);
        assertThat(scrubbed).doesNotContain("sk-ant-");
        assertThat(scrubbed).contains("[REDACTED]");
    }
}
