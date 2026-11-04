package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import metamodel.conformance.pipeline.adapter.ObservationException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Transport validation only. Native AST IDs remain ephemeral join handles;
 * neither a matched query nor a missing candidate grants population completeness. */
final class CppNativeBatchReceipt {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    enum Status { UNSUPPORTED, ALREADY_DEFINED, CONTEXT_LOCAL, NO_DEFINITION_IN_SELECTED_POPULATION,
        AMBIGUOUS, INCOMPATIBLE, IMPORT_REJECTED, IMPORT_IDENTITY_REJECTED, IMPORT_BODY_REJECTED, MATCHED }
    record Job(String contextId,Path astPath,Path inputPath,Path errorPath,int exitCode,long observationMicros) { }
    record Query(String targetContextId,String targetCanonicalAstId,String targetOwnerAstId,String targetUsr,Status status,
            String sourceContextId,String sourceCanonicalAstId,String sourceDefinitionAstId,String sourceOwnerAstId,String sourceUsr,String reason) { }
    record Metrics(long definitionIndexMicros,long correspondenceMicros,long importAttempts,long totalMicros) { }
    record Result(List<Job> jobs,List<Query> queries,Metrics metrics) {
        Result {jobs=List.copyOf(jobs);queries=List.copyOf(queries);}
    }

    static Result read(Path receipt,Path projectRoot,List<String> expectedContexts) throws IOException,ObservationException {
        return read(receipt,projectRoot,expectedContexts,null);
    }

    /** Group identities come from the request's qualified population, never from
     * native declaration names or physical source paths. */
    static Result read(Path receipt,Path projectRoot,Map<String,String> expectedContextGroups)
            throws IOException,ObservationException {
        return read(receipt,projectRoot,new ArrayList<>(expectedContextGroups.keySet()),
                new LinkedHashMap<>(expectedContextGroups));
    }

    private static Result read(Path receipt,Path projectRoot,List<String> expectedContexts,
            Map<String,String> expectedContextGroups) throws IOException,ObservationException {
        try {
            boolean grouped=expectedContextGroups!=null;
            require(!expectedContexts.isEmpty() && new HashSet<>(expectedContexts).size()==expectedContexts.size(),"empty/duplicate selected contexts");
            if(grouped)for(String group:expectedContextGroups.values())
                require(group!=null && group.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}"),"invalid expected correspondence group");
            JsonNode root=JSON.readTree(receipt.toFile());
            fields(root,Set.of("format","projectRoot","jobs","correspondenceQueries","metrics"));
            require(text(root,"format").equals(grouped?"cpp-retained-batch-v2":"cpp-retained-batch-v1"),"unsupported native batch format");
            require(text(root,"projectRoot").equals(projectRoot.toRealPath().toString()),"native batch project root mismatch");
            Path directory=receipt.toRealPath().getParent();
            var jobs=new ArrayList<Job>();var successful=new HashSet<String>();
            JsonNode rows=array(root,"jobs");require(rows.size()==expectedContexts.size(),"selected job population mismatch");
            for(int index=0;index<rows.size();index++) {
                JsonNode row=rows.get(index);
                var jobFields=new HashSet<>(Set.of("contextId","astPath","inputPath","errorPath","exitCode","frontendInvocations","observationMicros"));
                if(grouped)jobFields.add("correspondenceGroupId");
                fields(row,jobFields);
                String context=text(row,"contextId");require(context.equals(expectedContexts.get(index)),"selected job identity/order mismatch");
                if(grouped)require(text(row,"correspondenceGroupId").equals(expectedContextGroups.get(context)),"selected job correspondence group mismatch");
                long exit=number(row,"exitCode"),calls=number(row,"frontendInvocations");
                require(exit<=1 && calls<=1 && (exit!=0 || calls==1),"invalid frontend outcome/invocation count");
                String prefix="job-"+index;
                Path ast=artifact(row,"astPath",directory.resolve(prefix+".ast.json"));
                Path errors=artifact(row,"errorPath",directory.resolve(prefix+".stderr"));
                Path inputs=Path.of(text(row,"inputPath"));require(inputs.equals(directory.resolve(prefix+".inputs.json")),"unexpected native input receipt route");
                if(exit==0){artifact(row,"inputPath",inputs);successful.add(context);}
                jobs.add(new Job(context,ast,inputs,errors,(int)exit,number(row,"observationMicros")));
            }
            var queries=new ArrayList<Query>();var receivers=new HashSet<List<String>>();long matched=0;
            for(JsonNode row:array(root,"correspondenceQueries")) {
                String context=text(row,"targetContextId");require(successful.contains(context),"query refers to failed/unselected receiver");
                String target=astId(row,"targetCanonicalAstId"),owner=astId(row,"targetOwnerAstId");
                require(receivers.add(List.of(context,target)),"duplicate native receiver query");
                Status status=Status.valueOf(text(row,"status"));String usr=string(row,"targetUsr");
                if(status!=Status.UNSUPPORTED)require(usr.startsWith("c:"),"query lacks native declaration identity");
                Set<String> allowed=new HashSet<>(Set.of("targetContextId","targetCanonicalAstId","targetOwnerAstId","targetUsr","status"));
                if(grouped) {
                    allowed.add("targetCorrespondenceGroupId");
                    require(text(row,"targetCorrespondenceGroupId").equals(expectedContextGroups.get(context)),"receiver correspondence group mismatch");
                }
                String origin="",canonical="",definition="",sourceOwner="",sourceUsr="",reason="";
                if(status==Status.MATCHED) {
                    allowed.addAll(Set.of("sourceContextId","sourceCanonicalAstId","sourceDefinitionAstId","sourceOwnerAstId","sourceUsr"));
                    origin=text(row,"sourceContextId");require(successful.contains(origin) && !origin.equals(context),"invalid originating compiler context");
                    if(grouped) {
                        allowed.add("sourceCorrespondenceGroupId");
                        require(text(row,"sourceCorrespondenceGroupId").equals(expectedContextGroups.get(origin)),"origin correspondence group mismatch");
                        require(expectedContextGroups.get(origin).equals(expectedContextGroups.get(context)),"cross-group native correspondence");
                    }
                    canonical=astId(row,"sourceCanonicalAstId");definition=astId(row,"sourceDefinitionAstId");sourceOwner=astId(row,"sourceOwnerAstId");
                    sourceUsr=text(row,"sourceUsr");require(sourceUsr.equals(usr),"native declaration identities disagree");matched++;
                } else if(status==Status.IMPORT_REJECTED) {allowed.add("reason");reason=text(row,"reason");}
                fields(row,allowed);
                queries.add(new Query(context,target,owner,usr,status,origin,canonical,definition,sourceOwner,sourceUsr,reason));
            }
            JsonNode metrics=root.get("metrics");fields(metrics,Set.of("definitionIndexMicros","correspondenceMicros","importAttempts","totalMicros"));
            var measurement=new Metrics(number(metrics,"definitionIndexMicros"),number(metrics,"correspondenceMicros"),number(metrics,"importAttempts"),number(metrics,"totalMicros"));
            require(measurement.importAttempts()>=matched && measurement.importAttempts()<=queries.size(),"inconsistent native import count");
            return new Result(jobs,queries,measurement);
        } catch(IllegalArgumentException invalid) {
            throw new ObservationException("invalid C++ retained batch receipt: "+invalid.getMessage(),invalid);
        }
    }
    private static Path artifact(JsonNode row,String field,Path expected) throws IOException {
        Path actual=Path.of(text(row,field));
        require(actual.equals(expected) && Files.isRegularFile(actual,LinkOption.NOFOLLOW_LINKS)
                && actual.equals(actual.toRealPath()),"missing/noncanonical native artifact: "+field);return actual;
    }
    private static void fields(JsonNode value,Set<String> expected) {
        require(value!=null && value.isObject(),"missing native object");
        Set<String> actual=new HashSet<>();value.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected),"unexpected/missing native fields");
    }
    private static JsonNode array(JsonNode node,String key) {JsonNode value=node.get(key);require(value!=null && value.isArray(),"missing native array: "+key);return value;}
    private static String string(JsonNode node,String key) {JsonNode value=node.get(key);require(value!=null && value.isTextual() && value.asText().indexOf('\0')<0,"invalid native text: "+key);return value.asText();}
    private static String text(JsonNode node,String key) {String value=string(node,key);require(!value.isEmpty(),"empty native text: "+key);return value;}
    private static String astId(JsonNode node,String key) {String value=text(node,key);require(value.matches("0x[0-9a-f]+"),"invalid native AST handle: "+key);return value;}
    private static long number(JsonNode node,String key) {JsonNode value=node.get(key);require(value!=null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue()>=0,"invalid native count: "+key);return value.longValue();}
    private static void require(boolean value,String message) {if(!value)throw new IllegalArgumentException(message);}
}
