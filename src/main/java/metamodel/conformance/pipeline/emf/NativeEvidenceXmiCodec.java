package metamodel.conformance.pipeline.emf;

import metamodel.conformance.pipeline.model.NativeEvidence;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;

/** Maps only the closed NativeEvidence record family to its declared typed Ecore classes. */
final class NativeEvidenceXmiCodec {
    static final String PROJECTION_ID = "fixed-p0-structural-v1";
    private final ObservationSchema schema;

    NativeEvidenceXmiCodec(ObservationSchema schema) {
        this.schema = schema;
    }

    EObject write(NativeEvidence.Bundle bundle) throws IOException {
        return writeRecord(NativeEvidence.Bundle.class, bundle);
    }

    NativeEvidence.Bundle read(EObject object) throws IOException {
        return (NativeEvidence.Bundle) readRecord(NativeEvidence.Bundle.class, object);
    }

    @SuppressWarnings("unchecked")
    private EObject writeRecord(Type type, Object record) throws IOException {
        Class<?> raw = recordClass(type);
        EObject object = schema.ePackage().getEFactoryInstance().create(schema.classifier(name(type)));
        for (RecordComponent component : raw.getRecordComponents()) {
            if (raw == NativeEvidence.Bundle.class && component.getName().equals("reportedInputs")
                    && schema.version().equals("14")) {
                if (!((NativeEvidence.Bundle) record).reportedInputs().isEmpty()) {
                    throw new IOException("schema14 cannot retain reported inventories");
                }
                continue;
            }
            Type fieldType = resolve(component.getGenericType(), type);
            EStructuralFeature feature = feature(object, component.getName());
            Object value;
            try {
                value = component.getAccessor().invoke(record);
            } catch (ReflectiveOperationException exception) {
                throw new IOException("cannot access native evidence record", exception);
            }
            if (isList(fieldType)) {
                Type elementType = argument(fieldType);
                EList<Object> target = (EList<Object>) object.eGet(feature);
                for (Object element : (List<?>) value) target.add(writeValue(elementType, element));
            } else {
                object.eSet(feature, writeValue(fieldType, value));
            }
        }
        return object;
    }

    private Object writeValue(Type type, Object value) throws IOException {
        if (type == String.class || type == Long.class || type == int.class) return value;
        if (type instanceof Class<?> raw && raw.isEnum()) {
            EEnum enumeration = (EEnum) schema.ePackage().getEClassifier(enumName(raw));
            var literal = enumeration.getEEnumLiteral(((Enum<?>) value).name());
            if (literal == null) throw new IOException("undeclared native enum value");
            return literal.getInstance();
        }
        return writeRecord(type, value);
    }

    private Object readRecord(Type type, EObject object) throws IOException {
        Class<?> raw = recordClass(type);
        if (object == null || object.eClass() != schema.classifier(name(type))) {
            throw new IOException("unexpected native record type; expected " + name(type));
        }
        RecordComponent[] components = raw.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] constructorTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            Type fieldType = resolve(component.getGenericType(), type);
            if (raw == NativeEvidence.Bundle.class && component.getName().equals("reportedInputs")
                    && schema.version().equals("14")) {
                constructorTypes[i] = component.getType();
                values[i] = List.of();
                continue;
            }
            EStructuralFeature feature = feature(object, component.getName());
            constructorTypes[i] = component.getType();
            if (isList(fieldType)) {
                List<Object> elements = new ArrayList<>();
                for (Object value : (List<?>) object.eGet(feature)) {
                    elements.add(readValue(argument(fieldType), value));
                }
                values[i] = elements;
            } else {
                if (!object.eIsSet(feature)) {
                    throw new IOException("missing explicit native field: " + object.eClass().getName() + "." + feature.getName());
                }
                values[i] = readValue(fieldType, object.eGet(feature));
            }
        }
        try {
            // Canonical constructors perform cardinality, knowledge and cross-context reference checks.
            return raw.getConstructor(constructorTypes).newInstance(values);
        } catch (InvocationTargetException exception) {
            throw new IOException("invalid native evidence: " + exception.getCause().getMessage(), exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IOException("cannot construct declared native evidence record", exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object readValue(Type type, Object value) throws IOException {
        if (type == String.class || type == Long.class || type == int.class) {
            Class<?> expected = type == int.class ? Integer.class : (Class<?>) type;
            if (!expected.isInstance(value)) throw new IOException("invalid native scalar type");
            return value;
        }
        if (type instanceof Class<?> raw && raw.isEnum()) {
            try {
                return Enum.valueOf((Class<? extends Enum>) raw, value.toString());
            } catch (RuntimeException exception) {
                throw new IOException("invalid native enum value", exception);
            }
        }
        if (!(value instanceof EObject object)) throw new IOException("invalid native containment");
        return readRecord(type, object);
    }

    private static Type resolve(Type field, Type recordType) throws IOException {
        if (!(recordType instanceof ParameterizedType parameterized)) return field;
        if (field instanceof TypeVariable<?>) return parameterized.getActualTypeArguments()[0];
        if (isList(field) && argument(field) instanceof TypeVariable<?>) {
            return new ListType(parameterized.getActualTypeArguments()[0]);
        }
        return field;
    }

    private record ListType(Type element) implements ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return new Type[]{element}; }
        @Override public Type getRawType() { return List.class; }
        @Override public Type getOwnerType() { return null; }
    }

    private static boolean isList(Type type) {
        return type instanceof ParameterizedType value && value.getRawType() == List.class;
    }

    private static Type argument(Type type) throws IOException {
        if (type instanceof ParameterizedType value && value.getActualTypeArguments().length == 1) {
            return value.getActualTypeArguments()[0];
        }
        throw new IOException("unsupported native generic type");
    }

    private static Class<?> recordClass(Type type) throws IOException {
        Class<?> raw = type instanceof Class<?> value ? value
                : type instanceof ParameterizedType value && value.getRawType() instanceof Class<?> record ? record : null;
        if (raw == null || !raw.isRecord() || raw.getEnclosingClass() != NativeEvidence.class) {
            throw new IOException("type is outside the closed native evidence schema");
        }
        if (raw == NativeEvidence.Evidence.class && !(type instanceof ParameterizedType)) {
            throw new IOException("native evidence payload type must be declared");
        }
        return raw;
    }

    private static String name(Type type) throws IOException {
        Class<?> raw = recordClass(type);
        if (raw == NativeEvidence.Evidence.class) {
            Type payload = argument(type);
            if (!(payload instanceof Class<?> value)) throw new IOException("undeclared native payload type");
            return "Native" + value.getSimpleName() + "Evidence";
        }
        return "Native" + raw.getSimpleName();
    }

    private static String enumName(Class<?> enumeration) {
        return enumeration.getEnclosingClass() == NativeEvidence.class ? "Native" + enumeration.getSimpleName()
                : enumeration.getSimpleName();
    }

    private static EStructuralFeature feature(EObject object, String name) throws IOException {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(name);
        if (feature == null) throw new IOException("undeclared native feature: " + name);
        return feature;
    }
}
