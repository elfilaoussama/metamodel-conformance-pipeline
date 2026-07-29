package com.verification.mapper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Utility to map java-metamodel SPOON JSON output to AlloyInEcore .aie instance models.
 */
public class JsonToAieMapper {

    public static void map(String inputJsonPath, String outputAiePath) throws Exception {
        Gson gson = new Gson();
        JsonObject root = gson.fromJson(new FileReader(inputJsonPath), JsonObject.class);
        JsonArray projects = root.getAsJsonArray("projects");

        StringBuilder sb = new StringBuilder();
        // The verifier parses instances via AlloyInEcoreParser.instance(null), whose grammar expects:
        //   (optional) 'instance <name>;' then imports then 'model ...;' then ONE root eObject.
        // This mapper therefore emits a synthetic root container that nests all mapped objects.
        sb.append("instance results;\n");
        sb.append("model class_hierarchies : 'ECORE_PATH';\n\n");
        sb.append("Root {\n");
        sb.append("  contents: {\n");

        if (projects != null && projects.size() > 0) {
            JsonObject project = projects.get(0).getAsJsonObject();
            JsonArray types = project.getAsJsonArray("types");
            if (types != null) {
                int limit = Math.min(types.size(), 50);
                for (int i = 0; i < limit; i++) {
                    JsonObject typeObj = types.get(i).getAsJsonObject();
                    mapClass(typeObj, sb);
                }
            }
        }
        sb.append("  }\n");
        sb.append("}\n");

        Files.createDirectories(Paths.get(outputAiePath).getParent());
        try (FileWriter writer = new FileWriter(outputAiePath)) {
            writer.write(sb.toString());
        }
    }

    private static void mapClass(JsonObject typeObj, StringBuilder sb) {
        String qualifiedName = typeObj.has("qualifiedName") ? typeObj.get("qualifiedName").getAsString() : "Unknown";
        String cid = qualifiedName.replace(".", "_");
        
        String kindRaw = typeObj.has("kind") ? typeObj.get("kind").getAsString() : "class";
        String kind = kindRaw.equals("interface") ? "Interface" : "ConcreteClass";
        
        boolean isAbstractRaw = typeObj.has("isAbstract") && typeObj.get("isAbstract").getAsBoolean();
        String isAbstract = isAbstractRaw ? "Yes" : "No";

        sb.append("    Class {\n");
        sb.append("      cid: \"").append(cid).append("\",\n");
        sb.append("      kind: ").append(kind).append(",\n");
        sb.append("      isAbstract: ").append(isAbstract).append(",\n");

        // Methods
        sb.append("      methods: {\n");
        JsonArray methods = typeObj.has("methods") ? typeObj.getAsJsonArray("methods") : new JsonArray();
        for (int i = 0; i < methods.size(); i++) {
            JsonObject m = methods.get(i).getAsJsonObject();
            mapMethod(m, cid + "_m" + i, sb);
        }
        sb.append("      }");

        // Attributes (Fields)
        JsonArray fields = typeObj.has("fields") ? typeObj.getAsJsonArray("fields") : new JsonArray();
        if (fields.size() > 0) {
            sb.append(",\n      attributes: {\n");
            for (int i = 0; i < fields.size(); i++) {
                JsonObject f = fields.get(i).getAsJsonObject();
                mapAttribute(f, cid + "_a" + i, sb);
            }
            sb.append("      }\n");
        } else {
            sb.append("\n");
        }

        sb.append("    }\n");
    }

    private static void mapMethod(JsonObject m, String mid, StringBuilder sb) {
        String mname = m.has("name") ? m.get("name").getAsString() : "unknown";
        String rtype = m.has("returnType") ? m.get("returnType").getAsString() : "void";
        
        String visibility = m.has("visibility") ? m.get("visibility").getAsString() : "public";
        String mvis = mapVisibility(visibility);
        
        boolean isStatic = m.has("isStatic") && m.get("isStatic").getAsBoolean();
        String mscope = isStatic ? "Static" : "Instance";
        
        boolean isAbs = m.has("isAbstract") && m.get("isAbstract").getAsBoolean();
        String isAbstract = isAbs ? "Yes" : "No";

        String msig = mname + "()";
        if (m.has("parameters")) {
            JsonArray params = m.getAsJsonArray("parameters");
            StringBuilder sig = new StringBuilder(mname).append("(");
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sig.append(", ");
                sig.append(params.get(i).getAsJsonObject().get("type").getAsString());
            }
            sig.append(")");
            msig = sig.toString();
        }

        sb.append("        Method {\n");
        sb.append("          mid: \"").append(mid).append("\",\n");
        sb.append("          mname: \"").append(mname).append("\",\n");
        sb.append("          msig: \"").append(msig).append("\",\n");
        sb.append("          mvis: ").append(mvis).append(",\n");
        sb.append("          mscope: ").append(mscope).append(",\n");
        sb.append("          rtype: \"").append(rtype).append("\",\n");
        sb.append("          isAbstract: ").append(isAbstract).append("\n");
        sb.append("        }\n");
    }

    private static void mapAttribute(JsonObject f, String aid, StringBuilder sb) {
        String aname = f.has("name") ? f.get("name").getAsString() : "unknown";
        String atype = f.has("type") ? f.get("type").getAsString() : "Object";
        
        String visibility = f.has("visibility") ? f.get("visibility").getAsString() : "public";
        String avis = mapVisibility(visibility);
        
        boolean isStatic = f.has("isStatic") && f.get("isStatic").getAsBoolean();
        String ascope = isStatic ? "Static" : "Instance";

        sb.append("        Attribute {\n");
        sb.append("          aid: \"").append(aid).append("\",\n");
        sb.append("          aname: \"").append(aname).append("\",\n");
        sb.append("          atype: \"").append(atype).append("\",\n");
        sb.append("          avis: ").append(avis).append(",\n");
        sb.append("          ascope: ").append(ascope).append("\n");
        sb.append("        }\n");
    }

    private static String mapVisibility(String vis) {
        switch (vis.toLowerCase()) {
            case "private": return "Priv";
            case "protected": return "Prot";
            case "package-private":
            case "package": return "Pkg";
            case "public":
            default: return "Pub";
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: mvn exec:java -Dexec.mainClass=\"com.verification.mapper.JsonToAieMapper\" -Dexec.args=\"<input.json> <output.aie>\"");
            return;
        }
        try {
            System.out.println("Mapping JSON " + args[0] + " to " + args[1] + "...");
            map(args[0], args[1]);
            System.out.println("Mapping complete.");
        } catch (Exception e) {
            System.err.println("Failed to map JSON");
            e.printStackTrace();
        }
    }
}
