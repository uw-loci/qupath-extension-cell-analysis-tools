package qupath.ext.qpcat.service;

import java.util.regex.Pattern;

/**
 * Defense-in-depth API-key redaction for LLM audit-log payloads.
 * <p>
 * The Python error-classifier in {@code run_llm_explainer.py} is the
 * primary scrubber and the only one that sees raw provider exceptions.
 * This Java helper re-applies the same regex set before the
 * {@link OperationLogger} writes the audit-log entry to disk -- the
 * tested invariant (see {@code LlmKeyRedactionTest}) is "no Anthropic key,
 * bearer token, or X-Api-Key value ever lands in the on-disk log."
 * <p>
 * Idempotent: re-running {@link #scrub(String)} on its own output is a
 * no-op. Mirrors the Python regex set in {@code run_llm_explainer.py}.
 */
public final class LlmAuditScrubber {

    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)(authorization\\s*:?\\s*)(bearer\\s+)?[A-Za-z0-9_\\-./=+]+");

    private static final Pattern X_API_KEY = Pattern.compile(
            "(?i)(x-api-key\\s*:?\\s*)[A-Za-z0-9_\\-./=+]+");

    private static final Pattern GENERIC_BEARER = Pattern.compile(
            "(?i)\\b(bearer\\s+)[A-Za-z0-9_\\-./=+]{8,}");

    private static final Pattern ANTHROPIC_KEY = Pattern.compile(
            "sk-ant-[A-Za-z0-9_\\-]+");

    private LlmAuditScrubber() {}

    /**
     * Redact API-key-shaped substrings and Authorization headers from a
     * single string. Returns the empty string when {@code input} is null.
     */
    public static String scrub(String input) {
        if (input == null) return "";
        String s = input;
        s = AUTH_HEADER.matcher(s).replaceAll("$1[REDACTED]");
        s = X_API_KEY.matcher(s).replaceAll("$1[REDACTED]");
        s = GENERIC_BEARER.matcher(s).replaceAll("$1[REDACTED]");
        s = ANTHROPIC_KEY.matcher(s).replaceAll("[REDACTED]");
        return s;
    }
}
