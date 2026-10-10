package metamodel.conformance.pipeline.adapter.java;

import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;

/**
 * Compiler-synthesized members that javac's effective member view exposes but
 * that no canonical source declaration can correspond to.
 */
final class JavacImplicitMembers {
    private JavacImplicitMembers() {
    }

    /**
     * An enum implicitly declares {@code values()}, {@code valueOf(String)} and,
     * from Java 21, {@code $values()}. None has a source declaration, so none can
     * correspond to a canonical source member.
     */
    static boolean isImplicitEnumMethod(Element element, Trees trees) {
        if (element.getKind() != ElementKind.METHOD) {
            return false;
        }
        Element enclosing = element.getEnclosingElement();
        if (enclosing == null || enclosing.getKind() != ElementKind.ENUM) {
            return false;
        }
        if (trees.getTree(element) != null) {
            return false;
        }
        String name = element.getSimpleName().toString();
        return name.equals("values") || name.equals("valueOf") || name.equals("$values");
    }

    /**
     * The source-declaration domain does not model enum constants, so an
     * inherited constant has no canonical declaration to correspond to.
     */
    static boolean isEnumConstant(Element element) {
        return element.getKind() == ElementKind.ENUM_CONSTANT;
    }
}