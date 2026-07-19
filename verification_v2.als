// ==========================================================================
// verification_v2.als
// Verification commands mapped to obligations O-01 through O-09.
// Protocol: NV witnesses → diagnostic probes → assertions → stress checks.
// ==========================================================================
open kernel_v2_obligation

// ==========================================================================
// PART 1: Non-Vacuity Witnesses (NV)
// Prove the kernel admits representative valid structures for each carrier
// and structural configuration. SAT results required.
// ==========================================================================

// O-01, O-02, O-06, O-07: Simple non-abstract class with method, attribute,
// body, binding, and direct instance.
pred NV_SimpleNonAbstractClass {
    some c : Class, m : Method, a : Attribute, mb : MethodBody,
         b : ImplementationBinding, o : Object |
        c.isAbstract = No and
        m in c.localMethods and m.isAbstract = No and
        a in c.localAttributes and
        b.implementer = c and b.target = m and b.body = mb and
        o in c.directInstances
}
run NV_SimpleNonAbstractClass for 6 but
    exactly 1 Class, exactly 0 Interface,
    exactly 1 Method, exactly 1 Attribute, exactly 1 MethodBody,
    exactly 1 ImplementationBinding, exactly 1 Object,
    4 int expect 1

// O-03: Three-class linear inheritance chain.
pred NV_InheritanceChain {
    some disj root, mid, leaf : Class |
        root in mid.classParent and mid in leaf.classParent
}
run NV_InheritanceChain for 6 but
    exactly 3 Class, exactly 0 Interface,
    exactly 0 Method, exactly 0 Attribute, exactly 0 MethodBody,
    exactly 0 ImplementationBinding, exactly 0 Object,
    4 int expect 1

// O-07, O-03: Abstract base class with abstract method, concrete subclass
// provides implementation.
pred NV_AbstractMethodImplementedBySubclass {
    some base : Class, derived : Class, m : Method, mb : MethodBody,
         b : ImplementationBinding |
        base != derived and
        base in derived.classParent and
        m in base.localMethods and m.isAbstract = Yes and
        base.isAbstract = Yes and
        m in derived.inheritedMethods and
        b.implementer = derived and b.target = m and b.body = mb and
        derived.isAbstract = No
}
run NV_AbstractMethodImplementedBySubclass for 6 but
    exactly 2 Class, exactly 0 Interface,
    exactly 1 Method, exactly 0 Attribute,
    exactly 1 MethodBody, exactly 1 ImplementationBinding,
    exactly 0 Object, 4 int expect 1

// O-03: Class implementing an interface method with a concrete body.
pred NV_InterfaceImplementation {
    some i : Interface, c : Class, m : Method, mb : MethodBody,
         b : ImplementationBinding |
        i in c.interfaceParents and
        m in i.localMethods and
        m in c.inheritedMethods and
        b.implementer = c and b.target = m and b.body = mb and
        c.isAbstract = No
}
run NV_InterfaceImplementation for 6 but
    exactly 1 Class, exactly 1 Interface,
    exactly 1 Method, exactly 0 Attribute,
    exactly 1 MethodBody, exactly 1 ImplementationBinding,
    exactly 0 Object, 4 int expect 1

// O-08: Two methods in same class, same name, different parameter types
// (legal overloading).
pred NV_MethodOverloadingBySignature {
    some c : Class, disj m1, m2 : Method |
        m1 in c.localMethods and m2 in c.localMethods and
        m1.memberName = m2.memberName and
        m1.paramTypes != m2.paramTypes and
        m1.isAbstract = No and m2.isAbstract = No
}
run NV_MethodOverloadingBySignature for 7 but
    exactly 1 Class, exactly 0 Interface,
    exactly 2 Method, exactly 0 Attribute,
    exactly 2 MethodBody, exactly 2 ImplementationBinding,
    exactly 0 Object, 4 int expect 1

// O-09: Override with same return type (legal, isSubtype holds by equality).
pred NV_OverrideWithSameReturnType {
    some base, derived : Class, inherited, local : Method,
         mb : MethodBody, b : ImplementationBinding |
        base != derived and
        base in derived.classParent and
        inherited in base.localMethods and
        local in derived.localMethods and
        sameMethodKey[inherited, local] and
        inherited.returnType = local.returnType and
        local.isAbstract = No and
        b.implementer = derived and b.target = local and b.body = mb
}
run NV_OverrideWithSameReturnType for 7 but
    exactly 2 Class, exactly 0 Interface,
    exactly 2 Method, exactly 0 Attribute,
    exactly 2 MethodBody, exactly 2 ImplementationBinding,
    exactly 0 Object, 4 int expect 1

// O-09: Override with covariant return type (proper subtype).
pred NV_OverrideWithCovariantReturnType {
    some base, derived : Class, inherited, local : Method,
         r1, r2 : ClassifierType, mb : MethodBody, b : ImplementationBinding |
        base != derived and
        base in derived.classParent and
        inherited in base.localMethods and
        local in derived.localMethods and
        sameMethodKey[inherited, local] and
        inherited.returnType = r1 and local.returnType = r2 and
        r1.classifier = derived and r2.classifier = base and
        local.isAbstract = No and
        b.implementer = derived and b.target = local and b.body = mb
}
run NV_OverrideWithCovariantReturnType for 8 but
    exactly 2 Class, exactly 0 Interface,
    exactly 2 Method, exactly 0 Attribute,
    exactly 2 MethodBody, exactly 2 ImplementationBinding,
    exactly 0 Object, 4 int expect 1

// O-03: Class implementing methods from two distinct interfaces.
pred NV_MultipleInterfaceParents {
    some c : Class, disj i1, i2 : Interface, disj m1, m2 : Method,
         disj b1, b2 : ImplementationBinding |
        i1 in c.interfaceParents and i2 in c.interfaceParents and
        m1 in i1.localMethods and m2 in i2.localMethods and
        not sameMethodKey[m1, m2] and
        b1.implementer = c and b1.target = m1 and
        b2.implementer = c and b2.target = m2 and
        c.isAbstract = No
}
run NV_MultipleInterfaceParents for 8 but
    exactly 1 Class, exactly 2 Interface,
    exactly 2 Method, exactly 0 Attribute,
    exactly 2 MethodBody, exactly 2 ImplementationBinding,
    exactly 0 Object, 4 int expect 1

// O-07: Non-abstract class with direct instance.
pred NV_ObjectInNonAbstractClass {
    some c : Class, o : Object |
        c.isAbstract = No and o in c.directInstances
}
run NV_ObjectInNonAbstractClass for 4 but
    exactly 1 Class, exactly 0 Interface,
    exactly 0 Method, exactly 0 Attribute, exactly 0 MethodBody,
    exactly 0 ImplementationBinding, exactly 1 Object,
    4 int expect 1

// ==========================================================================
// PART 2: Diagnostic Probes (Bad_*)
// Each probe encodes a malformed structure that the obligations should block.
// expect 0 = expects UNSAT (no bad instance exists within scope).
// ==========================================================================

// --- O-01: Duplicate identifiers ---

pred Bad_DuplicateClassifierID {
    some disj c1, c2 : Classifier | c1.cid = c2.cid
}
run Bad_DuplicateClassifierID for 5 expect 0

pred Bad_DuplicateMethodID {
    some disj m1, m2 : Method | m1.mid = m2.mid
}
run Bad_DuplicateMethodID for 5 expect 0

pred Bad_DuplicateAttributeID {
    some disj a1, a2 : Attribute | a1.aid = a2.aid
}
run Bad_DuplicateAttributeID for 5 expect 0

pred Bad_DuplicateObjectID {
    some disj o1, o2 : Object | o1.oid = o2.oid
}
run Bad_DuplicateObjectID for 5 expect 0

// --- O-02: Shared member ownership ---

pred Bad_SharedMethodDeclaration {
    some disj c1, c2 : Classifier, m : Method |
        m in c1.localMethods and m in c2.localMethods
}
run Bad_SharedMethodDeclaration for 5 expect 0

pred Bad_SharedAttributeDeclaration {
    some disj c1, c2 : Classifier, a : Attribute |
        a in c1.localAttributes and a in c2.localAttributes
}
run Bad_SharedAttributeDeclaration for 5 expect 0

// --- O-03: Inheritance cycle ---

pred Bad_InheritanceCycle {
    some c : Classifier | c in c.^(classParent + interfaceParents)
}
run Bad_InheritanceCycle for 5 expect 0

pred Bad_InterfaceWithClassParent {
    some i : Interface | some i.classParent
}
run Bad_InterfaceWithClassParent for 4 expect 0

// --- O-04: Private method inherited (should be excluded) ---

pred Bad_PrivateMethodInherited {
    some c : Classifier, m : Method, owner : ancestors[c] |
        m in owner.localMethods and m.visibility = Priv and
        m in c.inheritedMethods
}
run Bad_PrivateMethodInherited for 6 expect 0

// O-04: Nearer ancestor should suppress deeper ancestor's method
pred Bad_NearerAncestorFailsToHide {
    some c : Classifier, disj nearer, deeper : ancestors[c],
         m : Method |
        deeper in ancestors[nearer] and
        m in deeper.localMethods and m.visibility != Priv and
        m in c.inheritedMethods and
        some replacement : nearer.localMethods |
            replacement.visibility != Priv and
            sameMethodKey[replacement, m]
}
run Bad_NearerAncestorFailsToHide for 7 expect 0

// --- O-05: Local/inherited overlap ---

pred Bad_LocalInheritedMethodOverlap {
    some c : Classifier, m : Method |
        m in c.localMethods and m in c.inheritedMethods
}
run Bad_LocalInheritedMethodOverlap for 5 expect 0

pred Bad_LocalInheritedAttributeOverlap {
    some c : Classifier, a : Attribute |
        a in c.localAttributes and a in c.inheritedAttributes
}
run Bad_LocalInheritedAttributeOverlap for 5 expect 0

// --- O-06: Implementation binding violations ---

pred Bad_PhantomImplementationTarget {
    some b : ImplementationBinding |
        b.target not in b.implementer.localMethods
                      + b.implementer.inheritedMethods
}
run Bad_PhantomImplementationTarget for 6 expect 0

pred Bad_OrphanMethodBody {
    some mb : MethodBody | no b : ImplementationBinding | b.body = mb
}
run Bad_OrphanMethodBody for 5 expect 0

pred Bad_DoubleBindingSameClassMethod {
    some disj b1, b2 : ImplementationBinding, c : Class, m : Method |
        b1.implementer = c and b1.target = m and
        b2.implementer = c and b2.target = m
}
run Bad_DoubleBindingSameClassMethod for 5 expect 0

pred Bad_AbstractLocalMethodWithDeclaringBody {
    some c : Class, m : Method, b : ImplementationBinding |
        m in c.localMethods and m.isAbstract = Yes and
        b.implementer = c and b.target = m
}
run Bad_AbstractLocalMethodWithDeclaringBody for 6 expect 0

pred Bad_NonAbstractLocalMethodWithoutBody {
    some c : Class, m : Method |
        m in c.localMethods and m.isAbstract = No and
        no b : ImplementationBinding | b.implementer = c and b.target = m
}
run Bad_NonAbstractLocalMethodWithoutBody for 5 expect 0

// --- O-07: Abstraction violations ---

pred Bad_AbstractClassDirectInstance {
    some c : Classifier, o : Object |
        c.isAbstract = Yes and o in c.directInstances
}
run Bad_AbstractClassDirectInstance for 5 expect 0

pred Bad_NonAbstractWithUnresolvedMethod {
    some c : Class, m : Method |
        c.isAbstract = No and unresolvedMethod[c, m]
}
run Bad_NonAbstractWithUnresolvedMethod for 6 expect 0

pred Bad_InterfaceWithInstanceAttribute {
    some i : Interface, a : i.localAttributes | a.scope = Instance
}
run Bad_InterfaceWithInstanceAttribute for 5 expect 0

// --- O-08: Namespace violations ---

pred Bad_DuplicateLocalMethodKey {
    some c : Classifier, disj m1, m2 : Method |
        m1 in c.localMethods and m2 in c.localMethods and
        sameMethodKey[m1, m2]
}
run Bad_DuplicateLocalMethodKey for 6 expect 0

pred Bad_DuplicateLocalAttributeName {
    some c : Classifier, disj a1, a2 : Attribute |
        a1 in c.localAttributes and a2 in c.localAttributes and
        sameAttributeName[a1, a2]
}
run Bad_DuplicateLocalAttributeName for 6 expect 0

pred Bad_InheritedMethodConflict {
    some c : Classifier, disj m1, m2 : Method |
        m1 in c.inheritedMethods and m2 in c.inheritedMethods and
        sameMethodKey[m1, m2]
}
run Bad_InheritedMethodConflict for 7 expect 0

pred Bad_InheritedAttributeConflict {
    some c : Classifier, disj a1, a2 : Attribute |
        a1 in c.inheritedAttributes and a2 in c.inheritedAttributes and
        sameAttributeName[a1, a2]
}
run Bad_InheritedAttributeConflict for 7 expect 0

// --- O-09: Override violations ---

// FIXED: was testing "returnType != inherited.returnType" which PASSES when
// covariance allows subtype. Now correctly tests that return type is NOT a
// subtype (the actual violation O-09 forbids).
pred Bad_OverrideReturnNotSubtype {
    some c : Classifier, inherited, local : Method |
        overrides[c, inherited, local] and
        not isSubtype[local.returnType, inherited.returnType]
}
run Bad_OverrideReturnNotSubtype for 7 expect 0

pred Bad_OverrideScopeMismatch {
    some c : Classifier, inherited, local : Method |
        inherited in (ancestors[c].localMethods) and
        inherited.visibility != Priv and
        local in c.localMethods and
        sameMethodKey[inherited, local] and
        inherited.scope != local.scope
}
run Bad_OverrideScopeMismatch for 7 expect 0

// --- O-04/O-08 impl: Parameter well-formedness ---

pred Bad_NegativeParameterIndex {
    some m : Method, i : Int | i < 0 and some m.paramTypes[i]
}
run Bad_NegativeParameterIndex for 5 expect 0

pred Bad_NonContiguousParameters {
    some m : Method, i : Int |
        i > 0 and no m.paramTypes[i - 1] and some m.paramTypes[i]
}
run Bad_NonContiguousParameters for 5 expect 0

// ==========================================================================
// PART 3: Assertion Checks (A_*)
// Each assertion follows from the encoded facts. expect 0 = expects UNSAT
// (no counterexample within the scope).
// ==========================================================================

// --- O-01: Identifier uniqueness ---

assert A_UniqueCarrierIdentifiers {
    all disj c1, c2 : Classifier | c1.cid != c2.cid
    all disj m1, m2 : Method | m1.mid != m2.mid
    all disj a1, a2 : Attribute | a1.aid != a2.aid
    all disj o1, o2 : Object | o1.oid != o2.oid
}
check A_UniqueCarrierIdentifiers for 8 expect 0

assert A_IdentifierClosure {
    ClassifierID = Classifier.cid and
    MethodID     = Method.mid and
    AttributeID  = Attribute.aid and
    ObjectID     = Object.oid
}
check A_IdentifierClosure for 8 expect 0

// --- O-02: Exclusive declaration ownership ---

assert A_ExclusiveMethodOwnership {
    all m : Method | one c : Classifier | m in c.localMethods
}
check A_ExclusiveMethodOwnership for 8 expect 0

assert A_ExclusiveAttributeOwnership {
    all a : Attribute | one c : Classifier | a in c.localAttributes
}
check A_ExclusiveAttributeOwnership for 8 expect 0

// --- O-03: Acyclic inheritance ---

assert A_AcyclicGeneralization {
    no c : Classifier | c in c.^(classParent + interfaceParents)
}
check A_AcyclicGeneralization for 8 expect 0

assert A_InterfaceHasNoClassParent {
    no i : Interface | some i.classParent
}
check A_InterfaceHasNoClassParent for 8 expect 0

// --- O-04: Inherited member derivation correctness ---

assert A_InheritedMethodsFromVisibleAncestors {
    all c : Classifier, m : c.inheritedMethods |
        some owner : ancestors[c] |
            m in owner.localMethods and m.visibility != Priv
}
check A_InheritedMethodsFromVisibleAncestors for 8 expect 0

assert A_InheritedAttributesFromVisibleAncestors {
    all c : Classifier, a : c.inheritedAttributes |
        some owner : ancestors[c] |
            a in owner.localAttributes and a.visibility != Priv
}
check A_InheritedAttributesFromVisibleAncestors for 8 expect 0

assert A_PrivateMethodsNotInherited {
    all c : Classifier, m : c.inheritedMethods | m.visibility != Priv
}
check A_PrivateMethodsNotInherited for 8 expect 0

// --- O-05: Local/inherited disjointness ---

assert A_LocalInheritedDisjoint {
    all c : Classifier | no c.localMethods & c.inheritedMethods
    all c : Classifier | no c.localAttributes & c.inheritedAttributes
}
check A_LocalInheritedDisjoint for 8 expect 0

// --- O-06: Implementation binding policy ---

assert A_ImplementationTargetsAvailable {
    all b : ImplementationBinding |
        b.target in b.implementer.localMethods
                  + b.implementer.inheritedMethods
}
check A_ImplementationTargetsAvailable for 8 expect 0

assert A_NoOrphanMethodBodies {
    all mb : MethodBody | one b : ImplementationBinding | b.body = mb
}
check A_NoOrphanMethodBodies for 8 expect 0

assert A_SingleBindingPerClassMethod {
    all c : Class, m : Method |
        lone b : ImplementationBinding |
            b.implementer = c and b.target = m
}
check A_SingleBindingPerClassMethod for 8 expect 0

// --- O-07: Abstraction and instantiation ---

assert A_AbstractClassifiersHaveNoDirectInstances {
    all c : Classifier | c.isAbstract = Yes implies no c.directInstances
}
check A_AbstractClassifiersHaveNoDirectInstances for 8 expect 0

assert A_NonAbstractHasNoUnresolvedMethods {
    all c : Classifier |
        c.isAbstract = No implies no m : Method | unresolvedMethod[c, m]
}
check A_NonAbstractHasNoUnresolvedMethods for 8 expect 0

assert A_InterfaceRestrictions {
    all i : Interface | {
        i.isAbstract = Yes
        no i.directInstances
        all a : i.localAttributes | a.scope = Static
    }
}
check A_InterfaceRestrictions for 8 expect 0

assert A_ObjectsInExactlyOneClass {
    all o : Object | one c : Class | o in c.directInstances
}
check A_ObjectsInExactlyOneClass for 8 expect 0

// --- O-08: Namespace policy ---

assert A_LocalMethodKeyUniqueness {
    all c : Classifier | all disj m1, m2 : c.localMethods |
        not sameMethodKey[m1, m2]
}
check A_LocalMethodKeyUniqueness for 8 expect 0

assert A_LocalAttributeNameUniqueness {
    all c : Classifier | all disj a1, a2 : c.localAttributes |
        not sameAttributeName[a1, a2]
}
check A_LocalAttributeNameUniqueness for 8 expect 0

assert A_InheritedMethodNoConflicts {
    all c : Classifier | all disj m1, m2 : c.inheritedMethods |
        not sameMethodKey[m1, m2]
}
check A_InheritedMethodNoConflicts for 8 expect 0

// --- O-09: Override policy ---

assert A_OverrideReturnCovariance {
    all c : Classifier, inherited, local : Method |
        overrides[c, inherited, local] implies
            isSubtype[local.returnType, inherited.returnType]
}
check A_OverrideReturnCovariance for 8 expect 0

// --- O-04/O-08 impl: Parameter well-formedness ---

assert A_ParameterPositionPolicy {
    all m : Method | all i : Int |
        some m.paramTypes[i] implies i >= 0
}
check A_ParameterPositionPolicy for 8 expect 0

assert A_ParameterContiguity {
    all m : Method, i : Int |
        i > 0 and some m.paramTypes[i] implies some m.paramTypes[i - 1]
}
check A_ParameterContiguity for 8 expect 0

// ==========================================================================
// PART 4: Stress Checks (scope 10-12, repeated selected assertions)
// ==========================================================================

check A_AcyclicGeneralization for 10 expect 0
check A_UniqueCarrierIdentifiers for 10 expect 0
check A_ImplementationTargetsAvailable for 10 expect 0
check A_AbstractClassifiersHaveNoDirectInstances for 10 expect 0
