package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.util.Hashing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Converts dependency bytecode symbols into deterministic canonical support observations. */
final class JavaDependencyObservation {
    private JavaDependencyObservation() {
    }

    static Result materialize(JavaDependencySymbols.Result symbols) throws ObservationException {
        Map<String, String> classifierIds = new HashMap<>();
        for (JavaDependencySymbols.TypeSymbol type : symbols.types()) {
            String previous = classifierIds.put(type.qualifiedName(), classifierId(type));
            if (previous != null) {
                throw new ObservationException(
                        "dependency classifier identity is ambiguous: " + type.qualifiedName());
            }
        }

        List<MemberObservation> members = new ArrayList<>();
        Map<MemberSignature, String> memberKeys = new HashMap<>();
        Map<String, List<String>> declaredKeysByClassifier = new HashMap<>();
        for (JavaDependencySymbols.TypeSymbol type : symbols.types()) {
            String ownerId = classifierIds.get(type.qualifiedName());
            List<String> declaredKeys = new ArrayList<>();
            for (JavaDependencySymbols.MemberSymbol member : type.members()) {
                MemberSignature signature = new MemberSignature(
                        type.qualifiedName(), member.kind(), member.name(), member.parameterTypes());
                String key = memberKey(type, member);
                String previous = memberKeys.put(signature, key);
                if (previous != null) {
                    throw new ObservationException(
                            "dependency member signature is ambiguous: " + signature);
                }
                members.add(new MemberObservation(
                        key,
                        null,
                        member.kind(),
                        member.inheritability(),
                        member.visibility(),
                        member.name(),
                        type.archiveUnitPath(),
                        1,
                        1,
                        member.parameterTypes(),
                        member.abstraction(),
                        member.scope(),
                        member.returnType(),
                        List.of()));
                declaredKeys.add(key);
            }
            declaredKeysByClassifier.put(ownerId, declaredKeys.stream().sorted().toList());
        }

        List<ClassifierObservation> classifiers = new ArrayList<>();
        for (JavaDependencySymbols.TypeSymbol type : symbols.types()) {
            String id = classifierIds.get(type.qualifiedName());
            List<String> parents = new ArrayList<>();
            for (String parentName : type.parentQualifiedNames()) {
                String parentId = classifierIds.get(parentName);
                if (parentId == null) {
                    throw new ObservationException(
                            "materialized dependency parent is unavailable: " + parentName);
                }
                parents.add(parentId);
            }
            classifiers.add(new ClassifierObservation(
                    id,
                    type.qualifiedName(),
                    type.packageName(),
                    type.kind(),
                    type.archiveUnitPath(),
                    1,
                    1,
                    parents.stream().sorted().toList(),
                    declaredKeysByClassifier.getOrDefault(id, List.of()),
                    List.of(),
                    type.abstraction()));
        }

        return new Result(
                classifiers.stream().sorted(Comparator.comparing(ClassifierObservation::id)).toList(),
                members.stream().sorted(Comparator.comparing(MemberObservation::technicalKey)).toList(),
                Map.copyOf(classifierIds),
                Map.copyOf(memberKeys));
    }

    private static String classifierId(JavaDependencySymbols.TypeSymbol type) {
        return "cls_" + Hashing.sha256(
                "java-bytecode-classifier\0"
                        + type.archiveSha256() + "\0"
                        + type.qualifiedName() + "\0"
                        + type.kind());
    }

    private static String memberKey(
            JavaDependencySymbols.TypeSymbol owner,
            JavaDependencySymbols.MemberSymbol member) {
        return "mem_" + Hashing.sha256(
                "java-bytecode-member\0"
                        + owner.archiveSha256() + "\0"
                        + owner.qualifiedName() + "\0"
                        + member.kind() + "\0"
                        + member.name() + "\0"
                        + String.join("\0", member.parameterTypes()));
    }

    record MemberSignature(
            String ownerQualifiedName,
            MemberKind kind,
            String name,
            List<String> parameterTypes) {
        MemberSignature {
            parameterTypes = List.copyOf(parameterTypes);
        }
    }

    record Result(
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            Map<String, String> classifierIdByQualifiedName,
            Map<MemberSignature, String> memberKeyBySignature) {
        Result {
            classifiers = List.copyOf(classifiers);
            members = List.copyOf(members);
            classifierIdByQualifiedName = Map.copyOf(classifierIdByQualifiedName);
            memberKeyBySignature = Map.copyOf(memberKeyBySignature);
        }

        String classifierId(String qualifiedName) {
            return classifierIdByQualifiedName.get(qualifiedName);
        }

        String memberKey(
                String ownerQualifiedName,
                MemberKind kind,
                String name,
                List<String> parameterTypes) {
            return memberKeyBySignature.get(
                    new MemberSignature(ownerQualifiedName, kind, name, parameterTypes));
        }
    }
}
