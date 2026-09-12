package metamodel.conformance.pipeline.adapter.java;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;
import java.util.Locale;

/** Compiler-to-declaration name translation used only for source correspondence. */
final class JavacDeclarationTypes {
    private JavacDeclarationTypes() {}

    static boolean matches(List<String> canonical, ExecutableElement method, Types types, Elements elements) {
        List<? extends TypeMirror> parameters = method.getParameters().stream()
                .map(parameter -> parameter.asType()).toList();
        return canonical.equals(parameters.stream().map(Object::toString).toList())
                || canonical.equals(parameters.stream().map(types::erasure).map(Object::toString).toList())
                || canonical.equals(parameters.stream().map(type -> declarationName(type, elements)).toList())
                || canonical.equals(parameters.stream().map(types::erasure)
                        .map(type -> declarationName(type, elements)).toList());
    }

    private static String declarationName(TypeMirror type, Elements elements) {
        return switch (type.getKind()) {
            case ARRAY -> declarationName(((ArrayType) type).getComponentType(), elements) + "[]";
            case DECLARED -> elements.getBinaryName((TypeElement) ((DeclaredType) type).asElement()).toString();
            case TYPEVAR -> ((TypeVariable) type).asElement().getSimpleName().toString();
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE, VOID ->
                    type.getKind().name().toLowerCase(Locale.ROOT);
            default -> type.toString();
        };
    }
}
