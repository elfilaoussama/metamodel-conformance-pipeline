package metamodel.conformance.pipeline.adapter.cpp;

import metamodel.conformance.pipeline.model.*;
import java.util.*;
import java.util.stream.Collectors;

/** Completeness of native source-definition incidence; contains no P0 conditions. */
final class CppSourceDefinitionCoverage {
 private CppSourceDefinitionCoverage() { }
 static boolean complete(List<MemberObservation> members,Map<String,CppNativeMethodReceipt.Method> nativeMethods,
                         boolean methodCensusComplete,List<MethodBodyObservation> bodies,
                         List<ImplementationBindingObservation> localBindings,boolean bodyCensusComplete) {
  if(!methodCensusComplete || !bodyCensusComplete)return false;
  Set<String> methods=members.stream().filter(m->m.kind()==MemberKind.METHOD)
      .map(MemberObservation::technicalKey).collect(Collectors.toSet());
  if(!methods.equals(nativeMethods.keySet()))return false;
  Set<String> bodyKeys=bodies.stream().map(MethodBodyObservation::technicalKey).collect(Collectors.toSet());
  if(bodyKeys.size()!=bodies.size())return false;
  Set<String> boundMethods=new HashSet<>(),boundBodies=new HashSet<>();
  for(var binding:localBindings) {
   var method=nativeMethods.get(binding.targetMemberKey());
   if(method==null || !method.owner().equals(binding.implementerClassifierId()) || !bodyKeys.contains(binding.bodyKey()))return false;
   boundMethods.add(binding.targetMemberKey());boundBodies.add(binding.bodyKey());
  }
  return boundMethods.equals(methods) && boundBodies.equals(bodyKeys);
 }
}
