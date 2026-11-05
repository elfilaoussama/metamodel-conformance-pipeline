package metamodel.conformance.pipeline.adapter.java;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import metamodel.conformance.pipeline.util.Hashing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Validates native transport and returned buffers, without inferring read/origin closure. */
final class JavaNativeReceipt {
    static final String FORMAT = "native-javac-source-world-v6";
    static final String BUILD_FORMAT = "native-javac-build-source-world-v1";
    private static final int MAX_RECEIPT = 64 * 1024 * 1024, MAX_ROW = 8 * 1024 * 1024;
    private static final int MAX_OBJECT = 32 * 1024 * 1024, MAX_TOTAL = 64 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final List<String> PHASES = List.of("AFTER_ANALYZE_BEFORE_EXPORT", "AFTER_EXPORT_BEFORE_GENERATE", "AFTER_GENERATE");
    record Request(String attempt, List<String> sources, List<String> options,
                   Map<String,String> sourceHashes, int feature, String runtimeHome) {
        Request {
            sources=List.copyOf(sources); options=List.copyOf(options); sourceHashes=Map.copyOf(sourceHashes);
            require(attempt != null && attempt.matches("[0-9a-f]{32}"), "invalid native attempt");
            require(JavaNativeConfiguration.SUPPORTED_FEATURES.contains(feature), "unsupported native compiler feature");
        }
    }
    record ParsedTypeSpan(String uri,long start,long end) {
        ParsedTypeSpan {uri=sourceUri(uri);require(start>=0 && end>start,"invalid parsed source class span");}
    }
    record BuildRequest(String attempt,long invocation,int feature,String runtimeHome,String javaVersion,
                        Map<String,String> sourceCharacters,Set<ParsedTypeSpan> parsedTypes) {
        BuildRequest {
            require(attempt!=null && attempt.matches("[0-9a-f]{32}") && invocation>0,"invalid build task identity");
            require(JavaNativeConfiguration.SUPPORTED_FEATURES.contains(feature),"unsupported build compiler feature");
            require(runtimeHome!=null && !runtimeHome.isBlank() && javaVersion!=null && !javaVersion.isBlank(),"actual build runtime required");
            Map<String,String> canonical=new TreeMap<>();
            long total=0;
            for(var entry:sourceCharacters.entrySet()) {
                String characters=entry.getValue();
                require(characters!=null && characters.length()<=16*1024*1024 && (total+=characters.length())<=32L*1024*1024,"build source character bound");
                require(canonical.put(sourceUri(entry.getKey()),characters)==null,"duplicate build source URI");
            }
            require(!canonical.isEmpty() && canonical.size()<=4096,"build source population unavailable/bound");
            sourceCharacters=Map.copyOf(canonical);parsedTypes=Set.copyOf(parsedTypes);
            for(var span:parsedTypes)require(canonical.containsKey(span.uri()) && span.end()<=canonical.get(span.uri()).length(),"parsed class outside build source snapshot");
        }
    }
    private final List<JsonNode> types;
    private final Map<String,String> characters;
    final String javaVersion;
    final String sha256;
    final String format;
    private JavaNativeReceipt(List<JsonNode> types,Map<String,String> characters,String version,String sha256) {
        this(types,characters,version,sha256,FORMAT);
    }
    private JavaNativeReceipt(List<JsonNode> types,Map<String,String> characters,String version,String sha256,String format) {
        this.types=List.copyOf(types);this.characters=Map.copyOf(characters);this.javaVersion=version;this.sha256=sha256;
        this.format=format;
    }
    // Internal consumers receive copies: caller mutation cannot change admitted data.
    List<JsonNode> types() { return types.stream().map(value -> (JsonNode)value.deepCopy()).toList(); }
    String characters(String uri) { return characters.get(uri); }

    static JavaNativeReceipt read(Path file,Path buffers,Request request,int observedExit) throws IOException {
        require(observedExit==0,"successful owned native process required");
        Transport transport=transport(file);byte[] raw=transport.raw();List<JsonNode> rows=transport.rows();int lastStart=transport.lastStart();
        require(rows.size()>=5,"incomplete native receipt");
        JsonNode header=rows.get(0),complete=rows.get(rows.size()-1);
        keys(header,"kind format attempt javaVersion javaHome compilerTaskClass compilerOptions explicitSources parsedSources consumptionCertified consumedSourceCharacters characterDigestEncoding binaryConsumptionCertified index");
        require(text(header,"kind").equals("HEADER") && text(header,"format").equals(FORMAT)
                && text(header,"attempt").equals(request.attempt()),"native header identity");
        require(strings(header.get("compilerOptions")).equals(request.options())
                && strings(header.get("explicitSources")).equals(request.sources()),"native request differs");
        String version=text(header,"javaVersion");
        require((request.feature()==8 ? version.startsWith("1.8.") : version.matches(request.feature()+"(?:[.+_-].*)?"))
                && text(header,"javaHome").equals(request.runtimeHome())
                && text(header,"compilerTaskClass").equals("com.sun.tools.javac.api.JavacTaskImpl"),"native compiler world differs");
        require(isFalse(header,"consumptionCertified") && isFalse(header,"binaryConsumptionCertified")
                && text(header,"characterDigestEncoding").equals("UTF16BE_CODE_UNITS_NO_BOM"),"unsupported native certification");
        keys(complete,"kind attempt completion parsedUnits sourceTypes generatedOutputs diagnosticErrors canonicalAdmissionCertified index priorRowsSha256");
        require(text(complete,"kind").equals("COMPLETE") && text(complete,"attempt").equals(request.attempt())
                && text(complete,"completion").equals("PARSE_ANALYZE_GENERATE_RETURNED")
                && integer(complete,"diagnosticErrors",0)==0 && isFalse(complete,"canonicalAdmissionCertified")
                && digest(complete,"priorRowsSha256").equals(Hashing.sha256(Arrays.copyOf(raw,lastStart))),"native completion/seal differs");
        integer(complete,"generatedOutputs",0);
        return readSourceWorld(buffers,request,raw,rows,header,complete,version);
    }
    private record Transport(byte[] raw,List<JsonNode> rows,int lastStart) { }
    private static Transport transport(Path file) throws IOException {
        byte[] raw=bounded(file,MAX_RECEIPT);
        require(raw.length>0 && raw[raw.length-1]=='\n',"unterminated native receipt");
        List<JsonNode> rows=new ArrayList<>();int start=0,lastStart=-1;
        for(int i=0;i<raw.length;i++) {
            require(raw[i]!='\r',"CR in native receipt");
            if(raw[i]!='\n')continue;
            require(i-start>0 && i-start<=MAX_ROW && rows.size()<16384,"native row bound");
            JsonNode row=JSON.readTree(Arrays.copyOfRange(raw,start,i));
            require(row!=null && row.isObject() && integer(row,"index",1)==rows.size()+1,"native row sequence");
            rows.add(row);lastStart=start;start=i+1;
        }
        return new Transport(raw,List.copyOf(rows),lastStart);
    }
    private static JavaNativeReceipt readSourceWorld(Path buffers,Request request,byte[] raw,List<JsonNode> rows,JsonNode header,JsonNode complete,String version) throws IOException {
        Map<String,String> parsed=new TreeMap<>();
        for(JsonNode item:array(header,"parsedSources")) {
            keys(item,"uri sha256 point");
            require(text(item,"point").equals("POST_ATTRIBUTION_FILE_SNAPSHOT")
                    && parsed.put(text(item,"uri"),digest(item,"sha256"))==null,"duplicate/invalid parsed source");
        }
        Map<String,String> canonicalParsed=new TreeMap<>();
        for(var source:parsed.entrySet())require(canonicalParsed.put(sourceUri(source.getKey()),source.getValue())==null,"aliased native source population");
        require(canonicalParsed.equals(request.sourceHashes()) && integer(complete,"parsedUnits",0)==parsed.size(),"native source population differs");
        List<JsonNode> snapshots=new ArrayList<>(),types=new ArrayList<>();
        for(JsonNode row:rows.subList(1,rows.size()-1)) {
            if(text(row,"kind").equals("CONSUMPTION_SNAPSHOT")) {
                keys(row,"kind phase sourceCharacters sourceBytes classBytes otherBytes retainedBuffers binaryConsumptionCertified index");
                require(snapshots.size()<3 && text(row,"phase").equals(PHASES.get(snapshots.size()))
                        && isFalse(row,"binaryConsumptionCertified"),"native snapshot lifecycle");
                for(String name:List.of("sourceCharacters","sourceBytes","classBytes","otherBytes")) {
                    Map<String,String> values=hashMap(row.get(name));
                    if(!snapshots.isEmpty()) require(values.entrySet().containsAll(hashMap(snapshots.get(snapshots.size()-1).get(name)).entrySet()),"native input map regressed");
                }
                snapshots.add(row);
            } else {
                require(text(row,"kind").equals("TYPE") && snapshots.size()==1,"native fact outside export phase");
                types.add(row);
            }
        }
        require(snapshots.size()==3 && integer(complete,"sourceTypes",0)==types.size()
                && hashMap(header.get("consumedSourceCharacters")).equals(hashMap(snapshots.get(0).get("sourceCharacters"))),"native snapshot/count mismatch");
        require(Files.isDirectory(buffers,LinkOption.NOFOLLOW_LINKS) && buffers.toRealPath().equals(buffers.toAbsolutePath()),"aliased native buffer directory");
        Map<String,byte[]> objects=new HashMap<>();Map<List<String>,JsonNode> previous=Map.of();long total=0;
        for(JsonNode snapshot:snapshots) {
            var refs=new HashMap<List<String>,JsonNode>();
            JsonNode references=array(snapshot,"retainedBuffers");require(references.size()<=8192,"native reference bound");
            for(JsonNode ref:references) {
                keys(ref,"uri representation sha256 bytes object point");
                String uri=text(ref,"uri"),form=text(ref,"representation"),sha=digest(ref,"sha256");
                long bytes=integer(ref,"bytes",0);
                require(sha.equals(text(ref,"object")) && bytes<=MAX_OBJECT
                        && Set.of("SOURCE_BYTES","SOURCE_UTF16BE_CODE_UNITS","CLASS_BYTES","OTHER_BYTES").contains(form)
                        && text(ref,"point").equals("WRAPPER_SNAPSHOT_AVAILABLE_TO_COMPILER_NOT_FULL_CONSUMPTION")
                        && refs.put(List.of(uri,form),ref)==null,"invalid/duplicate native buffer reference");
                if(!objects.containsKey(sha)) {
                    require(total+bytes<=MAX_TOTAL,"native aggregate buffer bound");
                    byte[] bytesRead=bounded(buffers.resolve(sha),MAX_OBJECT);
                    require(bytesRead.length==bytes && Hashing.sha256(bytesRead).equals(sha),"native buffer bytes differ");
                    objects.put(sha,bytesRead);total+=bytes;
                }
                require(objects.get(sha).length==bytes,"native buffer size differs");
            }
            require(refs.entrySet().containsAll(previous.entrySet()),"native references regressed");
            for(var mapping:Map.of("sourceCharacters","SOURCE_UTF16BE_CODE_UNITS","sourceBytes","SOURCE_BYTES","classBytes","CLASS_BYTES","otherBytes","OTHER_BYTES").entrySet()) {
                var mapped=new TreeMap<String,String>();
                for(var entry:refs.entrySet())if(entry.getKey().get(1).equals(mapping.getValue()))mapped.put(entry.getKey().get(0),text(entry.getValue(),"sha256"));
                require(mapped.equals(hashMap(snapshot.get(mapping.getKey()))),"native buffer map differs");
            }
            previous=refs;
        }
        Set<String> actualObjects=new HashSet<>();
        try(var entries=Files.list(buffers)) {
            for(Path entry:entries.toList()) {require(Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS),"unexpected native buffer node");actualObjects.add(entry.getFileName().toString());}
        }
        require(actualObjects.equals(objects.keySet()),"extra/missing native buffer object");
        Map<String,String> characters=new HashMap<>();
        for(var source:parsed.entrySet()) {
            JsonNode bytes=previous.get(List.of(source.getKey(),"SOURCE_BYTES"));
            JsonNode chars=previous.get(List.of(source.getKey(),"SOURCE_UTF16BE_CODE_UNITS"));
            require(bytes!=null && chars!=null && digest(bytes,"sha256").equals(source.getValue()),"source lacks returned buffers");
            String content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(objects.get(source.getValue()))).toString();
            require(Arrays.equals(content.getBytes(StandardCharsets.UTF_16BE),objects.get(text(chars,"sha256"))),"source characters differ");
            characters.put(source.getKey(),content);
        }
        Set<String> names=new HashSet<>();SourceCoordinates coordinates=new SourceCoordinates(characters);
        for(JsonNode type:types)verifyType(type,coordinates,names);
        return new JavaNativeReceipt(types,characters,version,Hashing.sha256(raw));
    }

    static JavaNativeReceipt readBuild(Path file,BuildRequest request,int observedBuildExit) throws IOException {
        require(observedBuildExit==0,"successful observed author build required");
        Transport transport=transport(file);List<JsonNode> rows=transport.rows();
        require(rows.size()>=2,"incomplete build semantic receipt");
        JsonNode header=rows.get(0),complete=rows.get(rows.size()-1);
        keys(header,"kind format attempt invocation javaVersion javaHome compilerTaskClass capturePoint completionStrategy parsedSources consumptionCertified binaryConsumptionCertified index");
        String strategy=request.feature()==8?"PROCESS_SHUTDOWN_WITH_OBSERVED_EXIT_REQUIRED":"TASK_COMPILATION_EVENT";
        require(text(header,"kind").equals("HEADER") && text(header,"format").equals(BUILD_FORMAT)
                && text(header,"attempt").equals(request.attempt()) && integer(header,"invocation",1)==request.invocation(),"build semantic task identity differs");
        String version=text(header,"javaVersion");
        require(version.equals(request.javaVersion()) && (request.feature()==8?version.startsWith("1.8."):version.matches(request.feature()+"(?:[.+_-].*)?"))
                && text(header,"javaHome").equals(request.runtimeHome())
                && Set.of("com.sun.tools.javac.api.BasicJavacTask","com.sun.tools.javac.api.JavacTaskImpl").contains(text(header,"compilerTaskClass")),"actual build semantic compiler differs");
        require(text(header,"capturePoint").equals("ANALYZE_FINISHED_BEFORE_GENERATION") && text(header,"completionStrategy").equals(strategy)
                && isFalse(header,"consumptionCertified") && isFalse(header,"binaryConsumptionCertified"),"build semantic capture profile differs");
        keys(complete,"kind attempt invocation completion parsedUnits sourceTypes canonicalAdmissionCertified index priorRowsSha256");
        require(text(complete,"kind").equals("COMPLETE") && text(complete,"attempt").equals(request.attempt()) && integer(complete,"invocation",1)==request.invocation()
                && text(complete,"completion").equals(strategy) && isFalse(complete,"canonicalAdmissionCertified")
                && digest(complete,"priorRowsSha256").equals(Hashing.sha256(Arrays.copyOf(transport.raw(),transport.lastStart()))),"build semantic completion/seal differs");
        Map<String,String> characters=new HashMap<>();Set<String> canonical=new HashSet<>();
        for(JsonNode source:array(header,"parsedSources")) {
            keys(source,"uri reportedSourceCharSha256 point");String uri=text(source,"uri"),key=sourceUri(uri);
            String content=request.sourceCharacters().get(key);
            require(content!=null && canonical.add(key) && text(source,"point").equals("PARSE_RETURNED_CHARACTER_SNAPSHOT_NOT_CONSUMED_BUFFER")
                    && digest(source,"reportedSourceCharSha256").equals(Hashing.sha256(content)),"build semantic source snapshot differs");
            characters.put(uri,content);
        }
        require(canonical.equals(request.sourceCharacters().keySet()) && integer(complete,"parsedUnits",0)==characters.size(),"build semantic source population differs");
        List<JsonNode> types=rows.subList(1,rows.size()-1);Set<String> names=new HashSet<>();Set<ParsedTypeSpan> spans=new HashSet<>();
        SourceCoordinates coordinates=new SourceCoordinates(characters);
        for(JsonNode type:types) {
            require(text(type,"kind").equals("TYPE"),"unexpected build semantic record");verifyType(type,coordinates,names);
            JsonNode source=type.get("source");
            require(spans.add(new ParsedTypeSpan(text(source,"uri"),integer(source,"start",0),integer(source,"end",1))),"duplicate build source class span");
        }
        require(integer(complete,"sourceTypes",0)==types.size() && spans.equals(request.parsedTypes()),"build semantic parsed/analyzed class census differs");
        return new JavaNativeReceipt(types,characters,version,Hashing.sha256(transport.raw()),BUILD_FORMAT);
    }

    private static final class SourceCoordinates {
        private final Map<String,String> characters;
        private final Map<String,int[]> lines=new HashMap<>();
        SourceCoordinates(Map<String,String> characters){this.characters=characters;}
        String get(String uri){return characters.get(uri);}
        int line(String uri,long offset) {
            int[] starts=lines.computeIfAbsent(uri,key->{
                String source=characters.get(key);int[] values=new int[Math.min(128,source.length()+1)];int count=1;
                for(int i=0;i<source.length();i++) {
                    char value=source.charAt(i);
                    if(value=='\r' && i+1<source.length() && source.charAt(i+1)=='\n')i++;
                    if(value=='\r'||value=='\n') {
                        if(count==values.length)values=Arrays.copyOf(values,values.length*2);
                        values[count++]=i+1;
                    }
                }
                return Arrays.copyOf(values,count);
            });
            int index=Arrays.binarySearch(starts,Math.toIntExact(offset));return index>=0?index+1:-index-1;
        }
    }
    private static void verifyType(JsonNode type,SourceCoordinates characters,Set<String> names) {
        keys(type,"kind binaryName typeKind modifiers packageName nestingKind writtenBases effectiveBases sourceCensus source directSupertypes declarations lookup overrides declaredFields lookupFields fieldHiding index");
        require(names.add(text(type,"binaryName")) && Set.of("CLASS","INTERFACE","ENUM","ANNOTATION_TYPE").contains(text(type,"typeKind")),"duplicate/unsupported native type");
        require(type.path("packageName").isTextual() && type.path("packageName").asText().indexOf('\0')<0
                && Set.of("TOP_LEVEL","MEMBER","LOCAL","ANONYMOUS").contains(text(type,"nestingKind")),"invalid native package/nesting");
        strings(type.get("modifiers"));strings(type.get("directSupertypes"));span(type.get("source"),characters,true);
        for(JsonNode base:array(type,"writtenBases")) {
            keys(base,"spelling binaryName sourceDeclaration source");text(base,"spelling");text(base,"binaryName");bool(base,"sourceDeclaration");span(base.get("source"),characters,bool(base,"sourceDeclaration"));
        }
        for(JsonNode base:array(type,"effectiveBases")) {
            keys(base,"spelling binaryName sourceDeclaration source rootObject");text(base,"spelling");text(base,"binaryName");bool(base,"rootObject");bool(base,"sourceDeclaration");span(base.get("source"),characters,bool(base,"sourceDeclaration"));
        }
        Set<String> methods=new HashSet<>(),fields=new HashSet<>();int sourceMethods=0,sourceFields=0;
        for(JsonNode method:array(type,"declarations")) {
            method(method,characters);require(text(method,"owner").equals(text(type,"binaryName")) && methods.add(text(method,"key")),"native method owner/identity differs");
            if(bool(method,"sourceDeclaration"))sourceMethods++;
        }
        for(JsonNode method:array(type,"lookup"))method(method,characters);
        for(JsonNode field:array(type,"declaredFields")) {
            field(field,characters);require(text(field,"owner").equals(text(type,"binaryName")) && fields.add(text(field,"name")),"native field owner/identity differs");
            if(bool(field,"sourceDeclaration"))sourceFields++;
        }
        for(JsonNode field:array(type,"lookupFields"))field(field,characters);
        for(JsonNode pair:array(type,"overrides")) {
            keys(pair,"method target effectiveTarget");require(methods.contains(text(pair,"method")) && text(pair,"target").equals(text(pair.get("effectiveTarget"),"key")),"unbound native override");method(pair.get("effectiveTarget"),characters);
        }
        for(JsonNode pair:array(type,"fieldHiding")) {keys(pair,"hiderOwner hiddenOwner name");text(pair,"hiderOwner");text(pair,"hiddenOwner");text(pair,"name");}
        JsonNode census=type.get("sourceCensus");keys(census,"methods fields constructors generatedMethods generatedFields");
        require(integer(census,"methods",0)==sourceMethods && integer(census,"fields",0)==sourceFields
                && integer(census,"generatedMethods",0)==type.get("declarations").size()-sourceMethods
                && integer(census,"generatedFields",0)==type.get("declaredFields").size()-sourceFields,"native source census differs");
        integer(census,"constructors",0);
    }
    private static void method(JsonNode value,SourceCoordinates characters) {
        keys(value,"key owner name declaredParameters declaredReturn declaredErasedParameters declaredErasedReturn effectiveParameters effectiveReturn effectiveErasedParameters effectiveErasedReturn modifiers varargs source sourceDeclaration receiver body bodySource");
        for(String name:List.of("key","owner","name","declaredReturn","declaredErasedReturn","effectiveReturn","effectiveErasedReturn"))text(value,name);
        int arity=strings(value.get("declaredParameters")).size();
        for(String name:List.of("declaredErasedParameters","effectiveParameters","effectiveErasedParameters"))require(strings(value.get(name)).size()==arity,"native method arity differs");
        strings(value.get("modifiers"));bool(value,"varargs");boolean source=bool(value,"sourceDeclaration");span(value.get("source"),characters,source);
        require(Set.of("ABSENT","EXPLICIT","UNKNOWN_NO_TREE").contains(text(value,"receiver")) && Set.of("PRESENT","ABSENT","UNKNOWN_NO_TREE").contains(text(value,"body")),"native method flags differ");
        JsonNode body=value.get("bodySource");
        if(text(value,"body").equals("ABSENT")){keys(body,"knowledge");require(text(body,"knowledge").equals("NO_BODY"),"native absent body span");}
        else span(body,characters,source && text(value,"body").equals("PRESENT"));
        if(source)require(!text(value,"receiver").equals("UNKNOWN_NO_TREE") && !text(value,"body").equals("UNKNOWN_NO_TREE"),"source method lacks tree facts");
    }
    private static void field(JsonNode value,SourceCoordinates characters) {
        keys(value,"owner name elementKind declaredType effectiveType declaredErasedType effectiveErasedType modifiers source sourceDeclaration");
        for(String name:List.of("owner","name","declaredType","effectiveType","declaredErasedType","effectiveErasedType"))text(value,name);
        require(Set.of("FIELD","ENUM_CONSTANT").contains(text(value,"elementKind")),"unsupported native field");strings(value.get("modifiers"));span(value.get("source"),characters,bool(value,"sourceDeclaration"));
    }
    private static void span(JsonNode value,SourceCoordinates characters,boolean required) {
        require(value!=null && value.isObject(),"native source location required");
        String knowledge=text(value,"knowledge");
        if(!knowledge.equals("SOURCE_SPAN")) {keys(value,"knowledge");require(!required && Set.of("NO_SOURCE_TREE","NO_STABLE_SOURCE_SPAN").contains(knowledge),"missing native source span");return;}
        keys(value,"knowledge uri start end startLine endLine");String source=characters.get(text(value,"uri"));
        long start=integer(value,"start",0),end=integer(value,"end",1);
        require(source!=null && start<end && end<=source.length() && integer(value,"startLine",1)<=integer(value,"endLine",1),"native source span outside returned characters");
        String uri=text(value,"uri");
        require(integer(value,"startLine",1)==characters.line(uri,start) && integer(value,"endLine",1)==characters.line(uri,end-1),"native line coordinates differ from source snapshot");
    }
    static byte[] bounded(Path file,int limit) throws IOException {
        require(file.toAbsolutePath().equals(file.toRealPath(LinkOption.NOFOLLOW_LINKS)) && !Files.isSymbolicLink(file),"aliased native input");
        BasicFileAttributes before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(before.isRegularFile() && before.size()<=limit,"native file bound/type");
        byte[] bytes;
        try(var input=Files.newInputStream(file)){bytes=input.readNBytes(limit+1);}
        BasicFileAttributes after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(bytes.length==before.size() && before.size()==after.size() && Objects.equals(before.fileKey(),after.fileKey())
                && before.lastModifiedTime().equals(after.lastModifiedTime()) && before.creationTime().equals(after.creationTime()),"native input changed during read");
        return bytes;
    }
    static String sourceUri(String value) {
        var uri=java.net.URI.create(value);
        require("file".equals(uri.getScheme()) && uri.getQuery()==null && uri.getFragment()==null,"native source URI must be a file");
        Path path=Path.of(uri);
        require(path.isAbsolute() && path.normalize().equals(path),"native source URI must be absolute and normalized");
        return path.toUri().toASCIIString();
    }
    static void keys(JsonNode node,String names) {
        require(node!=null && node.isObject(),"native object expected");Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(Set.of(names.split(" "))),"unexpected native fields");
    }
    static String text(JsonNode node,String name) {
        JsonNode value=node==null?null:node.get(name);require(value!=null && value.isTextual() && !value.asText().isEmpty() && value.asText().length()<=65536 && value.asText().indexOf('\0')<0,"invalid native text: "+name);return value.asText();
    }
    static long integer(JsonNode node,String name,long minimum) {
        JsonNode value=node.get(name);require(value!=null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue()>=minimum,"invalid native integer: "+name);return value.longValue();
    }
    static boolean bool(JsonNode node,String name) {JsonNode value=node.get(name);require(value!=null && value.isBoolean(),"native boolean required");return value.booleanValue();}
    private static boolean isFalse(JsonNode node,String name){return !bool(node,name);}
    private static String digest(JsonNode node,String name){String value=text(node,name);require(value.matches("[0-9a-f]{64}"),"invalid native digest");return value;}
    static List<String> strings(JsonNode node) {require(node!=null && node.isArray(),"native string array required");var values=new ArrayList<String>();for(JsonNode item:node){require(item.isTextual() && !item.asText().isEmpty() && item.asText().length()<=65536 && item.asText().indexOf('\0')<0,"invalid native string");values.add(item.asText());}return List.copyOf(values);}
    static JsonNode array(JsonNode node,String name){JsonNode value=node.get(name);require(value!=null && value.isArray(),"native array required: "+name);return value;}
    private static Map<String,String> hashMap(JsonNode node){require(node!=null && node.isObject(),"native hash map required");var values=new TreeMap<String,String>();node.fieldNames().forEachRemaining(name->{require(!name.isEmpty() && name.indexOf('\0')<0,"invalid native URI");values.put(name,digest(node,name));});return values;}
    static void require(boolean value,String reason){if(!value)throw new IllegalArgumentException(reason);}
}
