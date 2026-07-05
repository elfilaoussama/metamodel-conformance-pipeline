module class_level_structural_kernel

-- Class-level structural kernel for object-oriented models.
-- Alloy Analyzer 6.2.0; SAT4J; default bitwidth 4; symmetry breaking 20.

-- ---------------------------------------------------------------------------
-- Carriers and finite policy domains
-- ---------------------------------------------------------------------------

sig TYPE {}
sig SIGNATURE {}
sig EXPRESSION {}
sig NAME {}

abstract sig PropertyID {}
sig AttributeID extends PropertyID {}
sig MethodID extends PropertyID {}
sig ClassID {}
sig ObjectID {}

enum Visibility { Pub, Priv, Prot, Pkg }
enum YesNo { Yes, No }
enum Scope { Instance, Static }
enum ClassifierKind { ClassifierClass, ClassifierInterface }

sig Method {
    mid        : one MethodID,
    mname      : one NAME,
    msig       : one SIGNATURE,
    mvis       : one Visibility,
    mscope     : one Scope,
    rtype      : one TYPE,
    isAbstract : one YesNo
}

sig Attribute {
    aid    : one AttributeID,
    aname  : one NAME,
    atype  : one TYPE,
    avis   : one Visibility,
    ascope : one Scope
}

sig MethodBody {
    vars  : set AttributeID,
    calls : set MethodID,
    exprs : set EXPRESSION
}

sig Class {
    cid            : one ClassID,
    cname          : one NAME,
    kind           : one ClassifierKind,
    parents        : set Class,
    attributes     : set Attribute,
    methods        : set Method,
    iattributes    : set Attribute,
    imethods       : set Method,
    isAbstract     : one YesNo,
    implementation : MethodID -> lone MethodBody
}

sig Object {
    oid : one ObjectID
}

one sig World {
    instances : ClassID -> set Object
}

-- ---------------------------------------------------------------------------
-- Identity, ownership, and hierarchy
-- ---------------------------------------------------------------------------

fact IdentifierIntegrity {
    all disj c1, c2 : Class | c1.cid != c2.cid
    all disj m1, m2 : Method | m1.mid != m2.mid
    all disj a1, a2 : Attribute | a1.aid != a2.aid
    all disj o1, o2 : Object | o1.oid != o2.oid

    ClassID = Class.cid
    MethodID = Method.mid
    AttributeID = Attribute.aid
    ObjectID = Object.oid
}

fact ExclusiveDeclarationOwnership {
    all m : Method | one c : Class | m in c.methods
    all a : Attribute | one c : Class | a in c.attributes
}

fact AcyclicGeneralization {
    no c : Class | c in c.^parents
}

fun children[c : Class] : set Class { c.~parents }
fun ancestors[c : Class] : set Class { c.^parents }
fun offspring[c : Class] : set Class { c.^(~parents) }

fun methodOwner[m : Method] : one Class { methods.m }
fun attributeOwner[a : Attribute] : one Class { attributes.a }

fact GeneralizationKindPolicy {
    all c : Class | c.kind = ClassifierClass implies
        lone p : c.parents | p.kind = ClassifierClass

    all c : Class | c.kind = ClassifierInterface implies
        all p : c.parents | p.kind = ClassifierInterface
}

-- ---------------------------------------------------------------------------
-- Inherited views and name-based conflict policy
-- ---------------------------------------------------------------------------

pred hiddenByNearerMethod[c, owner : Class, m : Method] {
    some nearer : ancestors[c] - owner |
        owner in ancestors[nearer] and
        some replacement : nearer.methods |
            replacement.mname = m.mname
}

fact InheritedMethodView {
    all c : Class |
        c.imethods = {
            m : Method |
                some owner : ancestors[c] |
                    m in owner.methods and
                    m.mvis != Priv and
                    not hiddenByNearerMethod[c, owner, m]
        }
}

fact InheritedAttributeView {
    all c : Class |
        c.iattributes = {
            a : Attribute |
                some owner : ancestors[c] |
                    a in owner.attributes and a.avis != Priv
        }
}

fact LocalInheritedSeparation {
    all c : Class | no c.methods & c.imethods
    all c : Class | no c.attributes & c.iattributes
}

fact LocalMethodNamespace {
    all c : Class | all disj m1, m2 : c.methods |
        m1.mname != m2.mname
}

fact NoUnresolvedInheritedMethodConflict {
    all c : Class | all disj m1, m2 : c.imethods |
        m1.mname != m2.mname
}

-- ---------------------------------------------------------------------------
-- Implementation binding, abstraction, and classifier kind
-- ---------------------------------------------------------------------------

fun locallyImplementedMethodIDs[c : Class] : set MethodID {
    (c.implementation).MethodBody
}

fun implementedMethodIDs[c : Class] : set MethodID {
    locallyImplementedMethodIDs[c] +
    { id : MethodID |
        some a : ancestors[c] |
            id in locallyImplementedMethodIDs[a]
    }
}

fact ImplementationDomainValidity {
    all c : Class |
        locallyImplementedMethodIDs[c] in (c.methods + c.imethods).mid
}

fact AbstractMethodHasNoDeclaringBody {
    all c : Class, m : c.methods |
        m.isAbstract = Yes implies
            m.mid not in locallyImplementedMethodIDs[c]
}

fact StaticMethodPolicy {
    all m : Method |
        m.mscope = Static implies m.isAbstract = No
}

pred hasUnresolvedMethod[c : Class] {
    some m : c.methods |
        m.isAbstract = Yes or
        m.mid not in locallyImplementedMethodIDs[c]
    or
    some m : c.imethods |
        m.mid not in implementedMethodIDs[c]
}

fact AbstractionPolicy {
    all c : Class |
        hasUnresolvedMethod[c] implies c.isAbstract = Yes

    all c : Class |
        c.isAbstract = No implies not hasUnresolvedMethod[c]

    all c : Class |
        c.kind = ClassifierInterface implies c.isAbstract = Yes
}

fact InterfacePolicy {
    all c : Class | c.kind = ClassifierInterface implies {
        no c.attributes
        no c.implementation
        all m : c.methods |
            m.isAbstract = Yes and
            m.mvis = Pub and
            m.mscope = Instance
    }
}

pred overrides[c : Class, inherited, local : Method] {
    inherited in c.imethods
    local in c.methods
    inherited.mname = local.mname
}

fact ExplicitOverrideDecision {
    all c : Class, inherited : c.imethods, local : c.methods |
        overrides[c, inherited, local] implies
            (local.isAbstract = Yes or
             local.mid in locallyImplementedMethodIDs[c])
}

-- ---------------------------------------------------------------------------
-- Minimal direct-instance semantics
-- ---------------------------------------------------------------------------

fun directInstances[c : Class] : set Object {
    World.instances[c.cid]
}

fun instancesOf[c : Class] : set Object {
    { o : Object |
        some d : c.*(~parents) |
            o in directInstances[d]
    }
}

fact DirectInstancePolicy {
    all o : Object | one World.instances.o
    all c : Class |
        c.isAbstract = Yes implies no directInstances[c]
}

-- ---------------------------------------------------------------------------
-- Structural dependency helpers
-- ---------------------------------------------------------------------------

pred useMethods[c1, c2 : Class] {
    some (MethodID.(c1.implementation)).calls &
         (c2.methods.mid + c2.imethods.mid)
}

pred useVariables[c1, c2 : Class] {
    some (MethodID.(c1.implementation)).vars &
         (c2.attributes.aid + c2.iattributes.aid)
}

pred coupled[c1, c2 : Class] {
    useMethods[c1, c2] or useVariables[c1, c2] or
    useMethods[c2, c1] or useVariables[c2, c1]
}

-- ---------------------------------------------------------------------------
-- Non-vacuity witnesses: each command is expected to be satisfiable.
-- ---------------------------------------------------------------------------

pred NV_SimpleConcreteClass {
    some c : Class, m : Method, a : Attribute,
         mb : MethodBody, o : Object |
        c.kind = ClassifierClass and
        c.isAbstract = No and
        m in c.methods and
        m.isAbstract = No and
        m.mid -> mb in c.implementation and
        a in c.attributes and
        o in directInstances[c]
}
run NV_SimpleConcreteClass for 5 but
    exactly 1 Class, exactly 1 Method, exactly 1 Attribute,
    exactly 1 MethodBody, exactly 1 Object expect 1

pred NV_InheritanceChain {
    some disj root, middle, leaf : Class |
        root in middle.parents and
        middle in leaf.parents and
        root.kind = ClassifierClass and
        middle.kind = ClassifierClass and
        leaf.kind = ClassifierClass
}
run NV_InheritanceChain for 5 but exactly 3 Class expect 1

pred NV_AbstractMethodImplementedBySubclass {
    some disj base, derived : Class, m : Method, mb : MethodBody |
        base.kind = ClassifierClass and
        derived.kind = ClassifierClass and
        base in derived.parents and
        m in base.methods and
        m.isAbstract = Yes and
        base.isAbstract = Yes and
        m in derived.imethods and
        m.mid -> mb in derived.implementation and
        derived.isAbstract = No
}
run NV_AbstractMethodImplementedBySubclass for 5 but
    exactly 2 Class, exactly 1 Method, exactly 1 MethodBody expect 1

pred NV_ExplicitAbstractClassWithoutMethods {
    some c : Class |
        c.kind = ClassifierClass and
        c.isAbstract = Yes and
        no c.methods and no c.imethods
}
run NV_ExplicitAbstractClassWithoutMethods for 4 but exactly 1 Class expect 1

pred NV_EmptyInterface {
    some i : Class |
        i.kind = ClassifierInterface and
        no i.methods and no i.attributes
}
run NV_EmptyInterface for 4 but exactly 1 Class expect 1

pred NV_InterfaceInheritance {
    some disj parent, child : Class, m : Method |
        parent.kind = ClassifierInterface and
        child.kind = ClassifierInterface and
        parent in child.parents and
        m in parent.methods and
        m in child.imethods
}
run NV_InterfaceInheritance for 5 but
    exactly 2 Class, exactly 1 Method expect 1

pred NV_InheritedMembers {
    some disj parent, child : Class, m : Method,
         a : Attribute, mb : MethodBody |
        parent.kind = ClassifierClass and
        child.kind = ClassifierClass and
        parent in child.parents and
        m in parent.methods and
        m.isAbstract = No and
        m.mvis != Priv and
        m.mid -> mb in parent.implementation and
        m in child.imethods and
        a in parent.attributes and
        a.avis != Priv and
        a in child.iattributes
}
run NV_InheritedMembers for 5 but
    exactly 2 Class, exactly 1 Method, exactly 1 Attribute,
    exactly 1 MethodBody expect 1

pred NV_OverridingSituation {
    some disj parent, child : Class,
         disj inherited, local : Method,
         disj parentBody, childBody : MethodBody |
        parent.kind = ClassifierClass and
        child.kind = ClassifierClass and
        parent in child.parents and
        inherited in parent.methods and
        inherited.isAbstract = No and
        inherited.mvis != Priv and
        inherited.mid -> parentBody in parent.implementation and
        local in child.methods and
        local.mname = inherited.mname and
        local.isAbstract = No and
        local.mid -> childBody in child.implementation and
        overrides[child, inherited, local]
}
run NV_OverridingSituation for 6 but
    exactly 2 Class, exactly 2 Method, exactly 2 MethodBody expect 1

pred NV_MultipleInterfaceParents {
    some disj c, i1, i2 : Class |
        c.kind = ClassifierClass and
        i1.kind = ClassifierInterface and
        i2.kind = ClassifierInterface and
        i1 + i2 in c.parents
}
run NV_MultipleInterfaceParents for 5 but exactly 3 Class expect 1

pred NV_CoupledClasses {
    some disj c1, c2 : Class,
         disj caller, callee : Method,
         disj callerBody, calleeBody : MethodBody |
        caller in c1.methods and
        callee in c2.methods and
        caller.isAbstract = No and
        callee.isAbstract = No and
        caller.mid -> callerBody in c1.implementation and
        callee.mid -> calleeBody in c2.implementation and
        callee.mid in callerBody.calls and
        coupled[c1, c2]
}
run NV_CoupledClasses for 6 but
    exactly 2 Class, exactly 2 Method, exactly 2 MethodBody expect 1

-- ---------------------------------------------------------------------------
-- Negative probes: each command is expected to be unsatisfiable.
-- ---------------------------------------------------------------------------

pred BAD_InheritanceCycle {
    some c : Class | c in c.^parents
}
run BAD_InheritanceCycle for 8 expect 0

pred BAD_SharedMethodOwnership {
    some disj c1, c2 : Class, m : Method |
        m in c1.methods and m in c2.methods
}
run BAD_SharedMethodOwnership for 8 expect 0

pred BAD_SharedAttributeOwnership {
    some disj c1, c2 : Class, a : Attribute |
        a in c1.attributes and a in c2.attributes
}
run BAD_SharedAttributeOwnership for 8 expect 0

pred BAD_LocalInheritedOverlap {
    some c : Class |
        some c.methods & c.imethods or
        some c.attributes & c.iattributes
}
run BAD_LocalInheritedOverlap for 8 expect 0

pred BAD_PrivateMemberInherited {
    some c : Class |
        (some m : c.imethods | m.mvis = Priv) or
        (some a : c.iattributes | a.avis = Priv)
}
run BAD_PrivateMemberInherited for 8 expect 0

pred BAD_PhantomImplementation {
    some c : Class, id : MethodID, mb : MethodBody |
        id -> mb in c.implementation and
        id not in (c.methods + c.imethods).mid
}
run BAD_PhantomImplementation for 8 expect 0

pred BAD_AbstractDirectInstance {
    some c : Class |
        c.isAbstract = Yes and some directInstances[c]
}
run BAD_AbstractDirectInstance for 8 expect 0

pred BAD_ObjectWithMultipleDirectClasses {
    some o : Object | #World.instances.o > 1
}
run BAD_ObjectWithMultipleDirectClasses for 8 expect 0

pred BAD_InterfaceImplementation {
    some i : Class |
        i.kind = ClassifierInterface and some i.implementation
}
run BAD_InterfaceImplementation for 8 expect 0

pred BAD_InterfaceAttribute {
    some i : Class |
        i.kind = ClassifierInterface and some i.attributes
}
run BAD_InterfaceAttribute for 8 expect 0

pred BAD_AbstractMethodWithDeclaringBody {
    some c : Class, m : c.methods |
        m.isAbstract = Yes and
        m.mid in locallyImplementedMethodIDs[c]
}
run BAD_AbstractMethodWithDeclaringBody for 8 expect 0

pred BAD_StaticAbstractMethod {
    some m : Method |
        m.mscope = Static and m.isAbstract = Yes
}
run BAD_StaticAbstractMethod for 8 expect 0

pred BAD_InheritedMethodNameConflict {
    some c : Class | some disj m1, m2 : c.imethods |
        m1.mname = m2.mname
}
run BAD_InheritedMethodNameConflict for 8 expect 0

pred BAD_OverrideWithoutDecision {
    some c : Class, inherited : c.imethods, local : c.methods |
        overrides[c, inherited, local] and
        local.isAbstract = No and
        local.mid not in locallyImplementedMethodIDs[c]
}
run BAD_OverrideWithoutDecision for 8 expect 0

pred BAD_InterfaceExtendsClass {
    some i : Class, p : i.parents |
        i.kind = ClassifierInterface and
        p.kind = ClassifierClass
}
run BAD_InterfaceExtendsClass for 8 expect 0

pred BAD_TwoConcreteParents {
    some c : Class | c.kind = ClassifierClass and
        #({ p : c.parents | p.kind = ClassifierClass }) > 1
}
run BAD_TwoConcreteParents for 8 expect 0

pred BAD_UnknownIdentifier {
    some ClassID - Class.cid or
    some MethodID - Method.mid or
    some AttributeID - Attribute.aid or
    some ObjectID - Object.oid
}
run BAD_UnknownIdentifier for 8 expect 0

-- ---------------------------------------------------------------------------
-- Assertions: absence of a counterexample is expected in each finite scope.
-- ---------------------------------------------------------------------------

assert IdentifierBijections {
    ClassID = Class.cid
    MethodID = Method.mid
    AttributeID = Attribute.aid
    ObjectID = Object.oid
}
check IdentifierBijections for 8 expect 0

assert UniqueIdentifiers {
    all disj c1, c2 : Class | c1.cid != c2.cid
    all disj m1, m2 : Method | m1.mid != m2.mid
    all disj a1, a2 : Attribute | a1.aid != a2.aid
    all disj o1, o2 : Object | o1.oid != o2.oid
}
check UniqueIdentifiers for 8 expect 0

assert ParentAncestorConsistency {
    all c : Class | c.parents in ancestors[c]
}
check ParentAncestorConsistency for 8 expect 0

assert AncestorOffspringSymmetry {
    all c1, c2 : Class |
        c2 in ancestors[c1] iff c1 in offspring[c2]
}
check AncestorOffspringSymmetry for 8 expect 0

assert RootHasNoInheritedMembers {
    all c : Class | no c.parents implies
        no c.imethods and no c.iattributes
}
check RootHasNoInheritedMembers for 8 expect 0

assert InheritedMembersComeFromAncestors {
    all c : Class |
        (all m : c.imethods |
            m.mvis != Priv and methodOwner[m] in ancestors[c]) and
        (all a : c.iattributes |
            a.avis != Priv and attributeOwner[a] in ancestors[c])
}
check InheritedMembersComeFromAncestors for 8 expect 0

assert DeclarationOwnershipIsUnique {
    all m : Method | one methodOwner[m]
    all a : Attribute | one attributeOwner[a]
}
check DeclarationOwnershipIsUnique for 8 expect 0

assert ImplementationTargetsAreAvailable {
    all c : Class |
        locallyImplementedMethodIDs[c] in
            (c.methods + c.imethods).mid
}
check ImplementationTargetsAreAvailable for 8 expect 0

assert ConcreteClassesHaveNoUnresolvedMethods {
    all c : Class | c.isAbstract = No implies
        not hasUnresolvedMethod[c]
}
check ConcreteClassesHaveNoUnresolvedMethods for 8 expect 0

assert AbstractAndInterfaceClassesHaveNoDirectInstances {
    all c : Class |
        (c.isAbstract = Yes or c.kind = ClassifierInterface) implies
            no directInstances[c]
}
check AbstractAndInterfaceClassesHaveNoDirectInstances for 8 expect 0

assert EachObjectHasOneDirectClass {
    all o : Object | one World.instances.o
}
check EachObjectHasOneDirectClass for 8 expect 0

assert SubclassExtensionIsIncludedInParentExtension {
    all child, parent : Class |
        parent in ancestors[child] implies
            instancesOf[child] in instancesOf[parent]
}
check SubclassExtensionIsIncludedInParentExtension for 8 expect 0

assert InterfaceRestrictionsHold {
    all i : Class | i.kind = ClassifierInterface implies {
        i.isAbstract = Yes
        no i.attributes
        no i.implementation
        no directInstances[i]
        all m : i.methods |
            m.isAbstract = Yes and
            m.mvis = Pub and
            m.mscope = Instance
        all p : i.parents |
            p.kind = ClassifierInterface
    }
}
check InterfaceRestrictionsHold for 8 expect 0

assert InheritedMethodNamesAreUnambiguous {
    all c : Class | all disj m1, m2 : c.imethods |
        m1.mname != m2.mname
}
check InheritedMethodNamesAreUnambiguous for 8 expect 0

assert OverrideDecisionsAreExplicit {
    all c : Class, inherited : c.imethods, local : c.methods |
        overrides[c, inherited, local] implies
            (local.isAbstract = Yes or
             local.mid in locallyImplementedMethodIDs[c])
}
check OverrideDecisionsAreExplicit for 8 expect 0

assert NoDanglingBodyReferences {
    all mb : MethodBody |
        mb.calls in Method.mid and mb.vars in Attribute.aid
}
check NoDanglingBodyReferences for 8 expect 0

-- Selected larger-scope checks. These remain bounded analyses.

assert StressHierarchy {
    no c : Class | c in c.^parents
    all c1, c2 : Class |
        c2 in ancestors[c1] iff c1 in offspring[c2]
}
check StressHierarchy for 12 expect 0

assert StressOwnershipAndImplementation {
    all m : Method | one c : Class | m in c.methods
    all a : Attribute | one c : Class | a in c.attributes
    all c : Class |
        locallyImplementedMethodIDs[c] in
            (c.methods + c.imethods).mid
}
check StressOwnershipAndImplementation for 10 expect 0

assert StressInheritanceAndConflict {
    all c : Class |
        no c.methods & c.imethods and
        no c.attributes & c.iattributes
    all c : Class | all disj m1, m2 : c.imethods |
        m1.mname != m2.mname
}
check StressInheritanceAndConflict for 10 expect 0

assert StressAbstractionAndInstances {
    all c : Class | c.isAbstract = No implies
        not hasUnresolvedMethod[c]
    all c : Class | c.isAbstract = Yes implies
        no directInstances[c]
    all o : Object | one World.instances.o
}
check StressAbstractionAndInstances for 10 expect 0
