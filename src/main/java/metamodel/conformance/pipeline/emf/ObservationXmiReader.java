package metamodel.conformance.pipeline.emf;

import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.ImplementationBindingObservation;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import metamodel.conformance.pipeline.util.ArtifactLimits;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ObservationXmiReader {
    @SuppressWarnings("unchecked")
    public Observation read(Path path) throws IOException {
        Path input = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("observation is not a regular file");
        }
        ArtifactLimits.requireFileWithin("canonical XMI", input, ArtifactLimits.MAX_XMI_BYTES);
        String prefix = Files.readString(input, StandardCharsets.UTF_8);
        if (prefix.contains("<!DOCTYPE") || prefix.contains("<!ENTITY")) {
            throw new IOException("DTD and entity declarations are forbidden in observations");
        }

        ObservationSchema schema = ObservationSchema.load();
        EPackage ePackage = schema.ePackage();
        ResourceSetImpl resourceSet = new ResourceSetImpl();
        resourceSet.getPackageRegistry().put(ObservationSchema.NS_URI, ePackage);
        resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());
        Resource resource = resourceSet.getResource(URI.createFileURI(input.toString()), true);
        if (resource.getContents().size() != 1) {
            throw new IOException("observation must contain exactly one root object");
        }
        EObject root = resource.getContents().get(0);
        if (!"ObservationSet".equals(root.eClass().getName())) {
            throw new IOException("unexpected observation root: " + root.eClass().getName());
        }

        List<SourceUnit> units = new ArrayList<>();
        for (EObject unit : (EList<EObject>) value(root, "units")) {
            units.add(new SourceUnit(
                    Language.valueOf(value(unit, "language").toString()),
                    string(unit, "path"), string(unit, "sha256")));
        }
        List<MethodBodyObservation> bodies = new ArrayList<>();
        for (EObject body : (EList<EObject>) value(root, "methodBodies")) {
            bodies.add(new MethodBodyObservation(
                    string(body, "technicalKey"), string(body, "sourcePath"),
                    integer(body, "startLine"), integer(body, "endLine")));
        }
        List<MemberObservation> members = new ArrayList<>();
        for (EObject member : (EList<EObject>) value(root, "members")) {
            Object observedIdentifier = value(member, "observedIdentifier");
            Object returnType = value(member, "returnType");
            List<String> overriddenMemberKeys = new ArrayList<>();
            for (EObject overridden : (EList<EObject>) value(member, "overriddenMembers")) {
                overriddenMemberKeys.add(string(overridden, "technicalKey"));
            }
            members.add(new MemberObservation(
                    string(member, "technicalKey"),
                    observedIdentifier instanceof String text && !text.isBlank() ? text : null,
                    MemberKind.valueOf(value(member, "kind").toString()),
                    Inheritability.valueOf(value(member, "inheritability").toString()),
                    MemberVisibility.valueOf(value(member, "visibility").toString()),
                    string(member, "memberName"), string(member, "sourcePath"),
                    integer(member, "startLine"), integer(member, "endLine"),
                    new ArrayList<>((EList<String>) value(member, "parameterTypes")),
                    MethodAbstraction.valueOf(value(member, "abstraction").toString()),
                    MemberScope.valueOf(value(member, "scope").toString()),
                    returnType instanceof String text && !text.isBlank() ? text : null,
                    overriddenMemberKeys));
        }
        List<ClassifierObservation> classifiers = new ArrayList<>();
        for (EObject classifier : (EList<EObject>) value(root, "classifiers")) {
            List<String> parentIds = new ArrayList<>();
            for (EObject parent : (EList<EObject>) value(classifier, "parents")) {
                parentIds.add(string(parent, "id"));
            }
            List<String> declaredMemberKeys = new ArrayList<>();
            for (EObject member : (EList<EObject>) value(classifier, "declaredMembers")) {
                declaredMemberKeys.add(string(member, "technicalKey"));
            }
            List<String> inheritedMemberKeys = new ArrayList<>();
            for (EObject member : (EList<EObject>) value(classifier, "inheritedMembers")) {
                inheritedMemberKeys.add(string(member, "technicalKey"));
            }
            classifiers.add(new ClassifierObservation(
                    string(classifier, "id"), string(classifier, "qualifiedName"),
                    string(classifier, "packageName"), ClassifierKind.valueOf(value(classifier, "kind").toString()),
                    string(classifier, "sourcePath"), integer(classifier, "startLine"), integer(classifier, "endLine"),
                    parentIds, declaredMemberKeys, inheritedMemberKeys,
                    ClassifierAbstraction.valueOf(value(classifier, "abstraction").toString())));
        }
        List<ImplementationBindingObservation> bindings = new ArrayList<>();
        for (EObject binding : (EList<EObject>) value(root, "implementationBindings")) {
            bindings.add(new ImplementationBindingObservation(
                    string(binding, "technicalKey"),
                    string((EObject) value(binding, "implementer"), "id"),
                    string((EObject) value(binding, "target"), "technicalKey"),
                    string((EObject) value(binding, "body"), "technicalKey")));
        }
        List<UnresolvedParent> unresolved = new ArrayList<>();
        for (EObject item : (EList<EObject>) value(root, "unresolvedParents")) {
            unresolved.add(new UnresolvedParent(
                    string((EObject) value(item, "owner"), "id"), string(item, "targetName"),
                    string(item, "sourcePath"), integer(item, "line")));
        }
        List<String> externalParents = new ArrayList<>((EList<String>) value(root, "externalParents"));
        List<ObservationDiagnostic> diagnostics = new ArrayList<>();
        for (EObject item : (EList<EObject>) value(root, "diagnostics")) {
            diagnostics.add(new ObservationDiagnostic(
                    DiagnosticKind.valueOf(value(item, "kind").toString()),
                    string(item, "sourcePath"), integer(item, "line"), string(item, "message")));
        }
        Set<EvidenceKind> completeEvidence = ((EList<Object>) value(root, "completeEvidence")).stream()
                .map(Object::toString).map(EvidenceKind::valueOf).collect(Collectors.toUnmodifiableSet());
        return new Observation(
                string(root, "schemaVersion"), string(root, "adapterId"), string(root, "adapterVersion"),
                externalParents, completeEvidence, units, classifiers, members, bodies, bindings,
                unresolved, diagnostics);
    }

    private static Object value(EObject object, String name) throws IOException {
        var feature = object.eClass().getEStructuralFeature(name);
        if (feature == null) {
            throw new IOException("missing feature " + object.eClass().getName() + "." + name);
        }
        return object.eGet(feature);
    }

    private static String string(EObject object, String name) throws IOException {
        Object value = value(object, name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IOException("invalid string feature " + object.eClass().getName() + "." + name);
        }
        return text;
    }

    private static int integer(EObject object, String name) throws IOException {
        Object value = value(object, name);
        if (!(value instanceof Integer number)) {
            throw new IOException("invalid integer feature " + object.eClass().getName() + "." + name);
        }
        return number;
    }
}
