package mse.simulator;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Applies a scripted sequence of sensor-state changes to SimNodes at specified delays.
 * Used for reproducible demos — e.g. simulating fire spreading across nodes.
 *
 * Scenario file format:
 *   { "description": "...", "steps": [ { "delay_ms": N, "node_id": "X", "temperature": T, "co2": C } ] }
 *
 * delay_ms is relative to when start() is called (not cumulative).
 */
public class ScenarioRunner {

    private static final Logger LOG = Logger.getLogger(ScenarioRunner.class.getName());

    private static class ScenarioJson {
        String description;
        List<StepJson> steps;
    }

    private static class StepJson {
        @SerializedName("delay_ms") long delayMs;
        @SerializedName("node_id")  String nodeId;
        float temperature;
        float co2;
    }

    private final Simulator simulator;
    private final ScenarioJson scenario;
    private ScheduledExecutorService scheduler;

    public ScenarioRunner(Simulator simulator, Path scenarioFile) throws IOException {
        this.simulator = simulator;
        Gson gson = new Gson();
        try (Reader reader = Files.newBufferedReader(scenarioFile)) {
            this.scenario = gson.fromJson(reader, ScenarioJson.class);
        }
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scenario");
            t.setDaemon(true);
            return t;
        });
        LOG.info("Running scenario: " + scenario.description);
        for (StepJson step : scenario.steps) {
            scheduler.schedule(() -> applyStep(step), step.delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void applyStep(StepJson step) {
        SimNode node = simulator.getNode(step.nodeId);
        if (node == null) {
            LOG.warning("Scenario step: unknown node " + step.nodeId);
            return;
        }
        node.setTemperature(step.temperature);
        node.setCo2(step.co2);
        LOG.info("Scenario: " + step.nodeId
            + " temp=" + step.temperature + " co2=" + step.co2);
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
