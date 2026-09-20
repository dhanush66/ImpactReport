package io.spmp.impact.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.spmp.impact.model.ImpactReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JsonReportRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonReportRenderer() {}

    public static String renderToString(ImpactReport report) {
        try {
            return MAPPER.writeValueAsString(report);
        } catch (Exception e) {
            throw new RuntimeException("Failed to render JSON: " + e.getMessage(), e);
        }
    }

    public static void renderToFile(ImpactReport report, Path out) throws IOException {
        Files.writeString(out, renderToString(report));
    }
}
