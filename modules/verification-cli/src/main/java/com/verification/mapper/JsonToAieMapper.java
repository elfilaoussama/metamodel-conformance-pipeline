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

        aie.append("-- Structural kernel v2 instance\n");
        aie.append("Root = {\n");

        JsonArray types = root.getAsJsonArray("types");
        if (types == null) types = root.getAsJsonArray("typeModels");
        if (types == null) types = new JsonArray();

        int total = types.size();

        List<Boolean> isInterface = new ArrayList<>();
        List<Boolean> isClass = new ArrayList<>();
        List<String> atomIds = new ArrayList<>();
        List<JsonObject> typeList = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            JsonObject t = types.get(i).getAsJsonObject();
            typeList.add(t);
            String kind = getString(t, "kind", "class");
            if ("interface".equals(kind)) {
                atomIds.add("Interface" + i);
                isInterface.add(true);
                isClass.add(false);
            } else {
                atomIds.add("Class" + i);
                isInterface.add(false);
                isClass.add(true);
            }
        }

        aie.append("  classifiers = {");
        for (int i = 0; i < total; i++) {
            if (i > 0) aie.append(", ");
            aie.append(atomIds.get(i));
        }
        aie.append("}\n");

        for (int i = 0; i < total; i++) {
            JsonObject t = typeList.get(i);
            String tname = getString(t, "qualifiedName", getString(t, "name", "Unknown_"));
            String abstractStr = booleanToString(getString(t, "abstractType", getString(t, "abstract", "false")));
            aie.append("  ").append(atomIds.get(i)).append(" = { name = \"")
                    .append(tname.replace("\"", "\\\""))
                    .append("\", isAbstract = ").append(abstractStr)
                    .append(", cid = ").append("ClassifierID").append(i)
                    .append(" }\n");
        }

        int methodIndex = 0;
        int fieldIndex = 0;
        int bodyIndex = 0;
        int bindingIndex = 0;

        Map<Integer, List<String>> localMethods = new HashMap<>();
        Map<Integer, List<String>> localAttrs = new HashMap<>();
        Map<String, Map<String, String>> allMethodAttrs = new LinkedHashMap<>();
        Map<String, Map<String, String>> allAttrAttrs = new LinkedHashMap<>();

        for (int ti = 0; ti < total; ti++) {
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
                    String mabstract = booleanToString(getString(m, "abstractExecutable", getString(m, "abstract", "false")));
                    String mvis = visibilityToEnum(getString(m, "visibility", "public"));
                    String mstatic = getString(m, "staticExecutable", "false");
                    String scope = booleanToScope(mstatic);
                    String returnType = getString(m, "returnType", "unknown");

                    Map<String, String> attrs = new LinkedHashMap<>();
                    attrs.put("memberName", mname);
                    attrs.put("returnType", returnType);
                    attrs.put("isAbstract", mabstract);
                    attrs.put("visibility", mvis);
                    attrs.put("scope", scope);
                    attrs.put("mid", "MethodID" + methodIndex);
                    allMethodAttrs.put(mid, attrs);

                    String paramTypesStr = buildParamTypes(m);
                    aie.append("  ").append(mid).append(" = { memberName = \"")
                            .append(mname.replace("\"", "\\\""))
                            .append("\", returnType = \"").append(returnType).append("\"")
                            .append(", isAbstract = ").append(mabstract)
                            .append(", visibility = ").append(mvis)
                            .append(", scope = ").append(scope)
                            .append(", mid = ").append("MethodID").append(methodIndex)
                            .append(", paramTypes = ").append(paramTypesStr)
                            .append(" }\n");
                    methodIndex++;
                }
                localMethods.put(ti, mids);
            }

            JsonArray fields = t.getAsJsonArray("fields");
            if (fields != null) {
                List<String> fids = new ArrayList<>();
                for (JsonElement fe : fields) {
                    JsonObject f = fe.getAsJsonObject();
                    String fid = "Attribute" + fieldIndex;
                    fids.add(fid);
                    String fname = getString(f, "name", "unknown");
                    String fvis = visibilityToEnum(getString(f, "visibility", "public"));
                    String fstatic = getString(f, "staticField", "false");
                    String scope = booleanToScope(fstatic);
                    String ftype = getString(f, "type", "unknown");

                    Map<String, String> attrs = new LinkedHashMap<>();
                    attrs.put("memberName", fname);
                    attrs.put("type", ftype);
                    attrs.put("visibility", fvis);
                    attrs.put("scope", scope);
                    attrs.put("aid", "AttributeID" + fieldIndex);
                    allAttrAttrs.put(fid, attrs);

                    aie.append("  ").append(fid).append(" = { memberName = \"")
                            .append(fname.replace("\"", "\\\""))
                            .append("\", type = \"").append(ftype).append("\"")
                            .append(", visibility = ").append(fvis)
                            .append(", scope = ").append(scope)
                            .append(", aid = ").append("AttributeID").append(fieldIndex)
                            .append(" }\n");
                    fieldIndex++;
                }
                localAttrs.put(ti, fids);
            }
        }

        List<String> bodyIds = new ArrayList<>();
        List<String> bindingIds = new ArrayList<>();
        Map<Integer, List<String>> bindingsForClassifier = new HashMap<>();

        for (int ti = 0; ti < total; ti++) {
            if (!isClass.get(ti)) continue;
            List<String> mids = localMethods.getOrDefault(ti, List.of());
            List<String> classBindings = new ArrayList<>();
            for (String mid : mids) {
                Map<String, String> mattrs = allMethodAttrs.get(mid);
                if (mattrs == null) continue;
                if ("Yes".equals(mattrs.get("isAbstract"))) continue;
                String bodyId = "MethodBody" + bodyIndex;
                String bindingId = "ImplementationBinding" + bindingIndex;
                bodyIds.add(bodyId);
                bindingIds.add(bindingId);
                classBindings.add(bindingId);
                aie.append("  ").append(bodyId).append(" = {}\n");
                aie.append("  ").append(bindingId).append(" = { implementer = ").append(atomIds.get(ti))
                        .append(", target = ").append(mid)
                        .append(", body = ").append(bodyId)
                        .append(" }\n");
                bodyIndex++;
                bindingIndex++;
            }
            if (!classBindings.isEmpty()) {
                bindingsForClassifier.put(ti, classBindings);
            }
        }

        Map<Integer, Integer> parentClassMap = new HashMap<>();
        Map<Integer, List<String>> parentInterfacesMap = new HashMap<>();

        for (int ti = 0; ti < total; ti++) {
            JsonObject t = typeList.get(ti);
            String superClass = getString(t, "superClass", getString(t, "superclass", null));
            if (superClass != null) {
                for (int pi = 0; pi < total; pi++) {
                    JsonObject pt = typeList.get(pi);
                    String pQualName = getString(pt, "qualifiedName", null);
                    String pSimpleName = getString(pt, "simpleName", null);
                    if (superClass.equals(pQualName)
                            || (pSimpleName != null && superClass.equals(pSimpleName))
                            || superClass.equals(getString(pt, "name", null))) {
                        parentClassMap.put(ti, pi);
                        break;
                    }
                }
            }

            JsonArray interfaces = t.getAsJsonArray("interfaces");
            if (interfaces != null && interfaces.size() > 0) {
                List<String> intfIds = new ArrayList<>();
                for (JsonElement ie : interfaces) {
                    String intfName = ie.getAsString();
                    for (int pi = 0; pi < total; pi++) {
                        JsonObject pt = typeList.get(pi);
                        String pQualName = getString(pt, "qualifiedName", null);
                        String pSimpleName = getString(pt, "simpleName", null);
                    if (intfName.equals(pQualName)
                            || (pSimpleName != null && intfName.equals(pSimpleName))
                            || intfName.equals(getString(pt, "name", null))) {
                            intfIds.add(atomIds.get(pi));
                            break;
                        }
                    }
                }
                if (!intfIds.isEmpty()) {
                    parentInterfacesMap.put(ti, intfIds);
                }
            }
        }

        List<Set<Integer>> ancestorsCache = new ArrayList<>();
        for (int ti = 0; ti < total; ti++) {
            ancestorsCache.add(computeAncestors(ti, parentClassMap, parentInterfacesMap));
        }

        for (int ti = 0; ti < total; ti++) {
            String id = atomIds.get(ti);

            Integer pc = parentClassMap.get(ti);
            aie.append("  classParent[").append(id).append("] = ")
                    .append(pc != null ? atomIds.get(pc) : "null").append("\n");

            List<String> intfs = parentInterfacesMap.get(ti);
            if (intfs != null && !intfs.isEmpty()) {
                aie.append("  interfaceParents[").append(id).append("] = {");
                for (int i = 0; i < intfs.size(); i++) {
                    if (i > 0) aie.append(", ");
                    aie.append(intfs.get(i));
                }
                aie.append("}\n");
            }

            List<String> mids = localMethods.getOrDefault(ti, List.of());
            if (!mids.isEmpty()) {
                aie.append("  localMethods[").append(id).append("] = {");
                for (int i = 0; i < mids.size(); i++) {
                    if (i > 0) aie.append(", ");
                    aie.append(mids.get(i));
                }
                aie.append("}\n");
            }

            List<String> fids = localAttrs.getOrDefault(ti, List.of());
            if (!fids.isEmpty()) {
                aie.append("  localAttributes[").append(id).append("] = {");
                for (int i = 0; i < fids.size(); i++) {
                    if (i > 0) aie.append(", ");
                    aie.append(fids.get(i));
                }
                aie.append("}\n");
            }

            Set<Integer> ancestors = ancestorsCache.get(ti);
            computeAndWriteInherited(aie, ti, id, ancestors, typeList, total,
                    localMethods, allMethodAttrs, localAttrs, allAttrAttrs,
                    atomIds, ancestorsCache, isInterface,
                    parentClassMap, parentInterfacesMap);
        }

        aie.append("}\n");
        Files.writeString(aieOutput, aie.toString(), StandardCharsets.UTF_8);
    }

    private void computeAndWriteInherited(StringBuilder aie, int ti, String id,
            Set<Integer> ancestors, List<JsonObject> typeList, int total,
            Map<Integer, List<String>> localMethods, Map<String, Map<String, String>> allMethodAttrs,
            Map<Integer, List<String>> localAttrs, Map<String, Map<String, String>> allAttrAttrs,
            List<String> atomIds, List<Set<Integer>> ancestorsCache,
            List<Boolean> isInterface,
            Map<Integer, Integer> parentClassMap, Map<Integer, List<String>> parentInterfacesMap) {

        List<String> inheritedMethods = computeInheritedMethods(ti, ancestors, typeList,
                localMethods, allMethodAttrs, atomIds, ancestorsCache, isInterface,
                parentClassMap, parentInterfacesMap);
        if (!inheritedMethods.isEmpty()) {
            aie.append("  inheritedMethods[").append(id).append("] = {");
            for (int i = 0; i < inheritedMethods.size(); i++) {
                if (i > 0) aie.append(", ");
                aie.append(inheritedMethods.get(i));
            }
            aie.append("}\n");
        }

        List<String> inheritedAttrs = computeInheritedAttributes(ti, ancestors, typeList,
                localAttrs, allAttrAttrs, atomIds, ancestorsCache, isInterface,
                parentClassMap, parentInterfacesMap);
        if (!inheritedAttrs.isEmpty()) {
            aie.append("  inheritedAttributes[").append(id).append("] = {");
            for (int i = 0; i < inheritedAttrs.size(); i++) {
                if (i > 0) aie.append(", ");
                aie.append(inheritedAttrs.get(i));
            }
            aie.append("}\n");
        }
    }

    private List<String> computeInheritedMethods(int ti, Set<Integer> ancestors,
            List<JsonObject> typeList, Map<Integer, List<String>> localMethods,
            Map<String, Map<String, String>> allMethodAttrs,
            List<String> atomIds, List<Set<Integer>> ancestorsCache,
            List<Boolean> isInterface,
            Map<Integer, Integer> parentClassMap, Map<Integer, List<String>> parentInterfacesMap) {

        Set<Integer> sorted = new LinkedHashSet<>();
        List<Integer> topo = new ArrayList<>();
        collectNodes(ancestors, typeList, parentClassMap, parentInterfacesMap, topo);
        for (int a : topo) sorted.add(a);

        Map<String, String> seen = new LinkedHashMap<>();

        for (int ai : sorted) {
            List<String> mids = localMethods.getOrDefault(ai, List.of());
            for (String mid : mids) {
                Map<String, String> attrs = allMethodAttrs.get(mid);
                if (attrs == null) continue;
                if ("Priv".equals(attrs.get("visibility"))) continue;
                String key = methodKey(attrs);
                if (key == null) continue;
                // Ascending depth order (root first): later entries are nearer
                // to the target class and always override earlier ones with the
                // same key, matching O-04 nearer-ancestor priority.
                seen.put(key, mid);
            }
        }

        Map<String, String> localMethodKeys = new LinkedHashMap<>();
        List<String> lms = localMethods.getOrDefault(ti, List.of());
        for (String lm : lms) {
            Map<String, String> a = allMethodAttrs.get(lm);
            if (a != null) localMethodKeys.put(methodKey(a), lm);
        }

        List<String> result = new ArrayList<>(seen.values());
        result.removeIf(mid -> localMethodKeys.containsKey(allMethodAttrs.get(mid) != null
                ? methodKey(allMethodAttrs.get(mid)) : null));
        return result;
    }

    private List<String> computeInheritedAttributes(int ti, Set<Integer> ancestors,
            List<JsonObject> typeList, Map<Integer, List<String>> localAttrs,
            Map<String, Map<String, String>> allAttrAttrs,
            List<String> atomIds, List<Set<Integer>> ancestorsCache,
            List<Boolean> isInterface,
            Map<Integer, Integer> parentClassMap, Map<Integer, List<String>> parentInterfacesMap) {

        Set<Integer> sorted = new LinkedHashSet<>();
        List<Integer> topo = new ArrayList<>();
        collectNodes(ancestors, typeList, parentClassMap, parentInterfacesMap, topo);
        for (int a : topo) sorted.add(a);

        Map<String, String> seen = new LinkedHashMap<>();

        for (int ai : sorted) {
            List<String> fids = localAttrs.getOrDefault(ai, List.of());
            for (String fid : fids) {
                Map<String, String> attrs = allAttrAttrs.get(fid);
                if (attrs == null) continue;
                if ("Priv".equals(attrs.get("visibility"))) continue;
                String key = attrs.get("memberName");
                if (key == null) continue;
                String earlier = seen.get(key);
                if (earlier != null) {
                    int earlierIdx = findAncestorContainingAttr(earlier, ancestors, typeList,
                            localAttrs, allAttrAttrs, atomIds);
                    if (earlierIdx >= 0 && ancestors.contains(earlierIdx)) {
                        Set<Integer> earlierAncs = ancestorsCache.get(earlierIdx);
                        if (earlierAncs != null && earlierAncs.contains(ai)) {
                            seen.put(key, fid);
                        }
                    }
                } else {
                    seen.put(key, fid);
                }
            }
        }

        Map<String, String> localAttrKeys = new LinkedHashMap<>();
        List<String> las = localAttrs.getOrDefault(ti, List.of());
        for (String la : las) {
            Map<String, String> a = allAttrAttrs.get(la);
            if (a != null) localAttrKeys.put(a.get("memberName"), la);
        }

        List<String> result = new ArrayList<>(seen.values());
        result.removeIf(fid -> localAttrKeys.containsKey(allAttrAttrs.get(fid) != null
                ? allAttrAttrs.get(fid).get("memberName") : null));
        return result;
    }

    private int findAncestorContaining(String methodId, Set<Integer> ancestors,
            List<JsonObject> typeList, Map<Integer, List<String>> localMethods,
            Map<String, Map<String, String>> allMethodAttrs,
            List<String> atomIds) {
        for (int ai : ancestors) {
            List<String> mids = localMethods.getOrDefault(ai, List.of());
            if (mids.contains(methodId)) return ai;
        }
        return -1;
    }

    private int findAncestorContainingAttr(String attrId, Set<Integer> ancestors,
            List<JsonObject> typeList, Map<Integer, List<String>> localAttrs,
            Map<String, Map<String, String>> allAttrAttrs,
            List<String> atomIds) {
        for (int ai : ancestors) {
            List<String> fids = localAttrs.getOrDefault(ai, List.of());
            if (fids.contains(attrId)) return ai;
        }
        return -1;
    }

    private void collectNodes(Set<Integer> nodes, List<JsonObject> typeList,
            Map<Integer, Integer> parentClassMap, Map<Integer, List<String>> parentInterfacesMap,
            List<Integer> result) {
        Map<Integer, Integer> depth = new LinkedHashMap<>();
        for (int n : nodes) depth.put(n, nodeDepth(n, parentClassMap, parentInterfacesMap, new LinkedHashSet<>()));
        // Sort by depth ASCENDING (root first). Inherited methods are computed
        // top-down so that nearer ancestors' methods override farther ancestors'
        // methods with the same key, matching O-04's nearer-ancestor priority.
        List<Integer> sorted = new ArrayList<>(nodes);
        sorted.sort((a, b) -> Integer.compare(depth.getOrDefault(a, 0), depth.getOrDefault(b, 0)));
        result.addAll(sorted);
    }

    private int nodeDepth(int n, Map<Integer, Integer> parentClassMap,
            Map<Integer, List<String>> parentInterfacesMap, Set<Integer> visited) {
        if (!visited.add(n)) return 0;
        int maxParent = 0;
        Integer pc = parentClassMap.get(n);
        if (pc != null) maxParent = Math.max(maxParent, 1 + nodeDepth(pc, parentClassMap, parentInterfacesMap, visited));
        List<String> intfs = parentInterfacesMap.get(n);
        if (intfs != null) {
            for (String istr : intfs) {
                int idx = extractIndex(istr);
                if (idx >= 0) maxParent = Math.max(maxParent, 1 + nodeDepth(idx, parentClassMap, parentInterfacesMap, visited));
            }
        }
        return maxParent;
    }

    private Set<Integer> computeAncestors(int start, Map<Integer, Integer> parentClassMap,
            Map<Integer, List<String>> parentInterfacesMap) {
        Set<Integer> result = new LinkedHashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            int current = stack.pop();
            Integer pc = parentClassMap.get(current);
            if (pc != null && result.add(pc)) {
                stack.push(pc);
            }
            List<String> intfStr = parentInterfacesMap.get(current);
            if (intfStr != null) {
                for (String istr : intfStr) {
                    int idx = extractIndex(istr);
                    if (idx >= 0 && result.add(idx)) {
                        stack.push(idx);
                    }
                }
            }
        }
        return result;
    }

    private int extractIndex(String atomId) {
        String num = atomId.replaceAll("[^0-9]", "");
        if (num.isEmpty()) return -1;
        return Integer.parseInt(num);
    }

    private static String visibilityToEnum(String vis) {
        switch (vis) {
            case "public": return "Pub";
            case "private": return "Priv";
            case "protected": return "Prot";
            case "package-private": return "Pkg";
            default: return "Pub";
        }
    }

    private static String booleanToScope(String boolStr) {
        return "true".equalsIgnoreCase(boolStr) ? "Static" : "Instance";
    }

    private static String booleanToString(String boolStr) {
        return "true".equalsIgnoreCase(boolStr) ? "Yes" : "No";
    }

    private static String buildParamTypes(JsonObject method) {
        JsonArray params = method.getAsJsonArray("parameters");
        if (params == null || params.size() == 0) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (int pi = 0; pi < params.size(); pi++) {
            if (pi > 0) sb.append(", ");
            JsonObject p = params.get(pi).getAsJsonObject();
            String ptype = getString(p, "type", "unknown");
            sb.append(pi).append(" = \"").append(ptype).append("\"");
        }
        sb.append("}");
        return sb.toString();
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

    private static String methodKey(Map<String, String> attrs) {
        String name = attrs.get("memberName");
        if (name == null) return null;
        String paramTypes = attrs.get("paramTypes");
        if (paramTypes != null && !paramTypes.isEmpty() && !"{}".equals(paramTypes)) {
            String inner = paramTypes.replace("{", "").replace("}", "").trim();
            if (!inner.isEmpty()) {
                Map<Integer, String> map = new TreeMap<>();
                for (String entry : inner.split(",")) {
                    String trimmed = entry.trim();
                    int eq = trimmed.indexOf("=");
                    if (eq < 0) continue;
                    try {
                        int idx = Integer.parseInt(trimmed.substring(0, eq).trim());
                        String type = trimmed.substring(eq + 1).trim().replace("\"", "");
                        map.put(idx, type);
                    } catch (NumberFormatException ignored) { }
                }
                StringBuilder sb = new StringBuilder(name).append("(");
                boolean first = true;
                for (String t : map.values()) {
                    if (!first) sb.append(",");
                    sb.append(t);
                    first = false;
                }
                return sb.append(")").toString();
            }
        }
        return name;
    }
}
