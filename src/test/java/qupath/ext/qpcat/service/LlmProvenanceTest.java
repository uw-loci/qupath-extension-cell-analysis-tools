package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record of who wrote a set of cluster explanations says only what QP-CAT
 * knows. A chatbot label is what the user typed (and nothing when they typed
 * nothing); an MCP label is the agent's own description of itself.
 */
class LlmProvenanceTest {

    @Test
    void aChatbotNobodyNamedIsNotGivenAName() {
        assertThat(LlmExplainerService.describeAuthor("CHATBOT", null))
                .isEqualTo("a chatbot you did not name (reply pasted in)");
        assertThat(LlmExplainerService.describeAuthor("CHATBOT", "  "))
                .isEqualTo("a chatbot you did not name (reply pasted in)");
    }

    @Test
    void aNamedChatbotIsAttributedToTheUser() {
        assertThat(LlmExplainerService.describeAuthor("CHATBOT", "ChatGPT"))
                .isEqualTo("ChatGPT, as you named it (reply pasted in)");
    }

    @Test
    void anAgentLabelIsReportedAsTheAgentsOwn() {
        assertThat(LlmExplainerService.describeAuthor("MCP", "Claude Code"))
                .isEqualTo("an AI agent that called itself \"Claude Code\" (over MCP)");
        assertThat(LlmExplainerService.describeAuthor("MCP", ""))
                .isEqualTo("an AI agent that gave no name (over MCP)");
    }

    @Test
    void apiProvidersShowTheModelIdAndNotTheEnumName() {
        assertThat(LlmExplainerService.describeAuthor("ANTHROPIC", "claude-sonnet-5"))
                .isEqualTo("claude-sonnet-5 (Anthropic API)");
        assertThat(LlmExplainerService.describeAuthor("OLLAMA", "llama3.1:8b"))
                .isEqualTo("llama3.1:8b (Ollama)");
        assertThat(LlmExplainerService.describeAuthor("CHATBOT", "Gemini"))
                .doesNotContain("CHATBOT");
    }
}
