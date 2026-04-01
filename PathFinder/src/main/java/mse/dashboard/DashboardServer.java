package mse.dashboard;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mse.controller.Controller;
import mse.controller.NodeState;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Embedded Jetty server exposing:
 *   GET /              → dashboard.html (from classpath)
 *   GET /api/state     → JSON array of all NodeState objects
 *   GET /api/events    → SSE stream; push() sends an event to all connected clients
 *   GET /api/distress  → JSON array of recent distress events
 */
public class DashboardServer {

    private static final Logger LOG = Logger.getLogger(DashboardServer.class.getName());

    private final Controller controller;
    private final int port;
    private final List<PrintWriter> sseClients = new CopyOnWriteArrayList<>();
    private final Gson gson = new Gson();
    private Server server;

    public DashboardServer(Controller controller, int port) {
        this.controller = controller;
        this.port = port;
    }

    public void start() throws Exception {
        server = new Server(port);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");

        ctx.addServlet(new ServletHolder(new StateServlet()),   "/api/state");
        ctx.addServlet(new ServletHolder(new EventsServlet()),  "/api/events");
        ctx.addServlet(new ServletHolder(new DistressServlet()),"/api/distress");
        ctx.addServlet(new ServletHolder(new RootServlet()),    "/");

        server.setHandler(ctx);
        server.start();
        LOG.info("Dashboard available at http://localhost:" + port);
    }

    /** Push a state-change event to all connected SSE clients. */
    public void push() {
        String data = "data: " + gson.toJson(buildStateList()) + "\n\n";
        List<PrintWriter> dead = new ArrayList<>();
        for (PrintWriter w : sseClients) {
            w.print(data);
            w.flush();
            if (w.checkError()) dead.add(w);
        }
        sseClients.removeAll(dead);
    }

    public void stop() throws Exception {
        if (server != null) server.stop();
    }

    private List<Map<String, Object>> buildStateList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (NodeState s : controller.getNodeStates().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId",           s.nodeId);
            m.put("floor",            s.floor);
            m.put("locationLabel",    s.locationLabel);
            m.put("isExit",           s.isExit);
            m.put("isPassable",       s.isPassable);
            m.put("sensorError",      s.sensorError);
            m.put("temperature",      s.temperature);
            m.put("co2",              s.co2);
            m.put("computedNextHop",  s.computedNextHopId);
            m.put("computedDistance", s.computedDistance == Float.MAX_VALUE ? null : s.computedDistance);
            m.put("timedOut",         s.timedOut);
            m.put("distressActive",   s.distressActive);
            m.put("lastSeenMs",       s.lastSeenMs);
            list.add(m);
        }
        return list;
    }

    // --- Servlets ---

    private class StateServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse res)
                throws IOException {
            res.setContentType("application/json");
            res.setHeader("Access-Control-Allow-Origin", "*");
            res.getWriter().println(gson.toJson(buildStateList()));
        }
    }

    private class EventsServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse res)
                throws IOException {
            res.setContentType("text/event-stream");
            res.setHeader("Cache-Control", "no-cache");
            res.setHeader("Access-Control-Allow-Origin", "*");
            res.flushBuffer();
            PrintWriter writer = res.getWriter();
            sseClients.add(writer);
            // Block until client disconnects
            while (!writer.checkError()) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            }
            sseClients.remove(writer);
        }
    }

    private class DistressServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse res)
                throws IOException {
            res.setContentType("application/json");
            res.setHeader("Access-Control-Allow-Origin", "*");
            res.getWriter().println(gson.toJson(controller.getRecentDistressEvents()));
        }
    }

    private class RootServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse res)
                throws IOException {
            res.setContentType("text/html;charset=UTF-8");
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream("dashboard.html")) {
                if (in == null) { res.sendError(404, "dashboard.html not found"); return; }
                res.getOutputStream().write(in.readAllBytes());
            }
        }
    }
}
