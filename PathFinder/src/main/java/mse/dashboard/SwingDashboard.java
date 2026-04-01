package mse.dashboard;

import mse.controller.Controller;
import mse.controller.NodeState;
import mse.distress.DistressRecord;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Dark-theme Swing dashboard that mirrors the web dashboard.
 *
 * Opens a JFrame on start(). push() (called by the Controller on every path
 * recomputation) refreshes the node table and appends new distress alerts,
 * always on the Event Dispatch Thread.
 *
 * Row colouring:
 *   Active distress  — amber background
 *   Impassable node  — dark red background
 *   Exit node        — dark green background
 *   Normal           — dark grey background
 */
public class SwingDashboard {

    // Palette
    private static final Color BG          = new Color( 17,  17,  17);
    private static final Color BG_ROW      = new Color( 28,  28,  28);
    private static final Color BG_IMPASS   = new Color( 50,   0,   0);
    private static final Color BG_EXIT     = new Color(  0,  35,  15);
    private static final Color BG_DISTRESS = new Color( 50,  28,   0);
    private static final Color FG          = new Color(210, 210, 210);
    private static final Color FG_ERR      = new Color(245,  80,  80);
    private static final Color FG_OK       = new Color(100, 220, 130);
    private static final Color FG_WARN     = new Color(255, 180,  60);
    private static final Color FG_HEADER   = new Color(160, 160, 160);

    private static final String[] COLUMNS = {
        "Node", "Floor", "Location", "Passable",
        "Temp (°C)", "CO₂", "Next Hop", "Distance", "Last Seen"
    };

    private final Controller controller;

    // UI components (only accessed on EDT)
    private JFrame frame;
    private DefaultTableModel tableModel;
    private DefaultListModel<String> alertModel;

    // Dedup state
    private final Set<String> shownAlerts  = new HashSet<>();
    private final Set<String> distressNodes = new HashSet<>();

    public SwingDashboard(Controller controller) {
        this.controller = controller;
    }

    public void start() {
        SwingUtilities.invokeLater(this::buildUI);
    }

    public void stop() {
        if (frame != null) SwingUtilities.invokeLater(frame::dispose);
    }

    /** Thread-safe: called from any thread, updates happen on the EDT. */
    public void push() {
        SwingUtilities.invokeLater(this::refresh);
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private void buildUI() {
        frame = new JFrame("MSE — Emergency Exit Dashboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1150, 640);
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout());

        frame.add(buildStatusBar(), BorderLayout.NORTH);
        frame.add(buildSplitPane(),  BorderLayout.CENTER);

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

    private JSplitPane buildSplitPane() {
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

                Object nodeCell = getValueAt(row, 0);
                String nodeId   = nodeCell != null
                    ? nodeCell.toString().replace(" 🚪", "").trim() : "";
                boolean isExit     = nodeCell != null && nodeCell.toString().contains("🚪");
                boolean impassable = "NO".equals(getValueAt(row, 3));
                boolean distress   = distressNodes.contains(nodeId);

                if (distress) {
                    c.setBackground(BG_DISTRESS); c.setForeground(FG_WARN);
                } else if (impassable) {
                    c.setBackground(BG_IMPASS);   c.setForeground(FG_ERR);
                } else if (isExit) {
                    c.setBackground(BG_EXIT);     c.setForeground(FG_OK);
                } else {
                    c.setBackground(BG_ROW);      c.setForeground(FG);
                }
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
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

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

        // Rebuild distress set for row highlighting
        distressNodes.clear();
        for (NodeState s : states) {
            if (s.distressActive) distressNodes.add(s.nodeId);
        }

        // Rebuild table rows
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

        // Append new distress alerts (deduped by nodeId:seq)
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        List<DistressRecord> events = controller.getRecentDistressEvents();
        for (DistressRecord r : events) {
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
    }
}
