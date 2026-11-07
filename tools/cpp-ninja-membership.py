"""Pure, fail-closed reader for captured Ninja 1.10.1 direct participation.

No subprocesses, filesystem resolution, target selection, or ownership inference.
Paths use POSIX lexical normalization; original command records and ordered argv
are retained. Command IDs hash canonical JSON of the complete original row.
Duplicate rows mean structurally identical JSON records, not raw JSON whitespace.
The caller must retain the raw capture, executable identity and stderr separately.
"""
import hashlib
import json
import posixpath
import re
import shlex


def _text(value, label):
    if not isinstance(value, str) or not value or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise ValueError(label + ': expected nonempty text without control characters')
    return value


def _path(value):
    value = _text(value, 'path')
    if '\\' in value or value.startswith('//') or '..' in value.split('/'):
        raise ValueError('unsupported non-POSIX, double-root, or parent-traversal path: ' + value)
    return posixpath.normpath(value)


def _absolute(value, directory):
    value = _path(value)
    return value if value.startswith('/') else _path(posixpath.join(directory, value))


def parse_query(text, requested_operand):
    """Parse measured query stdout; outputs are consumers, never artifact members."""
    requested = _path(requested_operand)
    if not isinstance(text, str) or not text.endswith('\n') or '\r' in text:
        raise ValueError('query must be complete LF-terminated stdout')
    lines = text[:-1].split('\n')
    if not lines or not lines[0].endswith(':') or _path(lines[0][:-1]) != requested:
        raise ValueError('query node does not match requested operand')
    result = dict(node=requested, rule=None, explicitInputs=[], implicitInputs=[], orderOnlyInputs=[], outputs=[])
    section = None
    seen = set()
    for line in lines[1:]:
        if line.startswith('  input: '):
            if section is not None:
                raise ValueError('duplicate or misplaced input section')
            rule = _text(line[len('  input: '):], 'rule')
            if not re.fullmatch(r'[A-Za-z0-9_.+\-]+', rule):
                raise ValueError('unsupported query rule')
            result['rule'] = rule
            section = 'input'
        elif line == '  outputs:':
            if section == 'outputs':
                raise ValueError('duplicate outputs section')
            section = 'outputs'
        elif line.startswith('    ') and section is not None:
            operand = line[4:]
            key = 'outputs' if section == 'outputs' else 'explicitInputs'
            if section == 'input' and operand.startswith('|| '):
                key, operand = 'orderOnlyInputs', operand[3:]
            elif section == 'input' and operand.startswith('| '):
                key, operand = 'implicitInputs', operand[2:]
            if operand.startswith((' ', '|')):
                raise ValueError('malformed query dependency')
            operand = _path(operand)
            identity = ('outputs' if section == 'outputs' else 'inputs', operand)
            if identity in seen:
                raise ValueError('duplicate query dependency')
            seen.add(identity)
            result[key].append(operand)
        else:
            raise ValueError('unsupported query line: ' + repr(line))
    if section != 'outputs':
        raise ValueError('missing outputs section')
    return result


def _argv(row):
    if 'arguments' in row:
        args = row['arguments']
        if not isinstance(args, list) or not args:
            raise ValueError('arguments must be a nonempty array')
        args = [_text(a, 'argument') for a in args]
        if 'command' in row:
            raise ValueError('ambiguous arguments and command representations')
    else:
        command = _text(row.get('command'), 'command')
        # This reader supports direct compiler invocation, not shell programs.
        if any(c in command for c in '$`;&|<>\n\r'):
            raise ValueError('unsupported shell expansion or operator')
        try:
            args = shlex.split(command, posix=True)
        except ValueError as error:
            raise ValueError('malformed command quoting') from error
        if not args:
            raise ValueError('empty command')
    if any(a.startswith('@') for a in args):
        raise ValueError('response files are unsupported')
    return args


def derive_membership(targets, commands, queries, build_directory):
    """Return all command identities and direct artifact participation relations.

    STATIC_LIBRARY: artifact's direct explicit inputs join command outputs.
    OBJECT_LIBRARY: artifact itself joins a command output. Every joined output
    must have exactly one explicit graph input equal to its compileDB source.
    Implicit/order-only inputs and consumer edges never establish membership.
    Unknown static explicit inputs fail closed. Unsupported target types remain
    in targetDispositions. All command rows survive, including unselected rows.
    """
    build = _path(build_directory)
    if not build.startswith('/') or build != build_directory:
        raise ValueError('build_directory must be normalized and absolute')
    if not isinstance(targets, list) or not isinstance(commands, list) or not isinstance(queries, dict):
        raise ValueError('expected targets/commands arrays and query mapping')
    graph = {}
    for operand, raw in queries.items():
        key = _absolute(operand, build)
        parsed = parse_query(raw, operand)
        if key in graph and graph[key] != parsed:
            raise ValueError('conflicting normalized query operand')
        graph[key] = parsed
    by_output = {}
    records = {}
    for row in commands:
        if not isinstance(row, dict):
            raise ValueError('command row must be an object')
        directory = _path(row.get('directory'))
        if directory != row.get('directory') or directory != build:
            raise ValueError('command directory must equal normalized absolute build directory')
        source = _absolute(row.get('file'), directory)
        args = _argv(row)
        if args.count('-c') != 1:
            raise ValueError('expected one explicit compile-only flag')
        compile_index = args.index('-c')
        if compile_index + 1 == len(args) or args[compile_index + 1].startswith('-') or _absolute(args[compile_index + 1], directory) != source:
            raise ValueError('supported direct invocation requires -c followed by compileDB source')
        outputs = []
        for i, arg in enumerate(args[1:], 1):
            if arg == '-o':
                if i + 1 == len(args):
                    raise ValueError('missing -o argument')
                outputs.append(args[i + 1])
            elif arg.startswith('-o') and len(arg) > 2:
                # Restrict to separate -o to avoid misreading unrelated options.
                raise ValueError('joined or unsupported -o option')
        if len(outputs) != 1:
            raise ValueError('expected one explicit output')
        output = _absolute(outputs[0], directory)
        if 'output' in row and _absolute(row['output'], directory) != output:
            raise ValueError('compileDB output disagrees with invocation')
        if sum(1 for arg in args[1:] if not arg.startswith('-') and _absolute(arg, directory) == source) != 1:
            raise ValueError('compileDB source absent or repeated in invocation')
        canonical = json.dumps(row, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
        command_id = hashlib.sha256(canonical.encode('utf-8')).hexdigest()
        if output in by_output and by_output[output] != command_id:
            raise ValueError('nonidentical command rows share an output')
        by_output[output] = command_id
        if command_id in records:
            records[command_id]['occurrenceCount'] += 1
        else:
            records[command_id] = dict(id=command_id, directory=directory, source=source,
                                       output=output, arguments=args, occurrenceCount=1,
                                       original=row.copy())
    # Validate every command, including rows which participate in no known target.
    for output, command_id in by_output.items():
        query = graph.get(output)
        if query is None or query['rule'] in (None, 'phony'):
            raise ValueError('missing or non-compilation output query: ' + output)
        inputs = [_absolute(p, build) for p in query['explicitInputs']]
        if inputs != [records[command_id]['source']]:
            raise ValueError('command graph source mismatch: ' + output)
    dispositions, relations, target_ids = [], [], set()
    for target in targets:
        target_id = _text(target.get('id'), 'target id')
        name = _text(target.get('name'), 'target name')
        kind = _text(target.get('type'), 'target type')
        if target_id in target_ids:
            raise ValueError('duplicate target id')
        target_ids.add(target_id)
        supported = kind in ('STATIC_LIBRARY', 'OBJECT_LIBRARY')
        dispositions.append(dict(targetId=target_id, targetName=name, type=kind,
                                 status='SUPPORTED_DIRECT_ARTIFACTS' if supported else 'UNSUPPORTED_TARGET_TYPE'))
        if not supported:
            continue
        artifacts = target.get('artifacts')
        if not isinstance(artifacts, list) or not artifacts:
            raise ValueError('supported target has no artifacts')
        artifact_seen = set()
        for artifact_row in artifacts:
            artifact = _absolute(artifact_row.get('path'), build)
            if artifact in artifact_seen:
                raise ValueError('duplicate normalized target artifact')
            artifact_seen.add(artifact)
            query = graph.get(artifact)
            if query is None or query['rule'] in (None, 'phony'):
                raise ValueError('missing or phony artifact query: ' + artifact)
            outputs = [artifact] if kind == 'OBJECT_LIBRARY' else [_absolute(p, build) for p in query['explicitInputs']]
            for output in outputs:
                if output not in by_output:
                    raise ValueError('unknown direct compiled input: ' + output)
                relations.append(dict(targetId=target_id, targetName=name, artifact=artifact,
                                      commandId=by_output[output]))
    return dict(format='cpp-ninja-direct-participation-v1',
                commands=sorted(records.values(), key=lambda r: r['id']),
                relations=sorted(relations, key=lambda r: (r['targetId'], r['artifact'], r['commandId'])),
                targetDispositions=sorted(dispositions, key=lambda r: r['targetId']))
