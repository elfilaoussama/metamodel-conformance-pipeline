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
 * Deterministic mapper from TypeModel JSON to the AIE format expected
 * by the Java InvariantChecker parser (Root = { classifiers = {...} }).
 */
public class JsonToAieMapper {

    private static final String METAMODEL_HEADER = "model structural_metamodel : 'ECORE_PATH';";

    public static void map(String inputJsonPath, String outputAiePath) throws IOException {
        new JsonToAieMapper().map(Paths.get(inputJsonPath), Paths.get(outputAiePath));
    }

    public void map(Path extractionJson, Path aieOutput) throws IOException {
        String json = new String(Files.readAllBytes(extractionJson), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray types = root.getAsJsonArray("types");
        if (types == null || types.size() == 0) { writeEmpty(aieOutput); return; }

        int total = types.size();
        List<JsonObject> tl = new ArrayList<>(total);
        Map<String, Integer> fqnIdx = new HashMap<>();
        for (int i = 0; i < total; i++) {
            JsonObject t = types.get(i).getAsJsonObject(); tl.add(t);
            fqnIdx.put(t.get("qualifiedName").getAsString(), i);
        }

        StringBuilder aie = new StringBuilder();
        aie.append("instance results;\n");
        aie.append(METAMODEL_HEADER).append("\n\n");
        aie.append("Root = {\n");

        // Classifier atoms list
        aie.append("  classifiers = {");
        for (int i = 0; i < total; i++) {
            if (i > 0) aie.append(", ");
            aie.append("Classifier").append(i);
        }
        aie.append("}\n");

        // Classifier objects — inside Root block
        for (int i = 0; i < total; i++) {
            JsonObject t = tl.get(i);
            String fqn = t.get("qualifiedName").getAsString();
            String cid = fqn.replace(".", "_").replace("-", "_");
            boolean abs = t.has("abstractType") && t.get("abstractType").getAsBoolean();
            aie.append("Classifier").append(i).append(" = {")
              .append(" cid = \"").append(cid).append("\",")
              .append(" name = \"").append(esc(fqn)).append("\",")
              .append(" isAbstract = ").append(abs ? "Yes" : "No")
              .append(" }\n");
        }
        aie.append("\n");

        // Method objects and localMethods tuples
        int midx = 0;
        for (int i = 0; i < total; i++) {
            JsonObject t = tl.get(i);
            JsonArray methods = t.has("executables") ? t.getAsJsonArray("executables") : null;
            if (methods == null || methods.size() == 0) continue;
            List<String> mids = new ArrayList<>();
            for (JsonElement me : methods) {
                JsonObject m = me.getAsJsonObject();
                if (m.has("constructor") && m.get("constructor").getAsBoolean()) { midx++; continue; }
                String mid = "Method" + midx;
                mids.add(mid);
                aie.append(mid).append(" = {")
                  .append(" mid = \"").append(mid).append("\",")
                  .append(" memberName = \"").append(esc(m.get("name").getAsString())).append("\",")
                  .append(" returnType = \"").append(esc(getStr(m, "returnType", "void"))).append("\",")
                  .append(" paramTypes = \"").append(buildSig(m)).append("\",")
                  .append(" isInheritable = ").append(isInheritable(m)).append(",")
                  .append(" scope = ").append(hasBool(m, "staticExecutable") ? "Static" : "Instance").append(",")
                  .append(" isAbstract = ").append(hasBool(m, "abstractExecutable") ? "Yes" : "No")
                  .append(" }\n");
                midx++;
            }
            aie.append("localMethods[Classifier").append(i).append("] = { ");
            for (int j = 0; j < mids.size(); j++) {
                if (j > 0) aie.append(", ");
                aie.append(mids.get(j));
            }
            aie.append(" }\n");
        }

        // Attribute objects and localAttributes tuples (from fields)
        int aidx = 0;
        for (int i = 0; i < total; i++) {
            JsonObject t = tl.get(i);
            JsonArray fields = t.has("fields") ? t.getAsJsonArray("fields") : null;
            if (fields == null || fields.size() == 0) continue;
            List<String> aids = new ArrayList<>();
            for (JsonElement fe : fields) {
                JsonObject f = fe.getAsJsonObject();
                String aid = "Attribute" + aidx;
                aids.add(aid);
                aie.append(aid).append(" = {")
                  .append(" aid = \"").append(aid).append("\",")
                  .append(" memberName = \"").append(esc(f.get("name").getAsString())).append("\",")
                  .append(" type = \"").append(esc(getStr(f, "type", "Object"))).append("\",")
                  .append(" isInheritable = ").append(isInheritable(f)).append(",")
                  .append(" scope = ").append(hasBool(f, "staticField") ? "Static" : "Instance")
                  .append(" }\n");
                aidx++;
            }
            aie.append("localAttributes[Classifier").append(i).append("] = { ");
            for (int j = 0; j < aids.size(); j++) {
                if (j > 0) aie.append(", ");
                aie.append(aids.get(j));
            }
            aie.append(" }\n");
        }

        // parents tuples
        for (int i = 0; i < total; i++) {
            JsonObject t = tl.get(i);
            if (!t.has("superClass") || t.get("superClass").isJsonNull()) continue;
            Integer pi = fqnIdx.get(t.get("superClass").getAsString());
            if (pi == null) continue;
            aie.append("parents[Classifier").append(i).append("] = { Classifier").append(pi).append(" }\n");
        }

        // MethodBody + ImplementationBinding for non-abstract methods
        midx = 0;
        int bdx = 0;
        List<String> bodyList = new ArrayList<>();
        List<String> bindingList = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            JsonObject t = tl.get(i);
            JsonArray methods = t.has("executables") ? t.getAsJsonArray("executables") : null;
            if (methods == null) continue;
            for (JsonElement me : methods) {
                JsonObject m = me.getAsJsonObject();
                if (m.has("constructor") && m.get("constructor").getAsBoolean()) { midx++; continue; }
                if (hasBool(m, "abstractExecutable")) { midx++; continue; }
                String mb = "MethodBody" + bdx;
                String ib = "ImplementationBinding" + bdx;
                bodyList.add(mb);
                bindingList.add(ib);
                aie.append(mb).append(" = { }\n");
                aie.append("implementer[").append(ib).append("] = { Classifier").append(i).append(" }\n");
                aie.append("target[").append(ib).append("] = { Method").append(midx).append(" }\n");
                aie.append("body[").append(ib).append("] = { ").append(mb).append(" }\n");
                bdx++; midx++;
            }
        }
        // List bodies and bindings in Root compositions
        aie.append("  bodies = {");
        for (int i = 0; i < bodyList.size(); i++) {
            if (i > 0) aie.append(", ");
            aie.append(bodyList.get(i));
        }
        aie.append("}\n");
        aie.append("  bindings = {");
        for (int i = 0; i < bindingList.size(); i++) {
            if (i > 0) aie.append(", ");
            aie.append(bindingList.get(i));
        }
        aie.append("}\n");

        aie.append("}\n"); // close Root

        Files.createDirectories(aieOutput.getParent());
        Files.write(aieOutput, aie.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String buildSig(JsonObject m) {
        if (!m.has("parameters")) return "_";
        JsonArray p = m.getAsJsonArray("parameters");
        if (p == null || p.size() == 0) return "_";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(p.get(i).getAsJsonObject().get("type").getAsString());
        }
        return sb.toString();
    }

    private String isInheritable(JsonObject obj) {
        if (!obj.has("visibility")) return "Yes";
        return "private".equalsIgnoreCase(obj.get("visibility").getAsString()) ? "No" : "Yes";
    }

    private String getStr(JsonObject obj, String key, String def) {
        return obj.has(key) ? obj.get(key).getAsString() : def;
    }

    private boolean hasBool(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).getAsBoolean();
    }

    private String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    private void writeEmpty(Path aieOutput) throws IOException {
        Files.createDirectories(aieOutput.getParent());
        Files.write(aieOutput, ("instance results;\n" + METAMODEL_HEADER + "\n\nRoot = { classifiers = {} }\n").getBytes(StandardCharsets.UTF_8));
    }
}
