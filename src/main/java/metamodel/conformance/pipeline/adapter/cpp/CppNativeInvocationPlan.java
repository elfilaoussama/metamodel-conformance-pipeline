package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import metamodel.conformance.pipeline.util.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** Immutable retained participation transport. A world is a configured target,
 * not an inferred language universe or a semantic acceptance decision. */
public final class CppNativeInvocationPlan {
    public record World(String id, String configuration, String targetId, List<String> invocationIds) {
        public World { invocationIds = List.copyOf(invocationIds); }
    }
    public record Invocation(String id, String worldId, String commandId, String targetId,
            CppDependencyInputs.CppCompileContext context, boolean compilerDefault) { }
    private final Map<String,World> worlds;
    private final Map<String,Invocation> invocations;
    private final String bindingJson, bindingSha256;
    private CppNativeInvocationPlan(String json, Map<String,World> worlds, Map<String,Invocation> invocations) {
        this.bindingJson=json; this.bindingSha256=Hashing.sha256(json);
        this.worlds=Collections.unmodifiableMap(new TreeMap<>(worlds));
        this.invocations=Collections.unmodifiableMap(new TreeMap<>(invocations));
    }
    public Map<String,World> worlds() { return worlds; }
    public Map<String,Invocation> invocations() { return invocations; }
    public String bindingJson() { return bindingJson; }
    public String bindingSha256() { return bindingSha256; }

    public static CppNativeInvocationPlan parse(String bindingJson, List<String> worldJsons,
            Map<String,String> invocationJsons, CppConfiguredBuildModel model,
            CppConfiguredTargetSelection selection,
            Map<String,CppDependencyInputs.CppCompileContext> contexts, Function<Path,String> sourcePath) {
        Objects.requireNonNull(model); Objects.requireNonNull(selection); Objects.requireNonNull(sourcePath);
        JsonNode binding=json(bindingJson);
        fields(binding,"format","captureSha256","configuredModelSha256","configuredModelCanonicalSha256","compileCommandsSha256",
                "membershipSha256","readerSha256","toolSha256","toolVersion","profile","membership");
        require(text(binding,"format").equals("cpp-native-membership-binding-v1"),"unsupported membership binding");
        for(String key:List.of("captureSha256","configuredModelSha256","configuredModelCanonicalSha256","compileCommandsSha256","membershipSha256","readerSha256","toolSha256"))
            require(text(binding,key).matches("[0-9a-f]{64}"),"invalid membership digest");
        require(text(binding,"profile").equals("NINJA_1_10_1_LITERAL_GRAPH_READONLY_V1")
                && text(binding,"toolVersion").equals("1.10.1"),"unsupported membership profile");
        require(text(binding,"configuredModelCanonicalSha256").equals(model.sha256())
                && selection.modelSha256().equals(model.sha256()),"configured model identity differs");
        JsonNode configured=json(model.json());
        require(configured.path("cmake").path("generator").path("name").asText().equals("Ninja"),"membership requires Ninja");
        require(configured.path("codemodel").path("configurations").size()==1,"membership requires one configuration");
        Map<String,JsonNode> targets=new TreeMap<>();
        for(JsonNode row:configured.path("targets")) {
            require(text(row,"configuration").equals(selection.configuration()),"target configuration differs");
            require(targets.putIfAbsent(text(row,"id"),row.get("object"))==null,"duplicate target");
        }
        JsonNode membership=binding.get("membership");
        fields(membership,"format","commands","relations","targetDispositions");
        String membershipFormat=text(membership,"format");
        require(Set.of("cpp-ninja-direct-participation-v1","cpp-ninja-direct-participation-v2").contains(membershipFormat),"unsupported membership format");
        boolean executableSupport=membershipFormat.equals("cpp-ninja-direct-participation-v2");
        Map<String,JsonNode> commands=new TreeMap<>(); Set<String> outputs=new HashSet<>();
        for(JsonNode command:array(membership,"commands")) {
            fields(command,"id","directory","source","output","arguments","occurrenceCount","original");
            String id=text(command,"id");
            require(id.matches("[0-9a-f]{64}") && commands.putIfAbsent(id,command)==null,"invalid or duplicate command id");
            String directory=absolute(text(command,"directory"),null);
            require(directory.equals(text(command,"directory")) && directory.equals(model.buildRoot().toString()),"command directory differs from build root");
            String source=absolute(text(command,"source"),null), output=absolute(text(command,"output"),null);
            require(source.equals(text(command,"source")) && output.equals(text(command,"output")),"noncanonical command coordinates");
            require(outputs.add(output),"duplicate command output");
            JsonNode count=command.get("occurrenceCount");
            require(count.isIntegralNumber() && count.canConvertToLong() && count.longValue()>=1,"invalid occurrence count");
            JsonNode original=command.get("original");
            require(original.isObject(),"original command must be an object");
            Set<String> keys=new HashSet<>(); original.fieldNames().forEachRemaining(keys::add);
            require(keys.containsAll(Set.of("directory","file")) && keys.contains("arguments")!=keys.contains("command")
                    && Set.of("directory","file","arguments","command","output").containsAll(keys),"unsupported original command fields");
            require(text(original,"directory").equals(directory) && absolute(text(original,"file"),directory).equals(source),"original command coordinates differ");
            List<String> args=strings(array(command,"arguments"));
            require(!args.isEmpty() && args.stream().noneMatch(a->a.startsWith("@")),"empty command or response file");
            List<String> originalArgs=original.has("arguments")?strings(array(original,"arguments")):words(text(original,"command"));
            require(args.equals(originalArgs),"original argv differs");
            require(id.equals(Hashing.sha256(canonical(original))),"command identity differs");
            require(Collections.frequency(args,"-c")==1 && args.indexOf("-c")+1<args.size()
                    && absolute(args.get(args.indexOf("-c")+1),directory).equals(source),"invalid compile source operand");
            require(args.subList(1,args.size()).stream().filter(a->!a.startsWith("-") && absolute(a,directory).equals(source)).count()==1,
                    "global command source absent or repeated");
            require(args.subList(1,args.size()).stream().noneMatch(a->a.startsWith("-o") && a.length()>2),"joined output option unsupported");
            int oi=args.indexOf("-o");
            require(Collections.frequency(args,"-o")==1 && oi+1<args.size()
                    && absolute(args.get(oi+1),directory).equals(output),"invalid command output operand");
            if(original.has("output")) require(absolute(text(original,"output"),directory).equals(output),"original output differs");
        }
        Set<String> dispositions=new HashSet<>();
        for(JsonNode disposition:array(membership,"targetDispositions")) {
            fields(disposition,"targetId","targetName","type","status"); String id=text(disposition,"targetId");
            JsonNode target=targets.get(id); require(target!=null && dispositions.add(id),"unknown or duplicate target disposition");
            String type=text(target,"type");
            require(text(disposition,"targetName").equals(text(target,"name")) && text(disposition,"type").equals(type)
                    && text(disposition,"status").equals(supported(type,executableSupport)?"SUPPORTED_DIRECT_ARTIFACTS":"UNSUPPORTED_TARGET_TYPE"),"target disposition differs");
        }
        require(dispositions.equals(targets.keySet()),"incomplete target dispositions");
        Map<String,SortedSet<String>> participation=new TreeMap<>(); Set<String> relations=new HashSet<>();
        for(JsonNode relation:array(membership,"relations")) {
            fields(relation,"targetId","targetName","artifact","commandId");
            String targetId=text(relation,"targetId"), commandId=text(relation,"commandId"); JsonNode target=targets.get(targetId);
            require(target!=null && commands.containsKey(commandId) && supported(text(target,"type"),executableSupport)
                    && text(relation,"targetName").equals(text(target,"name")),"invalid participation endpoint");
            String artifact=absolute(text(relation,"artifact"),null); boolean declared=false;
            for(JsonNode row:array(target,"artifacts")) declared|=absolute(text(row,"path"),model.buildRoot().toString()).equals(artifact);
            require(declared && relations.add(targetId+"\0"+artifact+"\0"+commandId),"unknown or duplicate artifact relation");
            if(text(target,"type").equals("OBJECT_LIBRARY")) require(artifact.equals(text(commands.get(commandId),"output")),"object artifact differs from command output");
            participation.computeIfAbsent(targetId,k->new TreeSet<>()).add(commandId);
        }
        Set<String> wanted=new TreeSet<>(); selection.targets().forEach(t->wanted.add(t.id()));
        // Native artifact edges may add object-library contributions, but cannot
        // erase a source the configured target itself declares as compiled.
        for(var target:selection.targets()) {
            Set<String> participatingSources=new HashSet<>();
            for(String commandId:participation.getOrDefault(target.id(),Collections.emptySortedSet()))
                participatingSources.add(text(commands.get(commandId),"source"));
            for(var source:target.sources()) if(source.compileGroupIndex()!=null) {
                require(source.language().equals("CXX"),"selected configured compiled source is not CXX");
                require(participatingSources.contains(source.path().toString()),
                        "configured compiled source lacks native target participation");
            }
        }
        // Establish contributing object targets by exact native output edges,
        // then consult source language only inside those established targets.
        Map<String,Set<String>> objectTargetsByCommand=new HashMap<>();
        for(JsonNode relation:array(membership,"relations")) {
            String targetId=text(relation,"targetId");
            if(text(targets.get(targetId),"type").equals("OBJECT_LIBRARY"))
                objectTargetsByCommand.computeIfAbsent(text(relation,"commandId"),k->new TreeSet<>()).add(targetId);
        }
        for(String selectedId:wanted)for(String commandId:participation.getOrDefault(selectedId,Collections.emptySortedSet())) {
            Set<String> contributors=objectTargetsByCommand.getOrDefault(commandId,Set.of(selectedId));
            for(String contributor:contributors) {
                var configuredTarget=CppConfiguredTargetSelection.select(model,selection.configuration(),Set.of(contributor)).targets().get(0);
                String source=text(commands.get(commandId),"source");
                var matching=configuredTarget.sources().stream().filter(s->s.compiled() && s.path().toString().equals(source)).toList();
                require(matching.size()==1 && matching.get(0).language().equals("CXX"),
                        "participating command lacks unambiguous configured CXX source");
            }
        }
        Map<String,World> worlds=new TreeMap<>(); Map<String,Invocation> invocations=new TreeMap<>(); Set<String> seenTargets=new HashSet<>();
        for(String raw:worldJsons) {
            JsonNode world=json(raw); fields(world,"id","configuration","targetId","modelSha256","membershipSha256","invocationIds");
            String targetId=text(world,"targetId"), id=text(world,"id"), configuration=text(world,"configuration");
            require(wanted.contains(targetId) && seenTargets.add(targetId) && configuration.equals(selection.configuration())
                    && text(world,"modelSha256").equals(model.sha256()) && text(world,"membershipSha256").equals(text(binding,"membershipSha256")),"world identity differs");
            String expected="cpp-world-"+Hashing.sha256("CPP_NATIVE_WORLD_V1\0"+model.sha256()+"\0"+configuration+"\0"+targetId+"\0"+text(binding,"membershipSha256"));
            require(id.equals(expected),"world id differs");
            SortedSet<String> commandIds=participation.get(targetId); require(commandIds!=null && !commandIds.isEmpty(),"selected target lacks native participation");
            List<String> ids=new ArrayList<>();
            for(String commandId:commandIds) {
                String invocationId="cpp-invocation-"+Hashing.sha256(id+"\0"+commandId); ids.add(invocationId);
                require(invocationJsons.containsKey(invocationId) && contexts.containsKey(invocationId),"missing selected invocation");
                JsonNode invocation=json(invocationJsons.get(invocationId)); fields(invocation,"worldId","commandId","targetId","standardSelection");
                var context=contexts.get(invocationId);
                require(context!=null && context.compiler()!=null && context.execution()!=null && context.ordered(),"invocation needs ordered native context");
                boolean defaultMode=context.operations().stream().noneMatch(op->op.kind().equals("std"));
                require(text(invocation,"worldId").equals(id) && text(invocation,"commandId").equals(commandId)
                        && text(invocation,"targetId").equals(targetId) && text(invocation,"standardSelection").equals(defaultMode?"COMPILER_DEFAULT":"EXPLICIT_OPTION"),"invocation relation differs");
                JsonNode command=commands.get(commandId); String directory=text(command,"directory"), source=text(command,"source");
                require(context.execution().workingDirectory().equals(directory)
                        && absolute(context.execution().sourceOperand(),directory).equals(source)
                        && sourcePath.apply(Path.of(source)).equals(context.sourcePath()),"context source coordinates differ");
                require(normalize(strings(array(command,"arguments")),directory,source).equals(context.arguments(Path.of("."))),"context argument order differs");
                require(invocations.putIfAbsent(invocationId,new Invocation(invocationId,id,commandId,targetId,context,defaultMode))==null,"duplicate invocation");
            }
            require(strings(array(world,"invocationIds")).equals(ids),"incomplete or reordered world invocations");
            require(worlds.putIfAbsent(id,new World(id,configuration,targetId,ids))==null,"duplicate world");
        }
        require(seenTargets.equals(wanted) && invocations.keySet().equals(invocationJsons.keySet())
                && invocations.keySet().equals(contexts.keySet()),"orphan or missing world/context/invocation");
        return new CppNativeInvocationPlan(bindingJson,worlds,invocations);
    }

    private static List<String> normalize(List<String> args,String directory,String source) {
        List<String> out=new ArrayList<>(); int sources=0;
        for(int i=1;i<args.size();i++) {
            String a=args.get(i);
            if(Set.of("-o","-MF","-MT","-MQ").contains(a)) { require(++i<args.size(),"missing output option value"); continue; }
            if(Set.of("-c","-MD","-MMD","-MP").contains(a)) continue;
            if(a.equals("-D") || a.equals("-U")) { require(++i<args.size(),"missing macro value"); out.add(a+args.get(i)); continue; }
            boolean include=false;
            for(String prefix:List.of("-isystem","-iquote","-idirafter","-I")) if(a.startsWith(prefix)) {
                String value=a.substring(prefix.length()); if(value.isEmpty()) {require(++i<args.size(),"missing include value");value=args.get(i);}
                out.add(prefix);out.add(value);include=true;break;
            }
            if(include) continue;
            if(!a.startsWith("-") && absolute(a,directory).equals(source)) {sources++;continue;}
            out.add(a);
        }
        require(sources==1,"source operand absent or repeated"); return List.copyOf(out);
    }
    private static boolean supported(String type, boolean executableSupport) {
        return type.equals("STATIC_LIBRARY") || type.equals("OBJECT_LIBRARY") || executableSupport && type.equals("EXECUTABLE");
    }
    private static JsonNode json(String value) {
        try { return new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(Objects.requireNonNull(value)); }
        catch(IOException failure) {throw new IllegalArgumentException("invalid native invocation JSON",failure);}
    }
    private static void fields(JsonNode node,String... names) {
        require(node!=null && node.isObject(),"expected object"); Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(Set.of(names)),"unexpected or missing native invocation fields");
    }
    private static String text(JsonNode node,String key) {
        JsonNode value=node.get(key); require(value!=null && value.isTextual(),"expected text: "+key);
        String result=value.textValue(); require(result.chars().noneMatch(c->c<32 || c==127),"control character in text");
        require(!result.isEmpty() || key.equals("configuration"),"empty text: "+key); return result;
    }
    private static JsonNode array(JsonNode node,String key) {JsonNode value=node.get(key);require(value!=null && value.isArray(),"expected array: "+key);return value;}
    private static List<String> strings(JsonNode array) {
        List<String> result=new ArrayList<>(); for(JsonNode value:array) {
            require(value.isTextual() && !value.textValue().isEmpty() && value.textValue().chars().noneMatch(c->c<32 || c==127),"invalid argv/text element"); result.add(value.textValue());
        } return List.copyOf(result);
    }
    private static String absolute(String value,String directory) {
        require(!value.isEmpty() && !value.contains("\\") && !value.startsWith("//") && Arrays.stream(value.split("/",-1)).noneMatch(".."::equals),"unsupported path");
        Path path=Path.of(value); if(!path.isAbsolute()) {require(directory!=null,"expected absolute path");path=Path.of(directory).resolve(path);}
        return path.normalize().toString();
    }
    private static String canonical(JsonNode value) {
        if(value.isObject()) { ObjectNode sorted=new ObjectMapper().createObjectNode(); TreeMap<String,JsonNode> fields=new TreeMap<>();value.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));fields.forEach((k,v)->sorted.set(k,v));return sorted.toString(); }
        return value.toString();
    }
    /** The supported direct-command subset of POSIX shlex; no shell execution. */
    private static List<String> words(String command) {
        require(command.chars().noneMatch(c->"$`;&|<>\n\r".indexOf(c)>=0),"unsupported shell expression");
        List<String> result=new ArrayList<>();StringBuilder token=new StringBuilder();char quote=0;boolean active=false;
        for(int i=0;i<command.length();i++) {
            char c=command.charAt(i);
            if(quote=='\'') { if(c=='\'')quote=0;else token.append(c);active=true;continue; }
            if(c=='\\') {
                require(i+1<command.length(),"trailing command escape");char next=command.charAt(++i);
                if(quote=='"' && next!='"' && next!='\\')token.append('\\');token.append(next);active=true;continue;
            }
            if(quote=='"') {if(c=='"')quote=0;else token.append(c);active=true;continue;}
            if(c=='\'' || c=='"') {quote=c;active=true;continue;}
            if(Character.isWhitespace(c)) {if(active){require(!token.isEmpty(),"empty command word");result.add(token.toString());token.setLength(0);active=false;}continue;}
            token.append(c);active=true;
        }
        require(quote==0,"unclosed command quote");if(active){require(!token.isEmpty(),"empty command word");result.add(token.toString());}
        require(!result.isEmpty(),"empty command");return List.copyOf(result);
    }
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalArgumentException(message);}
}
