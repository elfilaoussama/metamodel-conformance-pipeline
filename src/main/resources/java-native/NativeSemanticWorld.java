package org.metamodel.nativeworld;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;

/** Portable native source facts. No invariant policy or automatic canonical admission. */
public final class NativeSemanticWorld {
    /** Capture syntax membership before attribution inserts compiler declarations. */
    static final class SourceCensus {
        final Map<ClassTree,Members> classes=new IdentityHashMap<ClassTree,Members>();
        final Set<Tree> declarations=Collections.newSetFromMap(new IdentityHashMap<Tree,Boolean>());
        final Map<Element,TreePath> sourcePaths=new IdentityHashMap<Element,TreePath>();
        final Set<CompilationUnitTree> classBearingUnits=Collections.newSetFromMap(new IdentityHashMap<CompilationUnitTree,Boolean>());
        private static final class Members {
            final List<MethodTree> methods=new ArrayList<MethodTree>();
            final List<MethodTree> constructors=new ArrayList<MethodTree>();
            final List<VariableTree> fields=new ArrayList<VariableTree>();
            final List<Tree> writtenBases=new ArrayList<Tree>();
        }
        synchronized void capture(CompilationUnitTree unit) {
            new TreePathScanner<Void,Void>() {
                public Void visitClass(ClassTree tree,Void unused) {
                    String kind=tree.getKind().name();
                    if(!Arrays.asList("CLASS","INTERFACE","ENUM","ANNOTATION_TYPE").contains(kind))
                        throw new IllegalStateException("unsupported source classifier kind: "+kind);
                    classBearingUnits.add(unit);
                    if(classes.containsKey(tree))throw new IllegalStateException("duplicate parsed class");
                    Members members=new Members();classes.put(tree,members);declarations.add(tree);
                    if(tree.getExtendsClause()!=null)members.writtenBases.add(tree.getExtendsClause());
                    members.writtenBases.addAll(tree.getImplementsClause());
                    for(Tree member:tree.getMembers()) {
                        if(member instanceof MethodTree) {
                            MethodTree method=(MethodTree)member;declarations.add(method);
                            (method.getReturnType()==null?members.constructors:members.methods).add(method);
                        } else if(member instanceof VariableTree) {
                            members.fields.add((VariableTree)member);declarations.add(member);
                        }
                    }
                    return super.visitClass(tree,unused);
                }
            }.scan(unit,null);
        }
        synchronized void bind(CompilationUnitTree unit,final Trees trees) {
            new TreePathScanner<Void,Void>() {
                public Void scan(Tree tree,Void unused) {
                    if(tree!=null && declarations.contains(tree)) {
                        TreePath path=getCurrentPath()==null?new TreePath((CompilationUnitTree)tree):new TreePath(getCurrentPath(),tree);
                        Element element=trees.getElement(path);
                        if(element!=null)sourcePaths.put(element,path);
                    }
                    return super.scan(tree,unused);
                }
            }.scan(unit,null);
        }
    }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map) {
            List<String> cells=new ArrayList<String>();
            for (Object entry : ((Map<?,?>)value).entrySet()) {
                Map.Entry<?,?> item=(Map.Entry<?,?>)entry;
                cells.add(json(item.getKey().toString())+":"+json(item.getValue()));
            }
            return "{"+String.join(",",cells)+"}";
        }
        if (value instanceof Iterable) {
            List<String> cells=new ArrayList<String>();for(Object item:(Iterable<?>)value)cells.add(json(item));
            return "["+String.join(",",cells)+"]";
        }
        String text=value.toString();StringBuilder result=new StringBuilder("\"");
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='"'||c=='\\')result.append('\\').append(c);
            else if(c<32||c>126)result.append(String.format("\\u%04x",(int)c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
    static Map<String,Object> row(Object... entries) {
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        for(int i=0;i<entries.length;i+=2)result.put((String)entries[i],entries[i+1]);return result;
    }
    private static List<String> flags(Element element) {
        List<String> result=new ArrayList<String>();for(Modifier flag:element.getModifiers())result.add(flag.name());
        Collections.sort(result);return result;
    }
    static String hex(byte[] data) throws Exception {
        StringBuilder value=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))value.append(String.format("%02x",b&255));return value.toString();
    }
    static final class Export {
        final Trees trees;final Elements elements;final Types types;final SourceCensus census;
        // javac may clear a previously generated owner's source trees before
        // another source class finishes analysis. Retain source metadata while
        // it is available; never relabel that declaration as binary afterwards.
        final Map<Element,SourceMetadata> sourceMetadata=new IdentityHashMap<Element,SourceMetadata>();
        private static final class SourceMetadata {
            final Tree tree;final TreePath path;final boolean declared;final Map<String,Object> location,bodyLocation;
            final String receiver,body;
            SourceMetadata(Tree tree,TreePath path,boolean declared,Map<String,Object> location,
                    String receiver,String body,Map<String,Object> bodyLocation) {
                this.tree=tree;this.path=path;this.declared=declared;this.location=location;
                this.receiver=receiver;this.body=body;this.bodyLocation=bodyLocation;
            }
        }
        Export(JavacTask task,SourceCensus census){trees=Trees.instance(task);elements=task.getElements();types=task.getTypes();this.census=census;}
        SourceMetadata source(Element element) {
            SourceMetadata previous=sourceMetadata.get(element);if(previous!=null)return previous;
            TreePath path=census.sourcePaths.get(element);if(path==null)path=trees.getPath(element);Tree tree=path==null?null:path.getLeaf();
            MethodTree method=tree instanceof MethodTree?(MethodTree)tree:null;
            TreePath bodyPath=method==null||method.getBody()==null?null:TreePath.getPath(path.getCompilationUnit(),method.getBody());
            SourceMetadata result=new SourceMetadata(tree,path,census.declarations.contains(tree),location(path),
                    method==null?"UNKNOWN_NO_TREE":method.getReceiverParameter()==null?"ABSENT":"EXPLICIT",
                    method==null?"UNKNOWN_NO_TREE":method.getBody()==null?"ABSENT":"PRESENT",
                    bodyPath==null?row("knowledge",method!=null&&method.getBody()==null?"NO_BODY":"NO_SOURCE_TREE"):location(bodyPath));
            // A binary/unresolved element can acquire a source tree later in
            // the task. Only positive census correspondence is retained.
            if(result.declared)sourceMetadata.put(element,result);
            return result;
        }
        String owner(ExecutableElement method){return elements.getBinaryName((TypeElement)method.getEnclosingElement()).toString();}
        List<String> parameters(List<? extends TypeMirror> parameters) {
            List<String> values=new ArrayList<String>();for(TypeMirror parameter:parameters)values.add(parameter.toString());return values;
        }
        List<String> declaredParameters(ExecutableElement method) {
            List<String> values=new ArrayList<String>();for(VariableElement parameter:method.getParameters())values.add(parameter.asType().toString());return values;
        }
        List<String> erased(List<? extends TypeMirror> parameters) {
            List<String> values=new ArrayList<String>();for(TypeMirror parameter:parameters)values.add(types.erasure(parameter).toString());return values;
        }
        String key(ExecutableElement method) {
            return owner(method)+"#"+method.getSimpleName()+"("+String.join(",",declaredParameters(method))+"):"+method.getReturnType();
        }
        Map<String,Object> location(Element element) {
            return source(element).location;
        }
        Map<String,Object> location(TreePath path) {
            if(path==null)return row("knowledge","NO_SOURCE_TREE");
            CompilationUnitTree unit=path.getCompilationUnit();
            long start=trees.getSourcePositions().getStartPosition(unit,path.getLeaf());
            long end=trees.getSourcePositions().getEndPosition(unit,path.getLeaf());
            if(start<0||end<=start)return row("knowledge","NO_STABLE_SOURCE_SPAN");
            return row("knowledge","SOURCE_SPAN","uri",unit.getSourceFile().toUri().toASCIIString(),
                    "start",start,"end",end,"startLine",unit.getLineMap().getLineNumber(start),"endLine",unit.getLineMap().getLineNumber(end-1));
        }
        Map<String,Object> method(ExecutableElement method,TypeElement viewedFrom) {
            ExecutableType effective=(ExecutableType)types.asMemberOf((DeclaredType)viewedFrom.asType(),method);
            List<TypeMirror> declared=new ArrayList<TypeMirror>();for(VariableElement parameter:method.getParameters())declared.add(parameter.asType());
            SourceMetadata syntax=source(method);
            return row("key",key(method),"owner",owner(method),"name",method.getSimpleName().toString(),
                    "declaredParameters",declaredParameters(method),"declaredReturn",method.getReturnType().toString(),
                    "declaredErasedParameters",erased(declared),"declaredErasedReturn",types.erasure(method.getReturnType()).toString(),
                    "effectiveParameters",parameters(effective.getParameterTypes()),"effectiveReturn",effective.getReturnType().toString(),
                    "effectiveErasedParameters",erased(effective.getParameterTypes()),"effectiveErasedReturn",types.erasure(effective.getReturnType()).toString(),
                    "modifiers",flags(method),"varargs",method.isVarArgs(),"source",syntax.location,
                    "sourceDeclaration",syntax.declared,"receiver",syntax.receiver,"body",syntax.body,"bodySource",syntax.bodyLocation);
        }
        Map<String,Object> field(VariableElement field,TypeElement viewedFrom) {
            TypeMirror declared=field.asType();
            TypeMirror effective=types.asMemberOf((DeclaredType)viewedFrom.asType(),field);
            return row("owner",elements.getBinaryName((TypeElement)field.getEnclosingElement()).toString(),
                    "name",field.getSimpleName().toString(),"elementKind",field.getKind().name(),
                    "declaredType",declared.toString(),"effectiveType",effective.toString(),
                    "declaredErasedType",types.erasure(declared).toString(),
                    "effectiveErasedType",types.erasure(effective).toString(),
                    "modifiers",flags(field),"source",location(field),
                    "sourceDeclaration",source(field).declared);
        }
        void ancestors(TypeMirror type,Set<String> seen,List<ExecutableElement> methods) {
            for(TypeMirror parent:types.directSupertypes(type)) {
                Element element=types.asElement(parent);
                if(!(element instanceof TypeElement))throw new IllegalStateException("unresolved ancestor");
                TypeElement owner=(TypeElement)element;
                if(seen.add(elements.getBinaryName(owner).toString())) {
                    methods.addAll(ElementFilter.methodsIn(owner.getEnclosedElements()));ancestors(parent,seen,methods);
                }
            }
        }
        void hierarchyFields(TypeElement type,Set<String> seen,List<VariableElement> fields) {
            if(!seen.add(elements.getBinaryName(type).toString()))return;
            fields.addAll(ElementFilter.fieldsIn(type.getEnclosedElements()));
            for(TypeMirror parent:types.directSupertypes(type.asType())) {
                Element element=types.asElement(parent);
                if(!(element instanceof TypeElement))throw new IllegalStateException("unresolved field ancestor");
                hierarchyFields((TypeElement)element,seen,fields);
            }
        }
        Map<String,Object> type(TypeElement type) {
            source(type);
            String name=elements.getBinaryName(type).toString();
            if(name.isEmpty())throw new IllegalStateException("source type has no unique binary name");
            if(!Arrays.asList("CLASS","INTERFACE","ENUM","ANNOTATION_TYPE").contains(type.getKind().name()))
                throw new IllegalStateException("unsupported source classifier kind: "+type.getKind().name());
            ClassTree tree=(ClassTree)source(type).tree;SourceCensus.Members syntax=census.classes.get(tree);
            if(syntax==null)throw new IllegalStateException("source class lacks parsed census correspondence");
            List<String> bases=parameters(types.directSupertypes(type.asType()));
            List<Map<String,Object>> effectiveBases=new ArrayList<Map<String,Object>>();
            TypeElement rootObject=elements.getTypeElement("java.lang.Object");
            for(TypeMirror base:types.directSupertypes(type.asType())) {
                Element element=types.asElement(base);
                if(!(element instanceof TypeElement))throw new IllegalStateException("effective base attribution is incomplete");
                TypeElement parent=(TypeElement)element;
                effectiveBases.add(row("spelling",base.toString(),"binaryName",elements.getBinaryName(parent).toString(),
                    "sourceDeclaration",source(parent).declared,"source",location(parent),
                    "rootObject",rootObject!=null&&types.isSameType(types.erasure(base),types.erasure(rootObject.asType()))));
            }
            List<Map<String,Object>> declarations=new ArrayList<Map<String,Object>>();
            List<Map<String,Object>> declaredFields=new ArrayList<Map<String,Object>>();
            List<Map<String,Object>> lookupFields=new ArrayList<Map<String,Object>>();
            List<Map<String,Object>> fieldHiding=new ArrayList<Map<String,Object>>();
            for(VariableElement field:ElementFilter.fieldsIn(type.getEnclosedElements()))declaredFields.add(field(field,type));
            for(VariableElement field:ElementFilter.fieldsIn(elements.getAllMembers(type)))lookupFields.add(field(field,type));
            List<VariableElement> allFields=ElementFilter.fieldsIn(elements.getAllMembers(type));
            // getAllMembers can retain hidden ancestors and omit a private
            // intermediate hider. Query the compiler over the full hierarchy,
            // just as the existing qualified Java inherited-member route does.
            List<VariableElement> hierarchyFields=new ArrayList<VariableElement>();
            hierarchyFields(type,new HashSet<String>(),hierarchyFields);
            for(VariableElement hider:hierarchyFields)for(VariableElement hidden:allFields)if(elements.hides(hider,hidden))
                fieldHiding.add(row("hiderOwner",elements.getBinaryName((TypeElement)hider.getEnclosingElement()).toString(),
                        "hiddenOwner",elements.getBinaryName((TypeElement)hidden.getEnclosingElement()).toString(),
                        "name",hider.getSimpleName().toString()));
            List<ExecutableElement> own=ElementFilter.methodsIn(type.getEnclosedElements());
            for(ExecutableElement method:own)declarations.add(method(method,type));
            int sourceMethods=0,sourceFields=0;
            for(Map<String,Object> method:declarations)if(Boolean.TRUE.equals(method.get("sourceDeclaration")))sourceMethods++;
            for(Map<String,Object> field:declaredFields)if(Boolean.TRUE.equals(field.get("sourceDeclaration")))sourceFields++;
            if(sourceMethods!=syntax.methods.size()||sourceFields!=syntax.fields.size())
                throw new IllegalStateException("parsed/member source census correspondence is incomplete");
            TreePath ownerPath=source(type).path;
            for(MethodTree constructor:syntax.constructors) {
                Element element=trees.getElement(TreePath.getPath(ownerPath.getCompilationUnit(),constructor));
                if(element==null||element.getKind()!=ElementKind.CONSTRUCTOR)
                    throw new IllegalStateException("parsed constructor correspondence is incomplete");
            }
            List<Map<String,Object>> writtenBases=new ArrayList<Map<String,Object>>();
            for(Tree base:syntax.writtenBases) {
                TypeMirror mirror=trees.getTypeMirror(TreePath.getPath(ownerPath.getCompilationUnit(),base));
                Element element=mirror==null?null:types.asElement(mirror);
                if(!(element instanceof TypeElement))throw new IllegalStateException("written base attribution is incomplete");
                TypeElement parent=(TypeElement)element;
                writtenBases.add(row("spelling",base.toString(),"binaryName",elements.getBinaryName(parent).toString(),
                    "sourceDeclaration",source(parent).declared,"source",location(parent)));
            }
            List<Map<String,Object>> lookup=new ArrayList<Map<String,Object>>();
            for(ExecutableElement method:ElementFilter.methodsIn(elements.getAllMembers(type)))lookup.add(method(method,type));
            List<ExecutableElement> ancestors=new ArrayList<ExecutableElement>();ancestors(type.asType(),new HashSet<String>(),ancestors);
            List<Map<String,Object>> overrides=new ArrayList<Map<String,Object>>();
            for(ExecutableElement method:own)for(ExecutableElement target:ancestors)if(elements.overrides(method,target,type))
                overrides.add(row("method",key(method),"target",key(target),"effectiveTarget",method(target,type)));
            Comparator<Map<String,Object>> order=new Comparator<Map<String,Object>>() {
                public int compare(Map<String,Object> first,Map<String,Object> second){return json(first).compareTo(json(second));}
            };
            Collections.sort(declarations,order);Collections.sort(lookup,order);Collections.sort(overrides,order);
            Collections.sort(declaredFields,order);Collections.sort(lookupFields,order);
            Collections.sort(fieldHiding,order);
            return row("kind","TYPE","binaryName",name,"typeKind",type.getKind().name(),"modifiers",flags(type),
                    "packageName",elements.getPackageOf(type).getQualifiedName().toString(),"nestingKind",type.getNestingKind().name(),
                    "writtenBases",writtenBases,"effectiveBases",effectiveBases,"sourceCensus",row("methods",sourceMethods,"fields",sourceFields,
                        "constructors",syntax.constructors.size(),"generatedMethods",declarations.size()-sourceMethods,
                        "generatedFields",declaredFields.size()-sourceFields),
                    "source",location(type),"directSupertypes",bases,"declarations",declarations,"lookup",lookup,"overrides",overrides,
                    "declaredFields",declaredFields,"lookupFields",lookupFields,"fieldHiding",fieldHiding);
        }
    }
    private static Map<String,Object> consumptionSnapshot(ConsumedSourceFileManager captured,String phase) throws Exception {
        return row("kind","CONSUMPTION_SNAPSHOT","phase",phase,
                "sourceCharacters",captured.consumedCharacterHashes(),
                "sourceBytes",captured.consumedByteHashes(JavaFileObject.Kind.SOURCE),
                "classBytes",captured.consumedByteHashes(JavaFileObject.Kind.CLASS),
                "otherBytes",captured.consumedByteHashes(JavaFileObject.Kind.OTHER),
                "retainedBuffers",captured.retainedBuffers(),"binaryConsumptionCertified",false);
    }
    public static void main(String[] args) throws Exception {
        if(args.length<5||!args[1].matches("[0-9a-f]{32}"))throw new IllegalArgumentException("output, attempt, sources and -- compiler options required");
        final Path output=Paths.get(args[0]);if(Files.exists(output))throw new IllegalArgumentException("fresh semantic receipt required");
        int split=Arrays.asList(args).indexOf("--");if(split<3)throw new IllegalArgumentException("explicit sources required");
        final String attempt=args[1];List<String> sources=Arrays.asList(args).subList(2,split);List<String> options=Arrays.asList(args).subList(split+1,args.length);
        if(!options.contains("-proc:none"))throw new IllegalArgumentException("candidate processor profile requires explicit proc:none");
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new IllegalStateException("actual JDK compiler unavailable");
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<JavaFileObject>();
        final List<CompilationUnitTree> units=new ArrayList<CompilationUnitTree>();List<Map<String,Object>> records=new ArrayList<Map<String,Object>>();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)) {
            String routeManager=System.getProperty("conformance.route.manager","base");
            ConsumedSourceFileManager captured=!routeManager.equals("modern-diagnostic")?new ConsumedSourceFileManager(manager):
                    (ConsumedSourceFileManager)Class.forName("org.metamodel.nativeworld.RouteFileManager17").getConstructor(StandardJavaFileManager.class).newInstance(manager);
            JavacTask task=(JavacTask)compiler.getTask(null,captured,diagnostics,options,null,captured.explicit(manager.getJavaFileObjectsFromStrings(sources)));
            if(routeManager.equals("effective-diagnostic")) {
                captured=(ConsumedSourceFileManager)Class.forName("org.metamodel.nativeworld.EffectiveManagerBridge")
                        .getMethod("install",JavacTask.class,ConsumedSourceFileManager.class).invoke(null,task,captured);
            }
            final SourceCensus census=new SourceCensus();
            task.addTaskListener(new TaskListener() {
                public void started(TaskEvent event) { }
                public void finished(TaskEvent event) {if(event.getKind()==TaskEvent.Kind.PARSE){units.add(event.getCompilationUnit());census.capture(event.getCompilationUnit());}}
            });
            for(CompilationUnitTree ignored:task.parse()) { }
            for(Element ignored:task.analyze()) { }
            for(Diagnostic<?> diagnostic:diagnostics.getDiagnostics())if(diagnostic.getKind()==Diagnostic.Kind.ERROR)throw new IllegalStateException("attribution failed: "+diagnostic.getMessage(Locale.ROOT));
            final Export export=new Export(task,census);final Map<String,TypeElement> classes=new TreeMap<String,TypeElement>();
            List<Map<String,Object>> snapshots=new ArrayList<Map<String,Object>>();
            for(final CompilationUnitTree unit:units) {
                census.bind(unit,export.trees);
                Path path=Paths.get(unit.getSourceFile().toUri());byte[] bytes=Files.readAllBytes(path);
                snapshots.add(row("uri",unit.getSourceFile().toUri().toASCIIString(),"sha256",hex(bytes),"point","POST_ATTRIBUTION_FILE_SNAPSHOT"));
                new TreePathScanner<Void,Void>() {
                    public Void visitClass(ClassTree tree,Void unused) {
                        if(!census.declarations.contains(tree))return super.visitClass(tree,unused);
                        Element element=export.trees.getElement(getCurrentPath());
                        if(!(element instanceof TypeElement))throw new IllegalStateException("source class unresolved");
                        TypeElement type=(TypeElement)element;String name=export.elements.getBinaryName(type).toString();
                        if(classes.put(name,type)!=null)throw new IllegalStateException("ambiguous source type");
                        return super.visitClass(tree,unused);
                    }
                }.scan(unit,null);
            }
            if(classes.size()!=census.classes.size())throw new IllegalStateException("parsed class population is incomplete");
            records.add(row("kind","HEADER","format","native-javac-source-world-v6","attempt",attempt,
                    "javaVersion",System.getProperty("java.version"),"javaHome",System.getProperty("java.home"),
                    "compilerTaskClass",task.getClass().getName(),"compilerOptions",new ArrayList<String>(options),
                    "explicitSources",new ArrayList<String>(sources),"parsedSources",snapshots,"consumptionCertified",false,
                    "consumedSourceCharacters",captured.consumedCharacterHashes(),"characterDigestEncoding","UTF16BE_CODE_UNITS_NO_BOM",
                    "binaryConsumptionCertified",false));
            records.add(consumptionSnapshot(captured,"AFTER_ANALYZE_BEFORE_EXPORT"));
            for(TypeElement type:classes.values())records.add(export.type(type));
            records.add(consumptionSnapshot(captured,"AFTER_EXPORT_BEFORE_GENERATE"));
            int generated=0;for(JavaFileObject ignored:task.generate())generated++;
            for(Diagnostic<?> diagnostic:diagnostics.getDiagnostics())if(diagnostic.getKind()==Diagnostic.Kind.ERROR)throw new IllegalStateException("generation failed: "+diagnostic.getMessage(Locale.ROOT));
            captured.verifySourceSnapshots();
            records.add(consumptionSnapshot(captured,"AFTER_GENERATE"));
            records.add(row("kind","COMPLETE","attempt",attempt,"completion","PARSE_ANALYZE_GENERATE_RETURNED",
                    "parsedUnits",units.size(),"sourceTypes",classes.size(),"generatedOutputs",generated,"diagnosticErrors",0,
                    "canonicalAdmissionCertified",false));
        }
        StringBuilder payload=new StringBuilder();int index=0;
        for(Map<String,Object> record:records) {
            record.put("index",++index);
            if("COMPLETE".equals(record.get("kind")))record.put("priorRowsSha256",hex(payload.toString().getBytes(StandardCharsets.UTF_8)));
            payload.append(json(record)).append('\n');
        }
        Files.write(output,payload.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    }
}
