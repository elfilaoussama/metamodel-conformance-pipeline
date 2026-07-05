package com.verification.mapper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class JsonToAieMapper {

    public void map(Path extractionJson, Path aieOutput) throws IOException {
        String json = Files.readString(extractionJson, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        StringBuilder aie = new StringBuilder();

        aie.append("-- Structural kernel instance\n");
        aie.append("Root = {\n");

        JsonArray types = root.getAsJsonArray("types");
        if (types == null) types = root.getAsJsonArray("typeModels");
        if (types == null) types = new JsonArray();

        List<String> typeIds = new ArrayList<>();
        List<JsonObject> typeList = new ArrayList<>();
        int classIndex = 0;

        for (JsonElement te : types) {
            JsonObject t = te.getAsJsonObject();
            typeList.add(t);
            classIndex++;
        }

        aie.append("  classes = {");
        for (int i = 0; i < typeList.size(); i++) {
            if (i > 0) aie.append(", ");
            aie.append("Class").append(i);
        }
        aie.append("}\n");

        for (int i = 0; i < typeList.size(); i++) {
            JsonObject t = typeList.get(i);
            String tname = getString(t, "qualifiedName", getString(t, "name", "Unknown_"));
            String abstractStr = getString(t, "abstractType", getString(t, "abstract", "false"));
            String kind = typeKind(t);
            aie.append("  Class").append(i).append(" = { name = \"")
                    .append(tname.replace("\"", "\\\""))
                    .append("\", abstract = ").append(abstractStr)
                    .append(", kind = \"").append(kind).append("\"")
                    .append(" }\n");
        }

        int methodIndex = 0;
        int fieldIndex = 0;

        Map<Integer, List<String>> classMethods = new HashMap<>();
        Map<Integer, List<String>> classFields = new HashMap<>();

        for (int ti = 0; ti < typeList.size(); ti++) {
            JsonObject t = typeList.get(ti);

            JsonArray executables = t.getAsJsonArray("executables");
            if (executables == null) executables = t.getAsJsonArray("methods");
            if (executables != null) {
                List<String> mids = new ArrayList<>();
                for (JsonElement me : executables) {
                    JsonObject m = me.getAsJsonObject();
                    if (getBoolean(m, "constructor", false)) continue;
                    String mid = "Method" + methodIndex;
                    mids.add(mid);
                    String mname = getString(m, "name", "unknown");
                    String mabstract = getString(m, "abstractExecutable", getString(m, "abstract", "false"));
                    String mvis = getString(m, "visibility", "public");
                    String mstatic = getString(m, "staticExecutable", "false");
                    aie.append("  ").append(mid).append(" = { name = \"")
                            .append(mname.replace("\"", "\\\""))
                            .append("\", abstract = ").append(mabstract)
                            .append(", visibility = \"").append(mvis).append("\"")
                            .append(", static = ").append(mstatic)
                            .append(" }\n");
                    methodIndex++;
                }
                classMethods.put(ti, mids);
            }

            JsonArray fields = t.getAsJsonArray("fields");
            if (fields != null) {
                List<String> fids = new ArrayList<>();
                for (JsonElement fe : fields) {
                    JsonObject f = fe.getAsJsonObject();
                    String fid = "Field" + fieldIndex;
                    fids.add(fid);
                    String fname = getString(f, "name", "unknown");
                    String fvis = getString(f, "visibility", "public");
                    String fstatic = getString(f, "staticField", "false");
                    aie.append("  ").append(fid).append(" = { name = \"")
                            .append(fname.replace("\"", "\\\""))
                            .append("\", visibility = \"").append(fvis).append("\"")
                            .append(", static = ").append(fstatic)
                            .append(" }\n");
                    fieldIndex++;
                }
                classFields.put(ti, fids);
            }
        }

        for (int ti = 0; ti < typeList.size(); ti++) {
            JsonObject t = typeList.get(ti);
            String id = "Class" + ti;
            String parent = getString(t, "superClass", getString(t, "superclass", null));
            String parentId = null;
            if (parent != null) {
                for (int pi = 0; pi < typeList.size(); pi++) {
                    String pname = getString(typeList.get(pi), "qualifiedName", getString(typeList.get(pi), "name", ""));
                    if (parent.equals(pname)) {
                        parentId = "Class" + pi;
                        break;
                    }
                }
            }

            aie.append("  classParent[").append(id).append("] = ")
                    .append(parentId != null ? parentId : "null").append("\n");

            List<String> mids = classMethods.getOrDefault(ti, List.of());
            if (!mids.isEmpty()) {
                aie.append("  classMethods[").append(id).append("] = {");
                for (int i = 0; i < mids.size(); i++) {
                    if (i > 0) aie.append(", ");
                    aie.append(mids.get(i));
                }
                aie.append("}\n");
            }

            List<String> fids = classFields.getOrDefault(ti, List.of());
            if (!fids.isEmpty()) {
                aie.append("  classAttributes[").append(id).append("] = {");
                for (int i = 0; i < fids.size(); i++) {
                    if (i > 0) aie.append(", ");
                    aie.append(fids.get(i));
                }
                aie.append("}\n");
            }
        }

        aie.append("}\n");

        Files.writeString(aieOutput, aie.toString(), StandardCharsets.UTF_8);
    }

    private static String typeKind(JsonObject t) {
        String kind = getString(t, "kind", "class");
        if ("enum".equals(kind) || "record".equals(kind) || "annotation".equals(kind)) {
            return "class";
        }
        return kind;
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        JsonElement e = obj.get(key);
        if (e != null && !e.isJsonNull()) return e.getAsString();
        return fallback;
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean fallback) {
        JsonElement e = obj.get(key);
        if (e != null && !e.isJsonNull()) return e.getAsBoolean();
        return fallback;
    }
}
