package mse;

/**
 * A Node that serves as an evacuation exit.
 * Exit nodes always hold distance = 0 when passable and seed the routing wavefront.
 * They can be force-blocked via setPassable(false) (e.g. structurally damaged exit).
 */
public class Exit extends Node {

    public Exit(String id) {
        super(id, 0f, 0f);
    }

    public Exit(String id, float temperatureThreshold, float gasConcentrationThreshold) {
        super(id, 0f, 0f, temperatureThreshold, gasConcentrationThreshold);
    }
}
