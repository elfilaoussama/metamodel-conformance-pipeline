package metamodel.conformance.pipeline.adapter.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import metamodel.conformance.pipeline.model.*;
import metamodel.conformance.pipeline.util.Hashing;

import java.nio.file.Path;
import java.util.*;

import static metamodel.conformance.pipeline.adapter.java.JavaNativeReceipt.*;

/** Mechanical source-declaration projection of successful native facts; no invariant policy. */
final class JavaNativeProjection {
    private JavaNativeProjection() { }
    record World(JavaCompilationContext context,NativeEvidence.MeasurementMode mode,
                 String inputIdentity,String provenance,NativeEvidence.Invocation invocation) {
        World {
            Objects.requireNonNull(context);Objects.requireNonNull(mode);Objects.requireNonNull(invocation);
            require(inputIdentity!=null && inputIdentity.matches("[0-9a-f]{64}") && provenance!=null && !provenance.isBlank(),"native projection origin required");
            require(context.id().equals(invocation.contextId()),"native projection/invocation context differs");
        }
    }
    static Observation project(JavaNativeConfiguration config,Path originalRoot,JavaNativeCompiler.Capture capture) throws java.io.IOException {
        String contextId="native-source-only";
        var context=new JavaCompilationContext(contextId,".",List.of("."),List.of(),List.of(),List.of(),Set.of(),
                new JavaCompilerSemantics(config.sourceLevel(),config.targetLevel(),config.releaseLevel(),false,"native-selected"));
        List<SourceUnit> units=capture.sourcesByUri().values().stream().sorted(Comparator.comparing(SourceUnit::path)).toList();
        String identity=Hashing.sha256(new ObjectMapper().writeValueAsString(List.of(capture.toolIdentity(),capture.semanticArguments(),Hashing.sourceSetDigest(units),capture.reportedInputs())));
        var invocation=new NativeEvidence.Invocation(contextId,config.javaHome().resolve("bin/java").toString(),originalRoot.toString(),capture.semanticArguments(),capture.toolIdentity(),
                NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN,"Returned wrapper snapshots do not establish full consumed-input closure"),NativeEvidence.Evidence.known(List.of()));
        return project(capture,new World(context,NativeEvidence.MeasurementMode.SOURCE_ONLY,identity,
                "Actual selected native javac; source declarations only. Whole-build, loaded-image and unique binary-origin closure are not certified.",invocation));
    }

    static Observation project(JavaNativeCompiler.Capture capture,World world) throws java.io.IOException {
        List<JsonNode> types=capture.receipt().types();Map<String,String> classIds=new TreeMap<>(),methodIds=new TreeMap<>(),fieldIds=new TreeMap<>();
        Map<String,List<String>> declared=new HashMap<>();
        List<MemberObservation> members=new ArrayList<>();List<MethodBodyObservation> bodies=new ArrayList<>();
        List<ImplementationBindingObservation> bindings=new ArrayList<>();
        Map<String,NativeEvidence.Evidence<JavacDirectParentNames.DeclaredBase>> directBases=new TreeMap<>();
        Map<String,JavacDirectParentNames.MethodTypes> signatures=new TreeMap<>();
        for(JsonNode type:types) {
            SourceUnit unit=unit(capture,type.get("source"));
            String id=id("cls_","native-javac-source-class-v1",unit.path(),text(type,"binaryName"));
            classIds.put(text(type,"binaryName"),id);declared.put(id,new ArrayList<>());
        }
        for(JsonNode type:types) {
            String owner=classIds.get(text(type,"binaryName"));ClassifierKind kind=kind(type);
            for(JsonNode method:array(type,"declarations")) {
                if(!bool(method,"sourceDeclaration"))continue;
                JsonNode source=method.get("source");SourceUnit unit=unit(capture,source);
                String key=id("mem_","native-javac-source-method-v1",unit.path(),text(method,"key"));
                require(methodIds.put(text(method,"key"),key)==null,"duplicate native source method");declared.get(owner).add(key);
                Set<String> flags=Set.copyOf(strings(method.get("modifiers")));
                members.add(new MemberObservation(key,text(method,"key"),MemberKind.METHOD,inheritability(flags,kind,true),visibility(flags),
                        text(method,"name"),unit.path(),line(source,"startLine"),line(source,"endLine"),strings(method.get("declaredParameters")),
                        flags.contains("ABSTRACT")?MethodAbstraction.ABSTRACT:MethodAbstraction.CONCRETE,
                        flags.contains("STATIC")?MemberScope.STATIC:MemberScope.INSTANCE,text(method,"declaredReturn"),List.of()));
                NativeEvidence.Evidence<String> receiver=text(method,"receiver").equals("ABSENT")?NativeEvidence.Evidence.known(List.of())
                        :NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNSUPPORTED,"Explicit Java receiver syntax is observed but qualifier tokens are not represented");
                signatures.put(key,new JavacDirectParentNames.MethodTypes(NativeEvidence.Evidence.known(strings(method.get("declaredParameters")).stream()
                        .map(value -> new NativeEvidence.Parameter(value,NativeEvidence.TypeMeaning.COMPILER_TYPE)).toList()),receiver,
                        NativeEvidence.Evidence.known(List.of(new NativeEvidence.TypeToken(text(method,"declaredReturn"),NativeEvidence.TypeMeaning.COMPILER_TYPE)))));
                if(text(method,"body").equals("PRESENT")) {
                    JsonNode body=method.get("bodySource");require(unit.equals(unit(capture,body)),"native body source differs from declaration");
                    String bodyKey=id("body_","native-javac-source-body-v1",unit.path(),Long.toString(integer(body,"start",0)),Long.toString(integer(body,"end",1)));
                    bodies.add(new MethodBodyObservation(bodyKey,unit.path(),line(body,"startLine"),line(body,"endLine")));
                    bindings.add(new ImplementationBindingObservation(id("bind_","native-javac-source-binding-v1",owner,key,bodyKey),owner,key,bodyKey));
                }
            }
            for(JsonNode field:array(type,"declaredFields")) {
                if(!bool(field,"sourceDeclaration"))continue;
                JsonNode source=field.get("source");SourceUnit unit=unit(capture,source);
                String nativeKey=fieldKey(field),key=id("mem_","native-javac-source-field-v1",unit.path(),nativeKey);
                require(fieldIds.put(nativeKey,key)==null,"duplicate native source field");declared.get(owner).add(key);
                Set<String> flags=Set.copyOf(strings(field.get("modifiers")));
                members.add(new MemberObservation(key,nativeKey,MemberKind.ATTRIBUTE,inheritability(flags,kind,false),visibility(flags),
                        text(field,"name"),unit.path(),line(source,"startLine"),line(source,"endLine"),List.of(),MethodAbstraction.UNKNOWN,MemberScope.UNKNOWN,null,List.of()));
            }
        }
        List<ClassifierObservation> classifiers=new ArrayList<>();List<UnresolvedParent> unresolved=new ArrayList<>();
        boolean overrideComplete=true;
        Map<String,List<String>> overrides=new HashMap<>();
        for(JsonNode type:types)for(JsonNode pair:array(type,"overrides")) {
            String method=methodIds.get(text(pair,"method"));if(method==null)continue;
            String target=methodIds.get(text(pair,"target"));
            if(target==null){overrideComplete=false;continue;}
            overrides.computeIfAbsent(method,ignored -> new ArrayList<>()).add(target);
        }
        for(JsonNode type:types) {
            String owner=classIds.get(text(type,"binaryName"));JsonNode source=type.get("source");SourceUnit unit=unit(capture,source);
            List<String> parents=new ArrayList<>(),inherited=new ArrayList<>();List<JavacDirectParentNames.DeclaredBase> bases=new ArrayList<>();
            for(JsonNode base:array(type,"writtenBases")) {
                String target=bool(base,"sourceDeclaration")?classIds.get(text(base,"binaryName")):null;
                bases.add(new JavacDirectParentNames.DeclaredBase(text(base,"spelling"),text(base,"binaryName"),target,bool(base,"sourceDeclaration")));
            }
            for(JsonNode base:array(type,"effectiveBases")) {
                String target=bool(base,"sourceDeclaration")?classIds.get(text(base,"binaryName")):null;
                // The source-declaration universe omits the compiler's binary root
                // Object declaration; no other anonymous/implicit parent is guessed.
                if(target==null && bool(base,"rootObject"))continue;
                if(target==null)unresolved.add(new UnresolvedParent(owner,text(base,"binaryName"),unit.path(),line(source,"startLine")));
                else parents.add(target);
            }
            directBases.put(owner,NativeEvidence.Evidence.known(bases));
            for(JsonNode method:array(type,"lookup")) {
                if(!bool(method,"sourceDeclaration"))continue;
                String target=methodIds.get(text(method,"key"));require(target!=null,"native source lookup method has no declaration");
                if(!declared.get(owner).contains(target))inherited.add(target);
            }
            Set<String> hiddenFields=new HashSet<>();
            for(JsonNode hiding:array(type,"fieldHiding"))
                hiddenFields.add(text(hiding,"hiddenOwner")+"#field:"+text(hiding,"name"));
            for(JsonNode field:array(type,"lookupFields")) {
                if(!bool(field,"sourceDeclaration"))continue;
                if(hiddenFields.contains(fieldKey(field)))continue;
                String target=fieldIds.get(fieldKey(field));require(target!=null,"native source lookup field has no declaration");
                if(!declared.get(owner).contains(target))inherited.add(target);
            }
            String packageName=type.path("packageName").asText();if(packageName.isEmpty())packageName="<default>";
            classifiers.add(new ClassifierObservation(owner,text(type,"binaryName"),packageName,kind(type),unit.path(),line(source,"startLine"),line(source,"endLine"),
                    parents,declared.get(owner),inherited,strings(type.get("modifiers")).contains("ABSTRACT")?ClassifierAbstraction.ABSTRACT:ClassifierAbstraction.CONCRETE));
        }
        members=members.stream().map(member -> new MemberObservation(member.technicalKey(),member.observedIdentifier(),member.kind(),member.inheritability(),member.visibility(),member.memberName(),
                member.sourcePath(),member.startLine(),member.endLine(),member.parameterTypes(),member.abstraction(),member.scope(),member.returnType(),overrides.getOrDefault(member.technicalKey(),List.of()))).toList();
        // Completeness is for this explicitly source-declaration universe, not binary/build closure.
        EnumSet<EvidenceKind> evidence=EnumSet.of(EvidenceKind.DECLARATION_OWNERSHIP,EvidenceKind.LOCAL_SIGNATURES,
                EvidenceKind.INHERITABILITY,EvidenceKind.METHOD_BODIES,EvidenceKind.METHOD_ABSTRACTION,EvidenceKind.IMPLEMENTATION_BINDINGS,
                EvidenceKind.CLASSIFIER_ABSTRACTION,EvidenceKind.METHOD_SCOPE,EvidenceKind.METHOD_RETURN_TYPES);
        if(unresolved.isEmpty()) {evidence.add(EvidenceKind.HIERARCHY);evidence.add(EvidenceKind.INHERITED_MEMBERS);}
        if(overrideComplete && unresolved.isEmpty())evidence.add(EvidenceKind.OVERRIDE_RELATIONS);
        List<SourceUnit> units=capture.sourcesByUri().values().stream().sorted(Comparator.comparing(SourceUnit::path)).toList();
        List<ObservationDiagnostic> diagnostics=new ArrayList<>();
        if(!evidence.contains(EvidenceKind.HIERARCHY) || !evidence.contains(EvidenceKind.OVERRIDE_RELATIONS))diagnostics.add(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE,units.get(0).path(),0,
                "Native source-declaration profile has unqualified external base/override correspondence; affected evidence remains incomplete"));
        Observation physical=new Observation("17",NativeJavacSourceObserver.ADAPTER_ID,NativeJavacSourceObserver.ADAPTER_VERSION,List.of(),evidence,units,classifiers,members,bodies,bindings,unresolved,diagnostics,List.of());
        String contextId=world.context().id();
        boolean sourceOnly=world.mode()==NativeEvidence.MeasurementMode.SOURCE_ONLY;
        Observation qualified=JavaNativeDeclarations.populate(world.context(),JavaDependencyInputs.none(),physical,
                new JavacDirectParentNames.Result(Map.of(),directBases,signatures),
                new JavaContextualObservation.DomainIdentity(world.inputIdentity(),world.provenance(),
                        classifiers.stream().map(ClassifierObservation::id).toList(),members.stream().map(MemberObservation::technicalKey).toList()),
                world.mode(),NativeEvidence.View.SOURCE_DECLARATIONS,
                sourceOnly?"Source-only target population; project-build ownership is not claimed":"Native PARSE population of the selected successful author compilation; generation remains unknown");
        var bundle=qualified.nativeEvidence();
        List<NativeEvidence.BodyBinding> nativeBodies=new ArrayList<>();
        Map<String,MethodBodyObservation> bodyByMember=new HashMap<>();
        Map<String,MethodBodyObservation> bodyById=new HashMap<>();for(var body:qualified.methodBodies())bodyById.put(body.technicalKey(),body);
        for(var binding:qualified.implementationBindings())bodyByMember.put(binding.targetMemberKey(),bodyById.get(binding.bodyKey()));
        for(var classifier:qualified.classifiers())for(String key:classifier.declaredMemberKeys()) {
            MemberObservation member=qualified.members().stream().filter(item -> item.technicalKey().equals(key)).findFirst().orElseThrow();
            if(member.kind()!=MemberKind.METHOD)continue;
            MethodBodyObservation body=bodyByMember.get(key);
            // BodyBinding describes an observed body, not an absent optional span.
            // Body-less source methods remain members; native census retains NO_BODY.
            if(body==null)continue;
            nativeBodies.add(new NativeEvidence.BodyBinding(contextId,classifier.id(),key,
                    NativeEvidence.Evidence.known(List.of(new NativeEvidence.SourceSpan(body.sourcePath(),body.startLine(),body.endLine()))),
                    NativeEvidence.Evidence.known(List.of(key))));
        }
        Map<String,Object> census=new TreeMap<>();for(JsonNode type:types)census.put(text(type,"binaryName"),type.get("sourceCensus"));
        var inventory=new NativeEvidence.ReportedInputs(contextId,capture.receipt().format,sourceOnly?"Selected source-only compiler and declared input inventory":"Captured author compiler task and reported input inventory",capture.reportedInputs(),
                List.of(new NativeEvidence.ConfigurationEntry("compilerVersion",NativeEvidence.Evidence.known(List.of(capture.receipt().javaVersion))),
                        new NativeEvidence.ConfigurationEntry("sourceCensus",NativeEvidence.Evidence.known(List.of(new ObjectMapper().writeValueAsString(census))))),
                sourceOnly?"No project-build ownership, unique binary origin, complete consumed/loaded-image closure or arbitrary-language correctness claim. Binary Object, generated members and constructors remain outside the source METHOD/ATTRIBUTE universe."
                        :"Selected successful author task, source declarations only; unique binary origin and complete consumed/loaded-image closure remain unproven. Binary Object, generated members and constructors remain outside the source METHOD/ATTRIBUTE universe.");
        return new Observation(qualified.schemaVersion(),NativeJavacSourceObserver.ADAPTER_ID,NativeJavacSourceObserver.ADAPTER_VERSION,
                qualified.externalParents(),qualified.completeEvidence(),qualified.units(),qualified.classifiers(),qualified.members(),qualified.methodBodies(),qualified.implementationBindings(),
                qualified.unresolvedParents(),qualified.diagnostics(),qualified.platformEvidence(),new NativeEvidence.Bundle(bundle.domains(),bundle.roles(),List.of(world.invocation()),bundle.bases(),bundle.signatures(),List.of(),nativeBodies,List.of(inventory)));
    }
    private static SourceUnit unit(JavaNativeCompiler.Capture capture,JsonNode span) {SourceUnit unit=capture.unit(text(span,"uri"));require(unit!=null,"native source origin is not selected");return unit;}
    private static int line(JsonNode node,String field){return Math.toIntExact(integer(node,field,1));}
    private static String fieldKey(JsonNode field){return text(field,"owner")+"#field:"+text(field,"name");}
    private static String id(String prefix,String format,String... cells){return prefix+Hashing.sha256(format+"\0"+String.join("\0",cells));}
    private static ClassifierKind kind(JsonNode type){return switch(text(type,"typeKind")){case "CLASS"->ClassifierKind.CLASS;case "INTERFACE"->ClassifierKind.INTERFACE;case "ENUM"->ClassifierKind.ENUM;case "ANNOTATION_TYPE"->ClassifierKind.ANNOTATION;default->throw new IllegalArgumentException("unsupported native classifier kind");};}
    private static Inheritability inheritability(Set<String> flags,ClassifierKind owner,boolean method){return flags.contains("PRIVATE") || (method && (owner==ClassifierKind.INTERFACE || owner==ClassifierKind.ANNOTATION) && flags.contains("STATIC"))?Inheritability.NOT_INHERITABLE:Inheritability.INHERITABLE;}
    private static MemberVisibility visibility(Set<String> flags){return flags.contains("PUBLIC")?MemberVisibility.PUBLIC:flags.contains("PROTECTED")?MemberVisibility.PROTECTED:flags.contains("PRIVATE")?MemberVisibility.PRIVATE:MemberVisibility.PACKAGE;}
}
