pred ObservationConsistency {}

fun ExclusiveDeclarationOwnershipViolations : set Member {
  { m : Member |
    not one c : Classifier | m in c.declaredMembers
  }
}

fun AcyclicGeneralizationViolations : set Classifier {
  { c : SourceClassifiers | c in c.^parents }
}

pred localMemberHides[c : Classifier, inherited : Member] {
  some local : c.declaredMembers | sameMemberKey[local, inherited]
}

pred nearerAncestorMemberHides[c, owner : Classifier, inherited : Member] {
  some nearer : c.^parents - owner |
    owner in nearer.^parents and
    some replacement : nearer.declaredMembers |
      memberAccessibleFrom[c, nearer, replacement] and
      sameMemberKey[replacement, inherited]
}

pred samePackageInheritancePath[c, owner : Classifier] {
  c.packageName = owner.packageName and
  owner in c.^({ from, to : Classifier |
    to in from.parents and
    from.packageName = owner.packageName and
    to.packageName = owner.packageName
  })
}

pred memberAccessibleFrom[c, owner : Classifier, member : Member] {
  member.inheritability = INHERITABLE and
  (
    member.visibility = PUBLIC or
    member.visibility = PROTECTED or
    (member.visibility = PACKAGE and samePackageInheritancePath[c, owner])
  )
}

fun formalInheritedMembers[c : Classifier] : set Member {
  { inherited : Member |
    some owner : c.^parents |
      inherited in owner.declaredMembers and
      memberAccessibleFrom[c, owner, inherited] and
      not localMemberHides[c, inherited] and
      not nearerAncestorMemberHides[c, owner, inherited]
  }
}

fun InheritedViewConsistencyViolations : Classifier -> Member {
  { c : SourceClassifiers, m : Member |
    (m in c.observedInheritedMembers and m not in formalInheritedMembers[c]) or
    (m not in c.observedInheritedMembers and m in formalInheritedMembers[c])
  }
}

fun LocalInheritedSeparationViolations : Classifier -> Member {
  { c : SourceClassifiers, m : Member |
    m in c.declaredMembers and m in c.observedInheritedMembers
  }
}

pred sameMemberKey[m1, m2 : Member] {
  (
    m1.kind = METHOD and
    m2.kind = METHOD and
    m1.memberName = m2.memberName and
    m1.parameterTypeAt = m2.parameterTypeAt
  ) or (
    m1.kind = ATTRIBUTE and
    m2.kind = ATTRIBUTE and
    m1.memberName = m2.memberName
  )
}

fun LocalNamespaceUniquenessViolations : set Member {
  { m1 : SourceClassifiers.declaredMembers |
    some c : SourceClassifiers |
      m1 in c.declaredMembers and
      some m2 : c.declaredMembers - m1 | sameMemberKey[m1, m2]
  }
}

fun InheritedNamespaceUniquenessViolations : Classifier -> Member {
  { c : SourceClassifiers, m1 : formalInheritedMembers[c] |
    some m2 : formalInheritedMembers[c] - m1 | sameMemberKey[m1, m2]
  }
}

pred bindingTargetAvailable[b : ImplementationBinding] {
  b.implementer in SourceClassifiers and
  b.target.kind = METHOD and
  b.target in b.implementer.declaredMembers + formalInheritedMembers[b.implementer]
}

fun implementedMethodsVisibleTo[c : Classifier] : set Member {
  { m : Member |
    m.kind = METHOD and
    (
      (some b : ImplementationBinding |
        b.target = m and b.implementer in c.*parents)
      or
      (some support : c.*parents - SourceClassifiers |
        m in support.declaredMembers and m.abstraction = CONCRETE)
    )
  }
}

pred unresolvedMethod[c : Classifier, m : Member] {
  c in SourceClassifiers and
  m.kind = METHOD and
  m in c.declaredMembers + formalInheritedMembers[c] and
  m not in implementedMethodsVisibleTo[c]
}

fun ImplementationBindingViolations : set univ {
  { m : SourceClassifiers.declaredMembers |
    (m.kind = METHOD and m.abstraction = ABSTRACTION_UNKNOWN) or
    (some b : ImplementationBinding |
      b.target = m and not bindingTargetAvailable[b]) or
    (some disj b1, b2 : ImplementationBinding |
      b1.target = m and b2.target = m and b1.implementer = b2.implementer) or
    (some c : SourceClassifiers |
      m.kind = METHOD and m in c.declaredMembers and
      (
        (m.abstraction = ABSTRACT and
          some b : ImplementationBinding | b.implementer = c and b.target = m) or
        (m.abstraction = CONCRETE and
          not (one b : ImplementationBinding | b.implementer = c and b.target = m))
      )
    )
  } +
  { methodBody : MethodBody |
    not (one b : ImplementationBinding |
      b.body = methodBody and b.implementer in SourceClassifiers)
  }
}

fun AbstractionImplementationViolations : set Classifier {
  { c : SourceClassifiers |
    c.classifierAbstraction = CLASSIFIER_ABSTRACTION_UNKNOWN or
    (
      (some m : Member | unresolvedMethod[c, m]) and
      c.classifierAbstraction != CLASSIFIER_ABSTRACT
    )
  }
}

fun StaticAbstractMethodViolations : set Member {
  { m : SourceClassifiers.declaredMembers |
    m.kind = METHOD and
    (
      m.abstraction = ABSTRACTION_UNKNOWN or
      m.memberScope = SCOPE_UNKNOWN or
      (m.abstraction = ABSTRACT and m.memberScope = STATIC_SCOPE)
    )
  }
}

pred formalOverrideCandidate[c : Classifier, inherited, local : Member] {
  c in SourceClassifiers
  inherited.kind = METHOD
  local.kind = METHOD
  local in c.declaredMembers
  some owner : c.^parents |
    inherited in owner.declaredMembers and
    memberAccessibleFrom[c, owner, inherited]
  sameMemberKey[inherited, local]
  inherited.memberScope = local.memberScope
}

pred formallyOverrides[inherited, local : Member] {
  some c : SourceClassifiers | formalOverrideCandidate[c, inherited, local]
}

pred localOverrideImplemented[local : Member] {
  some c : SourceClassifiers |
    local in c.declaredMembers and
    some b : ImplementationBinding |
      b.implementer = c and b.target = local
}

fun OverrideRelationConsistencyViolations : Member -> Member {
  { local : SourceClassifiers.declaredMembers, inherited : Member |
    (inherited in local.observedOverrides and not formallyOverrides[inherited, local]) or
    (inherited not in local.observedOverrides and formallyOverrides[inherited, local])
  }
}

fun OverrideDisciplineViolations : Member -> Member {
  { local : SourceClassifiers.declaredMembers, inherited : Member |
    formallyOverrides[inherited, local] and
    (
      no inherited.returnType or
      no local.returnType or
      inherited.returnType != local.returnType or
      (local.abstraction != ABSTRACT and not localOverrideImplemented[local])
    )
  }
}
