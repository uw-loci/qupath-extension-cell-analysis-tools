package qupath.ext.qpcat.mcp;

import com.google.gson.JsonParser;
import javafx.beans.property.SimpleObjectProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle of the singleton controller against a project-valued property:
 * no project -> refused; start -> token + URL + serving; project change -> stopped;
 * stop clears the credential.
 */
class McpServerControllerTest {

    @TempDir
    Path dir;

    private final McpServerController controller = McpServerController.getInstance();

    @AfterEach
    void stop() {
        controller.stop("test teardown");
    }

    @Test
    void startRefusesWithoutAProjectAndLeavesNothingRunning() {
        controller.bindToProjectProperty(new SimpleObjectProperty<Project<?>>(null));
        assertThatThrownBy(controller::start)
                .isInstanceOf(IOException.class)
                .hasMessage(McpServerController.NO_PROJECT_TEXT);
        assertThat(controller.isRunning()).isFalse();
        assertThat(controller.getToken()).isNull();
        assertThat(controller.getUrl()).isNull();
    }

    /** True when nothing on this machine holds the loopback port. */
    private static boolean portFree(int port) {
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
            probe.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException inUse) {
            return false;
        }
    }

    private static boolean anyPortFree() {
        for (int p = McpServerController.FIRST_PORT; p <= McpServerController.LAST_PORT; p++) {
            if (portFree(p)) return true;
        }
        return false;
    }

    @Test
    void thePolicyPropertyIsTrueOnlyForTheWordTrue() {
        java.util.Properties props = new java.util.Properties();
        assertThat(McpServerController.readDisabledByPolicy(props)).isFalse();
        props.setProperty(McpServerController.DISABLED_PROPERTY, "true");
        assertThat(McpServerController.readDisabledByPolicy(props)).isTrue();
        props.setProperty(McpServerController.DISABLED_PROPERTY, " TRUE ");
        assertThat(McpServerController.readDisabledByPolicy(props)).isTrue();
        for (String notTrue : List.of("false", "1", "yes", "")) {
            props.setProperty(McpServerController.DISABLED_PROPERTY, notTrue);
            assertThat(McpServerController.readDisabledByPolicy(props)).as(notTrue).isFalse();
        }
        assertThat(McpServerController.DISABLED_TEXT).contains("qpcat.mcp.disabled=true");
        // This JVM was not launched with the property, so the singleton is enabled.
        assertThat(McpServerController.isDisabledByPolicy()).isFalse();
    }

    @Test
    void anOccupiedFirstPortFallsThroughToTheNextOne() throws Exception {
        Assumptions.assumeTrue(portFree(McpServerController.FIRST_PORT),
                "port " + McpServerController.FIRST_PORT + " is already in use on this machine "
                        + "(another QuPath with the MCP server on?); skipping the squatter test");
        Project<BufferedImage> project = Projects.createProject(dir.toFile(), BufferedImage.class);
        controller.bindToProjectProperty(new SimpleObjectProperty<Project<?>>(project));
        try (java.net.ServerSocket squatter = new java.net.ServerSocket()) {
            squatter.bind(new java.net.InetSocketAddress(
                    java.net.InetAddress.getLoopbackAddress(), McpServerController.FIRST_PORT));
            controller.start();
            // Not "exactly FIRST_PORT + 1": another process on the machine may hold that too
            // (a second QuPath did, during Phase 3), and the scan is allowed to walk past it.
            assertThat(controller.getPort())
                    .isGreaterThan(McpServerController.FIRST_PORT)
                    .isLessThanOrEqualTo(McpServerController.LAST_PORT);
            assertThat(controller.getUrl()).endsWith(":" + controller.getPort() + "/mcp");
        }
    }

    @Test
    void startServesAndProjectChangeStops() throws Exception {
        Assumptions.assumeTrue(anyPortFree(), "ports " + McpServerController.FIRST_PORT + "-"
                + McpServerController.LAST_PORT + " are all in use on this machine; skipping");
        Project<BufferedImage> project = Projects.createProject(dir.toFile(), BufferedImage.class);
        SimpleObjectProperty<Project<?>> property = new SimpleObjectProperty<>(project);
        controller.bindToProjectProperty(property);

        controller.start();
        assertThat(controller.isRunning()).isTrue();
        assertThat(controller.getPort()).isBetween(McpServerController.FIRST_PORT, McpServerController.LAST_PORT);
        assertThat(controller.getUrl()).isEqualTo("http://127.0.0.1:" + controller.getPort() + "/mcp");
        assertThat(controller.getToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(controller.getStartedAt()).isNotNull();
        assertThat(controller.lastEventProperty().get()).startsWith("started on port");
        String token = controller.getToken();

        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<String> init = client.send(HttpRequest.newBuilder(URI.create(controller.getUrl()))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(init.statusCode()).isEqualTo(200);
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();

        HttpResponse<String> list = client.send(HttpRequest.newBuilder(URI.create(controller.getUrl()))
                .header("Authorization", "Bearer " + token)
                .header("Mcp-Session-Id", session)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                                + "\"name\":\"qpcat_list_results\",\"arguments\":{}}}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(200);
        var result = JsonParser.parseString(list.body()).getAsJsonObject().getAsJsonObject("result");
        assertThat(result.get("isError").getAsBoolean()).isFalse();
        assertThat(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
                .contains("no saved clustering results yet");
        assertThat(controller.toolCallCountProperty().get()).isEqualTo(1);

        // A second start is a no-op and keeps the same token.
        controller.start();
        assertThat(controller.getToken()).isEqualTo(token);

        property.set(null);
        assertThat(controller.isRunning()).isFalse();
        assertThat(controller.getToken()).isNull();
        assertThat(controller.lastEventProperty().get()).isEqualTo("stopped: project changed");
        assertThat(controller.runningProperty().get()).isFalse();

        // Restarting mints a new token.
        property.set(project);
        controller.start();
        assertThat(controller.getToken()).isNotNull().isNotEqualTo(token);
        controller.stop("done");
        assertThat(controller.getToken()).isNull();
        assertThat(controller.getStartedAt()).isNull();
        controller.stop("again");
    }
}
