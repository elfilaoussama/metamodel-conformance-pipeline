#!/usr/bin/env python3
"""Capture actual CMake codemodel-v2 facts; no inferred link/input closure."""
import hashlib
import json
from pathlib import Path
import sys


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON member: ' + key)
        result[key] = value
    return result


def capture(reply):
    reply = Path(reply).resolve(strict=True)
    captured = {}

    def read(name):
        if not isinstance(name, str) or not name.endswith('.json') or Path(name).name != name or '\\' in name:
            raise ValueError('invalid File API reply filename')
        path = reply / name
        if path.is_symlink() or path.resolve(strict=True).parent != reply:
            raise ValueError('aliased or escaping File API reply')
        data = path.read_bytes()
        previous = captured.setdefault(name, data)
        if previous != data:
            raise ValueError('File API reply changed during capture')
        return json.loads(data, object_pairs_hook=unique_object)

    indexes = list(reply.glob('index-*.json'))
    if len(indexes) != 1:
        raise ValueError('exactly one fresh CMake File API index required')
    index = read(indexes[0].name)
    response = index['reply']['codemodel-v2']
    if response.get('kind') != 'codemodel' or response.get('version', {}).get('major') != 2:
        raise ValueError('unsupported CMake codemodel response')
    model = read(response['jsonFile'])
    if model.get('kind') != 'codemodel' or model.get('version', {}).get('major') != 2:
        raise ValueError('unsupported CMake codemodel object')
    targets = []
    seen = set()
    configurations = set()
    for configuration in model['configurations']:
        name = configuration['name']
        if not isinstance(name, str) or name in configurations:
            raise ValueError('duplicate or invalid build configuration')
        configurations.add(name)
        for target in configuration.get('targets', []):
            key = name, target['id']
            if key in seen:
                raise ValueError('duplicate configured target identity')
            seen.add(key)
            body = read(target['jsonFile'])
            if (body['id'], body['name']) != (target['id'], target['name']):
                raise ValueError('target reference/object mismatch')
            targets.append(dict(configuration=name, id=body['id'], object=body))
    for name, data in captured.items():
        if (reply / name).read_bytes() != data:
            raise ValueError('File API reply changed after capture')
    # Index filenames contain timestamps. Retain their bytes in the capture
    # inventory, while semantic identity uses the actual model/tool facts.
    result = dict(formatVersion=1, cmake=index['cmake'], codemodel=model,
                  targets=sorted(targets, key=lambda t: (t['configuration'], t['id'])))
    text = json.dumps(result, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
    receipt = dict(formatVersion=1, semanticModelSha256=hashlib.sha256(text.encode()).hexdigest(),
                   inputs=[dict(path=name, sha256=hashlib.sha256(data).hexdigest())
                           for name, data in sorted(captured.items())],
                   meaning='CONFIGURED_TARGET_FACTS_NOT_LINKER_INPUT_CLOSURE')
    return text, receipt


def main():
    if len(sys.argv) != 3:
        raise ValueError('expected reply directory and fresh output path')
    output = Path(sys.argv[2])
    if output.exists() or Path(str(output) + '.capture.json').exists():
        raise ValueError('CMake model output must be fresh')
    text, receipt = capture(Path(sys.argv[1]))
    output.write_text(text + '\n', encoding='utf-8')
    Path(str(output) + '.capture.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
