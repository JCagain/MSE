package mse.topology;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TopologyCompilerTest {

    private static String compile() throws IOException {
        TopologyLoader.LoadResult result = TopologyLoader.load(Path.of("sample-topology.json"));
        return TopologyCompiler.compile(result.nodeJsons);
    }

    @Test
    void compile_correctNodeCount() throws IOException {
        assertTrue(compile().contains("#define NUM_NODES 4"));
    }

    @Test
    void compile_correctMaxNeighbors() throws IOException {
        assertTrue(compile().contains("#define MAX_NEIGHBORS 2"));
    }

    @Test
    void compile_nodeIdsInDeclarationOrder() throws IOException {
        assertTrue(compile().contains("\"1A\", \"1B\", \"1C\", \"1Exit-A\""));
    }

    @Test
    void compile_exitFlagCorrect() throws IOException {
        assertTrue(compile().contains("IS_EXIT[NUM_NODES] = {false, false, false, true}"));
    }

    @Test
    void compile_neighborIndicesCorrect() throws IOException {
        String out = compile();
        assertTrue(out.contains("{1, 2}"), "1A neighbors: " + out);
        assertTrue(out.contains("{0, 3}"));
    }
}
