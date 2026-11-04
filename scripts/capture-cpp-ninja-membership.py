#!/usr/bin/env python3
"""Bounded, read-only Ninja 1.10.1 direct-participation capture.

This explicit provider profile neither builds targets nor establishes compiler
semantics. Unsupported graph syntax fails closed and all attempts are retained.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON member: ' + key)
        result[key] = value
    return result


def _json(data):
    return json.loads(data, object_pairs_hook=_unique)


def _write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8')


def _unalias(path):
    path = Path(os.path.abspath(path))
    for item in (path, *path.parents):
        if item.is_symlink():
            raise ValueError('symlink input is unsupported: ' + str(item))
    return path.resolve(strict=True)


def capture(build_directory, configured_model, compile_commands, ninja, output):
    """Return receipt; failures raise after writing capture.json to fresh output.

    Inputs are paths. Ninja can be an executable path or a PATH-resolved name.
    The 60-second whole capture budget includes per-command maxima of 10 seconds.
    """
    started = time.monotonic()
    deadline = started + 60
    out = Path(os.path.abspath(output))
    if out.is_relative_to(Path(os.path.abspath(build_directory))):
        raise ValueError('capture output must be outside the build directory')
    out.mkdir(parents=False, exist_ok=False)
    receipt = dict(format='cpp-ninja-membership-capture-v1', status='FAIL_RETAINED',
                   profile='NINJA_1_10_1_LITERAL_GRAPH_READONLY_V1', queries=[])
    tracked = {}
    graph_paths = set()
    log_before = {}
    build = None
    query_directory = None

    def remember(path):
        path = _unalias(path)
        data = path.read_bytes()
        previous = tracked.setdefault(path, data)
        if previous != data:
            raise ValueError('input changed during capture: ' + str(path))
        return data

    def run(argv, label):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('capture deadline exceeded')
        record = dict(argv=argv, cwd=str(query_directory), label=label)
        receipt['queries'].append(record)
        try:
            completed = subprocess.run(argv, cwd=query_directory, stdout=subprocess.PIPE,
                                       stderr=subprocess.PIPE, timeout=min(10, remaining), check=False)
            stdout, stderr = completed.stdout, completed.stderr
            record['exitCode'] = completed.returncode
        except subprocess.TimeoutExpired as error:
            stdout, stderr = error.stdout or b'', error.stderr or b''
            record['timedOut'] = True
            raise
        finally:
            if 'stdout' in locals():
                prefix = '%04d' % (len(receipt['queries']) - 1)
                (out / (prefix + '.stdout')).write_bytes(stdout)
                (out / (prefix + '.stderr')).write_bytes(stderr)
                record.update(stdoutFile=prefix + '.stdout', stderrFile=prefix + '.stderr',
                              stdoutSha256=_sha(stdout), stderrSha256=_sha(stderr))
        if completed.returncode != 0 or stderr:
            raise ValueError('Ninja query failed or emitted stderr: ' + label)
        return stdout.decode('utf-8', errors='strict')

    def graph(path, active):
        if time.monotonic() > deadline:
            raise TimeoutError('capture deadline exceeded while reading graph')
        path = _unalias(path)
        if not path.is_relative_to(build):
            raise ValueError('graph input escapes build directory')
        if path in active:
            raise ValueError('recursive graph include cycle')
        data = remember(path)
        graph_paths.add(path)
        graph_copy = out / 'graph-inputs' / path.relative_to(build)
        graph_copy.parent.mkdir(parents=True, exist_ok=True)
        graph_copy.write_bytes(data)
        remember(graph_copy)
        text = data.decode('utf-8', errors='strict')
        if '\r' in text or '\x00' in text:
            raise ValueError('unsupported graph text encoding')
        for line in text.splitlines():
            if line.endswith('$'):
                raise ValueError('graph continuations unsupported')
            stripped = line.lstrip()
            if stripped.startswith('#'):
                continue
            if re.match(r'dyndep\s*=', stripped):
                raise ValueError('dyndep graph unsupported')
            match = re.match(r'^(include|subninja)\s+(.+)$', line)
            if match:
                name = match.group(2)
                if '$' in name or '#' in name or name != name.strip() or '\\' in name:
                    raise ValueError('graph include must be a literal path')
                child = Path(name)
                if child.is_absolute() or '..' in child.parts:
                    raise ValueError('escaping or absolute graph include unsupported')
                # Ninja resolves include/subninja paths against its working directory.
                graph(build / child, active | {path})
            elif re.match(r'^\s*(include|subninja)\b', line):
                raise ValueError('unsupported graph include syntax')

    try:
        build = _unalias(build_directory)
        if not build.is_dir():
            raise ValueError('build directory is not a directory')
        receipt['buildDirectory'] = str(build)
        for name in ('.ninja_log', '.ninja_deps'):
            path = build / name
            if path.is_symlink():
                raise ValueError('symlink Ninja state file unsupported')
            log_before[path] = (path.exists(), path.read_bytes() if path.exists() else None,
                                path.stat().st_mtime_ns if path.exists() else None)
        tool_value = shutil.which(str(ninja))
        if tool_value is None:
            raise ValueError('Ninja executable not found')
        tool = _unalias(tool_value)
        tool_bytes = remember(tool)
        receipt['executable'] = dict(path=str(tool), sha256=_sha(tool_bytes))
        execution_tool = out / 'execution-tool' / 'ninja'
        execution_tool.parent.mkdir()
        execution_tool.write_bytes(tool_bytes)
        execution_tool.chmod(0o500)
        remember(execution_tool)
        receipt['executable']['executedSnapshotPath'] = str(execution_tool)
        model_data = remember(configured_model)
        commands_data = remember(compile_commands)
        (out / 'configured-model.json').write_bytes(model_data)
        (out / 'compile-commands.json').write_bytes(commands_data)
        receipt.update(configuredModelSha256=_sha(model_data), compileCommandsSha256=_sha(commands_data))
        model, commands = _json(model_data), _json(commands_data)
        if not isinstance(model, dict) or model.get('formatVersion') != 1:
            raise ValueError('expected configured-model formatVersion 1')
        if model['cmake']['generator']['name'] != 'Ninja':
            raise ValueError('configured generator must be Ninja')
        codemodel = model['codemodel']
        if codemodel.get('kind') != 'codemodel' or codemodel.get('version', {}).get('major') != 2:
            raise ValueError('unsupported codemodel')
        if codemodel['paths']['build'] != str(build):
            raise ValueError('codemodel build directory mismatch')
        configurations = codemodel['configurations']
        if not isinstance(configurations, list) or len(configurations) != 1:
            raise ValueError('exactly one configured build configuration required')
        configuration = configurations[0]
        refs = configuration['targets']
        if not isinstance(refs, list) or not isinstance(model.get('targets'), list):
            raise ValueError('missing configured target inventory')
        ref_ids = [r['id'] for r in refs]
        targets = []
        for entry in model['targets']:
            body = entry['object']
            if entry['configuration'] != configuration['name'] or entry['id'] != body['id']:
                raise ValueError('target object/configuration mismatch')
            ref = [r for r in refs if r['id'] == entry['id']]
            if len(ref) != 1 or ref[0]['name'] != body['name']:
                raise ValueError('target reference mismatch')
            targets.append(body)
        if len(ref_ids) != len(set(ref_ids)) or sorted(ref_ids) != sorted(t['id'] for t in targets):
            raise ValueError('configured target inventory incomplete or duplicated')
        receipt['configuredTargets'] = model['targets']
        graph(build / 'build.ninja', set())
        # Queries read exactly the retained literal graph closure, rather than
        # reopening mutable originals between otherwise equal boundary hashes.
        query_directory = out / 'graph-inputs'
        receipt['physicalQueryDirectory'] = str(query_directory)
        for path in query_directory.rglob('*'):
            if path.is_file():path.chmod(0o400)
        for path in sorted([p for p in query_directory.rglob('*') if p.is_dir()], reverse=True):path.chmod(0o500)
        query_directory.chmod(0o500)
        for name in ('.ninja_log', '.ninja_deps'):
            log_before[query_directory / name] = (False, None, None)
        version = run([str(execution_tool), '--version'], 'version')
        if version != '1.10.1\n':
            raise ValueError('this profile requires exact Ninja 1.10.1')
        receipt['executable']['version'] = version.strip()
        reader_path = _unalias(Path(__file__).with_name('cpp-ninja-membership.py'))
        remember(reader_path)
        spec = importlib.util.spec_from_file_location('cpp_ninja_membership_capture_reader', reader_path)
        reader = importlib.util.module_from_spec(spec)
        # Execute the exact retained bytes, without writing __pycache__ beside inputs.
        exec(compile(tracked[reader_path], str(reader_path), 'exec'), reader.__dict__)
        operands = []
        for target in targets:
            for artifact in target.get('artifacts', []):
                operands.append(artifact['path'])
        if not isinstance(commands, list):
            raise ValueError('compile commands must be an array')
        for row in commands:
            args = reader._argv(row)
            outputs = [args[i + 1] for i, arg in enumerate(args[:-1]) if arg == '-o']
            if len(outputs) != 1:
                raise ValueError('exactly one separate -o output required')
            operands.append(outputs[0])
        queries = {}
        for operand in dict.fromkeys(operands):
            reader._path(operand)
            if operand.startswith('-'):
                raise ValueError('query operand cannot be a Ninja option')
            queries[operand] = run([str(execution_tool), '-n', '-t', 'query', operand], 'query:' + operand)
        result = reader.derive_membership(targets, commands, queries, str(build))
        for path, data in tracked.items():
            if _unalias(path) != path or path.read_bytes() != data:
                raise ValueError('immutable input changed: ' + str(path))
        for path, before in log_before.items():
            after = (path.exists(), path.read_bytes() if path.exists() else None,
                     path.stat().st_mtime_ns if path.exists() else None)
            if after != before:
                raise ValueError('Ninja log/deps modified: ' + str(path))
        if time.monotonic() > deadline:
            raise TimeoutError('capture deadline exceeded')
        receipt['inputs'] = [dict(path=str(p), sha256=_sha(data)) for p, data in sorted(tracked.items())]
        receipt['graphInputs'] = [row for row in receipt['inputs'] if Path(row['path']) in graph_paths]
        _write(out / 'membership.json', result)
        receipt['membershipSha256'] = _sha((out / 'membership.json').read_bytes())
        receipt['status'] = 'PASS_NATIVE_CPP_NINJA_MEMBERSHIP_CAPTURE'
        return receipt
    except Exception as error:
        receipt['error'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        active_error = sys.exc_info()[0] is not None
        # Also audit failed attempts; a timeout/error does not excuse mutation.
        checks = []
        for path, data in tracked.items():
            try:
                unchanged = _unalias(path) == path and path.read_bytes() == data
                checks.append(dict(path=str(path), unchanged=unchanged))
            except Exception as error:
                checks.append(dict(path=str(path), unchanged=False, error=str(error)))
        receipt['inputRechecks'] = checks
        state_checks = []
        for path, before in log_before.items():
            try:
                after = (path.exists(), path.read_bytes() if path.exists() else None,
                         path.stat().st_mtime_ns if path.exists() else None)
                state_checks.append(dict(path=str(path), unchanged=not path.is_symlink() and after == before))
            except Exception as error:
                state_checks.append(dict(path=str(path), unchanged=False, error=str(error)))
        receipt['ninjaStateRechecks'] = state_checks
        receipt['inputs'] = [dict(path=str(p), sha256=_sha(data)) for p, data in sorted(tracked.items())]
        receipt['graphInputs'] = [row for row in receipt['inputs'] if Path(row['path']) in graph_paths]
        if any(not row['unchanged'] for row in checks + state_checks):
            receipt['status'] = 'FAIL_RETAINED'
            receipt['mutationDetected'] = True
            if (out / 'membership.json').exists():
                (out / 'membership.json').rename(out / 'membership-rejected.json')
        receipt['elapsedSeconds'] = time.monotonic() - started
        _write(out / 'capture.json', receipt)
        if receipt.get('mutationDetected') and not active_error:
            raise ValueError('immutable input or Ninja state changed during capture')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('build-directory', 'configured-model', 'compile-commands', 'ninja', 'output'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    capture(args.build_directory, args.configured_model, args.compile_commands, args.ninja, args.output)


if __name__ == '__main__':
    main()
