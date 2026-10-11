package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.NativeEvidence;
import metamodel.conformance.pipeline.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates a same-pass transport receipt; never evaluates a conformance condition. */
final class CppNativeInputReceipt {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Pattern VERSION = Pattern.compile("(?:clang version|version)\\s+([0-9]+(?:\\.[0-9]+){0,2})");

    record Result(NativeEvidence.Invocation invocation, List<NativeEvidence.Input> physicalInputs,
                  JsonNode memberLookup) { }
    record Context(String contextId, String compilerExecutable, String compilerVersion, String target,
                   Path helper, Path workingDirectory, Path mainSource, List<String> actualCompilerArgv,
                   List<String> semanticCompilerArguments, String compilerSha256) {
        Context {
            actualCompilerArgv = List.copyOf(actualCompilerArgv);
            semanticCompilerArguments = List.copyOf(semanticCompilerArguments);
        }
    }

    static Result read(Path receipt, Context context)
            throws IOException, ObservationException {
        String contextId=context.contextId(), compilerExecutable=context.compilerExecutable(),
                compilerVersion=context.compilerVersion(), target=context.target(), compilerSha256=context.compilerSha256();
        Path helper=context.helper(), workingDirectory=context.workingDirectory();
        List<String> actualCompilerArgv=context.actualCompilerArgv(), semanticCompilerArguments=context.semanticCompilerArguments();
        try {
            JsonNode root = JSON.readTree(receipt.toFile());
            if (root == null || !root.isObject() || !text(root, "format").equals("cpp-same-pass-inputs-v1")) {
                throw new IllegalArgumentException("unsupported or missing same-pass receipt format");
            }
            var version = VERSION.matcher(text(root, "compilerVersion"));
            if (!version.find() || !version.group(1).equals(compilerVersion) || !text(root, "target").equals(target)) {
                throw new IllegalArgumentException("helper frontend version/target differs from selected compiler");
            }
            if (!root.path("physicalImagesComplete").isBoolean() || !root.get("physicalImagesComplete").asBoolean()) {
                throw new IllegalArgumentException("helper physical-image inventory is incomplete");
            }
            List<String> argv = new ArrayList<>();
            for (JsonNode argument : array(root, "arguments")) {
                if (!argument.isTextual()) throw new IllegalArgumentException("nontextual compiler argument");
                argv.add(argument.asText());
            }
            if (!argv.equals(actualCompilerArgv)) throw new IllegalArgumentException("receipt compiler argv differs from executed argv");

            List<NativeEvidence.Input> files = inputs(array(root, "consumedFiles"));
            List<NativeEvidence.Input> images = inputs(array(root, "physicalImages"));
            Path main = context.mainSource().toRealPath();
            boolean hasMain = false;
            for (NativeEvidence.Input input : files) {
                if (Path.of(input.path()).toRealPath().equals(main)) hasMain = true;
            }
            if (!hasMain) throw new IllegalArgumentException("receipt omits the main source buffer");
            Path actualHelper = helper.toRealPath();
            if (images.stream().noneMatch(image -> Path.of(image.path()).normalize().equals(actualHelper))) {
                throw new IllegalArgumentException("receipt does not identify the selected helper image");
            }
            List<NativeEvidence.Input> physical = new ArrayList<>(files);
            physical.addAll(images);
            // The same physical image may also be deliberately read by source. Preserve
            // one path only when both independently measured snapshots agree.
            Map<String, NativeEvidence.Input> unique = new java.util.TreeMap<>();
            for (NativeEvidence.Input input : physical) {
                NativeEvidence.Input previous = unique.putIfAbsent(input.path(), input);
                if (previous != null && !previous.equals(input)) throw new IllegalArgumentException("inconsistent physical input snapshots");
            }
            physical = List.copyOf(unique.values());
            for (NativeEvidence.Input input : physical) verify(input);

            JsonNode events = array(root, "builtinInputEvents"), literals = array(root, "builtinLiteralTokens");
            if (events.size() != literals.size()) throw new IllegalArgumentException("unassociated builtin expansion/literal tokens");
            List<NativeEvidence.MacroInput> macros = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                JsonNode event = events.get(i), literal = literals.get(i);
                String name = text(event, "name"), source = text(event, "source");
                int line = line(event);
                if (!name.equals(text(literal, "name")) || !source.equals(text(literal, "source")) || line != line(literal)) {
                    throw new IllegalArgumentException("ambiguous builtin expansion/literal association");
                }
                if (event.has("modificationEpochSeconds")) {
                    var metadata = epoch(event);
                    NativeEvidence.Input origin = files.stream().filter(input -> input.path().equals(source)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("builtin physical origin is absent from consumed files"));
                    if (!metadata.equals(origin.modificationEpochSeconds())) throw new IllegalArgumentException("builtin origin metadata disagrees with source buffer");
                }
                macros.add(new NativeEvidence.MacroInput(name, text(literal, "literal"),
                        new NativeEvidence.SourceSpan(source, line, line),
                        event.has("modificationEpochSeconds") ? epoch(event)
                                : NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN,
                                    "compiler expansion has no physical source-file metadata")));
            }
            String toolIdentity = Hashing.sha256(JSON.writeValueAsString(Map.of(
                    "format", "cpp-same-pass-tool-v1", "compilerSha256", compilerSha256,
                    "helperImages", images, "frontendVersion", text(root, "compilerVersion"), "target", target)));
            return new Result(new NativeEvidence.Invocation(contextId, compilerExecutable,
                    workingDirectory.toString(), semanticCompilerArguments, toolIdentity,
                    NativeEvidence.Evidence.known(physical), NativeEvidence.Evidence.known(macros)), physical,
                    root.get("memberLookup"));
        } catch (IllegalArgumentException invalid) {
            throw new ObservationException("invalid C++ same-pass input receipt: " + invalid.getMessage(), invalid);
        }
    }

    static void verify(NativeEvidence.Input input) throws IOException, ObservationException {
        Path file = Path.of(input.path());
        if (!Files.isRegularFile(file) || !Hashing.sha256(file).equals(input.sha256())) {
            throw new ObservationException("C++ compiler input snapshot differs from physical bytes/metadata: " + input.path());
        }
        verifyMetadata(input);
    }

    /** Retain literal paths as well as resolved paths: aliases can change targets. */
    static Map<Path, String> finalVerificationInputs(Map<Path, String> physicalHashes,
            java.util.Collection<NativeEvidence.Input> tracedInputs) throws ObservationException {
        Map<Path, String> result = new java.util.LinkedHashMap<>(physicalHashes);
        for (NativeEvidence.Input input : tracedInputs) {
            Path path = Path.of(input.path());
            String previous = result.putIfAbsent(path, input.sha256());
            if (previous != null && !previous.equals(input.sha256())) {
                throw new ObservationException("C++ compiler input snapshots disagree: " + input.path());
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    static void verifyMetadata(NativeEvidence.Input input) throws IOException, ObservationException {
        Path file = Path.of(input.path());
        if (!Files.isRegularFile(file) || Files.getLastModifiedTime(file).toInstant().getEpochSecond()
                != input.modificationEpochSeconds().values().get(0)) {
            throw new ObservationException("C++ compiler input snapshot differs from physical bytes/metadata: " + input.path());
        }
    }

    private static List<NativeEvidence.Input> inputs(JsonNode array) {
        List<NativeEvidence.Input> inputs = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (JsonNode value : array) {
            String path = text(value, "path");
            if (!Path.of(path).isAbsolute() || !paths.add(path)) throw new IllegalArgumentException("nonabsolute or duplicate physical input path");
            inputs.add(new NativeEvidence.Input(path, text(value, "sha256"), epoch(value)));
        }
        return inputs.stream().sorted(Comparator.comparing(NativeEvidence.Input::path)).toList();
    }
    private static NativeEvidence.Evidence<Long> epoch(JsonNode node) {
        JsonNode value = node.get("modificationEpochSeconds");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("invalid physical modification epoch");
        return NativeEvidence.Evidence.known(List.of(value.longValue()));
    }
    private static int line(JsonNode node) {
        JsonNode line = node.get("line");
        if (line == null || !line.isIntegralNumber() || !line.canConvertToInt() || line.intValue() < 1) throw new IllegalArgumentException("invalid builtin source line");
        return line.intValue();
    }
    private static JsonNode array(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isArray()) throw new IllegalArgumentException("missing/nonarray " + key);
        return value;
    }
    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) throw new IllegalArgumentException("missing/nontextual " + key);
        return value.asText();
    }
}
