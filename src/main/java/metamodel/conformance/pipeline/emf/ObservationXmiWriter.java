package metamodel.conformance.pipeline.emf;

import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.ImplementationBindingObservation;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import metamodel.conformance.pipeline.util.ArtifactLimits;
import metamodel.conformance.pipeline.util.AtomicFiles;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ObservationXmiWriter {
    public void write(Observation observation, Path target) throws IOException {
        if (!ObservationSchema.VERSION.equals(observation.schemaVersion())) {
            throw new IllegalArgumentException("unsupported observation schema: " + observation.schemaVersion());
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTarget.getParent());
        Path temporary = Files.createTempFile(normalizedTarget.getParent(), "observation-", ".xmi");
        try {
            save(observation, temporary);
            ArtifactLimits.requireFileWithin("canonical XMI", temporary, ArtifactLimits.MAX_XMI_BYTES);
            AtomicFiles.move(temporary, normalizedTarget);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @SuppressWarnings("unchecked")
    private void save(Observation observation, Path target) throws IOException {
        ObservationSchema schema = ObservationSchema.load();
        EPackage ePackage = schema.ePackage();
        EObject root = ePackage.getEFactoryInstance().create(schema.classifier("ObservationSet"));
        set(root, "schemaVersion", observation.schemaVersion());
        set(root, "adapterId", observation.adapterId());
        set(root, "adapterVersion", observation.adapterVersion());
        ((EList<String>) root.eGet(feature(root, "externalParents"))).addAll(observation.externalParents());
        EEnum evidenceKind = (EEnum) ePackage.getEClassifier("EvidenceKind");
        EList<Object> completeEvidence = (EList<Object>) root.eGet(feature(root, "completeEvidence"));
        observation.completeEvidence().stream().map(EvidenceKind::name).sorted()
                .forEach(name -> completeEvidence.add(evidenceKind.getEEnumLiteral(name).getInstance()));

        EList<EObject> units = (EList<EObject>) root.eGet(feature(root, "units"));
        EEnum language = (EEnum) ePackage.getEClassifier("Language");
        for (SourceUnit source : observation.units()) {
            EObject unit = ePackage.getEFactoryInstance().create(schema.classifier("SourceUnit"));
            set(unit, "language", language.getEEnumLiteral(source.language().name()).getInstance());
            set(unit, "path", source.path());
            set(unit, "sha256", source.sha256());
            units.add(unit);
        }

        Map<String, EObject> bodiesByKey = new LinkedHashMap<>();
        EList<EObject> methodBodies = (EList<EObject>) root.eGet(feature(root, "methodBodies"));
        for (MethodBodyObservation source : observation.methodBodies()) {
            EObject body = ePackage.getEFactoryInstance().create(schema.classifier("MethodBody"));
            set(body, "technicalKey", source.technicalKey());
            set(body, "sourcePath", source.sourcePath());
            set(body, "startLine", source.startLine());
            set(body, "endLine", source.endLine());
            methodBodies.add(body);
            bodiesByKey.put(source.technicalKey(), body);
        }

        Map<String, EObject> membersByKey = new LinkedHashMap<>();
        EList<EObject> members = (EList<EObject>) root.eGet(feature(root, "members"));
        EEnum memberKind = (EEnum) ePackage.getEClassifier("MemberKind");
        EEnum inheritability = (EEnum) ePackage.getEClassifier("Inheritability");
        EEnum visibility = (EEnum) ePackage.getEClassifier("MemberVisibility");
        EEnum abstraction = (EEnum) ePackage.getEClassifier("MethodAbstraction");
        EEnum scope = (EEnum) ePackage.getEClassifier("MemberScope");
        for (MemberObservation source : observation.members()) {
            EObject member = ePackage.getEFactoryInstance().create(schema.classifier("Member"));
            set(member, "technicalKey", source.technicalKey());
            if (source.observedIdentifier() != null) {
                set(member, "observedIdentifier", source.observedIdentifier());
            }
            set(member, "kind", memberKind.getEEnumLiteral(source.kind().name()).getInstance());
            set(member, "inheritability", inheritability.getEEnumLiteral(source.inheritability().name()).getInstance());
            set(member, "visibility", visibility.getEEnumLiteral(source.visibility().name()).getInstance());
            set(member, "memberName", source.memberName());
            set(member, "sourcePath", source.sourcePath());
            set(member, "startLine", source.startLine());
            set(member, "endLine", source.endLine());
            ((EList<String>) member.eGet(feature(member, "parameterTypes"))).addAll(source.parameterTypes());
            set(member, "abstraction", abstraction.getEEnumLiteral(source.abstraction().name()).getInstance());
            set(member, "scope", scope.getEEnumLiteral(source.scope().name()).getInstance());
            if (source.returnType() != null) {
                set(member, "returnType", source.returnType());
            }
            members.add(member);
            membersByKey.put(source.technicalKey(), member);
        }
        for (MemberObservation source : observation.members()) {
            EObject member = membersByKey.get(source.technicalKey());
            EList<EObject> overridden = (EList<EObject>) member.eGet(feature(member, "overriddenMembers"));
            source.overriddenMemberKeys().forEach(key -> overridden.add(membersByKey.get(key)));
        }

        Map<String, EObject> classifiersById = new LinkedHashMap<>();
        EList<EObject> classifiers = (EList<EObject>) root.eGet(feature(root, "classifiers"));
        EEnum kind = (EEnum) ePackage.getEClassifier("ClassifierKind");
        EEnum classifierAbstraction = (EEnum) ePackage.getEClassifier("ClassifierAbstraction");
        for (ClassifierObservation source : observation.classifiers()) {
            EObject classifier = ePackage.getEFactoryInstance().create(schema.classifier("Classifier"));
            set(classifier, "id", source.id());
            set(classifier, "qualifiedName", source.qualifiedName());
            set(classifier, "packageName", source.packageName());
            set(classifier, "kind", kind.getEEnumLiteral(source.kind().name()).getInstance());
            set(classifier, "sourcePath", source.sourcePath());
            set(classifier, "startLine", source.startLine());
            set(classifier, "endLine", source.endLine());
            set(classifier, "abstraction",
                    classifierAbstraction.getEEnumLiteral(source.abstraction().name()).getInstance());
            classifiers.add(classifier);
            classifiersById.put(source.id(), classifier);
        }
        for (ClassifierObservation source : observation.classifiers()) {
            EObject classifier = classifiersById.get(source.id());
            EList<EObject> parents = (EList<EObject>) classifier.eGet(feature(classifier, "parents"));
            source.parentIds().forEach(parentId -> parents.add(classifiersById.get(parentId)));
            EList<EObject> declared = (EList<EObject>) classifier.eGet(feature(classifier, "declaredMembers"));
            source.declaredMemberKeys().forEach(key -> declared.add(membersByKey.get(key)));
            EList<EObject> inherited = (EList<EObject>) classifier.eGet(feature(classifier, "inheritedMembers"));
            source.inheritedMemberKeys().forEach(key -> inherited.add(membersByKey.get(key)));
        }

        EList<EObject> bindings = (EList<EObject>) root.eGet(feature(root, "implementationBindings"));
        for (ImplementationBindingObservation source : observation.implementationBindings()) {
            EObject binding = ePackage.getEFactoryInstance().create(schema.classifier("ImplementationBinding"));
            set(binding, "technicalKey", source.technicalKey());
            set(binding, "implementer", classifiersById.get(source.implementerClassifierId()));
            set(binding, "target", membersByKey.get(source.targetMemberKey()));
            set(binding, "body", bodiesByKey.get(source.bodyKey()));
            bindings.add(binding);
        }

        EList<EObject> unresolved = (EList<EObject>) root.eGet(feature(root, "unresolvedParents"));
        for (UnresolvedParent source : observation.unresolvedParents()) {
            EObject item = ePackage.getEFactoryInstance().create(schema.classifier("UnresolvedParent"));
            set(item, "owner", classifiersById.get(source.ownerId()));
            set(item, "targetName", source.targetName());
            set(item, "sourcePath", source.sourcePath());
            set(item, "line", source.line());
            unresolved.add(item);
        }

        EList<EObject> diagnostics = (EList<EObject>) root.eGet(feature(root, "diagnostics"));
        EEnum diagnosticKind = (EEnum) ePackage.getEClassifier("DiagnosticKind");
        for (ObservationDiagnostic source : observation.diagnostics()) {
            EObject item = ePackage.getEFactoryInstance().create(schema.classifier("ObservationDiagnostic"));
            set(item, "kind", diagnosticKind.getEEnumLiteral(source.kind().name()).getInstance());
            set(item, "sourcePath", source.sourcePath());
            set(item, "line", source.line());
            set(item, "message", source.message());
            diagnostics.add(item);
        }

        ResourceSetImpl resourceSet = new ResourceSetImpl();
        resourceSet.getPackageRegistry().put(ObservationSchema.NS_URI, ePackage);
        resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());
        Resource resource = resourceSet.createResource(URI.createFileURI(target.toString()));
        resource.getContents().add(root);
        Map<String, Object> options = new HashMap<>();
        options.put(XMLResource.OPTION_ENCODING, "UTF-8");
        options.put(XMLResource.OPTION_FORMATTED, Boolean.TRUE);
        options.put(XMLResource.OPTION_LINE_WIDTH, 120);
        options.put(XMLResource.OPTION_SCHEMA_LOCATION, Boolean.FALSE);
        resource.save(options);
    }

    private static org.eclipse.emf.ecore.EStructuralFeature feature(EObject object, String name) {
        var feature = object.eClass().getEStructuralFeature(name);
        if (feature == null) {
            throw new IllegalStateException("missing feature " + object.eClass().getName() + "." + name);
        }
        return feature;
    }

    private static void set(EObject object, String feature, Object value) {
        object.eSet(feature(object, feature), value);
    }
}
