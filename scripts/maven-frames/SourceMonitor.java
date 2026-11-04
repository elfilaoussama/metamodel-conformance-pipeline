package org.metamodel.mavenframes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.apache.maven.execution.MojoExecutionEvent;
import static org.metamodel.mavenframes.CompilerFrames.invoke;

/** Explicit observation-only compiler option augmentation, scoped to the pinned runtime. */
final class SourceMonitor {
    private final Object configuration;
    private final Object originalProcessorPath;
    private final List<Map.Entry<String,String>> originalArguments;
    private final Path output;
    private final boolean selectedNonempty;
    private final Map<String,Object> identity;
    private final String resetMethod;
    private final List<String> processorPath;
    private final String option;

    static void initialize(org.apache.maven.execution.MavenSession session)throws Exception {
        var p=session.getUserProperties();
        if(!p.containsKey("conformance.compiler.sourcePlugin")){
            if(p.containsKey("conformance.compiler.parsedRoot"))throw new IllegalStateException("source plugin required for parsed root");
            return;
        }
        if(!p.containsKey("conformance.compiler.receipt"))throw new IllegalStateException("sealed compiler capture required");
        Path plugin=CompilerFrames.receipt(session,"conformance.compiler.sourcePlugin");
        String expected=p.getProperty("conformance.compiler.sourcePluginSha256");
        if(expected==null || !expected.matches("[0-9a-f]{64}") || !expected.equals(CompilerFrames.artifact(plugin).get("sha256")))throw new IllegalStateException("source plugin artifact identity mismatch");
        Files.createDirectory(CompilerFrames.receipt(session,"conformance.compiler.parsedRoot"));
    }

    static SourceMonitor prepare(MojoExecutionEvent event,long invocation,Object configuration)throws Exception {
        if(!event.getSession().getUserProperties().containsKey("conformance.compiler.sourcePlugin"))return null;
        return new SourceMonitor(event,invocation,configuration);
    }

    @SuppressWarnings("unchecked")
    private SourceMonitor(MojoExecutionEvent event,long invocation,Object configuration)throws Exception {
        this.configuration=configuration;
        if(!Boolean.FALSE.equals(invoke(configuration,"isFork")))throw new IllegalStateException("native parser monitor fork profile unqualified");
        Object modules=invoke(configuration,"getModulepathEntries");
        if(modules instanceof Collection<?> values && !values.isEmpty())throw new IllegalStateException("native parser monitor module-path profile unqualified");
        for(Method method:configuration.getClass().getMethods())if(method.getName().equals("getProcessorModulePathEntries")){
            Object value=method.invoke(configuration);if(value instanceof Collection<?> paths && !paths.isEmpty())throw new IllegalStateException("processor module-path monitor profile unqualified");
        }
        originalProcessorPath=invoke(configuration,"getProcessorPathEntries");
        originalArguments=new ArrayList<>();
        for(Object entry:(Collection<?>)invoke(configuration,"getCustomCompilerArgumentsEntries")){
            var pair=(Map.Entry<String,String>)entry;String key=pair.getKey();
            if(key==null || key.contains("ConformanceSourceTrace") || key.startsWith("-processorpath") || key.startsWith("--processor-path") || key.startsWith("--processor-module-path"))throw new IllegalStateException("custom processor-path/monitor arguments require a separate profile");
            originalArguments.add(new AbstractMap.SimpleImmutableEntry<>(pair));
        }
        Object probe=configuration.getClass().getConstructor().newInstance();
        add(probe,"-Aprobe=first",null);add(probe,"-Aprobe=second",null);
        configuration.getClass().getMethod("setCustomCompilerArgumentsAsMap",Map.class).invoke(probe,Map.of());
        if(((Collection<?>)invoke(probe,"getCustomCompilerArgumentsEntries")).isEmpty())resetMethod="SET_EMPTY_MAP";
        else{
            ((Collection<?>)invoke(probe,"getCustomCompilerArgumentsEntries")).clear();
            if(!((Collection<?>)invoke(probe,"getCustomCompilerArgumentsEntries")).isEmpty())throw new IllegalStateException("ordered compiler-argument restoration unavailable");
            resetMethod="CLEAR_ENTRIES";
        }
        Path plugin=CompilerFrames.receipt(event.getSession(),"conformance.compiler.sourcePlugin");
        output=CompilerFrames.receipt(event.getSession(),"conformance.compiler.parsedRoot").resolve("invocation-"+invocation+".jsonl");
        String attempt=CompilerFrames.attempt(event.getSession());
        String token=Base64.getUrlEncoder().encodeToString(output.toString().getBytes(StandardCharsets.UTF_8));
        String retention=event.getSession().getUserProperties().getProperty("conformance.compiler.retainInputs","false");
        if(!Set.of("true","false").contains(retention))throw new IllegalStateException("retainInputs must be true or false");
        RetainedInputs retained=retention.equals("true")?new RetainedInputs(output.resolveSibling("invocation-"+invocation+".inputs"),configuration):null;
        String retainedToken=retained==null?"":" "+Base64.getUrlEncoder().encodeToString(output.resolveSibling("invocation-"+invocation+".inputs").toString().getBytes(StandardCharsets.UTF_8));
        option="-Xplugin:ConformanceSourceTrace "+token+" "+attempt+" "+invocation+retainedToken;
        processorPath=new ArrayList<>((Collection<String>)(originalProcessorPath==null?invoke(configuration,"getClasspathEntries"):originalProcessorPath));
        processorPath.add(plugin.toString());
        identity=new LinkedHashMap<>();identity.put("format","native-javac-source-monitor-v1");identity.put("attempt",attempt);identity.put("invocation",invocation);identity.put("plugin",plugin.toString());identity.put("pluginSha256",CompilerFrames.artifact(plugin).get("sha256"));identity.put("output",output.toString());identity.put("addedCompilerArgument",option);identity.put("processorPath",processorPath);identity.put("consumptionCertified",false);
        if(retained!=null){identity.put("format","native-javac-source-monitor-v2");identity.put("retainedInputs",retained.receipt());}
        Object selected=invoke(configuration,"getSourceFiles");selectedNonempty=selected instanceof Collection<?> files && !files.isEmpty();
    }
    void activate()throws Exception {
        try{
            configuration.getClass().getMethod("setProcessorPathEntries",List.class).invoke(configuration,processorPath);
            add(configuration,option,null);
        }catch(Exception problem){restore();throw problem;}
    }

    private static void add(Object configuration,String key,String value)throws Exception {
        configuration.getClass().getMethod("addCompilerCustomArgument",String.class,String.class).invoke(configuration,key,value);
    }
    void restore()throws Exception {
        configuration.getClass().getMethod("setProcessorPathEntries",List.class).invoke(configuration,originalProcessorPath);
        if(resetMethod.equals("SET_EMPTY_MAP"))configuration.getClass().getMethod("setCustomCompilerArgumentsAsMap",Map.class).invoke(configuration,Map.of());
        else ((Collection<?>)invoke(configuration,"getCustomCompilerArgumentsEntries")).clear();
        for(var entry:originalArguments)add(configuration,entry.getKey(),entry.getValue());
        if(!new ArrayList<>((Collection<?>)invoke(configuration,"getCustomCompilerArgumentsEntries")).equals(originalArguments))throw new IllegalStateException("compiler arguments were not restored exactly");
    }
    Map<String,Object> receipt(boolean terminal)throws Exception {
        var result=new LinkedHashMap<String,Object>(identity);
        if(terminal){
            if(Files.exists(output,java.nio.file.LinkOption.NOFOLLOW_LINKS))result.put("artifact",CompilerFrames.artifact(output));
            else if(selectedNonempty)throw new IllegalStateException("native parser task receipt missing for selected sources");
            else result.put("artifact",null);
        }
        return result;
    }
}
