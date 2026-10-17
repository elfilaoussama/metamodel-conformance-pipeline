#!/usr/bin/env python3
"""Strict ordered-v2/v3 compilation-database transport. No build guessing."""
import json
import os
from pathlib import Path
import posixpath
import shlex
import sys
import tempfile
import base64
import hashlib
import re

FLAGS = {"-fPIC", "-fpic", "-fPIE", "-fpie", "-pthread", "-fno-exceptions",
         "-fexceptions", "-fno-rtti", "-frtti", "-O0", "-O1", "-O2", "-O3",
         "-Os", "-Oz", "-Og", "-g", "-g0", "-g1", "-g2", "-g3"}
STANDARDS = {f"{prefix}{version}" for prefix in ("c++", "gnu++")
             for version in ("98", "03", "11", "14", "17", "20", "2a", "2b")}
PATH_OPTIONS = {"-I": "include", "-isystem": "system-include",
                "-iquote": "quote-include", "-idirafter": "after-include"}

def atom(value):
    if not isinstance(value, str) or not value or any(c in value for c in "\t\r\n\0"):
        raise ValueError("unrepresentable argument")
    return value

def source_relative(value, directory, source_root):
    absolute = posixpath.normpath(value if value.startswith("/") else posixpath.join(directory, value))
    for prefix in ("/workspace/worktree", "/workspace/source", source_root.resolve().as_posix()):
        if absolute == prefix or absolute.startswith(prefix + "/"):
            relative = posixpath.relpath(absolute, prefix)
            host = source_root / relative
            if not host.exists() or not host.resolve().is_relative_to(source_root.resolve()):
                raise ValueError(f"unavailable or escaping source input: {value}")
            return relative
    raise ValueError(f"unsupported external/generated path: {value}")

def parse(entry, source_root, preserve_layout=False):
    directory = atom(entry["directory"])
    if not directory.startswith("/"):
        raise ValueError("database directory must be absolute")
    args = entry.get("arguments")
    if args is None:
        args = shlex.split(atom(entry["command"]))
    if not isinstance(args, list) or not args:
        raise ValueError("empty argument vector")
    args = [atom(a) for a in args]
    if posixpath.basename(args[0]) not in ("clang++", "clang++-14"):
        raise ValueError("unsupported compiler or wrapper")
    source = source_relative(atom(entry["file"]), directory, source_root)
    if not (source_root / source).is_file():
        raise ValueError("source is not a file")
    operations, stripped = [], []
    source_operand = None
    source_count = 0
    i = 1
    while i < len(args):
        a = args[i]
        if a in ("-o", "-MF", "-MT", "-MQ"):
            if i + 1 == len(args):
                raise ValueError(f"missing operand: {a}")
            stripped.append(args[i:i+2]); i += 2; continue
        if a in ("-c", "-MD", "-MMD", "-MP"):
            stripped.append([a]); i += 1; continue
        matched = False
        for option, kind in list(PATH_OPTIONS.items()) + [("-D", "define"), ("-U", "undef")]:
            if a == option or a.startswith(option) and len(a) > len(option):
                if a == option:
                    i += 1
                    if i == len(args):
                        raise ValueError(f"missing operand: {option}")
                    value = args[i]
                else:
                    value = a[len(option):]
                if kind in PATH_OPTIONS.values():
                    relative = source_relative(value, directory, source_root)
                    if not (source_root / relative).is_dir():
                        raise ValueError("include operand is not a directory")
                    if not preserve_layout:
                        value = relative
                operations.append([kind, atom(value)])
                matched = True; break
        if not matched:
            if a.startswith("-std="):
                value = a[5:]
                if value not in STANDARDS:
                    raise ValueError(f"unsupported standard: {value}")
                operations.append(["std", value])
            elif a in FLAGS:
                operations.append(["flag", a])
            elif not a.startswith("-") and source_relative(a, directory, source_root) == source:
                source_count += 1
                source_operand = a
            else:
                raise ValueError(f"unsupported compiler input: {a}")
        i += 1
    if source_count != 1 or not any(k == "std" for k, v in operations):
        raise ValueError("exactly one source operand and explicit language standard required")
    return source, operations, {"directory": directory, "arguments": args, "sourceOperand": source_operand,
                                "consumedOperations": operations, "strippedDriverOperations": stripped}

def atomic(path, content):
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=path.name + ".")
    try:
        if len(sys.argv) not in (4, 5, 6, 7):
            raise ValueError('expected database, output, source root and optional compiler receipt')
        with os.fdopen(descriptor, "w", encoding="utf-8") as sink:
            sink.write(content); sink.flush(); os.fsync(sink.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)

def main():
    database_path, output, source_root = map(Path, sys.argv[1:4])
    provenance = Path(str(output) + ".provenance.json")
    output.write_text("", encoding="utf-8")
    provenance.unlink(missing_ok=True)
    try:
        compiler_receipt = json.loads(Path(sys.argv[4]).read_text()) if len(sys.argv) >= 5 else None
        logical_root = atom(sys.argv[5]) if len(sys.argv) >= 6 else None
        if logical_root is not None and (not logical_root.startswith('/') or posixpath.normpath(logical_root) != logical_root):
            raise ValueError('logical root must be canonical and absolute')
        tree_digest = input_tree_digest(source_root) if logical_root else None
        build_model = json.loads(Path(sys.argv[6]).read_text()) if len(sys.argv) == 7 else None
        if build_model is not None and (not logical_root or not compiler_receipt
                or build_model.get('formatVersion') != 1
                or build_model.get('codemodel', {}).get('paths', {}).get('source') != logical_root):
            raise ValueError('configured target model requires compatible attested execution layout')
        transport = 'ordered-v5' if build_model is not None else 'ordered-v4' if logical_root else 'ordered-v3' if compiler_receipt else 'ordered-v2'
        identity = None
        if compiler_receipt is not None:
            identity = compiler_receipt['identity']
            if set(identity) != {'executableSha256', 'versionSha256', 'target'}:
                raise ValueError('invalid compiler identity fields')
            if not all(re.fullmatch('[0-9a-f]{64}', identity[k]) for k in ('executableSha256', 'versionSha256')):
                raise ValueError('invalid compiler identity hashes')
            if not re.fullmatch('[A-Za-z0-9_.+-]+', identity['target']):
                raise ValueError('invalid compiler target')
            version = base64.b64decode(compiler_receipt['versionBase64'], validate=True)
            if hashlib.sha256(version).hexdigest() != identity['versionSha256']:
                raise ValueError('compiler version receipt does not match its digest')
            compiler_command = base64.b64decode(compiler_receipt['commandBase64'], validate=True).decode('utf-8')
        database = json.loads(database_path.read_text())
        if not isinstance(database, list) or not database:
            raise ValueError("empty or invalid compilation database")
        contexts, originals = {}, []
        for entry in database:
            source, operations, original = parse(entry, source_root, logical_root is not None)
            if identity is not None and original['arguments'][0] not in (compiler_command, posixpath.basename(compiler_command)):
                raise ValueError('database compiler differs from captured compiler command')
            layout = {'sourceRoot': logical_root, 'workingDirectory': original['directory'],
                      'sourceOperand': original['sourceOperand'], 'treeSha256': tree_digest} if logical_root else None
            if source in contexts and contexts[source] != (operations, layout):
                raise ValueError(f"conflicting compilation profiles: {source}")
            contexts[source] = (operations, layout)
            originals.append({"source": source, **original})
        rows = []
        if build_model is not None:
            rows.append(['build-model', 'cmake-codemodel-v2', json.dumps(build_model, sort_keys=True, separators=(',', ':'), ensure_ascii=False)])
        for source, (operations, layout) in sorted(contexts.items()):
            identifier = "cpp|" + source
            rows += [["context", identifier, transport], ["source", identifier, source]]
            if identity is not None:
                rows.append(['compiler', identifier, json.dumps(identity, sort_keys=True, separators=(',', ':'))])
            if layout:
                rows.append(['execution', identifier, json.dumps(layout, sort_keys=True, separators=(',', ':'))])
            rows += [[kind, identifier, value] for kind, value in operations]
        content = "".join("\t".join(row) + "\n" for row in rows)
        atomic(provenance, json.dumps({"transportVersion": transport,
               "configuredBuildModel": build_model,
               "compilerReceipt": compiler_receipt, "invocations": originals,
               "manifestSha256": hashlib.sha256(content.encode()).hexdigest()}, indent=2) + "\n")
        atomic(output, content)
    except Exception:
        output.write_text("", encoding="utf-8")
        provenance.unlink(missing_ok=True)
        raise

def input_tree_digest(root):
    inventory = []
    for path in sorted(root.rglob('*'), key=lambda p: p.relative_to(root).as_posix()):
        relative = path.relative_to(root).as_posix()
        if path.is_symlink() or any(c in relative for c in '\n\r\0'):
            raise ValueError('unsupported configured input path: ' + relative)
        if path.is_dir():
            inventory.append('D\0' + relative + '\n')
        elif path.is_file():
            digest = hashlib.sha256()
            with path.open('rb') as stream:
                for chunk in iter(lambda: stream.read(8192), b''):
                    digest.update(chunk)
            inventory.append('F\0' + relative + '\0' + digest.hexdigest() + '\n')
        else:
            raise ValueError('configured input is not a regular file or directory: ' + relative)
    return hashlib.sha256(''.join(inventory).encode()).hexdigest()

if __name__ == "__main__":
    main()
