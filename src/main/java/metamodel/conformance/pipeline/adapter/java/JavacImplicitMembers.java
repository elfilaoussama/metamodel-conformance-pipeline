package metamodel.conformance.pipeline.adapter.java;

import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * Compiler-synthesized members that javac's effective member view exposes but
 * that no canonical source declaration can correspond to.
 */
final class JavacImplicitMembers {
    private JavacImplicitMembers() {
    }

    /**
     * JLS 9.2 gives interfaces implicit Object-shaped methods, not an Object
     * superclass. javac exposes Object carriers in its lookup view; these are
     * not inherited explicit source declarations. A declaration owned by an
     * actual superinterface is deliberately retained, even for the same name.
     */
    static boolean isObjectLookupOnInterface(TypeElement receiver, Element member, Elements elements) {
        if (!receiver.getKind().isInterface() || member.getKind() != ElementKind.METHOD) {
            return false;
        }
        return member.getEnclosingElement() instanceof TypeElement owner
                && isImplicitObjectSupertype(receiver, owner, elements);
    }

    /** Types.directSupertypes includes Object for interfaces as a type-system carrier. */
    static boolean isImplicitObjectSupertype(TypeElement receiver, TypeElement candidate, Elements elements) {
        if (!receiver.getKind().isInterface()) {
            return false;
        }
        TypeElement object = elements.getTypeElement("java.lang.Object");
        return object != null && object.equals(candidate);
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
