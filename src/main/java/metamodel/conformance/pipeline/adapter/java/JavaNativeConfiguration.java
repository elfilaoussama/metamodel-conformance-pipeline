package metamodel.conformance.pipeline.adapter.java;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import metamodel.conformance.pipeline.util.ToolProcessPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Explicit source-only native compiler selection; it never asserts project-build ownership. */
public record JavaNativeConfiguration(Path javaHome, int feature, Integer sourceLevel,
        Integer targetLevel, Integer releaseLevel, List<Path> classpath, long timeoutMillis) {
    static final Set<Integer> SUPPORTED_FEATURES=Set.of(8,11,17,25);
    public JavaNativeConfiguration {
        Objects.requireNonNull(javaHome,"native compiler home");
        javaHome=javaHome.toAbsolutePath().normalize();
        if(!SUPPORTED_FEATURES.contains(feature))throw new IllegalArgumentException("native Java profile requires an explicitly supported actual compiler: "+SUPPORTED_FEATURES);
        new JavaCompilerSemantics(sourceLevel,targetLevel,releaseLevel,false,"native-selected");
        if(releaseLevel!=null && (feature==8 || releaseLevel<7 || releaseLevel>feature))
            throw new IllegalArgumentException("unsupported native release selection");
        for(Integer level:new Integer[]{sourceLevel,targetLevel})if(level!=null && (level<6 || level>feature))
            throw new IllegalArgumentException("unsupported native source/target selection");
        classpath=classpath==null?List.of():classpath.stream().map(path -> Objects.requireNonNull(path).toAbsolutePath().normalize()).toList();
        new ToolProcessPolicy(timeoutMillis);
    }

    public static JavaNativeConfiguration read(Path path) throws IOException {
        byte[] bytes=JavaNativeReceipt.bounded(path.toAbsolutePath().normalize(),1024*1024);
        var json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode node=json.readTree(bytes);
        JavaNativeReceipt.require(node!=null && node.isObject(),"native compiler configuration must be an object");
        Set<String> allowed=Set.of("schemaVersion","javaHome","feature","sourceLevel","targetLevel","releaseLevel","classpath","timeoutMillis");
        node.fieldNames().forEachRemaining(name -> JavaNativeReceipt.require(allowed.contains(name),"unknown native compiler configuration field: "+name));
        JavaNativeReceipt.require(JavaNativeReceipt.text(node,"schemaVersion").equals("1"),"unsupported native compiler configuration");
        Path base=path.toAbsolutePath().getParent();
        List<Path> classpath=new ArrayList<>();
        if(node.has("classpath"))for(String entry:JavaNativeReceipt.strings(node.get("classpath")))classpath.add(base.resolve(entry));
        int feature=Math.toIntExact(JavaNativeReceipt.integer(node,"feature",1));
        long timeout=node.hasNonNull("timeoutMillis")?JavaNativeReceipt.integer(node,"timeoutMillis",1):ToolProcessPolicy.configured().timeoutMillis();
        return new JavaNativeConfiguration(base.resolve(JavaNativeReceipt.text(node,"javaHome")),feature,
                level(node,"sourceLevel"),level(node,"targetLevel"),level(node,"releaseLevel"),classpath,timeout);
    }
    private static Integer level(JsonNode node,String name) {
        return node.hasNonNull(name)?Math.toIntExact(JavaNativeReceipt.integer(node,name,1)):null;
    }
}
