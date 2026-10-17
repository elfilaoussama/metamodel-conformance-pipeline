#include "cpp-observation-action.h"

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
