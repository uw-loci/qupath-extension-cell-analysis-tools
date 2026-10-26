package qupath.ext.outsider;

import qupath.ext.qpcat.mcp.McpServerController;

/** Stands in for a Groovy script: code outside QP-CAT's packages. */
public final class OutsideCaller {

    private OutsideCaller() {}

    public static String readToken() {
        return McpServerController.getInstance().getToken();
    }

    public static void start() throws java.io.IOException {
        McpServerController.getInstance().start();
    }
}
