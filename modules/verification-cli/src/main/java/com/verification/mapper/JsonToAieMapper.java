package com.verification.mapper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Maps TypeModel extraction JSON to AlloyInEcore .aie instances
 * conforming to the StructuralMetamodel.recore specification.
 * Deterministic field mapping with no fallback logic.
 */
public class JsonToAieMapper {

    private static final String METAMODEL_HEADER = "model structural_metamodel : 'ECORE_PATH';";

    public void map(Path extractionJson, Path aieOutput) throws IOException {
        String json = Files.readString(extractionJson);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray types = root.getAsJsonArray("types");
        if (types == null || types.size() == 0) {
            writeEmpty(aieOutput);
            return;
        }

        int total = types.size();
        Map<String, Integer> fqnToIndex = new HashMap<>();

        // Pre-scan: build FQN → index map
        for (int i = 0; i < total; i++) {
            JsonObject t = types.get(i).getAsJsonObject();
            String fqn = t.get("qualifiedName").getAsString();
            fqnToIndex.put(fqn, i);
        }

        StringBuilder aie = new StringBuilder();
        aie.append("instance results;\n");
        aie.append(METAMODEL_HEADER).append("\n\n");
        aie.append("Root {\n");
        aie.append("  contents: {\n");

        int methodIdx = 0;
        int attrIdx = 0;

        for (int ti = 0; ti < total; ti++) {
            JsonObject t = types.get(ti).getAsJsonObject();

            String fqn = t.get("qualifiedName").getAsString();
            String cid = fqn.replace(".", "_").replace("-", "_");
            String kind = t.has("kind") ? t.get("kind").getAsString() : "class";
            boolean isAbstract = t.has("abstractType") && t.get("abstractType").getAsBoolean();

            aie.append("    Classifier {\n");
            aie.append("      cid: \"").append(cid).append("\",\n");
            aie.append("      name: \"").append(escape(fqn)).append("\",\n");
            aie.append("      isAbstract: ").append(isAbstract ? "Yes" : "No").append(",\n");

            // classParent
            if (t.has("superClass") && !t.get("superClass").isJsonNull()) {
                String superCls = t.get("superClass").getAsString();
                Integer pi = fqnToIndex.get(superCls);
                if (pi != null) {
                    String pFqn = types.get(pi).getAsJsonObject().get("qualifiedName").getAsString();
                    aie.append("      classParent: //").append(dotToUnderscore(pFqn)).append("\n");
                }
            }

            // interfaceParents
            if (t.has("interfaces") && !t.get("interfaces").isJsonNull()) {
                JsonArray interfaces = t.getAsJsonArray("interfaces");
                if (interfaces.size() > 0) {
                    aie.append("      interfaceParents: {\n");
                    for (JsonElement ie : interfaces) {
                        String intfName = ie.getAsString();
                        Integer pi = fqnToIndex.get(intfName);
                        if (pi != null) {
                            String pFqn = types.get(pi).getAsJsonObject().get("qualifiedName").getAsString();
                            aie.append("        //").append(dotToUnderscore(pFqn)).append("\n");
                        }
                    }
                    aie.append("      },\n");
                }
            }

            // localMethods
            JsonArray methods = t.has("executables") ? t.getAsJsonArray("executables") : null;
            boolean hasMethods = (methods != null && methods.size() > 0);
            if (hasMethods) {
                aie.append("      localMethods: {\n");
                for (JsonElement me : methods) {
                    JsonObject m = me.getAsJsonObject();
                    if (m.has("constructor") && m.get("constructor").getAsBoolean()) continue;

                    String mid = cid + "_m" + methodIdx;
                    String mname = m.get("name").getAsString();
                    boolean mAbstract = m.has("abstractExecutable") && m.get("abstractExecutable").getAsBoolean();
                    boolean mStatic = m.has("staticExecutable") && m.get("staticExecutable").getAsBoolean();
                    String vis = mapVisibility(m.has("visibility") ? m.get("visibility").getAsString() : "public");
                    String rtype = m.has("returnType") ? m.get("returnType").getAsString() : "void";
                    String sig = buildSignature(m);

                    aie.append("        Method {\n");
                    aie.append("          mid: \"").append(mid).append("\",\n");
                    aie.append("          memberName: \"").append(escape(mname)).append("\",\n");
                    aie.append("          paramTypes: \"").append(sig).append("\",\n");
                    aie.append("          returnType: \"").append(escape(rtype)).append("\",\n");
                    aie.append("          visibility: ").append(vis).append(",\n");
                    aie.append("          scope: ").append(mStatic ? "Static" : "Instance").append(",\n");
                    aie.append("          isAbstract: ").append(mAbstract ? "Yes" : "No").append("\n");
                    aie.append("        }\n");
                    methodIdx++;
                }
                aie.append("      }");
            }

            // localAttributes
            JsonArray fields = t.has("fields") ? t.getAsJsonArray("fields") : null;
            boolean hasFields = (fields != null && fields.size() > 0);
            if (hasFields) {
                if (hasMethods) aie.append(",\n");
                aie.append("      localAttributes: {\n");
                for (JsonElement fe : fields) {
                    JsonObject f = fe.getAsJsonObject();
                    String aid = cid + "_a" + attrIdx;
                    String aname = f.get("name").getAsString();
                    String atype = f.has("type") ? f.get("type").getAsString() : "Object";
                    boolean aStatic = f.has("staticField") && f.get("staticField").getAsBoolean();
                    String vis = mapVisibility(f.has("visibility") ? f.get("visibility").getAsString() : "public");

                    aie.append("        Attribute {\n");
                    aie.append("          aid: \"").append(aid).append("\",\n");
                    aie.append("          memberName: \"").append(escape(aname)).append("\",\n");
                    aie.append("          type: \"").append(escape(atype)).append("\",\n");
                    aie.append("          visibility: ").append(vis).append(",\n");
                    aie.append("          scope: ").append(aStatic ? "Static" : "Instance").append("\n");
                    aie.append("        }\n");
                    attrIdx++;
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
        Files.writeString(aieOutput, aie.toString());
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

    private String mapVisibility(String vis) {
        if (vis == null) return "Pub";
        switch (vis.toLowerCase()) {
            case "private": return "Priv";
            case "protected": return "Prot";
            case "package": return "Pkg";
            default: return "Pub";
        }
    }

    private static String dotToUnderscore(String fqn) {
        return fqn.replace(".", "_").replace("-", "_");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeEmpty(Path aieOutput) throws IOException {
        Files.createDirectories(aieOutput.getParent());
        Files.writeString(aieOutput, "instance results;\n" + METAMODEL_HEADER + "\n\nRoot {}\n");
    }
}