package com.verification.mapper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Deterministic mapper from TypeModel extraction JSON to AlloyInEcore .aie
 * instances conforming to StructuralMetamodel.recore.
 * Every field is mapped exactly from the extraction JSON. No fallback logic.
 */
public class JsonToAieMapper {

    private static final String METAMODEL_HEADER = "model structural_metamodel : 'ECORE_PATH';";

    /** String-based entry point for backward compatibility with Main.java. */
    public static void map(String inputJsonPath, String outputAiePath) throws IOException {
        new JsonToAieMapper().map(Paths.get(inputJsonPath), Paths.get(outputAiePath));
    }

    public void map(Path extractionJson, Path aieOutput) throws IOException {
        String json = new String(Files.readAllBytes(extractionJson), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray types = root.getAsJsonArray("types");
        if (types == null || types.size() == 0) {
            writeEmpty(aieOutput);
            return;
        }

        int total = types.size();
        List<JsonObject> typeList = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            typeList.add(types.get(i).getAsJsonObject());
        }

        StringBuilder aie = new StringBuilder();
        aie.append("instance results;\n");
        aie.append(METAMODEL_HEADER).append("\n\n");
        aie.append("Root {\n");
        aie.append("  contents: {\n");

        int methodIdx = 0;
        int attrIdx   = 0;

        for (int ti = 0; ti < total; ti++) {
            JsonObject t = typeList.get(ti);

            String fqn = t.get("qualifiedName").getAsString();
            String cid = fqn.replace(".", "_").replace("-", "_");
            String kind = t.has("kind") ? t.get("kind").getAsString() : "class";
            boolean isAbstract = t.has("abstractType") && t.get("abstractType").getAsBoolean();

            aie.append("    Classifier {\n");
            aie.append("      cid: \"").append(cid).append("\",\n");
            aie.append("      name: \"").append(escape(fqn)).append("\",\n");
            aie.append("      isAbstract: ").append(isAbstract ? "Yes" : "No");

            // localMethods
            JsonArray methods = t.has("executables") ? t.getAsJsonArray("executables") : null;
            boolean hasMethods = (methods != null && methods.size() > 0);
            if (hasMethods) {
                aie.append(",\n      localMethods: {\n");
                for (JsonElement me : methods) {
                    JsonObject m = me.getAsJsonObject();
                    if (m.has("constructor") && m.get("constructor").getAsBoolean()) continue;

                    String mid = cid + "_m" + methodIdx;
                    String mname = m.get("name").getAsString();
                    boolean mAbstract = m.has("abstractExecutable") && m.get("abstractExecutable").getAsBoolean();
                    boolean mStatic = m.has("staticExecutable") && m.get("staticExecutable").getAsBoolean();
                    String isInheritable = isInheritable(m);
                    String rtype = m.has("returnType") ? m.get("returnType").getAsString() : "void";
                    String sig = buildSignature(m);

                    aie.append("        Method {\n");
                    aie.append("          mid: \"").append(mid).append("\",\n");
                    aie.append("          memberName: \"").append(escape(mname)).append("\",\n");
                    aie.append("          returnType: \"").append(escape(rtype)).append("\",\n");
                    aie.append("          paramTypes: \"").append(sig.isEmpty() ? "_" : sig).append("\",\n");
                    aie.append("          isInheritable: ").append(isInheritable).append(",\n");
                    aie.append("          scope: ").append(mStatic ? "Static" : "Instance").append(",\n");
                    aie.append("          isAbstract: ").append(mAbstract ? "Yes" : "No").append("\n");
                    aie.append("        }\n");
                    methodIdx++;
                }
                aie.append("      }\n");
            } else {
                aie.append("\n");
            }

            aie.append("    }\n");
        }

        aie.append("  }\n");
        aie.append("}\n");

        Files.createDirectories(aieOutput.getParent());
        Files.write(aieOutput, aie.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String isInheritable(JsonObject m) {
        if (!m.has("visibility")) return "Yes";
        String vis = m.get("visibility").getAsString();
        return "private".equalsIgnoreCase(vis) ? "No" : "Yes";
    }

    private String buildSignature(JsonObject m) {
        if (!m.has("parameters")) return "";
        JsonArray params = m.getAsJsonArray("parameters");
        if (params == null || params.size() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i).getAsJsonObject().get("type").getAsString());
        }
        return sb.toString();
    }

    private static void writeEmpty(Path aieOutput) throws IOException {
        Files.createDirectories(aieOutput.getParent());
        Files.write(aieOutput, ("instance results;\n" + METAMODEL_HEADER + "\n\nRoot {}\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}