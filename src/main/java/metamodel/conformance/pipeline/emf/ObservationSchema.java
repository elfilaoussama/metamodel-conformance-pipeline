package metamodel.conformance.pipeline.emf;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.EcoreResourceFactoryImpl;

import java.net.URL;

final class ObservationSchema {
    static final String VERSION = "15";
    static final String NS_URI = "urn:metamodel-conformance:observation:15";

    private final EPackage ePackage;

    private ObservationSchema(EPackage ePackage) {
        this.ePackage = ePackage;
    }

    static ObservationSchema load() {
        return load(VERSION);
    }

    static ObservationSchema load(String version) {
        if (!version.equals("14") && !version.equals(VERSION)) {
            throw new IllegalArgumentException("unsupported observation schema: " + version);
        }
        EcorePackage.eINSTANCE.eClass();
        URL resourceUrl = ObservationSchema.class.getResource(version.equals("14")
                ? "/model/observation.ecore" : "/model/observation-v15.ecore");
        if (resourceUrl == null) {
            throw new IllegalStateException("bundled observation.ecore is missing");
        }
        ResourceSetImpl resourceSet = new ResourceSetImpl();
        resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap()
                .put("ecore", new EcoreResourceFactoryImpl());
        Resource resource = resourceSet.getResource(URI.createURI(resourceUrl.toExternalForm()), true);
        EPackage ePackage = (EPackage) resource.getContents().get(0);
        ePackage.setEFactoryInstance(ePackage.getEFactoryInstance());
        return new ObservationSchema(ePackage);
    }

    EPackage ePackage() {
        return ePackage;
    }

    String version() {
        return ePackage.getNsURI().substring(ePackage.getNsURI().lastIndexOf(':') + 1);
    }

    EClass classifier(String name) {
        Object value = ePackage.getEClassifier(name);
        if (!(value instanceof EClass eClass)) {
            throw new IllegalStateException("missing EClass in observation schema: " + name);
        }
        return eClass;
    }
}
