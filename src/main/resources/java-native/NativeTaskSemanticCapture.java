package org.metamodel.nativeworld;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import static org.metamodel.nativeworld.NativeSemanticWorld.*;

/** Source facts from the author's task; no second compiler, policy or read-closure claim. */
public final class NativeTaskSemanticCapture implements Plugin,TaskListener {
    private final SourceCensus census=new SourceCensus();
    private final Map<String,Object> records=new TreeMap<String,Object>();
    private final Map<String,Object> sources=new TreeMap<String,Object>();
    private JavacTask task;private Export export;private Path output;
    private String attempt,strategy;private long invocation;private boolean complete;
    private Throwable firstFailure;

    public String getName(){return "ConformanceSemanticTrace";}
    public void init(JavacTask task,String... args) {
        if(this.task!=null || args.length!=3 || !args[1].matches("[0-9a-f]{32}") || !args[2].matches("[1-9][0-9]*"))
            throw new IllegalArgumentException("fresh semantic capture requires output token, attempt and invocation");
        this.task=task;attempt=args[1];invocation=Long.parseLong(args[2]);
        output=Paths.get(new String(Base64.getUrlDecoder().decode(args[0]),StandardCharsets.UTF_8));
        if(!output.isAbsolute() || !output.normalize().equals(output) || Files.exists(output))throw new IllegalArgumentException("fresh absolute semantic output required");
        for(Path p=output;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IllegalArgumentException("aliased semantic output");
        boolean legacy8="1.8".equals(System.getProperty("java.specification.version"));
        strategy=legacy8?"PROCESS_SHUTDOWN_WITH_OBSERVED_EXIT_REQUIRED":"TASK_COMPILATION_EVENT";
        task.addTaskListener(this);
        if(legacy8)Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){public void run(){finish();}},"conformance-semantic-process-completion"));
    }

    public void started(TaskEvent event) { }
    public synchronized void finished(TaskEvent event) {
        try {
            if(event.getKind()==TaskEvent.Kind.PARSE) {
                CompilationUnitTree unit=event.getCompilationUnit();census.capture(unit);
                if(sources.size()>=4096 || census.classes.size()>4094)throw new IllegalStateException("native semantic source population bound");
                String characters=unit.getSourceFile().getCharContent(true).toString();
                if(characters.length()>16*1024*1024)throw new IllegalStateException("native semantic character snapshot bound");
                String uri=unit.getSourceFile().toUri().toASCIIString();
                if(sources.put(uri,row("uri",uri,"reportedSourceCharSha256",hex(characters.getBytes(StandardCharsets.UTF_8)),
                        "point","PARSE_RETURNED_CHARACTER_SNAPSHOT_NOT_CONSUMED_BUFFER"))!=null)
                    throw new IllegalStateException("repeated native semantic source URI");
            }
            if(event.getKind()==TaskEvent.Kind.ANALYZE && event.getTypeElement()!=null && census.classBearingUnits.contains(event.getCompilationUnit())) {
                // Compiler semantic services belong to the analysis lifecycle,
                // after module setup, not plugin-discovery initialization.
                if(export==null)export=new Export(task,census);
                census.bind(event.getCompilationUnit(),export.trees);
                TreePath path=census.sourcePaths.get(event.getTypeElement());
                if(path==null)path=export.trees.getPath(event.getTypeElement());
                if(path==null)throw new IllegalStateException("analyzed source class has no source tree: "+event.getTypeElement().getQualifiedName()+" in "+event.getCompilationUnit().getSourceFile().toUri());
                new TreePathScanner<Void,Void>() {
                    public Void visitClass(ClassTree tree,Void unused) {
                        if(!census.declarations.contains(tree))return super.visitClass(tree,unused);
                        Element element=export.trees.getElement(getCurrentPath());
                        if(!(element instanceof TypeElement))throw new IllegalStateException("analyzed source class unresolved");
                        TypeElement type=(TypeElement)element;String name=export.elements.getBinaryName(type).toString();
                        if(!records.containsKey(name))records.put(name,export.type(type));
                        return super.visitClass(tree,unused);
                    }
                }.scan(path,null);
            }
            if(event.getKind().name().equals("COMPILATION"))finish();
        } catch(Exception failure){if(firstFailure==null)firstFailure=failure;throw new IllegalStateException("native semantic task capture failed",failure);}
    }

    private synchronized void finish() {
        if(complete || firstFailure!=null)return;
        try {
            if(records.size()!=census.classes.size())throw new IllegalStateException("native analyzed source census incomplete: analyzed="+records.size()+", parsed="+census.classes.size()+", names="+records.keySet());
            List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
            rows.add(row("kind","HEADER","format","native-javac-build-source-world-v1","attempt",attempt,"invocation",invocation,
                    "javaVersion",System.getProperty("java.version"),"javaHome",System.getProperty("java.home"),
                    "compilerTaskClass",task.getClass().getName(),"capturePoint","ANALYZE_FINISHED_BEFORE_GENERATION",
                    "completionStrategy",strategy,"parsedSources",new ArrayList<Object>(sources.values()),
                    "consumptionCertified",false,"binaryConsumptionCertified",false));
            for(Object record:records.values()) {
                @SuppressWarnings("unchecked") Map<String,Object> typed=(Map<String,Object>)record;rows.add(typed);
            }
            rows.add(row("kind","COMPLETE","attempt",attempt,"invocation",invocation,"completion",strategy,
                    "parsedUnits",sources.size(),"sourceTypes",records.size(),"canonicalAdmissionCertified",false));
            StringBuilder payload=new StringBuilder();int index=0;
            for(Map<String,Object> record:rows) {
                record.put("index",++index);
                if("COMPLETE".equals(record.get("kind")))record.put("priorRowsSha256",hex(payload.toString().getBytes(StandardCharsets.UTF_8)));
                String encoded=json(record)+"\n";
                if(encoded.length()>8*1024*1024 || payload.length()+encoded.length()>64*1024*1024)throw new IllegalStateException("native semantic receipt bound");
                payload.append(encoded);
            }
            Files.write(output,payload.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            complete=true;
        } catch(Exception failure){throw new IllegalStateException("native semantic completion failed; successful observed compiler exit remains required",failure);}
    }
}
