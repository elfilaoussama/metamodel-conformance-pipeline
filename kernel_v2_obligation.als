// ==========================================================================
// kernel_v2_obligation.als
// Built purely from the ten obligations (Section III).
// Each construct marked with the obligation that demands it.
// ==========================================================================
module kernel_v2_obligation

// ==========================================================================
// O-01: Distinct Structural Carriers
// Six carrier types, each with independent identity. Name separate from ID.
// ==========================================================================

sig Name {}
                                        // O-01: names not conflated with IDs

sig ClassifierID {}                     // O-01
sig MethodID {}                         // O-01
sig AttributeID {}                      // O-01
sig ObjectID {}                         // O-01

// --- Types (needed for O-09 isSubtype, declared here per O-01 carrier rule) ---

abstract sig Type {}                    // O-09 (base for isSubtype)
sig PrimitiveType extends Type {}       // O-09 (primitive types are always 
                                        //        equal only to themselves)
sig ClassifierType extends Type {       // O-09 (subtype when classifier is
    classifier : one Classifier         //        descendant of other's 
}                                       //        classifier in parent chain)

// --- Enums (O-04, O-07, O-09) ---

enum Visibility { Pub, Prot, Pkg, Priv }   // O-04 (private exclusion)
enum Scope { Instance, Static }            // O-09 (override scope matching)
enum Flag { Yes, No }                      // O-07 (isAbstract binary marker)

// --- Classifiers (O-01 carrier, O-03 parent relations) ---

abstract sig Classifier {               // O-01
    cid                 : one ClassifierID,                   // O-01
    name                : one Name,                          // O-01
    classParent         : set Class,   // O-03 (class parents)
    interfaceParents    : set Interface,// O-03 (multiple interface parents)
    localMethods        : set Method,   // O-01, O-02
    localAttributes     : set Attribute,// O-01, O-02
    inheritedMethods    : set Method,   // O-04
    inheritedAttributes : set Attribute,// O-04
    isAbstract          : one Flag,     // O-07
    directInstances     : set Object    // O-07
}

sig Class extends Classifier {}         // O-03 (needed to distinguish class
                                        //        parent from interface parent)
sig Interface extends Classifier {}     // O-03

// --- Members (O-01 carriers, O-04 visibility, O-09 scope) ---

abstract sig Member {                   // O-01
    memberName : one Name,             // O-04, O-08 (used in key/name preds)
    visibility : one Visibility,        // O-04 (private exclusion filter)
    scope      : one Scope             // O-09 (scope matching in override)
}

sig Method extends Member {             // O-01
    mid        : one MethodID,         // O-01
    returnType : one Type,             // O-09 (covariance in override)
    paramTypes : Int -> lone Type,     // O-04, O-08 (method key = name + types)
    isAbstract : one Flag              // O-07
}

sig Attribute extends Member {          // O-01
    aid  : one AttributeID,            // O-01
    type : one Type                    // O-01 (attribute must carry a type)
}

// --- Method bodies and bindings (O-01, O-06) ---

sig MethodBody {}                       // O-01 carrier, O-06 binding target
                                        // No reads/calls/exprs: not demanded
                                        // by any obligation

sig ImplementationBinding {             // O-01, O-06
    implementer : one Class,           // O-06 (class that provides the body)
    target      : one Method,          // O-06 (method being implemented)
    body        : one MethodBody       // O-06 (the concrete body)
}

// --- Objects (O-01 carrier, O-07 direct instances) ---

sig Object {                            // O-01
    oid : one ObjectID                 // O-01
}

// ==========================================================================
// O-01: Identifier Integrity
// "Each with its own identifier space." IDs unique, closed per carrier.
// ==========================================================================

fact IdentifierIntegrity {              // O-01
    all disj c1, c2 : Classifier | c1.cid != c2.cid
    all disj m1, m2 : Method | m1.mid != m2.mid
    all disj a1, a2 : Attribute | a1.aid != a2.aid
    all disj o1, o2 : Object | o1.oid != o2.oid

    ClassifierID = Classifier.cid       // every ID atom belongs to a carrier
    MethodID     = Method.mid
    AttributeID  = Attribute.aid
    ObjectID     = Object.oid
}

// ==========================================================================
// O-02: Exclusive Declaration Ownership
// "Every method/attribute declared by exactly one classifier."
// ==========================================================================

fact ExclusiveDeclarationOwnership {    // O-02
    all m : Method | one c : Classifier | m in c.localMethods
    all a : Attribute | one c : Classifier | a in c.localAttributes
}

// ==========================================================================
// O-03: Explicit Acyclic Inheritance
// "Must name which kinds of inheritance are supported (class parents,
//  multiple interface parents)." Acyclicity. Closures for ancestors.
// ==========================================================================

fact AcyclicGeneralization {            // O-03
    no c : Classifier | c in c.^(classParent + interfaceParents)
}

fact GeneralizationKindPolicy {         // O-03 (guards interfaces from
    no i : Interface | some i.classParent  //   having class parents)
}

fun ancestors[c : Classifier] : set Classifier {   // O-03, O-04
    c.^(classParent + interfaceParents)
}

fun descendants[c : Classifier] : set Classifier { // O-03
    c.^(~(classParent + interfaceParents))
}

// ==========================================================================
// O-04: Inherited Member Derivation
// derived sets: ancestors, visibility != Priv, local suppression, 
// nearer-ancestor priority.
// ==========================================================================

pred sameMethodKey[m1, m2 : Method] {        // O-04, O-08
    m1.memberName = m2.memberName
    m1.paramTypes = m2.paramTypes
}

pred sameAttributeName[a1, a2 : Attribute] { // O-04, O-08
    a1.memberName = a2.memberName
}

// --- Suppression predicates (O-04 local override + nearer-ancestor) ---

pred localMethodHides[c : Classifier, m : Method] {    // O-04
    some lm : c.localMethods | sameMethodKey[lm, m]
}

pred localAttributeHides[c : Classifier, a : Attribute] { // O-04
    some la : c.localAttributes | sameAttributeName[la, a]
}

pred nearerAncestorMethodHides[c, owner : Classifier, m : Method] {  // O-04
    some nearer : ancestors[c] - owner |
        owner in ancestors[nearer] and
        some replacement : nearer.localMethods |
            replacement.visibility != Priv and
            sameMethodKey[replacement, m]
}

pred nearerAncestorAttributeHides[c, owner : Classifier, a : Attribute] {  // O-04
    some nearer : ancestors[c] - owner |
        owner in ancestors[nearer] and
        some replacement : nearer.localAttributes |
            replacement.visibility != Priv and
            sameAttributeName[replacement, a]
}

// --- Derived set facts (O-04) ---

fact InheritedMethodView {              // O-04
    all c : Classifier |
        c.inheritedMethods = {
            m : Method |
                some owner : ancestors[c] |
                    m in owner.localMethods and
                    m.visibility != Priv and
                    not localMethodHides[c, m] and
                    not nearerAncestorMethodHides[c, owner, m]
        }
}

fact InheritedAttributeView {           // O-04
    all c : Classifier |
        c.inheritedAttributes = {
            a : Attribute |
                some owner : ancestors[c] |
                    a in owner.localAttributes and
                    a.visibility != Priv and
                    not localAttributeHides[c, a] and
                    not nearerAncestorAttributeHides[c, owner, a]
        }
}

// ==========================================================================
// O-05: Local/Inherited Disjointness
// "The set of local members and inherited members must be disjoint."
// ==========================================================================

fact LocalInheritedSeparation {         // O-05
    all c : Classifier | no c.localMethods & c.inheritedMethods
    all c : Classifier | no c.localAttributes & c.inheritedAttributes
}

// ==========================================================================
// O-08: Namespace Policy (declared before O-06 because O-06 depends on 
// no duplicate keys for its binding uniqueness constraint)
// "No two local methods share the same key. No two local attributes share
//  the same name. Inherited members must not introduce conflicts."
// ==========================================================================

fact LocalNamespaces {                  // O-08
    all c : Classifier | all disj m1, m2 : c.localMethods |
        not sameMethodKey[m1, m2]
    all c : Classifier | all disj a1, a2 : c.localAttributes |
        not sameAttributeName[a1, a2]
}

fact InheritedConflictPolicy {          // O-08
    all c : Classifier | all disj m1, m2 : c.inheritedMethods |
        not sameMethodKey[m1, m2]
    all c : Classifier | all disj a1, a2 : c.inheritedAttributes |
        not sameAttributeName[a1, a2]
}

// ==========================================================================
// O-06: Implementation Binding
// "Domain validity: target in implementer's local or inherited methods.
//  Every body in exactly one binding. At most one binding per class×method.
//  Abstract methods get no local body. Non-abstract local methods get one."
// ==========================================================================

fun bindingsOf[c : Class] : set ImplementationBinding {  // O-06 helper
    { b : ImplementationBinding | b.implementer = c }
}

fun implementedMethodsVisibleTo[c : Classifier] : set Method { // O-06 helper
    { m : Method |
        some b : ImplementationBinding |
            b.target = m and
            b.implementer in c.*(classParent + interfaceParents) & Class
    }
}

pred unresolvedMethod[c : Classifier, m : Method] {  // O-06, O-07
    m in c.localMethods + c.inheritedMethods
    m not in implementedMethodsVisibleTo[c]
}

fact ImplementationBindingPolicy {      // O-06
    // Domain validity
    all b : ImplementationBinding |
        b.target in b.implementer.localMethods
                  + b.implementer.inheritedMethods

    // No orphan bodies
    all mb : MethodBody |
        one b : ImplementationBinding | b.body = mb

    // At most one binding per method per class
    all c : Class, m : Method |
        lone b : ImplementationBinding |
            b.implementer = c and b.target = m

    // Abstract methods: no local binding from declaring class
    all c : Class, m : c.localMethods |
        m.isAbstract = Yes implies
            no b : ImplementationBinding |
                b.implementer = c and b.target = m

    // Non-abstract local methods: exactly one binding from declaring class
    all c : Class, m : c.localMethods |
        m.isAbstract = No implies
            one b : ImplementationBinding |
                b.implementer = c and b.target = m
}

// ==========================================================================
// O-07: Abstraction and Instantiation Consistency
// "Abstract classifiers: no direct instances. Abstract methods: no local 
//  body from declaring class. Non-abstract: no unresolved methods."
// "Interfaces inherently abstract, no direct instances, no instance-scoped 
//  attributes."
// ==========================================================================

fact AbstractionPolicy {                // O-07
    // Unresolved method forces abstract
    all c : Classifier |
        (some m : Method | unresolvedMethod[c, m])
            implies c.isAbstract = Yes

    // Non-abstract must have no unresolved methods
    all c : Classifier |
        c.isAbstract = No implies
            no m : Method | unresolvedMethod[c, m]

    // Interfaces are inherently abstract
    all i : Interface | i.isAbstract = Yes
}

fact InterfacePolicy {                  // O-07
    all i : Interface | {
        no i.directInstances           // no direct instances
        all a : i.localAttributes | a.scope = Static  // no instance-scoped attrs
    }
}

fact DirectInstancePolicy {             // O-07
    all o : Object | one c : Class | o in c.directInstances
    all c : Classifier |
        c.isAbstract = Yes implies no c.directInstances
    all i : Interface | no i.directInstances
}

// ==========================================================================
// O-09: Override Discipline
// "Return type must be subtype. Overriding method must be abstract or 
//  receive a concrete implementation from the overriding class."
// ==========================================================================

pred isSubtype[t1, t2 : Type] {         // O-09
    t1 = t2
    or
    some ct1, ct2 : ClassifierType |
        ct1 = t1 and ct2 = t2 and
        ct1.classifier in ct2.classifier.*(classParent + interfaceParents)
}

pred overrides[c : Classifier, inherited, local : Method] { // O-09
    inherited in (ancestors[c].localMethods)
    inherited.visibility != Priv
    local in c.localMethods
    sameMethodKey[inherited, local]
    inherited.scope = local.scope
}

fact OverridePolicy {                   // O-09
    all c : Classifier, inherited, local : Method |
        overrides[c, inherited, local] implies {
            isSubtype[local.returnType, inherited.returnType]
            local.isAbstract = Yes or
            some b : ImplementationBinding |
                c in Class and
                b.implementer = c and
                b.target = local
        }
}

// ==========================================================================
// Parameter Well-Formedness (implied by O-04/O-08: paramTypes must be 
// well-formed for sameMethodKey comparison to be reliable)
// ==========================================================================

fact ParameterPositionPolicy {          // O-04, O-08
    all m : Method | all i : Int |
        some m.paramTypes[i] implies i >= 0
}

fact ParameterContiguity {              // O-04, O-08
    all m : Method, i : Int |
        i > 0 and some m.paramTypes[i]
            implies some m.paramTypes[i - 1]
}
