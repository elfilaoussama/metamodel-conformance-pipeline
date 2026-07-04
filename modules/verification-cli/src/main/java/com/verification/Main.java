package com.verification;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.verification.mapper.JsonToAieMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Main {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) {
        CliOptions opts = parseArgs(args);
        if (opts == null) {
            System.exit(1);
            return;
        }

        try {
            if (!Files.isRegularFile(opts.metamodel)) {
                System.err.println("Metamodel not found: " + opts.metamodel);
                System.exit(1);
                return;
            }

            if (opts.extractionJson != null && !Files.isRegularFile(opts.extractionJson)) {
                System.err.println("Extraction JSON not found: " + opts.extractionJson);
                System.exit(1);
                return;
            }

            Files.createDirectories(opts.outputDir);

            String recoreContent = Files.readString(opts.metamodel, StandardCharsets.UTF_8);

            Path mappedInstance = opts.outputDir.resolve("MappedInstance.aie");

            if (opts.extractionJson != null) {
                System.out.println("Mapping Spoon extraction to AIE instance...");
                JsonToAieMapper mapper = new JsonToAieMapper();
                mapper.map(opts.extractionJson, mappedInstance);
                System.out.println("Mapped instance written to " + mappedInstance);
            }

            String aieContent = mappedInstance != null && Files.isRegularFile(mappedInstance)
                    ? Files.readString(mappedInstance, StandardCharsets.UTF_8)
                    : "";

            System.out.println("Running invariant checker (strict=" + opts.strict
                    + ", details=" + opts.details + ")...");

            InvariantChecker checker = new InvariantChecker(opts.strict, opts.details);
            VerificationReport report = checker.check(aieContent, recoreContent);

            if (opts.reportPath != null) {
                String json = gson.toJson(report);
                Files.writeString(opts.reportPath, json, StandardCharsets.UTF_8);
                System.out.println("Report written to " + opts.reportPath);
            }

            if (opts.csvPath != null) {
                writeCsv(opts.csvPath, report);
                System.out.println("CSV written to " + opts.csvPath);
            }

            System.out.println("Result: " + report.getResult());
            if (report.getViolations() != null && !report.getViolations().isEmpty()) {
                System.out.println("Violations: " + report.getViolations().size());
                for (VerificationReport.Violation v : report.getViolations()) {
                    System.out.println("  - " + (v.getDescription() != null ? v.getDescription() : "unspecified"));
                }
            }

            if ("SAT".equals(report.getResult())) System.exit(0);
            else if ("UNSAT".equals(report.getResult())) System.exit(1);
            else System.exit(2);

        } catch (Exception e) {
            System.err.println("Verification failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static CliOptions parseArgs(String[] args) {
        CliOptions opts = new CliOptions();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-r":
                    if (++i < args.length) opts.metamodel = Paths.get(args[i]);
                    break;
                case "-i":
                    if (++i < args.length) opts.extractionJson = Paths.get(args[i]);
                    break;
                case "-o":
                    if (++i < args.length) opts.outputDir = Paths.get(args[i]);
                    break;
                case "--strict":
                    opts.strict = true;
                    break;
                case "--details":
                    opts.details = true;
                    break;
                case "--report":
                    if (++i < args.length) opts.reportPath = Paths.get(args[i]);
                    break;
                case "--csv":
                    if (++i < args.length) opts.csvPath = Paths.get(args[i]);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    return null;
            }
        }

        if (opts.metamodel == null) {
            System.err.println("Usage: java com.verification.Main -r <recore> [-i <extraction.json>] -o <outputDir> [--strict] [--details] [--report <report.json>] [--csv <report.csv>]");
            System.err.println("  -r <file>       : AlloyInEcore .recore metamodel (required)");
            System.err.println("  -i <file>       : Spoon extraction JSON input");
            System.err.println("  -o <dir>        : Output directory");
            System.err.println("  --strict        : Strict conformance mode (fix non-derived relations)");
            System.err.println("  --details       : Show detailed violation information");
            System.err.println("  --report <file> : JSON report output path");
            System.err.println("  --csv <file>    : CSV report output path");
            return null;
        }

        if (opts.outputDir == null) {
            opts.outputDir = Paths.get(".");
        }

        return opts;
    }

    private static void writeCsv(Path path, VerificationReport report) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("Result,InvariantName,Description,Formula,Line\n");
        csv.append(report.getResult()).append(",,,,\n");
        if (report.getViolations() != null) {
            for (VerificationReport.Violation v : report.getViolations()) {
                csv.append(",");
                csv.append(escapeCsv(v.getInvariantName())).append(",");
                csv.append(escapeCsv(v.getDescription())).append(",");
                csv.append(escapeCsv(v.getFormula())).append(",");
                csv.append(v.getLine() != null ? v.getLine() : "");
                csv.append("\n");
            }
        }
        Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static class CliOptions {
        Path metamodel;
        Path extractionJson;
        Path outputDir;
        boolean strict;
        boolean details;
        Path reportPath;
        Path csvPath;
    }
}
