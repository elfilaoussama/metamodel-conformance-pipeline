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
import importlib.util

FLAGS = {"-fPIC", "-fpic", "-fPIE", "-fpie", "-pthread", "-fno-exceptions",
         "-fexceptions", "-fno-rtti", "-frtti", "-O0", "-O1", "-O2", "-O3",
         "-Os", "-Oz", "-Og", "-g", "-g0", "-g1", "-g2", "-g3",
         "-Wall", "-Wextra", "-Wpedantic", "-Werror", "-Wno-error", "-pedantic", "-pedantic-errors"}
STANDARDS = {f"{prefix}{version}" for prefix in ("c++", "gnu++")
             for version in ("98", "03", "11", "14", "17", "20", "2a", "2b")}
PATH_OPTIONS = {"-I": "include", "-isystem": "system-include",
                "-iquote": "quote-include", "-idirafter": "after-include"}

def atom(value):
    if not isinstance(value, str) or not value or any(c in value for c in "\t\r\n\0"):
        raise ValueError("unrepresentable argument")
    return value


def selected_targets(path, model):
    """Resolve an explicit request against native target names, never edges."""
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('duplicate target selection field')
            result[key] = value
        return result
    request = json.loads(path.read_text(), object_pairs_hook=unique)
    if not isinstance(request, dict) or set(request) != {'configuration', 'targetNames'} \
            or not isinstance(request['configuration'], str) or '\0' in request['configuration'] \
            or not isinstance(request['targetNames'], list) or not request['targetNames']:
        raise ValueError('target selection needs an explicit configuration and targetNames')
    names = request['targetNames']
    if any(not isinstance(name, str) or not name.strip() or '\0' in name for name in names) \
            or len(set(names)) != len(names):
        raise ValueError('target names must be textual, nonempty and unique')
    if model is None:
        raise ValueError('target selection requires configured build facts')
    configuration = request['configuration']
    configurations = {row['name'] for row in model['codemodel']['configurations']}
    if configuration not in configurations:
        raise ValueError('unknown selected configuration')
    targets = {}
    for row in model['targets']:
        if row['configuration'] != configuration:
            continue
        name = row['object']['name']
        if name in targets:
            raise ValueError('ambiguous configured target name')
        targets[name] = row['id']
    if not set(names).issubset(targets):
        raise ValueError('unknown selected target name')
    return {'configuration': configuration, 'targetIds': sorted(targets[name] for name in names)}

def source_relative(value, directory, source_root, space=None):
    if space is not None:
        return space.relative(value,directory)
    absolute = posixpath.normpath(value if value.startswith("/") else posixpath.join(directory, value))
    for prefix in ("/workspace/worktree", "/workspace/source", source_root.resolve().as_posix()):
        if absolute == prefix or absolute.startswith(prefix + "/"):
            relative = posixpath.relpath(absolute, prefix)
            host = source_root / relative
            if not host.exists() or not host.resolve().is_relative_to(source_root.resolve()):
                raise ValueError(f"unavailable or escaping source input: {value}")
            return relative
    raise ValueError(f"unsupported external/generated path: {value}")

def parse(entry, source_root, preserve_layout=False, space=None, require_explicit_standard=True):
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
    source = source_relative(atom(entry["file"]), directory, source_root,space)
    host=lambda relative:source_root/relative if space is None else space.materialized(relative)
    if not host(source).is_file():
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
                    relative = source_relative(value, directory, source_root,space)
                    if not host(relative).is_dir():
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
            elif not a.startswith("-") and source_relative(a, directory, source_root,space) == source:
                source_count += 1
                source_operand = a
            else:
                raise ValueError(f"unsupported compiler input: {a}")
        i += 1
    if source_count != 1 or require_explicit_standard and not any(k == "std" for k, v in operations):
        raise ValueError("exactly one source operand and explicit language standard required")
    return source, operations, {"directory": directory, "arguments": args, "sourceOperand": source_operand,
                                "consumedOperations": operations, "strippedDriverOperations": stripped}

def atomic(path, content):
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=path.name + ".")
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as sink:
            sink.write(content); sink.flush(); os.fsync(sink.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)

def main():
    argv=sys.argv[:];space_spec=None;space_error=None;native_capture=None;native_error=None
    if '--native-membership-capture' in argv:
        if argv.count('--native-membership-capture')!=1 or len(argv)<3 or argv[-2]!='--native-membership-capture':
            native_error=ValueError('native membership capture must be the final option pair')
        else:
            native_capture=Path(argv[-1]);argv=argv[:-2]
    if '--input-space-spec' in argv:
        if argv.count('--input-space-spec')!=1 or len(argv)<3 or argv[-2]!='--input-space-spec':
            space_error=ValueError('captured input specification must be the final option pair')
        else:
            space_spec=Path(argv[-1]);argv=argv[:-2]
    database_path, output, source_root = map(Path, argv[1:4])
    provenance = Path(str(output) + ".provenance.json")
    output.write_text("", encoding="utf-8")
    provenance.unlink(missing_ok=True)
    try:
        if native_error is not None:raise native_error
        if space_error is not None:raise space_error
        compiler_receipt = json.loads(Path(argv[4]).read_text()) if len(argv) >= 5 else None
        logical_root = atom(argv[5]) if len(argv) >= 6 else None
        if logical_root is not None and (not logical_root.startswith('/') or posixpath.normpath(logical_root) != logical_root):
            raise ValueError('logical root must be canonical and absolute')
        space=None
        if space_spec is not None:
            spec=importlib.util.spec_from_file_location('cpp_captured_space',Path(__file__).with_name('cpp-input-space.py'))
            module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
            space=module.CapturedSpace.read(space_spec,input_tree_digest)
        tree_digest = space.binding_sha256 if space is not None else input_tree_digest(source_root) if logical_root else None
        if not 4 <= len(argv) <= 8:
            raise ValueError('unsupported compilation transport arguments')
        build_model = json.loads(Path(argv[6]).read_text()) if len(argv) >= 7 else None
        selection = selected_targets(Path(argv[7]), build_model) if len(argv) == 8 else None
        if native_capture is not None and (space is None or selection is None or compiler_receipt is None):
            raise ValueError('native membership requires captured roots, compiler identity and explicit target selection')
        if space is not None and selection is None:raise ValueError('captured input space requires explicit configured target selection')
        if space is not None:space.validate_model(build_model,logical_root,source_root)
        if build_model is not None and (not logical_root or not compiler_receipt
                or build_model.get('formatVersion') != 1
                or build_model.get('codemodel', {}).get('paths', {}).get('source') != logical_root):
            raise ValueError('configured target model requires compatible attested execution layout')
        transport = 'ordered-v6' if space is not None else 'ordered-v5' if build_model is not None else 'ordered-v4' if logical_root else 'ordered-v3' if compiler_receipt else 'ordered-v2'
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
        native_binding=None;worlds=[];invocations={}
        if native_capture is not None:
            loader=importlib.util.spec_from_file_location('cpp_native_membership_binding',Path(__file__).with_name('cpp-native-membership-binding.py'))
            binder=importlib.util.module_from_spec(loader);loader.loader.exec_module(binder)
            native_binding=binder.bind_capture(native_capture,Path(argv[6]),database_path)
            transport='ordered-v7'
            entries=[(row['id'],row['original']) for row in native_binding['membership']['commands']]
        else:entries=[(None,entry) for entry in database]
        contexts, originals = {}, [];parsed_commands={}
        for command_id,entry in entries:
            source, operations, original = parse(entry, source_root, logical_root is not None,space,native_binding is None)
            if identity is not None and original['arguments'][0] not in (compiler_command, posixpath.basename(compiler_command)):
                raise ValueError('database compiler differs from captured compiler command')
            layout = {'sourceRoot': space.observation_root if space is not None else logical_root, 'workingDirectory': original['directory'],
                      'sourceOperand': original['sourceOperand'], 'treeSha256': tree_digest} if logical_root else None
            if native_binding is None:
                if source in contexts and contexts[source] != (source,operations,layout):
                    raise ValueError(f"conflicting compilation profiles: {source}")
                contexts[source] = (source,operations,layout)
                originals.append({"source": source, **original})
            else:
                parsed_commands[command_id]=(source,operations,layout)
                originals.append({"commandId":command_id,"source":source,**original})
        if native_binding is not None:
            model_text=json.dumps(build_model,sort_keys=True,separators=(',', ':'),ensure_ascii=False)
            model_sha=hashlib.sha256(model_text.encode()).hexdigest()
            membership=native_binding['membership'];dispositions={row['targetId']:row for row in membership['targetDispositions']}
            selected_ids=set(selection['targetIds'])
            for target_id in sorted(selected_ids):
                if target_id not in dispositions or dispositions[target_id]['status']!='SUPPORTED_DIRECT_ARTIFACTS':
                    raise ValueError('selected target has unsupported native participation profile')
                command_ids=sorted({row['commandId'] for row in membership['relations'] if row['targetId']==target_id})
                if not command_ids:raise ValueError('selected target has no native command participation')
                seed='CPP_NATIVE_WORLD_V1\0'+model_sha+'\0'+selection['configuration']+'\0'+target_id+'\0'+native_binding['membershipSha256']
                world_id='cpp-world-'+hashlib.sha256(seed.encode()).hexdigest();ids=[]
                for command_id in command_ids:
                    invocation_id='cpp-invocation-'+hashlib.sha256((world_id+'\0'+command_id).encode()).hexdigest()
                    source,operations,layout=parsed_commands[command_id]
                    contexts[invocation_id]=(source,operations,layout);ids.append(invocation_id)
                    invocations[invocation_id]=dict(worldId=world_id,commandId=command_id,targetId=target_id,
                            standardSelection='EXPLICIT_OPTION' if any(k=='std' for k,v in operations) else 'COMPILER_DEFAULT')
                worlds.append(dict(id=world_id,configuration=selection['configuration'],targetId=target_id,
                    modelSha256=model_sha,membershipSha256=native_binding['membershipSha256'],invocationIds=ids))
        rows = []
        if space is not None:
            rows.append(['input-space',module.FORMAT,json.dumps(space.value,sort_keys=True,separators=(',', ':'),ensure_ascii=False)])
        if build_model is not None:
            rows.append(['build-model', 'cmake-codemodel-v2', json.dumps(build_model, sort_keys=True, separators=(',', ':'), ensure_ascii=False)])
        if selection is not None:
            rows.append(['build-selection', 'direct-targets-v1', json.dumps(selection, sort_keys=True, separators=(',', ':'), ensure_ascii=False)])
        if native_binding is not None:
            rows.append(['native-membership','cpp-native-membership-binding-v1',json.dumps(native_binding,sort_keys=True,separators=(',', ':'),ensure_ascii=False)])
            rows.extend(['native-world','cpp-native-world-v1',json.dumps(world,sort_keys=True,separators=(',', ':'),ensure_ascii=False)] for world in worlds)
        for key, (source,operations,layout) in sorted(contexts.items()):
            identifier = key if native_binding is not None else "cpp|" + source
            rows += [["context", identifier, transport], ["source", identifier, source]]
            if native_binding is not None:
                rows.append(['native-invocation',identifier,json.dumps(invocations[identifier],sort_keys=True,separators=(',', ':'),ensure_ascii=False)])
            if identity is not None:
                rows.append(['compiler', identifier, json.dumps(identity, sort_keys=True, separators=(',', ':'))])
            if layout:
                rows.append(['execution', identifier, json.dumps(layout, sort_keys=True, separators=(',', ':'))])
            rows += [[kind, identifier, value] for kind, value in operations]
        content = "".join("\t".join(row) + "\n" for row in rows)
        atomic(provenance, json.dumps({"transportVersion": transport,
               **({"nativeMembership":native_binding,"nativeWorlds":worlds} if native_binding is not None else {}),
               **({"capturedInputSpace":space.value} if space is not None else {}),
               "configuredBuildModel": build_model,
               "configuredTargetSelection": selection,
               "compilerReceipt": compiler_receipt, "invocations": originals,
               "manifestSha256": hashlib.sha256(content.encode()).hexdigest()}, indent=2) + "\n")
        atomic(output, content)
    except Exception:
        output.write_text("", encoding="utf-8")
        provenance.unlink(missing_ok=True)
        raise

def input_tree_digest(root):
    if root.is_symlink() or not root.is_dir():
        raise ValueError('configured input root is not a regular directory')
    root = root.resolve(strict=True)
    inventory = []
    for path in sorted(root.rglob('*'), key=lambda p: p.relative_to(root).as_posix()):
        relative = path.relative_to(root).as_posix()
        if any(c in relative for c in '\n\r\0'):
            raise ValueError('unsupported configured input path: ' + relative)
        if path.is_symlink():
            literal = str(path.readlink())
            if Path(literal).is_absolute() or any(c in literal for c in '\n\r\0'):
                raise ValueError('unsupported configured input alias: ' + relative)
            lexical = Path(os.path.normpath(str(path.parent / literal)))
            if not lexical.is_relative_to(root):
                raise ValueError('configured input alias leaves root: ' + relative)
            target = path.resolve(strict=True)
            if not target.is_relative_to(root) or not target.is_file():
                raise ValueError('configured input alias is not an internal regular file: ' + relative)
            digest = hashlib.sha256()
            with target.open('rb') as stream:
                for chunk in iter(lambda: stream.read(8192), b''):
                    digest.update(chunk)
            if str(path.readlink()) != literal or path.resolve(strict=True) != target:
                raise ValueError('configured input alias changed: ' + relative)
            inventory.append('L\0' + relative + '\0' + literal + '\0'
                             + target.relative_to(root).as_posix() + '\0' + digest.hexdigest() + '\n')
        elif path.is_dir():
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
