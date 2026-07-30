package com.verification;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Minimal batch runner: maps JSON to AIE, then runs InvariantChecker.
 * No AlloyInEcore/EMF dependencies — only needs gson on classpath.
 *
 * Usage: java -cp <cp> com.verification.BatchRunner <extraction.json> <output_dir>
 */
public class BatchRunner {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: BatchRunner <extraction.json> <output_dir>");
            System.exit(1);
            return;
        }
        String jsonPath = args[0];
        String outputDir = args[1];

        try {
            Files.createDirectories(Paths.get(outputDir));
            String aiePath = outputDir + "/MappedInstance.aie";
            com.verification.mapper.JsonToAieMapper.map(jsonPath, aiePath);

            String aieContent = new String(Files.readAllBytes(Paths.get(aiePath)), StandardCharsets.UTF_8);
            VerificationReport report = new InvariantChecker().check(aieContent, null);

            // Write JSON report
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String jsonReport = gson.toJson(report);
            Files.write(Paths.get(outputDir, "verification-report.json"),
                    jsonReport.getBytes(StandardCharsets.UTF_8));

            int violations = report.getViolations().size();
            String result = report.getResult();
            System.out.println(result + " violations=" + violations);
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }
}