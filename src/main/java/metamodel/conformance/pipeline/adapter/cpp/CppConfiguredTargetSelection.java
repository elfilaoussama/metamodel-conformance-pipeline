package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import metamodel.conformance.pipeline.util.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** An explicit population of directly owned configured sources. Dependency edges
 * are retained, never traversed as if they were linker/source membership facts. */
public final class CppConfiguredTargetSelection {
    private final String modelSha256,configuration,sha256;
    private final List<Target> targets;
    public String modelSha256() { return modelSha256; }
    public String configuration() { return configuration; }
    public List<Target> targets() { return targets; }
    public String sha256() { return sha256; }
    /** declaredGenerated is CMake's GENERATED property, not a claim about how
     * an arbitrary file was originally authored. */
    public record Source(Path path, Integer compileGroupIndex, String language, boolean declaredGenerated) {
        public Source {
            Objects.requireNonNull(path);
            if (!path.isAbsolute() || !path.equals(path.normalize()))
                throw new IllegalArgumentException("selected source must be absolute and normalized");
            if ((compileGroupIndex == null) != (language == null) || compileGroupIndex != null && compileGroupIndex < 0)
                throw new IllegalArgumentException("inconsistent selected source compile group");
            if(language!=null)checked(language,false);
        }
        public boolean compiled() { return compileGroupIndex != null; }
    }
    public record Target(String id, String name, String type, List<Source> sources, List<String> dependencies) {
        public Target {
            id=checked(id,false);name=checked(name,false);type=checked(type,false);
            sources=List.copyOf(sources);dependencies=List.copyOf(dependencies);
        }
    }
    private CppConfiguredTargetSelection(String modelSha256,String configuration,List<Target> targets) {
        if (modelSha256 == null || !modelSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid selected configured-model identity");
        configuration=checked(configuration,true);targets=List.copyOf(targets);
        if(targets.isEmpty() || targets.stream().map(Target::id).distinct().count()!=targets.size())
            throw new IllegalArgumentException("selected targets must be nonempty and unique");
        this.modelSha256=modelSha256;this.configuration=configuration;this.targets=targets;
        this.sha256=identity(modelSha256,configuration,targets.stream().map(Target::id).toList());
    }

    public static CppConfiguredTargetSelection select(CppConfiguredBuildModel model,
            String configuration, Set<String> targetIds) {
        Objects.requireNonNull(model);configuration=checked(configuration,true);
        if(targetIds==null || targetIds.isEmpty())throw new IllegalArgumentException("explicit configured targets required");
        var wanted=new TreeSet<String>();for(String id:targetIds)wanted.add(checked(id,false));
        try {
            JsonNode root=new ObjectMapper().readTree(model.json());
            final String selectedConfiguration=configuration;
            boolean found=false;
            for(JsonNode row:root.path("codemodel").path("configurations"))
                found |= row.path("name").asText().equals(configuration);
            require(found,"unknown build configuration");
            Map<String,JsonNode> objects=new TreeMap<>();
            for(JsonNode row:root.path("targets"))if(row.path("configuration").asText().equals(selectedConfiguration))
                objects.put(row.path("id").asText(),row.path("object"));
            require(objects.keySet().containsAll(wanted),"unknown target in selected configuration");
            List<Target> targets=new ArrayList<>();
            for(String id:wanted)targets.add(readTarget(id,objects.get(id),objects.keySet(),model));
            return new CppConfiguredTargetSelection(model.sha256(),configuration,targets);
        } catch(IOException invalid) {
            throw new IllegalArgumentException("configured model cannot be decoded",invalid);
        }
    }

    private static Target readTarget(String id,JsonNode target,Set<String> knownTargets,CppConfiguredBuildModel model) {
        JsonNode sourceRows=array(target,"sources"),groups=array(target,"compileGroups");
        // CMake exposes this relation in both directions. Validate the exact
        // relation, including group/source indexes that Java int conversion could wrap.
        Map<Integer,Integer> membership=new HashMap<>();
        for(int group=0;group<groups.size();group++) {
            JsonNode row=groups.get(group);text(row,"language");
            JsonNode indexes=array(row,"sourceIndexes");
            require(!indexes.isEmpty(),"compile group has no source membership");
            for(JsonNode value:indexes) {
                int index=index(value,sourceRows.size());
                require(membership.putIfAbsent(index,group)==null,"duplicate compile-group source membership");
            }
        }
        List<Source> sources=new ArrayList<>();
        for(int index=0;index<sourceRows.size();index++) {
            JsonNode row=sourceRows.get(index);Path source=Path.of(text(row,"path"));
            if(!source.isAbsolute()) {
                source=model.sourceRoot().resolve(source).normalize();
                require(source.startsWith(model.sourceRoot()),"external configured sources must use absolute paths");
            }
            source=source.normalize();
            Integer group=row.has("compileGroupIndex") ? index(row.get("compileGroupIndex"),groups.size()):null;
            require(Objects.equals(group,membership.get(index)),"source/compile-group membership disagreement");
            boolean generated=false;
            if(row.has("isGenerated")) {
                require(row.get("isGenerated").isBoolean(),"source generation must be boolean");
                generated=row.get("isGenerated").booleanValue();
            }
            sources.add(new Source(source,group,group==null?null:text(groups.get(group),"language"),generated));
        }
        List<String> dependencies=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(JsonNode row:array(target,"dependencies")) {
            String dependency=text(row,"id");
            require(knownTargets.contains(dependency) && seen.add(dependency),"unknown/duplicate configured dependency");
            dependencies.add(dependency);
        }
        return new Target(id,text(target,"name"),text(target,"type"),sources,dependencies);
    }
    private static JsonNode array(JsonNode node,String field) {
        JsonNode value=node.get(field);
        if(value==null)return new ObjectMapper().createArrayNode();
        require(value.isArray(),"configured target "+field+" must be an array");return value;
    }
    private static int index(JsonNode value,int upper) {
        require(value!=null && value.isIntegralNumber() && value.canConvertToInt(),"invalid configured population index");
        int result=value.intValue();require(result>=0 && result<upper,"configured population index outside array");return result;
    }
    private static String text(JsonNode node,String field) {
        JsonNode value=node.get(field);require(value!=null && value.isTextual(),"missing/nontextual configured "+field);
        return checked(value.textValue(),false);
    }
    private static String checked(String value,boolean empty) {
        require(value!=null && (empty || !value.isBlank()) && value.indexOf('\0')<0,"invalid configured selection text");return value;
    }
    private static String identity(String model,String configuration,List<String> ids) {
        try {
            return Hashing.sha256("cpp-direct-target-population-v1\0"+model+"\0"
                    +new ObjectMapper().writeValueAsString(List.of(configuration,ids.stream().sorted().toList())));
        } catch(IOException impossible) { throw new IllegalArgumentException("selected population cannot be encoded",impossible); }
    }
    private static void require(boolean condition,String message) {
        if(!condition)throw new IllegalArgumentException(message);
    }
}
