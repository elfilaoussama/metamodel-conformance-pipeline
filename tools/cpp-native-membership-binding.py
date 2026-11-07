"""Validate retained Ninja capture evidence without reopening its original host.

This binds direct artifact participation, not compiler semantics or ownership.
No executable is launched and no provider profile is enabled by importing it.
"""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import types


PROFILE = 'NINJA_1_10_1_LITERAL_GRAPH_READONLY_V1'


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
    return json.loads(data, object_pairs_hook=_unique,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError('nonfinite JSON: ' + value)))


def _logical(value):
    if not isinstance(value, str) or not value.startswith('/') or '\\' in value or any(ord(c) < 32 for c in value):
        raise ValueError('expected absolute POSIX capture path')
    path = PurePosixPath(value)
    if '..' in path.parts or str(path) != value or value.startswith('//'):
        raise ValueError('noncanonical capture path: ' + value)
    return path


def _bind_capture(capture_directory, configured_model_path, compile_commands_path):
    """Return a hash-bound, rederived cpp-native-membership-binding-v1 object.

    Original container paths are compared lexically only. All filesystem reads
    address retained snapshots, supplied model/commands, or the deployed reader.
    """
    watched = {}

    def read(path):
        path = Path(path).absolute()
        if any(p.is_symlink() for p in (path, *path.parents)):
            raise ValueError('symlink evidence path: ' + str(path))
        if not path.is_file():
            raise ValueError('missing evidence file: ' + str(path))
        data = path.read_bytes()
        if path in watched and watched[path] != data:
            raise ValueError('evidence changed during binding: ' + str(path))
        watched[path] = data
        return data

    # The validator and accepted reader must remain the same bytes throughout
    # the operation, just as the supplied and retained evidence must.
    read(__file__)
    out = Path(capture_directory).absolute()

    def retained(name):
        if not isinstance(name, str) or not name or '\\' in name:
            raise ValueError('invalid retained file name')
        p = PurePosixPath(name)
        if p.is_absolute() or '..' in p.parts or str(p) != name:
            raise ValueError('unsafe retained file name: ' + name)
        return read(out.joinpath(*p.parts))

    raw_capture = retained('capture.json')
    receipt = _json(raw_capture)
    if receipt.get('format') != 'cpp-ninja-membership-capture-v1' or receipt.get('status') != 'PASS_NATIVE_CPP_NINJA_MEMBERSHIP_CAPTURE':
        raise ValueError('capture is not an accepted native membership capture')
    if receipt.get('profile') != PROFILE or receipt.get('mutationDetected', False) is not False:
        raise ValueError('unsupported or mutated capture profile')
    build = _logical(receipt['buildDirectory'])
    tool = receipt['executable']
    tool_original = _logical(tool['path'])
    executed = _logical(tool['executedSnapshotPath'])
    logical_out = executed.parent.parent
    if executed != logical_out / 'execution-tool/ninja' or tool.get('version') != '1.10.1':
        raise ValueError('unsupported tool snapshot or version')
    if receipt.get('physicalQueryDirectory') != str(logical_out / 'graph-inputs'):
        raise ValueError('query directory is not the retained graph snapshot')

    inputs = receipt.get('inputs')
    if not isinstance(inputs, list) or not inputs:
        raise ValueError('missing capture input inventory')
    inventory = {}
    for row in inputs:
        if set(row) != {'path', 'sha256'}:
            raise ValueError('invalid capture input row')
        path = _logical(row['path'])
        digest = row['sha256']
        if not isinstance(digest, str) or not re.fullmatch('[0-9a-f]{64}', digest) or path in inventory:
            raise ValueError('duplicate path or invalid input digest')
        inventory[path] = digest

    def checks(name, expected):
        rows = receipt.get(name)
        if not isinstance(rows, list) or not rows:
            raise ValueError('missing ' + name)
        paths = []
        for row in rows:
            paths.append(_logical(row['path']))
            if row.get('unchanged') is not True or 'error' in row:
                raise ValueError('failed immutable recheck: ' + name)
        if len(paths) != len(set(paths)) or set(paths) != expected:
            raise ValueError('incomplete or duplicate ' + name)

    checks('inputRechecks', set(inventory))
    checks('ninjaStateRechecks', {build / '.ninja_log', build / '.ninja_deps',
                                logical_out / 'graph-inputs/.ninja_log', logical_out / 'graph-inputs/.ninja_deps'})
    accounted = set()

    def matches(path, data):
        if inventory.get(path) != _sha(data):
            raise ValueError('snapshot/input identity mismatch: ' + str(path))
        accounted.add(path)

    tool_bytes = retained('execution-tool/ninja')
    if _sha(tool_bytes) != tool.get('sha256'):
        raise ValueError('executed tool snapshot hash mismatch')
    matches(tool_original, tool_bytes)
    matches(executed, tool_bytes)
    reader_path = Path(__file__).with_name('cpp-ninja-membership.py')
    reader_bytes = read(reader_path)
    reader_rows = [p for p in inventory if p.name == 'cpp-ninja-membership.py']
    if len(reader_rows) != 1:
        raise ValueError('expected one original reader input')
    matches(reader_rows[0], reader_bytes)

    model_bytes = read(configured_model_path)
    commands_bytes = read(compile_commands_path)
    for name, data, key in [('configured-model.json', model_bytes, 'configuredModelSha256'),
                             ('compile-commands.json', commands_bytes, 'compileCommandsSha256')]:
        if retained(name) != data or receipt.get(key) != _sha(data):
            raise ValueError('supplied input differs from capture: ' + name)
    model, commands = _json(model_bytes), _json(commands_bytes)
    if not isinstance(model, dict) or model.get('formatVersion') != 1 or model['cmake']['generator']['name'] != 'Ninja':
        raise ValueError('unsupported configured model')
    codemodel = model['codemodel']
    if codemodel.get('kind') != 'codemodel' or codemodel.get('version', {}).get('major') != 2 or codemodel['paths']['build'] != str(build):
        raise ValueError('codemodel identity mismatch')
    configurations = codemodel['configurations']
    if not isinstance(configurations, list) or len(configurations) != 1:
        raise ValueError('expected one configuration')
    configuration = configurations[0]
    refs = configuration['targets']
    targets = []
    for entry in model['targets']:
        body = entry['object']
        ref = [r for r in refs if r['id'] == entry['id']]
        if entry['configuration'] != configuration['name'] or entry['id'] != body['id'] or len(ref) != 1 or ref[0]['name'] != body['name']:
            raise ValueError('configured target reference mismatch')
        targets.append(body)
    ids = [r['id'] for r in refs]
    if len(ids) != len(set(ids)) or sorted(ids) != sorted(t['id'] for t in targets) or receipt.get('configuredTargets') != model['targets']:
        raise ValueError('incomplete configured target inventory')

    graph_rows = receipt.get('graphInputs')
    if not isinstance(graph_rows, list) or not graph_rows:
        raise ValueError('missing graph inventory')
    graph_inventory = {}
    for row in graph_rows:
        p = _logical(row['path'])
        if set(row) != {'path', 'sha256'} or p in graph_inventory or not p.is_relative_to(build) or inventory.get(p) != row['sha256']:
            raise ValueError('invalid graph input inventory')
        graph_inventory[p] = row['sha256']
    visited = set()

    def graph(path, active):
        if path in active:
            raise ValueError('recursive graph include cycle')
        if path not in graph_inventory:
            raise ValueError('reachable graph input missing from inventory')
        relative = path.relative_to(build)
        data = retained('graph-inputs/' + str(relative))
        matches(path, data)
        matches(logical_out / 'graph-inputs' / relative, data)
        visited.add(path)
        text = data.decode('utf-8', errors='strict')
        if '\r' in text or '\x00' in text:
            raise ValueError('unsupported graph encoding')
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
                child = PurePosixPath(name)
                if '$' in name or '#' in name or '\\' in name or name != name.strip() or child.is_absolute() or '..' in child.parts:
                    raise ValueError('unsupported graph include path')
                graph(build / child, active | {path})
            elif re.match(r'^\s*(include|subninja)\b', line):
                raise ValueError('unsupported graph include syntax')

    graph(build / 'build.ninja', set())
    if visited != set(graph_inventory):
        raise ValueError('graph inventory contains unreachable inputs')
    remaining = set(inventory) - accounted
    # The capture tracks original model/DB paths, but does not record their
    # argument names. Require exactly those two distinct input rows by bytes.
    if len(remaining) != 2 or sorted(inventory[p] for p in remaining) != sorted([_sha(model_bytes), _sha(commands_bytes)]):
        raise ValueError('unaccounted or aliased capture inputs')

    reader = types.ModuleType('validated_cpp_ninja_membership_reader')
    exec(compile(reader_bytes, str(reader_path), 'exec'), reader.__dict__)
    operands = []
    for target in targets:
        operands.extend(a['path'] for a in target.get('artifacts', []))
    if not isinstance(commands, list):
        raise ValueError('compile commands must be an array')
    for row in commands:
        args = reader._argv(row)
        outputs = [args[i + 1] for i, arg in enumerate(args[:-1]) if arg == '-o']
        if len(outputs) != 1:
            raise ValueError('expected one separate command output')
        operands.append(outputs[0])
    expected = set(operands)
    queries, version_count, query_files = {}, 0, set()
    for record in receipt['queries']:
        argv = record.get('argv')
        if not isinstance(argv, list) or not argv or argv[0] != str(executed) or record.get('cwd') != receipt['physicalQueryDirectory']:
            raise ValueError('query executable or directory mismatch')
        if type(record.get('exitCode')) is not int or record['exitCode'] != 0 or record.get('timedOut', False) is not False:
            raise ValueError('failed or timed out query')
        blobs = []
        for field in ('stdout', 'stderr'):
            name = record[field + 'File']
            if name in query_files:
                raise ValueError('duplicate query capture file')
            query_files.add(name)
            data = retained(name)
            if _sha(data) != record.get(field + 'Sha256'):
                raise ValueError('query stream hash mismatch')
            blobs.append(data)
        stdout, stderr = blobs
        if stderr:
            raise ValueError('query emitted stderr')
        if argv == [str(executed), '--version'] and record.get('label') == 'version':
            version_count += 1
            if stdout != b'1.10.1\n':
                raise ValueError('version query mismatch')
        elif len(argv) == 5 and argv[1:4] == ['-n', '-t', 'query']:
            operand = argv[4]
            if operand not in expected or operand in queries or record.get('label') != 'query:' + operand:
                raise ValueError('unknown or duplicate query operand')
            queries[operand] = stdout.decode('utf-8', errors='strict')
        else:
            raise ValueError('unsupported query operation')
    if version_count != 1 or set(queries) != expected:
        raise ValueError('incomplete query inventory')
    membership_bytes = retained('membership.json')
    membership = _json(membership_bytes)
    if _sha(membership_bytes) != receipt.get('membershipSha256'):
        raise ValueError('membership hash mismatch')
    if reader.derive_membership(targets, commands, queries, str(build)) != membership:
        raise ValueError('stored membership differs from retained query derivation')
    for path, before in list(watched.items()):
        if read(path) != before:
            raise ValueError('binding input changed: ' + str(path))
    return dict(format='cpp-native-membership-binding-v1', captureSha256=_sha(raw_capture),
                configuredModelSha256=_sha(model_bytes),
                configuredModelCanonicalSha256=_sha(json.dumps(model,sort_keys=True,separators=(',', ':'),ensure_ascii=False).encode('utf-8')),
                compileCommandsSha256=_sha(commands_bytes),
                membershipSha256=_sha(membership_bytes), readerSha256=_sha(reader_bytes),
                toolSha256=_sha(tool_bytes), toolVersion='1.10.1', profile=PROFILE,
                membership=membership)


def bind_capture(capture_directory, configured_model_path, compile_commands_path):
    """Validate the retained capture and return its exact bound membership."""
    try:
        return _bind_capture(capture_directory, configured_model_path, compile_commands_path)
    except (KeyError, TypeError, AttributeError, IndexError, OSError, UnicodeError) as error:
        raise ValueError('invalid retained membership capture: ' + str(error)) from error
