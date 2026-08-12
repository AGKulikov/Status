/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Keeps connector-only workers cooperative during the shared automotive startup window. */
public final class Ha1214ConnectorThreadPriorityContractTest {
    @Test public void mqttWorkerRunsAtBackgroundPriority() throws Exception {
        assertBackgroundWrapper(source("mqtt/MqttClient.java"),
                "status-widget-mqtt", "runLoop(generation);");
    }

    @Test public void phoneIconWorkerRunsAtBackgroundPriorityWithoutDemotingGatt()
            throws Exception {
        String controller = source("phone/PhoneConnectorController.java");
        assertTrue(controller.contains("new HandlerThread(\"StatusWidgetPhone\")"));

        assertBackgroundWrapper(source("phone/PhoneAppIconStore.java"),
                "phone-app-icons", "runnable.run();");
    }

    @Test public void homeAssistantReconnectWorkerRunsAtBackgroundPriority() throws Exception {
        assertBackgroundWrapper(source("ha/api/HaWebSocketConnector.java"),
                "ha-websocket-reconnect", "runnable.run();");
    }

    @Test public void sprutWorkersRunAtBackgroundPriority() throws Exception {
        assertBackgroundWrapper(source("sprut/SprutHubRpcClient.java"),
                "spruthub-rpc-timeouts", "runnable.run();");
        assertBackgroundWrapper(source("sprut/SprutHubController.java"),
                "spruthub-controller", "runnable.run();");
    }

    private static void assertBackgroundWrapper(String source, String threadName,
                                                String workInvocation) {
        int name = source.indexOf("\"" + threadName + "\"");
        int priority = source.lastIndexOf("THREAD_PRIORITY_BACKGROUND", name);
        int work = source.lastIndexOf(workInvocation, name);
        assertTrue("Missing worker thread: " + threadName, name >= 0);
        assertTrue("Priority must be set inside worker " + threadName,
                priority >= 0 && priority < name);
        assertTrue("Priority must be set before work in " + threadName,
                work >= 0 && priority < work && work < name);
    }

    private static String source(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        String projectRelative = "app/src/main/java/dezz/status/widget/" + relative;
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(projectRelative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + projectRelative);
    }
}
