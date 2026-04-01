package mse.dashboard;

import mse.controller.Controller;

/** Stub — full implementation in Task 14. */
public class DashboardServer {
    private final Controller controller;
    private final int port;

    public DashboardServer(Controller controller, int port) {
        this.controller = controller;
        this.port = port;
    }

    public void start() throws Exception {
        // Stub — implemented in Task 14
        System.out.println("[Dashboard stub] will serve on port " + port);
    }

    public void push() {
        // Stub — no-op until Task 14
    }

    public void stop() throws Exception {}
}
