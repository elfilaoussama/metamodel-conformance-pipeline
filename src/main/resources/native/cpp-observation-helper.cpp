// Clang 14 AST/input transport. No conformance rules or P0 judgments live here.
#include <clang/Basic/FileManager.h>
#include <clang/Basic/SourceManager.h>
#include <clang/Basic/TargetInfo.h>
#include <clang/Basic/Version.h>
#include <clang/Frontend/CompilerInstance.h>
#include <clang/Frontend/FrontendActions.h>
#include <clang/Lex/Lexer.h>
#include <clang/Lex/MacroInfo.h>
#include <clang/Lex/PPCallbacks.h>
#include <clang/Lex/Preprocessor.h>
#include <clang/Tooling/Tooling.h>
#include <llvm/ADT/IntrusiveRefCntPtr.h>
#include <llvm/ADT/StringExtras.h>
#include <llvm/Support/FileSystem.h>
#include <llvm/Support/JSON.h>
#include <llvm/Support/MemoryBuffer.h>
#include <llvm/Support/SHA256.h>
#include <llvm/Support/raw_ostream.h>
#include <link.h>
#include <sys/stat.h>
#include <map>
#include <set>
#include <string>
#include <vector>

static std::string digest(llvm::StringRef bytes) {
    auto value = llvm::SHA256::hash(llvm::ArrayRef<uint8_t>(
        reinterpret_cast<const uint8_t *>(bytes.data()), bytes.size()));
    return llvm::toHex(llvm::ArrayRef<uint8_t>(value), true);
}
static std::string absolute(llvm::StringRef spelling) {
    llvm::SmallString<256> path(spelling);
    if (llvm::sys::fs::make_absolute(path)) return {};
    return path.str().str();
}
static bool clockBuiltin(llvm::StringRef name) {
    return name == "__DATE__" || name == "__TIME__" || name == "__TIMESTAMP__";
}
class Inputs final : public clang::PPCallbacks {
    clang::SourceManager &manager;
    llvm::json::Array &events;
    std::map<std::string, llvm::json::Object> &files;
public:
    Inputs(clang::SourceManager &sm, llvm::json::Array &e,
           std::map<std::string, llvm::json::Object> &f) : manager(sm), events(e), files(f) {}
    void FileChanged(clang::SourceLocation location, FileChangeReason reason,
                     clang::SrcMgr::CharacteristicKind, clang::FileID) override {
        if (reason != EnterFile) return;
        auto id = manager.getFileID(manager.getExpansionLoc(location));
        const auto *entry = manager.getFileEntryForID(id);
        if (!entry) return; // Compiler-synthesized buffers have no physical file.
        bool invalid = false;
        auto bytes = manager.getBufferData(id, &invalid);
        if (invalid) return;
        auto path = absolute(entry->getName());
        files[path] = llvm::json::Object{{"path", path}, {"sha256", digest(bytes)},
            {"modificationEpochSeconds", static_cast<int64_t>(entry->getModificationTime())}};
    }
    void MacroExpands(const clang::Token &token, const clang::MacroDefinition &definition,
                      clang::SourceRange range, const clang::MacroArgs *) override {
        if (!token.getIdentifierInfo() || !definition.getMacroInfo()
                || !definition.getMacroInfo()->isBuiltinMacro()) return;
        auto name = token.getIdentifierInfo()->getName();
        if (!clockBuiltin(name)) return;
        auto location = manager.getExpansionLoc(range.getBegin());
        llvm::json::Object event{{"name", name.str()},
            {"source", absolute(manager.getFilename(location))},
            {"line", static_cast<int64_t>(manager.getExpansionLineNumber(location))}};
        const auto *entry = manager.getFileEntryForID(manager.getFileID(location));
        if (entry) event["modificationEpochSeconds"] = static_cast<int64_t>(entry->getModificationTime());
        events.push_back(std::move(event));
    }
};
static int mappedImage(dl_phdr_info *info, size_t, void *data) {
    if (info->dlpi_name && *info->dlpi_name) {
        auto &names = *static_cast<std::set<std::string> *>(data);
        // The kernel's virtual DSO has no physical image; this is not OS closure.
        if (std::string(info->dlpi_name).find("linux-vdso") != 0) names.insert(info->dlpi_name);
    }
    return 0;
}
class Observation final : public clang::ASTDumpAction {
    llvm::json::Array events, literals;
    std::map<std::string, llvm::json::Object> files;
    std::string receiptPath;
    bool &written;
    std::vector<std::string> arguments;
public:
    Observation(std::string path, bool &success, std::vector<std::string> argv)
        : receiptPath(std::move(path)), written(success), arguments(std::move(argv)) {}
protected:
    bool BeginSourceFileAction(clang::CompilerInstance &compiler) override {
        compiler.getFrontendOpts().ASTDumpFormat = clang::ADOF_JSON;
        auto &pp = compiler.getPreprocessor();
        pp.addPPCallbacks(std::make_unique<Inputs>(compiler.getSourceManager(), events, files));
        pp.setTokenWatcher([this, &compiler](const clang::Token &token) {
            if (!token.is(clang::tok::string_literal)) return;
            auto &sm = compiler.getSourceManager();
            auto location = token.getLocation();
            while (location.isMacroID()) {
                auto name = clang::Lexer::getImmediateMacroName(location, sm, compiler.getLangOpts());
                if (clockBuiltin(name)) {
                    auto expansion = sm.getExpansionLoc(token.getLocation());
                    literals.push_back(llvm::json::Object{{"name", name.str()},
                        {"literal", compiler.getPreprocessor().getSpelling(token)},
                        {"source", absolute(sm.getFilename(expansion))},
                        {"line", static_cast<int64_t>(sm.getExpansionLineNumber(expansion))}});
                    return;
                }
                location = sm.getImmediateMacroCallerLoc(location);
            }
        });
        return true;
    }
    void EndSourceFileAction() override {
        auto &compiler = getCompilerInstance();
        llvm::json::Array consumed, images, argv;
        for (auto &file : files) consumed.push_back(std::move(file.second));
        std::set<std::string> names;
        dl_iterate_phdr(mappedImage, &names);
        names.insert("/proc/self/exe");
        bool imagesComplete = true;
        for (const auto &name : names) {
            llvm::SmallString<256> real;
            if (llvm::sys::fs::real_path(name, real)) { imagesComplete = false; continue; }
            auto bytes = llvm::MemoryBuffer::getFile(real);
            struct stat metadata;
            if (!bytes || stat(real.c_str(), &metadata)) { imagesComplete = false; continue; }
            images.push_back(llvm::json::Object{{"path", real.str().str()},
                {"sha256", digest((*bytes)->getBuffer())},
                {"modificationEpochSeconds", static_cast<int64_t>(metadata.st_mtime)}});
        }
        for (const auto &argument : arguments) argv.push_back(argument);
        std::error_code error;
        llvm::raw_fd_ostream sink(receiptPath, error, llvm::sys::fs::OF_Text);
        if (error) { llvm::errs() << error.message() << "\n"; return; }
        sink << llvm::json::Value(llvm::json::Object{{"format", "cpp-same-pass-inputs-v1"},
            {"compilerVersion", clang::getClangFullVersion()},
            {"target", compiler.getTarget().getTriple().str()}, {"arguments", std::move(argv)},
            {"consumedFiles", std::move(consumed)}, {"physicalImages", std::move(images)},
            {"physicalImagesComplete", imagesComplete},
            {"builtinInputEvents", std::move(events)}, {"builtinLiteralTokens", std::move(literals)}}) << "\n";
        sink.flush(); written = !sink.has_error() && imagesComplete;
    }
};
int main(int argc, char **argv) {
    if (argc < 4) return 64;
    bool written = false;
    std::vector<std::string> command;
    for (int i = 2; i < argc; ++i) command.emplace_back(argv[i]);
    clang::FileSystemOptions options;
    llvm::IntrusiveRefCntPtr<clang::FileManager> files(new clang::FileManager(options));
    clang::tooling::ToolInvocation invocation(command,
        std::make_unique<Observation>(argv[1], written, command), files.get());
    return invocation.run() && written ? 0 : 1;
}
