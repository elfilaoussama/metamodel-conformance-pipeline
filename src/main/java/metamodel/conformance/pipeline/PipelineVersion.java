package metamodel.conformance.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public final class PipelineVersion {
    public static final String TOOL_ID = "metamodel-conformance-pipeline-next";
    public static final String VERSION = loadVersion();

    private PipelineVersion() {
    }

    private static String loadVersion() {
        try {
            return readVersion(PipelineVersion.class.getResourceAsStream(
                    "/metamodel-conformance-pipeline-version.properties"));
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read build producer identity", failure);
        }
    }

    static String readVersion(InputStream input) throws IOException {
        if (input == null) throw new IllegalStateException("build producer identity is missing");
        Properties properties = new Properties();
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String version = properties.getProperty("version");
        if (version == null || version.isBlank() || version.contains("${")) {
            throw new IllegalStateException("build producer identity is empty or unexpanded");
        }
        return version.trim();
    }
}
