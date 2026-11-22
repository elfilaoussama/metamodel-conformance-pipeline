// Same-invocation compiler method facts, independent of formal override policy.
#pragma once
#include <clang/AST/ASTContext.h>
#include <clang/AST/RecursiveASTVisitor.h>
#include <llvm/Support/FileSystem.h>
#include <llvm/Support/JSON.h>
#include <llvm/Support/raw_ostream.h>
#include <set>

class CppMethodSemantics : public clang::RecursiveASTVisitor<CppMethodSemantics> {
    clang::ASTContext &context;
    std::string root;
    static std::string id(const clang::Decl *decl) {
        std::string text;llvm::raw_string_ostream output(text);
        output << static_cast<const void *>(decl);return output.str();
    }
    bool project(clang::SourceLocation location) const {
        if(root.empty() || location.isInvalid())return false;
        auto &sources=context.getSourceManager();
        auto filename=sources.getFilename(sources.getExpansionLoc(location));
        llvm::SmallString<256> physical;
        return !filename.empty() && !llvm::sys::fs::real_path(filename,physical)
            && llvm::StringRef(physical).startswith(root+"/");
    }
    static void ancestors(const clang::CXXMethodDecl *method,
                          std::set<const clang::CXXMethodDecl*> &seen,
                          llvm::json::Array &output) {
        for(auto *base:method->overridden_methods()) {
            base=base->getCanonicalDecl();
            if(seen.insert(base).second) {
                output.push_back(id(base));ancestors(base,seen,output);
            }
        }
    }
public:
    llvm::json::Array rows;
    explicit CppMethodSemantics(clang::ASTContext &value,llvm::StringRef projectRoot):context(value) {
        llvm::SmallString<256> physical;
        if(!projectRoot.empty() && !llvm::sys::fs::real_path(projectRoot,physical)
                && physical==projectRoot && llvm::sys::fs::is_directory(physical))root=physical.str().str();
    }
    bool rootAvailable() const {return !root.empty();}
    bool VisitCXXMethodDecl(clang::CXXMethodDecl *method) {
        auto *owner=method->getParent()->getDefinition();
        if(!owner || owner->isImplicit() || owner->isLambda() || owner->isUnion()
                || method->isImplicit() || llvm::isa<clang::CXXConstructorDecl>(method)
                || llvm::isa<clang::CXXDestructorDecl>(method) || !project(method->getLocation()))return true;
        bool known=!context.getDiagnostics().hasErrorOccurred() && !owner->isDependentContext() && !method->isInvalidDecl()
            && method->getTemplatedKind()==clang::FunctionDecl::TK_NonTemplate;
        llvm::json::Array direct,closure;
        if(known) {
            for(auto *base:method->overridden_methods())direct.push_back(id(base->getCanonicalDecl()));
            std::set<const clang::CXXMethodDecl*> seen;ancestors(method,seen,closure);
        }
        auto type=method->getReturnType();
        bool returnKnown=known && !type.isNull() && !type->isDependentType()
            && !type->isUndeducedType();
        std::string signatureReason;
        if(context.getDiagnostics().hasErrorOccurred())signatureReason="COMPILER_DIAGNOSTIC_ERROR";
        else if(method->isInvalidDecl())signatureReason="INVALID_DECLARATION";
        else if(owner->isDependentContext() || method->getTemplatedKind()!=clang::FunctionDecl::TK_NonTemplate)
            signatureReason="DEPENDENT_OR_TEMPLATE_DECLARATION";
        else if(method->isVariadic())signatureReason="VARIADIC_SIGNATURE";
        auto *functionType=method->getType().getCanonicalType()->getAs<clang::FunctionProtoType>();
        if(signatureReason.empty() && (!functionType || functionType->getNumParams()!=method->getNumParams()))
            signatureReason="INVALID_FUNCTION_PARAMETER_CENSUS";
        llvm::json::Array parameters;
        unsigned parameterIndex=0;
        if(signatureReason.empty())for(auto *parameter:method->parameters()) {
            // Canonical function types apply parameter adjustments and erase
            // top-level cv, while preserving pointee/referent qualification.
            auto parameterType=functionType->getParamType(parameterIndex++);
            if(parameter->isParameterPack() || parameterType.isNull() || parameterType->isDependentType()
                    || parameterType->isUndeducedType()) {
                signatureReason="DEPENDENT_UNDEDUCED_OR_INVALID_PARAMETER";break;
            }
            std::string spelling=parameterType.getCanonicalType().getAsString(context.getPrintingPolicy());
            if(spelling.empty()) {signatureReason="EMPTY_CANONICAL_PARAMETER";break;}
            parameters.push_back(spelling);
        }
        if(!signatureReason.empty())parameters=llvm::json::Array();
        rows.push_back(llvm::json::Object{{"declarationAstId",id(method)},
            {"canonicalMethodAstId",id(method->getCanonicalDecl())},{"ownerAstId",id(owner)},
            {"overrideKnowledge",known ? "KNOWN" : "UNKNOWN"},
            {"directOverrides",std::move(direct)},{"transitiveOverrides",std::move(closure)},
            {"returnKnowledge",returnKnown ? "KNOWN" : "UNKNOWN"},
            {"signatureKnowledge",signatureReason.empty() ? "KNOWN" : "UNKNOWN"},
            {"signatureReason",signatureReason},{"signatureVariadic",method->isVariadic()},
            {"canonicalParameterTypes",std::move(parameters)},
            {"canonicalReturnType",returnKnown ? type.getCanonicalType().getAsString(context.getPrintingPolicy()) : ""}});
        return true;
    }
};
