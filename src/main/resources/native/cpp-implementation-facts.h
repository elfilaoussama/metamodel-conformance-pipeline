// Compiler facts for written class/struct methods. No P0 or link-closure policy.
#pragma once
#include <clang/AST/RecursiveASTVisitor.h>
#include <clang/Basic/SourceManager.h>
#include <clang/Lex/Lexer.h>
#include <llvm/Support/FileSystem.h>
#include <llvm/Support/JSON.h>
#include <llvm/Support/raw_ostream.h>

class CppImplementationFacts : public clang::RecursiveASTVisitor<CppImplementationFacts> {
    clang::SourceManager &sources;
    const clang::LangOptions &language;
    std::string root;

    static std::string id(const clang::Decl *decl) {
        std::string text; llvm::raw_string_ostream output(text);
        output << static_cast<const void *>(decl); return output.str();
    }
    bool project(clang::SourceLocation location) const {
        if (root.empty() || location.isInvalid()) return false;
        location=sources.getExpansionLoc(location);
        auto filename=sources.getFilename(location);
        if (filename.empty()) return false;
        llvm::SmallString<256> physical;
        if (llvm::sys::fs::real_path(filename,physical)) return false;
        return llvm::StringRef(physical).startswith(root+"/");
    }
    llvm::json::Object span(const clang::Stmt *body) const {
        auto unknown=[](const char *reason) {
            return llvm::json::Object{{"knowledge","UNKNOWN"},{"reason",reason}};
        };
        if (!body) return unknown("compiler reports an unavailable or delayed body");
        auto begin=body->getBeginLoc(), last=body->getEndLoc();
        if (begin.isInvalid() || last.isInvalid()) return unknown("body source range unavailable");
        if (begin.isMacroID() || last.isMacroID())
            return unknown("macro body spelling/expansion needs a distinct source occurrence contract");
        auto end=clang::Lexer::getLocForEndOfToken(last,0,sources,language);
        if (end.isInvalid() || sources.getFileID(begin)!=sources.getFileID(end))
            return unknown("body range crosses source buffers");
        auto startOffset=sources.getFileOffset(begin), endOffset=sources.getFileOffset(end);
        if (endOffset<=startOffset || !project(begin)) return unknown("body span outside measured project source");
        llvm::SmallString<256> path;
        if (llvm::sys::fs::real_path(sources.getFilename(begin),path))
            return unknown("body physical source is unavailable");
        return llvm::json::Object{{"knowledge","KNOWN"},{"path",path.str().str()},
            {"startOffset",static_cast<int64_t>(startOffset)},
            {"endOffsetExclusive",static_cast<int64_t>(endOffset)},
            {"startLine",static_cast<int64_t>(sources.getSpellingLineNumber(begin))},
            {"endLine",static_cast<int64_t>(sources.getSpellingLineNumber(end.getLocWithOffset(-1)))}};
    }
    static bool sourceRecord(const clang::CXXRecordDecl *record) {
        return record && !record->isImplicit() && !record->isLambda() && !record->isUnion();
    }
public:
    llvm::json::Array classes, methods, bodies;
    explicit CppImplementationFacts(clang::SourceManager &sm, const clang::LangOptions &lang,
                                     llvm::StringRef projectRoot):sources(sm),language(lang) {
        llvm::SmallString<256> physical;
        if (!projectRoot.empty() && !llvm::sys::fs::real_path(projectRoot,physical)
                && physical==projectRoot && llvm::sys::fs::is_directory(physical)) root=physical.str().str();
    }
    bool rootAvailable() const { return !root.empty(); }
    const std::string &projectRoot() const { return root; }

    bool VisitCXXRecordDecl(clang::CXXRecordDecl *record) {
        if (!sourceRecord(record) || !record->isCompleteDefinition() || !project(record->getLocation())) return true;
        bool known=!record->isDependentContext() && !record->isInvalidDecl();
        classes.push_back(llvm::json::Object{{"ownerAstId",id(record)},
            {"abstraction",known ? (record->isAbstract() ? "ABSTRACT" : "CONCRETE") : "UNKNOWN"}});
        return true;
    }
    bool VisitCXXMethodDecl(clang::CXXMethodDecl *method) {
        auto *owner=method->getParent()->getDefinition();
        if (!sourceRecord(owner) || method->isImplicit()
                || llvm::isa<clang::CXXConstructorDecl>(method) || llvm::isa<clang::CXXDestructorDecl>(method)
                || !project(method->getLocation())) return true;
        // getBody() searches all redeclarations. Count only the declaration
        // carrying the written body; defaulted/deleted methods have no suite.
        bool written=method->doesThisDeclarationHaveABody() && !method->isDefaulted() && !method->isDeleted();
        methods.push_back(llvm::json::Object{{"methodAstId",id(method)},
            {"canonicalMethodAstId",id(method->getCanonicalDecl())},{"ownerAstId",id(owner)},
            {"hasWrittenBody",written},{"pure",method->isPure()},
            {"deleted",method->isDeleted()},{"defaulted",method->isDefaulted()}});
        if (written) bodies.push_back(llvm::json::Object{{"methodAstId",id(method)},
            {"canonicalMethodAstId",id(method->getCanonicalDecl())},{"ownerAstId",id(owner)},
            {"span",span(method->getBody())}});
        return true;
    }
};
