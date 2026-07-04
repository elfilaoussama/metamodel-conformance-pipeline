package com.verification.mapper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class JsonToAieMapper {
    private final Gson gson = new Gson();

    public void map(Path extractionJson, Path aieOutput) throws IOException {
        String json = Files.readString(extractionJson, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        StringBuilder aie = new StringBuilder();

        aie.append("-- Root container\n");
        aie.append("Root = {\n");

        JsonArray types = root.getAsJsonArray("types");
        if (types == null) types = root.getAsJsonArray("typeModels");
        if (types == null) types = new JsonArray();

        Map<String, String> typeIds = new HashMap<>();
        List<JsonObject> typeList = new ArrayList<>();
        int classIndex = 0;

        for (JsonElement te : types) {
            JsonObject t = te.getAsJsonObject();
            String name = getString(t, "name", "Unknown_" + classIndex);
            String id = "Class" + classIndex;
            typeIds.put(name, id);
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
            aie.append("  Class").append(i).append(" = { name = \"")
                    .append(getString(t, "name", "Unknown_").replace("\"", "\\\""))
                    .append("\", abstract = ").append(getString(t, "abstract", "false"))
                    .append(" }\n");
        }

        int methodIndex = 0;
        int fieldIndex = 0;

        Map<String, List<String>> classMethods = new HashMap<>();
        Map<String, List<String>> classFields = new HashMap<>();

        for (JsonObject t : typeList) {
            String name = getString(t, "name", "Unknown");
            String id = typeIds.get(name);

            JsonArray methods = t.getAsJsonArray("methods");
            if (methods == null) methods = t.getAsJsonArray("executables");
            if (methods != null) {
                List<String> mids = new ArrayList<>();
                for (JsonElement me : methods) {
                    JsonObject m = me.getAsJsonObject();
                    String mid = "Method" + methodIndex;
                    mids.add(mid);
                    aie.append("  ").append(mid).append(" = { name = \"")
                            .append(getString(m, "name", "unknown"))
                            .append("\" }\n");
                    methodIndex++;
                }
                classMethods.put(id, mids);
            }

            JsonArray fields = t.getAsJsonArray("fields");
            if (fields != null) {
                List<String> fids = new ArrayList<>();
                for (JsonElement fe : fields) {
                    JsonObject f = fe.getAsJsonObject();
                    String fid = "Field" + fieldIndex;
                    fids.add(fid);
                    aie.append("  ").append(fid).append(" = { name = \"")
                            .append(getString(f, "name", "unknown"))
                            .append("\" }\n");
                    fieldIndex++;
                }
                classFields.put(id, fids);
            }
        }

        for (JsonObject t : typeList) {
            String name = getString(t, "name", "Unknown");
            String id = typeIds.get(name);
            String parent = getString(t, "superclass", null);
            String parentId = parent != null ? typeIds.get(parent) : null;

            aie.append("  classParent[").append(id).append("] = ")
                    .append(parentId != null ? parentId : "null").append("\n");

            List<String> mids = classMethods.getOrDefault(id, List.of());
            if (!mids.isEmpty()) {
                aie.append("  classMethods[").append(id).append("] = {");
                for (int i = 0; i < mids.size(); i++) {
                    if (i > 0) aie.append(", ");
                    aie.append(mids.get(i));
                }
                aie.append("}\n");
            }

            List<String> fids = classFields.getOrDefault(id, List.of());
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

    private static String getString(JsonObject obj, String key, String fallback) {
        JsonElement e = obj.get(key);
        if (e != null && !e.isJsonNull()) return e.getAsString();
        return fallback;
    }
}
