package mse.dashboard;

import mse.Node;
import mse.controller.Controller;
import mse.controller.NodeState;
import mse.distress.DistressRecord;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.geom.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Dark-theme Swing dashboard with a node graph view and a data table.
 *
 * Layout:
 *   +-- status bar ----------------------------+
 *   | graph panel  | table panel               |
 *   |              +----------------------------|
 *   |              | distress alerts panel      |
 *   +--------------------------------------------+
 *
 * push() refreshes both panels on every path recomputation.
 *
 * Graph panel:
 *   Nodes laid out via a force-directed algorithm (runs once at first paint).
 *   Gold arrows show each node's computed next-hop direction.
 *   Node colour: dark-red = impassable, dark-green = exit, amber border = distress.
 *
 * Table row colouring:
 *   Active distress — amber, Impassable — dark-red, Exit — dark-green, Normal — dark-grey.
 */
public class SwingDashboard {

    // -------------------------------------------------------------------------
    // Palette
    // -------------------------------------------------------------------------

    private static final Color BG            = new Color( 17,  17,  17);
    private static final Color BG_ROW        = new Color( 28,  28,  28);
    private static final Color BG_IMPASS     = new Color( 50,   0,   0);
    private static final Color BG_EXIT       = new Color(  0,  35,  15);
    private static final Color BG_DISTRESS   = new Color( 50,  28,   0);
    private static final Color FG            = new Color(210, 210, 210);
    private static final Color FG_ERR        = new Color(245,  80,  80);
    private static final Color FG_OK         = new Color(100, 220, 130);
    private static final Color FG_WARN       = new Color(255, 180,  60);
    private static final Color FG_HEADER     = new Color(160, 160, 160);

    // Graph-specific
    private static final Color GRAPH_BG      = new Color( 20,  20,  20);
    private static final Color NODE_NORMAL   = new Color( 30,  55,  90);
    private static final Color EDGE_COLOR    = new Color( 55,  55,  55);
    private static final Color ARROW_COLOR   = new Color(255, 200,  50);

    private static final String[] COLUMNS = {
        "Node", "Floor", "Location", "Passable",
        "Temp (°C)", "CO₂", "Next Hop", "Distance", "Last Seen"
    };

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Controller controller;

    private JFrame              frame;
    private DefaultTableModel   tableModel;
    private DefaultListModel<String> alertModel;
    private GraphPanel          graphPanel;

    private final Set<String> shownAlerts   = new HashSet<>();
    private final Set<String> distressNodes = new HashSet<>();

    public SwingDashboard(Controller controller) {
        this.controller = controller;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void start() {
        SwingUtilities.invokeLater(this::buildUI);
    }

    public void stop() {
        if (frame != null) SwingUtilities.invokeLater(frame::dispose);
    }

    /** Thread-safe: schedules a refresh on the EDT. */
    public void push() {
        SwingUtilities.invokeLater(this::refresh);
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private void buildUI() {
        frame = new JFrame("MSE — Emergency Exit Dashboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1500, 700);
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout());

        frame.add(buildStatusBar(), BorderLayout.NORTH);
        frame.add(buildMainSplit(),  BorderLayout.CENTER);

        frame.setVisible(true);
        refresh();
    }

    private JLabel buildStatusBar() {
        JLabel bar = new JLabel("  MSE Emergency Exit System   ●   live", JLabel.LEFT);
        bar.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        bar.setForeground(FG_OK);
        bar.setBackground(new Color(22, 22, 22));
        bar.setOpaque(true);
        bar.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
        return bar;
    }

    /** Horizontal split: graph on the left, table+alerts on the right. */
    private JSplitPane buildMainSplit() {
        graphPanel = new GraphPanel();

        JSplitPane main = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, graphPanel, buildTableSplit());
        main.setDividerLocation(430);
        main.setDividerSize(3);
        main.setBackground(BG);
        return main;
    }

    private JSplitPane buildTableSplit() {
        JSplitPane split = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, buildTablePanel(), buildAlertPanel());
        split.setResizeWeight(0.78);
        split.setDividerSize(3);
        split.setBackground(BG);
        return split;
    }

    private JScrollPane buildTablePanel() {
        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        JTable table = new JTable(tableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int col) {
                Component c = super.prepareRenderer(renderer, row, col);
                if (isRowSelected(row)) return c;

                Object nodeCell  = getValueAt(row, 0);
                String nodeId    = nodeCell != null
                    ? nodeCell.toString().replace(" 🚪", "").trim() : "";
                boolean isExit     = nodeCell != null && nodeCell.toString().contains("🚪");
                boolean impassable = "NO".equals(getValueAt(row, 3));
                boolean distress   = distressNodes.contains(nodeId);

                if (distress)        { c.setBackground(BG_DISTRESS); c.setForeground(FG_WARN); }
                else if (impassable) { c.setBackground(BG_IMPASS);   c.setForeground(FG_ERR);  }
                else if (isExit)     { c.setBackground(BG_EXIT);     c.setForeground(FG_OK);   }
                else                 { c.setBackground(BG_ROW);      c.setForeground(FG);       }
                return c;
            }
        };

        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        table.setBackground(BG_ROW);
        table.setForeground(FG);
        table.setRowHeight(23);
        table.setGridColor(new Color(42, 42, 42));
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setShowVerticalLines(false);

        JTableHeader header = table.getTableHeader();
        header.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        header.setBackground(new Color(34, 34, 34));
        header.setForeground(FG_HEADER);
        header.setReorderingAllowed(false);

        int[] widths = {115, 50, 190, 82, 90, 72, 105, 88, 92};
        for (int i = 0; i < widths.length; i++)
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        JScrollPane scroll = new JScrollPane(table);
        scroll.getViewport().setBackground(BG_ROW);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    private JPanel buildAlertPanel() {
        alertModel = new DefaultListModel<>();
        JList<String> alertList = new JList<>(alertModel);
        alertList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        alertList.setBackground(new Color(28, 14, 0));
        alertList.setForeground(FG_WARN);
        alertList.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));

        JLabel title = new JLabel("  Distress Alerts");
        title.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        title.setForeground(FG_WARN);
        title.setBackground(new Color(40, 20, 0));
        title.setOpaque(true);
        title.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        JScrollPane scroll = new JScrollPane(alertList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(new Color(28, 14, 0));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(28, 14, 0));
        panel.add(title,  BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    // -------------------------------------------------------------------------
    // Refresh (runs on EDT)
    // -------------------------------------------------------------------------

    private void refresh() {
        if (tableModel == null) return;

        long now = System.currentTimeMillis();
        Collection<NodeState> states = controller.getNodeStates().values();

        distressNodes.clear();
        for (NodeState s : states) {
            if (s.distressActive) distressNodes.add(s.nodeId);
        }

        tableModel.setRowCount(0);
        for (NodeState s : states) {
            String dist = s.computedDistance == Float.MAX_VALUE
                ? "∞" : String.format("%.1f", s.computedDistance);
            String ago  = s.lastSeenMs > 0
                ? ((now - s.lastSeenMs) / 1000) + "s ago" : "—";

            tableModel.addRow(new Object[]{
                s.nodeId + (s.isExit ? " 🚪" : ""),
                s.floor,
                s.locationLabel,
                s.isPassable ? "YES" : "NO",
                String.format("%.1f", s.temperature),
                String.format("%.2f", s.co2),
                s.computedNextHopId != null ? s.computedNextHopId : "—",
                dist,
                ago
            });
        }

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        for (DistressRecord r : controller.getRecentDistressEvents()) {
            String key = r.nodeId + ":" + r.seq;
            if (shownAlerts.add(key)) {
                alertModel.add(0,
                    "⚠  " + r.nodeId
                    + "  floor " + r.floor
                    + "  — " + r.locationLabel
                    + "  (seq=" + r.seq + ")"
                    + "  at " + sdf.format(new Date(r.receivedAtMs)));
            }
        }

        if (graphPanel != null) graphPanel.repaint();
    }

    // -------------------------------------------------------------------------
    // Graph panel
    // -------------------------------------------------------------------------

    private class GraphPanel extends JPanel {

        private static final int R = 22;   // node radius px (in normalised space)

        /** Normalised [0,1] positions, computed once by force-directed layout. */
        private final Map<String, Point2D.Float> positions = new LinkedHashMap<>();
        private boolean layoutDone = false;

        GraphPanel() {
            setBackground(GRAPH_BG);
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(45, 45, 45)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Map<String, NodeState> states = controller.getNodeStates();
            if (states.isEmpty()) return;

            ensureLayout(states);

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            drawTitle(g2);
            drawEdges(g2, states, w, h);
            drawArrows(g2, states, w, h);
            drawNodes(g2, states, w, h);
        }

        // --- Title -------------------------------------------------------

        private void drawTitle(Graphics2D g2) {
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
            g2.setColor(FG_HEADER);
            g2.drawString("Node Graph", 10, 20);
        }

        // --- Edges -------------------------------------------------------

        private void drawEdges(Graphics2D g2, Map<String, NodeState> states, int w, int h) {
            g2.setColor(EDGE_COLOR);
            g2.setStroke(new BasicStroke(1.5f));

            Set<String> drawn = new HashSet<>();
            for (String id : states.keySet()) {
                Point2D.Float p = scaledPos(id, w, h);
                if (p == null) continue;
                controller.getGraph().getNode(id).ifPresent(node -> {
                    for (Node nb : node.getNeighbors().keySet()) {
                        String nid = nb.getId();
                        String edgeKey = id.compareTo(nid) < 0 ? id + "-" + nid : nid + "-" + id;
                        if (!drawn.add(edgeKey)) continue;
                        Point2D.Float np = scaledPos(nid, w, h);
                        if (np != null) g2.drawLine((int) p.x, (int) p.y, (int) np.x, (int) np.y);
                    }
                });
            }
        }

        // --- Next-hop arrows ---------------------------------------------

        private void drawArrows(Graphics2D g2, Map<String, NodeState> states, int w, int h) {
            g2.setColor(ARROW_COLOR);
            g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            for (NodeState s : states.values()) {
                if (s.computedNextHopId == null) continue;
                Point2D.Float from = scaledPos(s.nodeId, w, h);
                Point2D.Float to   = scaledPos(s.computedNextHopId, w, h);
                if (from == null || to == null) continue;

                double dx  = to.x - from.x;
                double dy  = to.y - from.y;
                double len = Math.sqrt(dx * dx + dy * dy);
                if (len < 1) continue;
                double ux = dx / len, uy = dy / len;

                float sx = (float) (from.x + ux * R);
                float sy = (float) (from.y + uy * R);
                float ex = (float) (to.x   - ux * R);
                float ey = (float) (to.y   - uy * R);

                g2.drawLine((int) sx, (int) sy, (int) ex, (int) ey);
                drawArrowHead(g2, sx, sy, ex, ey);
            }
        }

        private void drawArrowHead(Graphics2D g2, float sx, float sy, float ex, float ey) {
            double angle = Math.atan2(ey - sy, ex - sx);
            int sz = 10;
            int[] xs = {
                (int) ex,
                (int) (ex - sz * Math.cos(angle - Math.PI / 6)),
                (int) (ex - sz * Math.cos(angle + Math.PI / 6))
            };
            int[] ys = {
                (int) ey,
                (int) (ey - sz * Math.sin(angle - Math.PI / 6)),
                (int) (ey - sz * Math.sin(angle + Math.PI / 6))
            };
            g2.fillPolygon(xs, ys, 3);
        }

        // --- Nodes -------------------------------------------------------

        private void drawNodes(Graphics2D g2, Map<String, NodeState> states, int w, int h) {
            for (NodeState s : states.values()) {
                Point2D.Float p = scaledPos(s.nodeId, w, h);
                if (p == null) continue;

                int cx = (int) p.x, cy = (int) p.y;

                // Fill
                g2.setColor(nodeColor(s));
                g2.fillOval(cx - R, cy - R, R * 2, R * 2);

                // Border — amber if distress, faint otherwise
                boolean inDistress = distressNodes.contains(s.nodeId);
                g2.setColor(inDistress ? FG_WARN : new Color(75, 75, 75));
                g2.setStroke(new BasicStroke(inDistress ? 2.5f : 1f));
                g2.drawOval(cx - R, cy - R, R * 2, R * 2);

                // Distance label inside circle
                if (s.computedDistance < Float.MAX_VALUE) {
                    String dist = String.format("%.0f", s.computedDistance);
                    g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
                    FontMetrics fm = g2.getFontMetrics();
                    g2.setColor(new Color(190, 190, 190));
                    g2.drawString(dist, cx - fm.stringWidth(dist) / 2, cy + 4);
                }

                // Node ID below circle
                g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
                FontMetrics fm = g2.getFontMetrics();
                String label = s.nodeId + (s.isExit ? " 🚪" : "");
                g2.setColor(FG);
                g2.drawString(label, cx - fm.stringWidth(label) / 2, cy + R + 14);

                // "DISTRESS" label above circle
                if (inDistress) {
                    g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
                    fm = g2.getFontMetrics();
                    g2.setColor(FG_WARN);
                    g2.drawString("DISTRESS", cx - fm.stringWidth("DISTRESS") / 2, cy - R - 4);
                }
            }
        }

        private Color nodeColor(NodeState s) {
            if (!s.isPassable) return BG_IMPASS;
            if (s.isExit)      return BG_EXIT;
            return NODE_NORMAL;
        }

        // --- Layout ------------------------------------------------------

        private void ensureLayout(Map<String, NodeState> states) {
            if (layoutDone) return;

            Random rng = new Random(42);
            for (String id : states.keySet()) {
                positions.put(id, new Point2D.Float(
                    0.1f + rng.nextFloat() * 0.8f,
                    0.1f + rng.nextFloat() * 0.8f));
            }

            List<String> ids = new ArrayList<>(positions.keySet());
            Map<String, float[]> vel = new HashMap<>();
            for (String id : ids) vel.put(id, new float[]{0f, 0f});

            for (int iter = 0; iter < 300; iter++) {
                Map<String, float[]> force = new HashMap<>();
                for (String id : ids) force.put(id, new float[]{0f, 0f});

                // Repulsion between all pairs
                for (int i = 0; i < ids.size(); i++) {
                    for (int j = i + 1; j < ids.size(); j++) {
                        String a = ids.get(i), b = ids.get(j);
                        Point2D.Float pa = positions.get(a), pb = positions.get(b);
                        float dx = pa.x - pb.x, dy = pa.y - pb.y;
                        float d  = Math.max(0.001f, (float) Math.sqrt(dx * dx + dy * dy));
                        float f  = 0.02f / (d * d);
                        force.get(a)[0] += f * dx / d;  force.get(a)[1] += f * dy / d;
                        force.get(b)[0] -= f * dx / d;  force.get(b)[1] -= f * dy / d;
                    }
                }

                // Attraction along edges (natural length 0.3)
                for (String id : ids) {
                    controller.getGraph().getNode(id).ifPresent(node -> {
                        Point2D.Float pa = positions.get(id);
                        for (Node nb : node.getNeighbors().keySet()) {
                            Point2D.Float pb = positions.get(nb.getId());
                            if (pb == null) return;
                            float dx = pb.x - pa.x, dy = pb.y - pa.y;
                            float d  = Math.max(0.001f, (float) Math.sqrt(dx * dx + dy * dy));
                            float f  = 0.5f * (d - 0.3f);
                            force.get(id)[0] += f * dx / d;
                            force.get(id)[1] += f * dy / d;
                        }
                    });
                }

                // Integrate with damping
                for (String id : ids) {
                    float[] v = vel.get(id), f = force.get(id);
                    v[0] = (v[0] + f[0]) * 0.85f;
                    v[1] = (v[1] + f[1]) * 0.85f;
                    Point2D.Float p = positions.get(id);
                    p.x = Math.max(0.05f, Math.min(0.95f, p.x + v[0]));
                    p.y = Math.max(0.05f, Math.min(0.95f, p.y + v[1]));
                }
            }

            layoutDone = true;
        }

        /** Converts normalised [0,1] position to panel pixel coordinates. */
        private Point2D.Float scaledPos(String id, int w, int h) {
            Point2D.Float p = positions.get(id);
            if (p == null) return null;
            int margin = R + 30;
            return new Point2D.Float(
                margin + p.x * (w - 2 * margin),
                margin + p.y * (h - 2 * margin));
        }
    }
}
