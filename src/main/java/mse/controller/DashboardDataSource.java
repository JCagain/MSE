package mse.controller;

import mse.Graph;
import mse.distress.DistressRecord;

import java.util.List;
import java.util.Map;

public interface DashboardDataSource {
    Map<String, NodeState> getNodeStates();
    Graph getGraph();
    List<DistressRecord> getRecentDistressEvents();
}
