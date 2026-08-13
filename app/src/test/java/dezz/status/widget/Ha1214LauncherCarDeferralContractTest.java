/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards the HA1214 rule: visual HOME inflation must not initialize the vendor car SDK. */
public final class Ha1214LauncherCarDeferralContractTest {
    @Test public void panelInflationCarriesSuppliersWithoutResolvingEcarx() throws Exception {
        String launcher = source("LauncherActivity.java");
        String initialize = between(launcher, "private void initializePanels()",
                "private void continuePanelInitialization()");
        assertFalse(initialize.contains("CarIntegrations.get"));
        assertFalse(initialize.contains("requireLauncherCarIntegration"));

        assertTrue(launcher.contains("new ClimatePanelView(this, "
                + "this::requireLauncherCarIntegration"));
        assertTrue(launcher.contains("new VehicleInfoPanelView(this, "
                + "this::requireLauncherCarIntegration"));
        assertTrue(launcher.contains("new InformationPanelView(this, "
                + "this::requireLauncherCarIntegration"));

        String shortcut = source("launcher/InformationShortcutView.java");
        assertTrue(launcher.contains("shortcut, this::requireLauncherCarIntegration"));
        assertFalse(shortcut.contains(
                "new InformationPanelView(context, CarIntegrations.get(context)"));
        assertTrue(shortcut.contains("if (automaticLifecycle) content.start();"));
    }

    @Test public void automaticCarResolutionUsesTheSerialWorkerFromRuntimeStageTwo()
            throws Exception {
        String launcher = source("LauncherActivity.java");
        String runtime = between(launcher,
                "private final Runnable deferredLauncherRuntimeStep",
                "@Override\n    protected void onCreate");
        int stageTwo = runtime.indexOf("case 2:");
        int asyncStart = runtime.indexOf("startLauncherCarRuntimeAsync();", stageTwo);
        assertTrue(stageTwo >= 0 && asyncStart > stageTwo);
        assertFalse(runtime.contains("launcherRuntimeDelayMillis("));
        assertFalse(runtime.contains("requireLauncherCarIntegration();"));

        String async = between(launcher,
                "private void startLauncherCarRuntimeAsync()",
                "private void activateLauncherCarRuntime()");
        assertTrue(async.contains("launcherWorker.execute"));
        assertTrue(async.contains("CarIntegrations.get(getApplicationContext())"));
        String activate = between(launcher,
                "private void activateLauncherCarRuntime()",
                "/** Resolves ECARX after automatic background warm-up");
        assertTrue(activate.contains("reconcileInformationShortcutRuntime()"));

        String resolver = between(launcher,
                "private CarIntegration requireLauncherCarIntegration()",
                "private void resubscribeCarControls()");
        assertTrue(resolver.contains("CarIntegrations.get(this)"));
        String initialize = between(launcher, "private void initializePanels()",
                "private void continuePanelInitialization()");
        assertFalse(initialize.contains("CarIntegrations.get"));

        String command = between(launcher, "private void executeCarControl(",
                "/** Constructs the process-wide vendor bridge");
        assertTrue(command.contains("requireLauncherCarIntegration().executeControl"));
        String stop = between(launcher, "protected void onStop()",
                "/** Starts migration immediately");
        assertFalse(stop.contains("requireLauncherCarIntegration"));
        assertFalse(stop.contains("CarIntegrations.get"));
        assertTrue(stop.contains("InformationShortcutView shortcut"));
        assertTrue(stop.contains("shortcut.stop()"));
    }

    @Test public void lazyPanelConstructorsAndStopsNeverResolveTheirSuppliers() throws Exception {
        assertLazyPanel(source("launcher/climate/ClimatePanelView.java"),
                "Keeps ECARX construction out of HOME inflation",
                "public void reloadConfig()", "public void start()", "public void stop()",
                "public ClimatePanelConfig currentConfig()", "requireIntegration();");
        assertLazyPanel(source("launcher/vehicle/VehicleInfoPanelView.java"),
                "Keeps vendor binding outside constructor-time HOME inflation",
                "/** Begin catalog discovery", "public void start()", "public void stop()",
                "public void reloadConfig()", "requireIntegration();");
        assertLazyPanel(source("launcher/information/InformationPanelView.java"),
                "Keeps the vehicle connector dormant while HOME builds its first visual frame",
                "public void start()", "public void start()", "public void stop()",
                "public void reloadConfig()", "requireCarIntegration();");
    }

    private static void assertLazyPanel(String source, String constructorStart,
                                        String constructorEnd, String startMarker,
                                        String stopMarker, String stopEnd,
                                        String resolverCall) {
        String constructor = between(source, constructorStart, constructorEnd);
        assertTrue(constructor.contains("Supplier<CarIntegration>"));
        assertFalse(constructor.contains(".get()"));
        assertFalse(constructor.contains(".subscribe"));
        assertFalse(constructor.contains(".request"));

        String start = between(source, startMarker, stopMarker);
        assertTrue(start.contains(resolverCall));
        String stop = between(source, stopMarker, stopEnd);
        assertFalse(stop.contains(resolverCall));
        assertFalse(stop.contains("Supplier.get"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        Path module = Paths.get("src", "main", "java", "dezz", "status", "widget");
        Path base = Files.isDirectory(root) ? root : module;
        return new String(Files.readAllBytes(base.resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(0, start.length()));
        assertTrue("missing start: " + start, from >= 0);
        assertTrue("missing end: " + end, to > from);
        return source.substring(from, to);
    }
}
