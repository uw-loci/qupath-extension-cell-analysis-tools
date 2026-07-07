package qupath.ext.qpcat.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Resolves image channels from ranked measurement (marker) names.
 *
 * <p>QP-CAT's Marker Rankings report the most cluster-defining measurements per
 * cluster (e.g. "Cell: CD8: Mean"). To turn those into a channel legend we have
 * to map each measurement back to an image channel. There is no single naming
 * convention -- different detection engines emit different measurement schemas
 * ("CD8: Cell: Mean", "Cell: CD8 mean", "Mean_CD8") -- so we anchor on the
 * channel name appearing somewhere in the measurement string rather than
 * assuming any fixed prefix/suffix layout.</p>
 *
 * <p>The match is deliberately forgiving: if the user renamed channels or
 * measurements so that nothing lines up, {@link #matchChannels} simply returns
 * an empty list (no channels shown) rather than throwing.</p>
 */
public final class ChannelMatcher {

    private ChannelMatcher() {}

    /** Channel names shorter than this are ignored to avoid pathological substring hits. */
    private static final int MIN_CHANNEL_NAME_LEN = 2;

    /**
     * Match ranked marker (measurement) names to image channels.
     *
     * <p>For each marker in rank order, the best matching channel is the one
     * whose (case-insensitive) name occurs as a substring of the measurement
     * name; when several match, the longest channel name wins (so "CD31" is
     * preferred over "CD3" for a "CD31" measurement). Channels are returned in
     * marker-rank order, de-duplicated, capped at {@code maxChannels}.</p>
     *
     * @param channelNames     image channel names (name -> color handled by the caller)
     * @param rankedMarkerNames measurement names, most cluster-defining first
     * @param maxChannels      maximum channels to return
     * @return matched channel names in rank order; empty when nothing matches
     */
    public static List<String> matchChannels(List<String> channelNames,
                                             List<String> rankedMarkerNames,
                                             int maxChannels) {
        List<String> out = new ArrayList<>();
        if (channelNames == null || rankedMarkerNames == null || maxChannels <= 0) {
            return out;
        }
        LinkedHashSet<String> chosen = new LinkedHashSet<>();
        for (String marker : rankedMarkerNames) {
            if (marker == null || marker.isBlank()) {
                continue;
            }
            String m = marker.toLowerCase(Locale.ROOT);
            String best = null;
            int bestLen = -1;
            for (String ch : channelNames) {
                if (ch == null) {
                    continue;
                }
                String trimmed = ch.trim();
                if (trimmed.length() < MIN_CHANNEL_NAME_LEN) {
                    continue;
                }
                String c = trimmed.toLowerCase(Locale.ROOT);
                if (m.contains(c) && c.length() > bestLen) {
                    best = ch;
                    bestLen = c.length();
                }
            }
            if (best != null && chosen.add(best) && chosen.size() >= maxChannels) {
                break;
            }
        }
        out.addAll(chosen);
        return out;
    }
}
