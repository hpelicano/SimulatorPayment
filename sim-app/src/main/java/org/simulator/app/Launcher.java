package org.simulator.app;

import org.simulator.ui.SimulatorApp;

import java.util.Arrays;

/**
 * Entry point of the fat jar. It does not extend {@code javafx.application.Application} so JavaFX can be
 * loaded from the classpath. With {@code --headless} it runs without UI (CI / servers).
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--headless".equals(args[0])) {
            System.exit(Headless.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        SimulatorApp.main(args);
    }
}
