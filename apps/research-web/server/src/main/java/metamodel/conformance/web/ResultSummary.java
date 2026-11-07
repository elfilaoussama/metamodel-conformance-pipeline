package metamodel.conformance.web;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** Read-only presentation of recorded decisions. Never evaluates or reclassifies a rule. */
public record ResultSummary(List<Rule> rules, Map<String,Long> statusCounts, List<Fact> facts,
        int total, long evaluated, long conformant, long violations, long unknown,
        int requiredCategories, int completeCategories, int witnessCount,
        int passWidth, int violationWidth, int unknownWidth, String headline) {
    public record Rule(String id, String title, String status, String label, String message,
                       int witnessCount, int requiredCount, int completeCount, List<String> missingEvidence) { }
    public record Fact(String name, long count, long width) { }
    public static String label(String status) {
        return switch(status) { case "CONFORMANT" -> "Conformant"; case "NON_CONFORMANT" -> "Violations found";
            case "NOT_EVALUATED" -> "Not evaluated"; default -> throw new IllegalArgumentException("Unsupported recorded decision status: " + status); };
    }
    public static ResultSummary from(JsonNode capsule, JsonNode registry, JsonNode evidence, Map<String,?> counts) {
        Map<String,JsonNode> metadata=new LinkedHashMap<>();
        Set<String> required=new TreeSet<>(), complete=new HashSet<>();
        for(var item:evidence) complete.add(item.asText());
        for(var item:registry) {
            String id=item.path("id").asText();
            if(metadata.putIfAbsent(id,item)!=null) throw new IllegalArgumentException("Duplicate rule metadata.");
            for(var category:item.path("requiredEvidence")) required.add(category.asText());
        }
        Map<String,Long> statuses=new LinkedHashMap<>();for(String status:List.of("CONFORMANT","NON_CONFORMANT","NOT_EVALUATED")) statuses.put(status,0L);
        List<Rule> rows=new ArrayList<>();Set<String> seen=new HashSet<>();int witnesses=0;
        for(var decision:capsule.path("decisions")) {
            String id=decision.path("invariantId").asText(), status=decision.path("status").asText();
            String label=label(status); var rule=metadata.get(id);
            if(rule==null||!seen.add(id)) throw new IllegalArgumentException("Recorded decisions do not match unique rule metadata.");
            Set<String> needed=new TreeSet<>();for(var category:rule.path("requiredEvidence")) needed.add(category.asText());
            List<String> missing=needed.stream().filter(category->!complete.contains(category)).toList();
            int count=decision.path("witnesses").size();witnesses+=count;
            rows.add(new Rule(id,rule.path("title").asText(),status,label,decision.path("message").asText(),count,needed.size(),needed.size()-missing.size(),missing));
            statuses.put(status,statuses.get(status)+1);
        }
        if(seen.size()!=metadata.size()) throw new IllegalArgumentException("Some registered rules have no recorded decision.");
        long pass=statuses.get("CONFORMANT"),fail=statuses.get("NON_CONFORMANT"),unknown=statuses.get("NOT_EVALUATED");int total=rows.size();
        int passWidth=total==0?0:(int)(600*pass/total), failWidth=total==0?0:unknown==0?600-passWidth:(int)(600*fail/total);
        Map<String,String> names=Map.of("classifiers","Classes / interfaces","members","Members","methodBodies","Method bodies","implementationBindings","Implementation bindings","units","Source files","diagnostics","Diagnostics");
        long max=1;for(var count:counts.values()) {if(!(count instanceof Number n)||n.longValue()<0) throw new IllegalArgumentException("Invalid recorded model count.");max=Math.max(max,((Number)count).longValue());}
        List<Fact> facts=new ArrayList<>();for(var entry:counts.entrySet()) {long count=((Number)entry.getValue()).longValue();facts.add(new Fact(names.getOrDefault(entry.getKey(),entry.getKey()),count,400*count/max));}
        String headline=total==0?"No recorded rule decisions":fail>0?"Violations detected":unknown>0?"Some rules could not be evaluated":"No violations detected in this formal profile";
        int covered=(int)required.stream().filter(complete::contains).count();
        return new ResultSummary(List.copyOf(rows),Collections.unmodifiableMap(statuses),List.copyOf(facts),total,pass+fail,pass,fail,unknown,required.size(),covered,witnesses,passWidth,failWidth,total==0?0:600-passWidth-failWidth,headline);
    }
}
