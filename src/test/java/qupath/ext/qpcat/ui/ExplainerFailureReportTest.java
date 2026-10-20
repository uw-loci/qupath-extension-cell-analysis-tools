package qupath.ext.qpcat.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * A failed explainer run is written to the log at ERROR, not only to the tab's status line,
 * so a bug report or a log review finds it -- "Failed: 401" used to appear on screen only.
 */
class ExplainerFailureReportTest {

    @Test
    void aClassifiedFailureIsLoggedAtErrorWithTheKeyScrubbed() {
        Logger log = mock(Logger.class);
        String banner = ClusterExplainerPanel.reportFailure(log, "AUTH_INVALID",
                "Anthropic auth rejected: x-api-key: sk-ant-abc123secret");
        assertThat(banner).isEqualTo("Failed: 401 -- API key was rejected.");
        verify(log).error(anyString(), eq(banner), eq("AUTH_INVALID"),
                eq("Anthropic auth rejected: x-api-key: [REDACTED]"));
    }

    @Test
    void aCutOffReplyNamesTheLimitAndTheWayRound() {
        Logger log = mock(Logger.class);
        String banner = ClusterExplainerPanel.reportFailure(log, "RESPONSE_TRUNCATED",
                "Anthropic stopped the reply early (stop_reason max_tokens, 16000 output tokens).");
        assertThat(banner).contains("cut off").contains("Selected:");
        verify(log).error(anyString(), eq(banner), eq("RESPONSE_TRUNCATED"), any());
    }

    @Test
    void aReplyWithNoUsableRowsKeepsTheDocumentedText() {
        // documentation/llm-explainer.md quotes this line for a reply in the wrong shape.
        assertThat(ClusterExplainerPanel.reportFailure(mock(Logger.class), "MALFORMED_RESPONSE", "x"))
                .isEqualTo("Failed: provider returned an unexpected response shape.");
    }

    @Test
    void aCancelIsNotAnError() {
        Logger log = mock(Logger.class);
        ClusterExplainerPanel.reportFailure(log, "REQUEST_CANCELLED", "Cancelled by user.");
        verify(log).info(anyString(), eq("Cancelled. No results applied."));
        verifyNoMoreInteractions(log);
    }
}
