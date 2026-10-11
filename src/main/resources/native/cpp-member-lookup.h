// Compiler-native declaration/subobject lookup transport. No formal-policy logic.
#pragma once
#include <clang/AST/RecursiveASTVisitor.h>
#include <clang/AST/CXXInheritance.h>
#include <clang/Sema/Sema.h>
#include <clang/Sema/Lookup.h>
#include <llvm/Support/JSON.h>
#include <llvm/Support/raw_ostream.h>
#include <set>

class CppMemberLookup : public clang::RecursiveASTVisitor<CppMemberLookup> {
    clang::Sema &sema;
    static std::string id(const clang::Decl *decl) {
        std::string value; llvm::raw_string_ostream stream(value);
        stream << static_cast<const void *>(decl); return stream.str();
    }
    static std::string access(clang::AccessSpecifier value) {
        return value==clang::AS_public ? "PUBLIC" : value==clang::AS_protected ? "PROTECTED"
            : value==clang::AS_private ? "PRIVATE" : "UNKNOWN";
    }
    void names(clang::CXXRecordDecl *record,std::set<std::string> &out,
               std::set<clang::CXXRecordDecl*> &seen) {
        record=record->getDefinition();
        if (!record || !seen.insert(record).second) return;
        for (auto *decl:record->decls())
            if (auto *named=llvm::dyn_cast<clang::NamedDecl>(decl))
                if (llvm::isa<clang::CXXMethodDecl>(named) || llvm::isa<clang::FieldDecl>(named)
                    || llvm::isa<clang::VarDecl>(named))
                    if (named->getIdentifier() && !named->isImplicit()) out.insert(named->getNameAsString());
        for (const auto &base:record->bases())
            if (auto *parent=base.getType()->getAsCXXRecordDecl()) names(parent,out,seen);
    }
    llvm::json::Array paths(clang::CXXRecordDecl *owner,clang::CXXRecordDecl *target) {
        llvm::json::Array result;
        target=target->getDefinition();
        if (!target) return result;
        if (owner==target) {
            result.push_back(llvm::json::Object{{"classes",llvm::json::Array{id(owner)}},
                {"targetClassAstId",id(target)},{"virtual",false},{"subobjectNumber",0}});
            return result;
        }
        // DetectVirtual=true stops at the first virtual edge; recording lookup
        // routes requires continuing through every compiler-recognized path.
        clang::CXXBasePaths found(true,true,false);
        owner->lookupInBases([target](const clang::CXXBaseSpecifier *base,clang::CXXBasePath &path) {
            return clang::CXXRecordDecl::FindBaseClass(base,path,target->getCanonicalDecl());
        },found);
        for (const auto &path:found) {
            llvm::json::Array classes{id(owner)};
            for (const auto &step:path)
                classes.push_back(id(step.Base->getType()->getAsCXXRecordDecl()->getDefinition()));
            const auto &last=path.back();
            result.push_back(llvm::json::Object{{"classes",std::move(classes)},
                {"targetClassAstId",id(target)},{"virtual",last.Base->isVirtual()},
                {"subobjectNumber",last.Base->isVirtual() ? 0 : last.SubobjectNumber},
                {"pathAccess",access(path.Access)}});
        }
        return result;
    }
public:
    llvm::json::Array rows;
    explicit CppMemberLookup(clang::Sema &s):sema(s){}
    bool VisitCXXRecordDecl(clang::CXXRecordDecl *record) {
        if (!record->isCompleteDefinition() || record->isImplicit()) return true;
        llvm::json::Array bases;
        for (const auto &base:record->bases()) {
            auto *parent=base.getType()->isDependentType() ? nullptr
                : base.getType()->getAsCXXRecordDecl();
            if (parent) parent=parent->getDefinition();
            bases.push_back(llvm::json::Object{
                {"sourceSpelling",base.getType().getAsString()},
                {"targetClassAstId",parent ? id(parent) : ""},
                {"knowledge",parent ? "KNOWN" : "UNKNOWN"},
                {"access",access(base.getAccessSpecifier())},
                {"virtual",base.isVirtual()}});
        }
        llvm::json::Array lookups;
        if (!record->isDependentContext()) {
            std::set<std::string> all; std::set<clang::CXXRecordDecl*> seen;
            names(record,all,seen);
            for (const auto &name:all) {
                clang::DeclarationNameInfo info(&sema.Context.Idents.get(name),record->getLocation());
                clang::LookupResult result(sema,info,clang::Sema::LookupMemberName);
                result.suppressDiagnostics(); sema.LookupQualifiedName(result,record);
                llvm::json::Array targets;
                for (auto it=result.begin();it!=result.end();++it) {
                    auto *decl=(*it)->getUnderlyingDecl();
                    auto *declOwner=llvm::dyn_cast<clang::CXXRecordDecl>(decl->getDeclContext());
                    targets.push_back(llvm::json::Object{{"declarationAstId",id(decl)},
                        {"access",access(it.getAccess())},
                        {"paths",declOwner ? paths(record,declOwner) : llvm::json::Array{}}});
                }
                lookups.push_back(llvm::json::Object{{"name",name},
                    {"knowledge",result.isAmbiguous() ? "AMBIGUOUS" : result.isUnresolvableResult() ? "UNKNOWN" : "KNOWN"},
                    {"targets",std::move(targets)}});
            }
        }
        rows.push_back(llvm::json::Object{{"ownerAstId",id(record)},
            {"dependent",record->isDependentContext()},{"bases",std::move(bases)},
            {"lookups",std::move(lookups)}});
        return true;
    }
};
