package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.databind.JsonNode;
import metamodel.conformance.pipeline.model.NativeEvidence;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Decodes a named same-pass frontend option subset; never evaluates fixed P0. */
final class CppNativeLanguageMode {
    private static final List<String> LEVELS=List.of("CPlusPlus","CPlusPlus11","CPlusPlus14",
            "CPlusPlus17","CPlusPlus20","CPlusPlus2b");
    private static final List<String> OPTIONS=List.of("CPlusPlus","CPlusPlus11","CPlusPlus14",
            "CPlusPlus17","CPlusPlus20","CPlusPlus2b","GNUMode","GNUKeywords");
    private CppNativeLanguageMode() { }

    static NativeEvidence.LanguageMode read(JsonNode node,String context,List<String> semanticArguments) {
        require(node!=null && node.isObject(),"language-mode witness must be an object");
        var expected=new HashSet<>(OPTIONS);
        expected.addAll(Set.of("format","scope","standardKnown","standard","standardKind"));
        var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected),"unexpected/missing language-mode fields");
        require(text(node,"format").equals("cpp-native-language-mode-v1"),"unsupported language-mode format");
        require(text(node,"scope").equals("CLANG14_LANGOPTIONS_NAMED_SUBSET"),"unsupported language-mode scope");
        boolean known=flag(node,"standardKnown");String standard=text(node,"standard");
        require(known?!standard.isBlank():standard.isEmpty(),"incoherent language-standard knowledge");
        JsonNode kind=node.get("standardKind");
        require(kind.isIntegralNumber() && kind.canConvertToLong() && kind.longValue()>=0,
                "invalid compiler-local standard enum");
        require(flag(node,"CPlusPlus"),"C++ observation has non-C++ language mode");
        for(int index=1;index<LEVELS.size();index++)
            require(!flag(node,LEVELS.get(index)) || flag(node,LEVELS.get(index-1)),"noncumulative C++ level flags");
        if(known) {
            require(standard.matches("(c\\+\\+|gnu\\+\\+)(98|03|11|14|17|20|2a|2b)"),"unsupported native C++ standard");
            String suffix=standard.substring(standard.indexOf("++")+2);
            int level=switch(suffix) {
                case "98","03" -> 0;case "11" -> 1;case "14" -> 2;case "17" -> 3;
                case "20","2a" -> 4;case "2b" -> 5;default -> throw new IllegalArgumentException("unsupported standard");
            };
            for(int index=0;index<LEVELS.size();index++)
                require(flag(node,LEVELS.get(index))==(index<=level),"standard name disagrees with C++ level flags");
            require(flag(node,"GNUMode")==standard.startsWith("gnu++"),"standard name disagrees with GNU mode");
            var explicit=semanticArguments.stream().filter(argument->argument.startsWith("-std=")).toList();
            if(!explicit.isEmpty())require(canonicalStandard(standard).equals(
                    canonicalStandard(explicit.get(explicit.size()-1).substring(5))),
                    "witnessed standard differs from last explicit standard option");
        }
        var options=new ArrayList<NativeEvidence.LanguageOption>();
        for(String name:OPTIONS)options.add(new NativeEvidence.LanguageOption(name,
                flag(node,name)?NativeEvidence.SwitchState.ENABLED:NativeEvidence.SwitchState.DISABLED));
        var selection=semanticArguments.stream().anyMatch(argument->argument.startsWith("-std="))
                ?NativeEvidence.StandardSelection.EXPLICIT_OPTION:NativeEvidence.StandardSelection.COMPILER_DEFAULT;
        return new NativeEvidence.LanguageMode(context,"CLANG_LANGOPTIONS_SAME_PASS_V1",
                "CLANG14_LANGOPTIONS_NAMED_SUBSET",selection,
                known?NativeEvidence.Evidence.known(List.of(standard)):
                        NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN,
                                "native frontend left LangStd unspecified"),
                kind.longValue(),options);
    }
    private static String text(JsonNode node,String name) {
        JsonNode value=node.get(name);require(value!=null && value.isTextual() && value.asText().indexOf('\0')<0,
                "invalid language-mode text: "+name);return value.asText();
    }
    private static String canonicalStandard(String value) {
        if(value.equals("c++03"))return "c++98";
        if(value.equals("gnu++03"))return "gnu++98";
        if(value.equals("c++2a"))return "c++20";
        if(value.equals("gnu++2a"))return "gnu++20";
        return value;
    }
    private static boolean flag(JsonNode node,String name) {
        JsonNode value=node.get(name);require(value!=null && value.isBoolean(),"invalid language-mode boolean: "+name);
        return value.booleanValue();
    }
    private static void require(boolean value,String message) {if(!value)throw new IllegalArgumentException(message);}
}
