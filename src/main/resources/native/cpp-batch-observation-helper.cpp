// One native parse per selected context; guarded queries use the retained ASTs.
#include "cpp-observation-action.h"
#include <clang/AST/ASTImporter.h>
#include <clang/AST/ASTStructuralEquivalence.h>
#include <clang/AST/RecordLayout.h>
#include <clang/Frontend/ASTUnit.h>
#include <clang/Index/USRGeneration.h>
#include <llvm/Support/MemoryBuffer.h>
#include <fcntl.h>
#include <unistd.h>
#include <chrono>

using BatchClock=std::chrono::steady_clock;
static int64_t elapsedMicros(BatchClock::time_point start) {
    return std::chrono::duration_cast<std::chrono::microseconds>(BatchClock::now()-start).count();
}

static std::string nativeId(const clang::Decl *value) {
    std::string text;llvm::raw_string_ostream stream(text);stream<<static_cast<const void*>(value);return stream.str();
}
static std::string nativeUsr(const clang::Decl *value) {
    llvm::SmallString<256> text;
    if(clang::index::generateUSRForDecl(value,text))return "";
    return text.str().str();
}
static std::string physicalFile(clang::SourceManager &sources,clang::SourceLocation location,const std::string &cwd) {
    if(location.isInvalid())return "";
    auto *entry=sources.getFileEntryForID(sources.getFileID(location));if(!entry)return "";
    llvm::SmallString<256> path(entry->tryGetRealPathName());
    if(path.empty())path=entry->getName();
    if(!llvm::sys::path::is_absolute(path)) {
        llvm::SmallString<256> absolutePath(cwd);llvm::sys::path::append(absolutePath,path);path=absolutePath;
    }
    llvm::SmallString<256> result;if(llvm::sys::fs::real_path(path,result))return "";return result.str().str();
}
static std::string locationKey(clang::SourceManager &sources,clang::SourceLocation location,const std::string &cwd) {
    if(location.isInvalid())return "";
    auto spelling=sources.getSpellingLoc(location),expansion=sources.getExpansionLoc(location);
    auto a=physicalFile(sources,spelling,cwd),b=physicalFile(sources,expansion,cwd);if(a.empty() || b.empty())return "";
    const std::string separator(1,'\0');
    return a+separator+std::to_string(sources.getFileOffset(spelling))+separator+b+separator+std::to_string(sources.getFileOffset(expansion));
}
class OutputRedirect {
    int descriptor,saved=-1;
public:
    OutputRedirect(int number,const std::string &path):descriptor(number) {
        llvm::outs().flush();llvm::errs().flush();saved=dup(descriptor);
        int file=open(path.c_str(),O_WRONLY|O_CREAT|O_EXCL,0600);
        if(saved<0 || file<0 || dup2(file,descriptor)<0){if(file>=0)close(file);std::abort();}
        close(file);
    }
    ~OutputRedirect(){llvm::outs().flush();llvm::errs().flush();dup2(saved,descriptor);close(saved);}
};
class RetainedJob final:public clang::tooling::ToolAction {
public:
    std::string context,cwd,astPath,inputPath,errorPath;
    std::vector<std::string> arguments;
    bool written=false,success=false;
    unsigned frontendInvocations=0;
    llvm::IntrusiveRefCntPtr<clang::DiagnosticsEngine> diagnostics;
    std::unique_ptr<Observation> action;
    std::unique_ptr<clang::ASTUnit> unit;
    bool runInvocation(std::shared_ptr<clang::CompilerInvocation> invocation,clang::FileManager *files,
                       std::shared_ptr<clang::PCHContainerOperations> pch,clang::DiagnosticConsumer *) override {
        if(++frontendInvocations!=1)return false;
        invocation->getFileSystemOpts()=files->getFileSystemOpts();
        // Keep our own frontend diagnostic client alive with the retained unit.
        diagnostics=clang::CompilerInstance::createDiagnostics(&invocation->getDiagnosticOpts(),nullptr,true);
        action=std::make_unique<Observation>(inputPath,written,arguments);
        unit.reset(clang::ASTUnit::LoadFromCompilerInvocationAction(invocation,pch,diagnostics,action.get(),nullptr,true));
        return unit && written && !diagnostics->hasErrorOccurred();
    }
};
class ProjectMethods:public clang::RecursiveASTVisitor<ProjectMethods> {
    clang::SourceManager &sources;
    std::string root;
    std::string cwd;
    std::map<unsigned,bool> projectFiles;
    bool project(clang::SourceLocation location) {
        if(location.isInvalid())return false;
        location=sources.getExpansionLoc(location);auto file=sources.getFileID(location);unsigned id=file.getHashValue();
        auto known=projectFiles.find(id);if(known!=projectFiles.end())return known->second;
        auto physical=physicalFile(sources,location,cwd);
        bool result=!physical.empty() && llvm::StringRef(physical).startswith(root+"/");
        projectFiles[id]=result;return result;
    }
public:
    std::map<clang::CXXMethodDecl*,clang::CXXMethodDecl*> declarations;
    std::map<clang::CXXMethodDecl*,bool> originalBodies;
    std::vector<clang::CXXMethodDecl*> definitions;
    ProjectMethods(clang::ASTContext &context,std::string projectRoot,std::string workingDirectory)
        :sources(context.getSourceManager()),root(std::move(projectRoot)),cwd(std::move(workingDirectory)){}
    bool VisitCXXMethodDecl(clang::CXXMethodDecl *method) {
        auto *owner=method->getParent()->getDefinition();
        if(!owner || owner->isImplicit() || owner->isLambda() || owner->isUnion() || method->isImplicit()
            || llvm::isa<clang::CXXConstructorDecl>(method) || llvm::isa<clang::CXXDestructorDecl>(method)
            || !project(owner->getLocation()) || !project(method->getLocation()))return true;
        declarations.emplace(method->getCanonicalDecl(),method->getCanonicalDecl());
        originalBodies[method->getCanonicalDecl()]=method->getCanonicalDecl()->hasBody();
        if(method->doesThisDeclarationHaveABody() && !method->isDefaulted() && !method->isDeleted())definitions.push_back(method);
        return true;
    }
};
static bool ownerBodiesAgree(clang::CXXRecordDecl *source,clang::CXXRecordDecl *target,
                             clang::ASTContext &from,clang::ASTContext &to) {
    std::map<std::string,clang::CXXMethodDecl*> originals,receivers;
    for(auto *method:source->methods())if(!method->isImplicit() && !method->isOutOfLine()) {
        auto id=nativeUsr(method);if(id.empty() || !originals.emplace(id,method).second)return false;
    }
    for(auto *method:target->methods())if(!method->isImplicit() && !method->isOutOfLine()) {
        auto id=nativeUsr(method);if(id.empty() || !receivers.emplace(id,method).second)return false;
    }
    if(originals.size()!=receivers.size())return false;
    for(auto &entry:originals) {
        auto found=receivers.find(entry.first);if(found==receivers.end())return false;
        auto *left=entry.second,*right=found->second;
        if(left->isDefaulted()!=right->isDefaulted() || left->isDeleted()!=right->isDeleted() || left->isPure()!=right->isPure())return false;
        bool a=left->doesThisDeclarationHaveABody() && !left->isDefaulted() && !left->isDeleted();
        bool b=right->doesThisDeclarationHaveABody() && !right->isDefaulted() && !right->isDeleted();
        if(a!=b)return false;
        if(a) {
            llvm::DenseSet<std::pair<clang::Decl*,clang::Decl*>> cache;
            clang::StructuralEquivalenceContext eq(from,to,cache,clang::StructuralEquivalenceKind::Default,false,false,true);
            if(!eq.IsEquivalent(left->getBody(),right->getBody()))return false;
        }
    }
    return true;
}
struct Definition {size_t job;clang::CXXMethodDecl *method;};
static bool nativeLayoutsAgree(clang::CXXRecordDecl *source,clang::CXXRecordDecl *target,
                               clang::ASTContext &from,clang::ASTContext &to) {
    if(!source || !target || source->isInvalidDecl() || target->isInvalidDecl()
            || source->isDependentContext() || target->isDependentContext())return false;
    const auto &a=from.getASTRecordLayout(source),&b=to.getASTRecordLayout(target);
    if(a.getSize()!=b.getSize() || a.getDataSize()!=b.getDataSize() || a.getAlignment()!=b.getAlignment()
            || a.getNonVirtualSize()!=b.getNonVirtualSize() || a.getNonVirtualAlignment()!=b.getNonVirtualAlignment()
            || a.getFieldCount()!=b.getFieldCount())return false;
    auto left=source->field_begin(),right=target->field_begin();
    for(unsigned index=0;index<a.getFieldCount();++index,++left,++right) {
        if(a.getFieldOffset(index)!=b.getFieldOffset(index)
                || from.getTypeSize((*left)->getType())!=to.getTypeSize((*right)->getType())
                || from.getTypeAlign((*left)->getType())!=to.getTypeAlign((*right)->getType()))return false;
    }
    if(source->getNumBases()!=target->getNumBases() || source->getNumVBases()!=target->getNumVBases())return false;
    auto targetBase=target->bases_begin();
    for(auto &base:source->bases()) {
        auto *x=base.getType()->getAsCXXRecordDecl(),*y=targetBase->getType()->getAsCXXRecordDecl();
        if(!x || !y || base.isVirtual()!=targetBase->isVirtual() || nativeUsr(x)!=nativeUsr(y))return false;
        if(!base.isVirtual() && a.getBaseClassOffset(x)!=b.getBaseClassOffset(y))return false;
        ++targetBase;
    }
    auto targetVirtual=target->vbases_begin();
    for(auto &base:source->vbases()) {
        auto *x=base.getType()->getAsCXXRecordDecl(),*y=targetVirtual->getType()->getAsCXXRecordDecl();
        if(!x || !y || nativeUsr(x)!=nativeUsr(y) || a.getVBaseClassOffset(x)!=b.getVBaseClassOffset(y))return false;
        ++targetVirtual;
    }
    auto *primaryA=a.getPrimaryBase(),*primaryB=b.getPrimaryBase();
    return (!primaryA && !primaryB) || (primaryA && primaryB && nativeUsr(primaryA)==nativeUsr(primaryB)
            && a.isPrimaryBaseVirtual()==b.isPrimaryBaseVirtual());
}
int main(int argc,char **argv) {
    auto batchStarted=BatchClock::now();
    if(argc!=3)return 64;
    auto input=llvm::MemoryBuffer::getFile(argv[1]);if(!input)return 66;
    auto parsed=llvm::json::parse((*input)->getBuffer());if(!parsed){llvm::consumeError(parsed.takeError());return 65;}
    auto *request=parsed->getAsObject();if(!request || request->size()!=2)return 65;
    auto root=request->getString("projectRoot");auto *rows=request->getArray("jobs");
    if(!root || !rows || rows->empty())return 65;
    llvm::SmallString<256> realRoot;if(llvm::sys::fs::real_path(*root,realRoot) || realRoot!=*root)return 65;
    std::string output=argv[2];llvm::SmallString<256> directory(llvm::sys::path::parent_path(output));
    llvm::SmallString<256> realOutput;if(llvm::sys::fs::real_path(directory,realOutput) || directory!=realOutput)return 65;
    setenv("METAMODEL_CPP_SOURCE_ROOT",root->str().c_str(),1);
    std::vector<std::unique_ptr<RetainedJob>> jobs;std::set<std::string> contexts;
    llvm::json::Array results,queries;
    for(auto &value:*rows) {
        auto *row=value.getAsObject();if(!row || row->size()!=3)return 65;
        auto context=row->getString("contextId"),cwd=row->getString("workingDirectory");auto *arguments=row->getArray("arguments");
        if(!context || context->empty() || !contexts.insert(context->str()).second || !cwd || !arguments || arguments->empty())return 65;
        llvm::SmallString<256> realCwd;if(llvm::sys::fs::real_path(*cwd,realCwd) || realCwd!=*cwd)return 65;
        auto job=std::make_unique<RetainedJob>();job->context=context->str();job->cwd=cwd->str();
        std::string prefix=directory.str().str()+"/job-"+std::to_string(jobs.size());
        job->astPath=prefix+".ast.json";job->inputPath=prefix+".inputs.json";job->errorPath=prefix+".stderr";
        for(auto &argument:*arguments) {
            auto text=argument.getAsString();if(!text || text->contains('\0'))return 65;job->arguments.push_back(text->str());
        }
        if(llvm::sys::fs::set_current_path(job->cwd))return 66;
        clang::FileSystemOptions options;options.WorkingDir=job->cwd;
        llvm::IntrusiveRefCntPtr<clang::FileManager> files(new clang::FileManager(options));
        auto jobStarted=BatchClock::now();
        {
            OutputRedirect stdoutFile(STDOUT_FILENO,job->astPath),stderrFile(STDERR_FILENO,job->errorPath);
            clang::tooling::ToolInvocation invocation(job->arguments,job.get(),files.get(),std::make_shared<clang::PCHContainerOperations>());
            job->success=invocation.run();
        }
        results.push_back(llvm::json::Object{{"contextId",job->context},{"astPath",job->astPath},{"inputPath",job->inputPath},
            {"errorPath",job->errorPath},{"exitCode",job->success?0:1},
            {"frontendInvocations",static_cast<int64_t>(job->frontendInvocations)},{"observationMicros",elapsedMicros(jobStarted)}});
        jobs.push_back(std::move(job));
    }
    auto indexStarted=BatchClock::now();
    std::vector<std::unique_ptr<ProjectMethods>> populations;std::map<std::string,std::vector<Definition>> definitions;
    for(size_t index=0;index<jobs.size();++index) {
        std::unique_ptr<ProjectMethods> population;
        if(jobs[index]->success) {
            population=std::make_unique<ProjectMethods>(jobs[index]->unit->getASTContext(),root->str(),jobs[index]->cwd);
            population->TraverseDecl(jobs[index]->unit->getASTContext().getTranslationUnitDecl());
            for(auto *method:population->definitions) {
                // USRs for anonymous owners declared in a shared header can be
                // equal in different TUs. Native linkage must independently
                // permit cross-context identity before indexing a definition.
                if(!method->isExternallyVisible() || !method->getParent()->isExternallyVisible())continue;
                auto id=nativeUsr(method);if(!id.empty())definitions[id].push_back({index,method});
            }
        }
        populations.push_back(std::move(population));
    }
    int64_t indexMicros=elapsedMicros(indexStarted),importAttempts=0;
    auto queryStarted=BatchClock::now();
    for(size_t index=0;index<jobs.size();++index)if(populations[index])for(auto &entry:populations[index]->declarations) {
        auto *target=entry.first;std::string id=nativeUsr(target);
        llvm::json::Object query{{"targetContextId",jobs[index]->context},{"targetCanonicalAstId",nativeId(target)},
            {"targetOwnerAstId",nativeId(target->getParent()->getDefinition())},{"targetUsr",id}};
        if(id.empty() || target->isInvalidDecl() || target->getParent()->isDependentContext()
                || target->getType()->isDependentType() || target->getReturnType()->isUndeducedType()
                || target->isDeleted() || target->isDefaulted())
            query["status"]="UNSUPPORTED";
        else if(populations[index]->originalBodies.at(target))query["status"]="ALREADY_DEFINED";
        else if(!target->isExternallyVisible() || !target->getParent()->isExternallyVisible())query["status"]="CONTEXT_LOCAL";
        else {
            auto candidates=definitions.find(id);
            if(candidates==definitions.end())query["status"]="NO_DEFINITION_IN_SELECTED_POPULATION";
            else if(candidates->second.size()!=1)query["status"]="AMBIGUOUS";
            else {
                auto candidate=candidates->second.front();auto *source=candidate.method;
                auto &from=jobs[candidate.job]->unit->getASTContext(),&to=jobs[index]->unit->getASTContext();
                llvm::DenseSet<std::pair<clang::Decl*,clang::Decl*>> cache;
                clang::StructuralEquivalenceContext eq(from,to,cache,clang::StructuralEquivalenceKind::Default,false,false,true);
                bool compatible=!source->isInvalidDecl() && !source->getParent()->isDependentContext()
                    && eq.IsEquivalent(source->getType(),target->getType())
                    && eq.IsEquivalent(source->getParent()->getDefinition(),target->getParent()->getDefinition())
                    && nativeLayoutsAgree(source->getParent()->getDefinition(),target->getParent()->getDefinition(),from,to)
                    && ownerBodiesAgree(source->getParent()->getDefinition(),target->getParent()->getDefinition(),from,to);
                if(!compatible)query["status"]="INCOMPATIBLE";
                else {
                    ++importAttempts;
                    clang::ASTImporter importer(to,jobs[index]->unit->getFileManager(),from,jobs[candidate.job]->unit->getFileManager(),false);
                    importer.setODRHandling(clang::ASTImporter::ODRHandlingType::Conservative);
                    auto imported=importer.Import(source);
                    if(!imported){query["status"]="IMPORT_REJECTED";query["reason"]=llvm::toString(imported.takeError());}
                    else {
                        auto *method=llvm::dyn_cast<clang::CXXMethodDecl>(*imported);
                        if(!method || !method->hasBody() || nativeUsr(method)!=id || method->getCanonicalDecl()!=target)
                            query["status"]="IMPORT_IDENTITY_REJECTED";
                        else {
                            auto sourceBegin=locationKey(from.getSourceManager(),source->getBody()->getBeginLoc(),jobs[candidate.job]->cwd);
                            auto targetBegin=locationKey(to.getSourceManager(),method->getBody()->getBeginLoc(),jobs[index]->cwd);
                            auto sourceEnd=locationKey(from.getSourceManager(),source->getBody()->getEndLoc(),jobs[candidate.job]->cwd);
                            auto targetEnd=locationKey(to.getSourceManager(),method->getBody()->getEndLoc(),jobs[index]->cwd);
                            llvm::DenseSet<std::pair<clang::Decl*,clang::Decl*>> bodyCache;
                            clang::StructuralEquivalenceContext bodyEq(from,to,bodyCache,clang::StructuralEquivalenceKind::Default,false,false,true);
                            if(sourceBegin.empty() || sourceEnd.empty() || sourceBegin!=targetBegin || sourceEnd!=targetEnd
                                    || !bodyEq.IsEquivalent(source->getBody(),method->getBody())) {
                                query["status"]="IMPORT_BODY_REJECTED";queries.push_back(std::move(query));continue;
                            }
                            query["status"]="MATCHED";query["sourceContextId"]=jobs[candidate.job]->context;
                            query["sourceCanonicalAstId"]=nativeId(source->getCanonicalDecl());query["sourceDefinitionAstId"]=nativeId(source);
                            query["sourceOwnerAstId"]=nativeId(source->getParent()->getDefinition());query["sourceUsr"]=nativeUsr(source);
                        }
                    }
                }
            }
        }
        queries.push_back(std::move(query));
    }
    std::error_code error;llvm::raw_fd_ostream sink(output,error,llvm::sys::fs::OF_Text);if(error)return 73;
    sink<<llvm::json::Value(llvm::json::Object{{"format","cpp-retained-batch-v1"},{"projectRoot",root->str()},
        {"jobs",std::move(results)},{"correspondenceQueries",std::move(queries)},
        {"metrics",llvm::json::Object{{"definitionIndexMicros",indexMicros},{"correspondenceMicros",elapsedMicros(queryStarted)},
            {"importAttempts",importAttempts},{"totalMicros",elapsedMicros(batchStarted)}}}})<<"\n";
    sink.flush();return sink.has_error()?74:0;
}
