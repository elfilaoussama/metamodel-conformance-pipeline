#!/usr/bin/env python3
"""Explicit Linux/Docker declared-mount retention and fresh execution, not input closure.

Recipes are executable trusted inputs. Role labels never certify build semantics.
No host path or provider provenance is rewritten during relocation.
"""
import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import stat
import subprocess
import time

FORMAT = 'declared-reproduction-bundle-v1'
HARDLINK_POLICY = 'PRESERVE_COMPLETE_DISTINCT_MEMBER_GROUPS_V1'


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''): h.update(block)
    return h.hexdigest()


def write(path, value):
    with Path(path).open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, indent=2); stream.write('\n')


def relative(value):
    p = PurePosixPath(value)
    if not isinstance(value, str) or not value or '\\' in value or '\0' in value or p.is_absolute() or '..' in p.parts or str(p) != value:
        raise ValueError('unsafe relative path')
    return p


def absolute(value):
    if not isinstance(value, str) or not value.startswith('/') or value == '/' or posixpath.normpath(value) != value or any(c in value for c in '\0\n,\\'):
        raise ValueError('unsafe container path')
    return value


def recipe_check(recipe):
    if set(recipe) != {'format', 'imageId', 'mounts', 'stages', 'outputTarget', 'environment', 'workingDirectory', 'resources'} or recipe['format'] != FORMAT:
        raise ValueError('unsupported recipe')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', recipe['imageId']): raise ValueError('immutable image ID required')
    absolute(recipe['outputTarget']); absolute(recipe['workingDirectory'])
    resources = recipe['resources']
    if set(resources) != {'memory', 'pidsLimit', 'user'} or not re.fullmatch('[1-9][0-9]*[kmg]?', resources['memory']) or type(resources['pidsLimit']) != int or resources['pidsLimit'] < 1 or not re.fullmatch('[0-9]+:[0-9]+', resources['user']): raise ValueError('invalid explicit operational resource policy')
    if not isinstance(recipe['environment'], dict) or any(not re.fullmatch('[A-Za-z_][A-Za-z0-9_]*', k) or not isinstance(v, str) or '\0' in v for k, v in recipe['environment'].items()):
        raise ValueError('invalid explicit environment')
    seen = set(); targets = []
    for m in recipe['mounts']:
        if set(m) != {'name', 'path', 'target', 'role'} or not re.fullmatch('[A-Za-z0-9_-]+', m['name']) or m['name'] in seen or not isinstance(m['role'], str): raise ValueError('invalid mount')
        seen.add(m['name']); targets.append(absolute(m['target']))
    targets.append(recipe['outputTarget'])
    if not seen or len(set(targets)) != len(targets) or any(a != b and (a + '/').startswith(b + '/') for a in targets for b in targets): raise ValueError('overlapping container mounts')
    if [s.get('name') for s in recipe['stages']] != ['observe', 'reference', 'replay']: raise ValueError('three explicit ordered stages required')
    for s in recipe['stages']:
        if set(s) != {'name', 'argv', 'seconds', 'acceptedExits'} or not isinstance(s['argv'], list) or not s['argv'] or any(not isinstance(a, str) or '\0' in a for a in s['argv']) or type(s['seconds']) not in (int, float) or not math.isfinite(s['seconds']) or s['seconds'] <= 0 or not isinstance(s['acceptedExits'], list) or not s['acceptedExits'] or any(type(e) != int for e in s['acceptedExits']): raise ValueError('invalid bounded stage')
    return recipe


def fresh(path):
    path = Path(path).absolute()
    if any(p.is_symlink() for p in path.parents): raise ValueError('aliased output ancestor')
    if path.exists() or path.is_symlink(): raise FileExistsError(path)
    path.mkdir(parents=True)
    return path


def control(directory, action, **values):
    write(directory / ('state-' + action + '.json'), dict(action=action, **values))


def scan(root, objects=None):
    root = Path(root)
    if root.is_symlink(): raise ValueError('mount root aliases are unsupported')
    rows = []
    def visit(path, rel):
        before = path.lstat(); mode = stat.S_IMODE(before.st_mode)
        row = dict(path=rel, mode=mode, mtimeNs=before.st_mtime_ns)
        if stat.S_ISLNK(before.st_mode): row.update(kind='symlink', target=os.readlink(path))
        elif stat.S_ISDIR(before.st_mode):
            row['kind'] = 'directory'; rows.append(row)
            for child in sorted(path.iterdir()): visit(child, child.name if rel == '.' else rel + '/' + child.name)
            if path.lstat().st_mtime_ns != before.st_mtime_ns: raise ValueError('directory changed during capture')
            return
        elif stat.S_ISREG(before.st_mode):
            row.update(kind='file', size=before.st_size, originalLinkCount=before.st_nlink)
            if before.st_nlink > 1:
                row['originalAliasGroup'] = str(before.st_dev)+':'+str(before.st_ino)
                row['originalAliasMember'] = str(path.resolve(strict=True))
            if objects is None: row['sha256'] = sha(path)
            else:
                temp = objects / '.capturing'
                with path.open('rb') as source, temp.open('xb') as target:
                    shutil.copyfileobj(source, target, 1 << 20)
                digest = sha(temp); row['sha256'] = digest
                if (objects / digest).exists(): temp.unlink()
                else: temp.rename(objects / digest)
            after = path.lstat()
            if (before.st_ino, before.st_size, before.st_mtime_ns, before.st_mode) != (after.st_ino, after.st_size, after.st_mtime_ns, after.st_mode): raise ValueError('file changed during capture')
        else: raise ValueError('special files unsupported')
        rows.append(row)
    visit(root, '.')
    return rows


def alias_groups(trees):
    groups = {}; membership = {}
    for name, rows in trees.items():
        for row in rows:
            if row['kind'] != 'file': continue
            count = row.get('originalLinkCount', 1)
            if type(count) != int or count < 1: raise ValueError('invalid original hardlink count')
            if count == 1:
                if 'originalAliasGroup' in row or 'originalAliasMember' in row: raise ValueError('alias metadata on singly linked file')
                continue
            group = row.get('originalAliasGroup'); member = row.get('originalAliasMember')
            if not isinstance(group, str) or not re.fullmatch('[0-9]+:[0-9]+', group) or not isinstance(member, str) or not member or '\0' in member: raise ValueError('missing original alias identity')
            if member in membership and membership[member] != group: raise ValueError('physical member belongs to conflicting alias groups')
            membership[member] = group
            signature = {k:row[k] for k in ['sha256','size','mode','mtimeNs','originalLinkCount']}
            entry = groups.setdefault(group, dict(group=group, metadata=signature, members={}))
            if entry['metadata'] != signature: raise ValueError('conflicting hardlink member metadata')
            entry['members'].setdefault(member, []).append(dict(mount=name, path=row['path']))
    result = []
    for group, entry in sorted(groups.items()):
        members = [dict(physicalPath=path, locations=sorted(locations, key=lambda x:(x['mount'],x['path']))) for path,locations in sorted(entry['members'].items())]
        complete = len(members) == entry['metadata']['originalLinkCount']
        duplicate_views = any(len(member['locations']) != 1 for member in members)
        if len(members) > entry['metadata']['originalLinkCount']: raise ValueError('more distinct alias members than original nlink')
        result.append(dict(group=group, metadata=entry['metadata'], members=members, complete=complete,
                           restorationSupported=complete and not duplicate_views,
                           reason='COMPLETE_DISTINCT_GROUP' if complete and not duplicate_views else 'DUPLICATE_MOUNT_VIEWS_UNSUPPORTED' if complete else 'EXTERNAL_OR_MISSING_GROUP_MEMBERS'))
    return result


def check_restored_aliases(restored, groups):
    restored = Path(restored)
    for group in groups:
        if not group['restorationSupported']: raise ValueError('hardlink alias semantics unsupported: '+group['reason'])
        identities = set()
        for member in group['members']:
            location = member['locations'][0]; path = restored/location['mount']
            if location['path'] != '.': path = path.joinpath(*PurePosixPath(location['path']).parts)
            info = path.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != group['metadata']['originalLinkCount']: raise ValueError('restored hardlink count differs')
            identities.add((info.st_dev,info.st_ino))
        if len(identities) != 1: raise ValueError('restored hardlink group was split')


def tree_check(recipe, trees, image_namespace=None):
    namespace = dict(image_namespace or {}); declared_links = set()
    for mount in recipe['mounts']:
        for key in list(namespace):
            if key == mount['target'] or key.startswith(mount['target']+'/'): del namespace[key]
        rows = trees[mount['name']]; seen = set(); by_path = {}
        for row in rows:
            rel = row['path']
            if rel != '.': relative(rel)
            if rel in seen: raise ValueError('duplicate tree path')
            seen.add(rel)
            by_path[rel] = row
            if type(row['mode']) != int or not 0 <= row['mode'] <= 0o7777 or type(row['mtimeNs']) != int: raise ValueError('invalid metadata')
            if row['kind'] == 'file':
                if not re.fullmatch('[0-9a-f]{64}', row['sha256']) or type(row['size']) != int or row['size'] < 0: raise ValueError('invalid file')
            elif row['kind'] == 'symlink':
                if not isinstance(row['target'], str) or not row['target'] or '\0' in row['target']: raise ValueError('invalid symlink')
            elif row['kind'] != 'directory': raise ValueError('unsupported entry')
            full = mount['target'] + ('' if rel == '.' else '/' + rel)
            namespace[full] = row
            if row['kind'] == 'symlink': declared_links.add(full)
        if '.' not in seen: raise ValueError('missing root')
        for row in rows:
            if row['path'] != '.':
                parent = posixpath.dirname(row['path']) or '.'
                if by_path.get(parent, {}).get('kind') != 'directory': raise ValueError('entry parent is not a directory')
    # Resolve only within the complete declared namespace; image fallback is not guessed.
    def resolve(path, visited):
        if path in visited: raise ValueError('symlink cycle')
        parts = PurePosixPath(path).parts
        for i in range(1, len(parts) + 1):
            prefix = str(PurePosixPath(*parts[:i])); row = namespace.get(prefix)
            if row and row['kind'] == 'symlink':
                target = posixpath.normpath(posixpath.join(posixpath.dirname(prefix), row['target'], *parts[i:]))
                return resolve(target, visited | {path})
        if path not in namespace: raise ValueError('symlink target outside declared mounts or missing: '+path)
        return path
    # Unused image links may intentionally target /proc or /dev supplied by the
    # container runtime. Only declared-mount links demand closure here.
    for path in declared_links: resolve(path, set())


def docker(command, seconds):
    result = subprocess.run(['docker'] + command, capture_output=True, text=True, timeout=seconds)
    if result.returncode: raise ValueError('docker command failed: ' + result.stderr)
    return result.stdout


def image_check(path, image):
    # Docker export is retained as opaque bytes; the daemon validates layers on load.
    import tarfile
    with tarfile.open(path) as archive:
        members = [m for m in archive.getmembers() if m.name == 'manifest.json']
        if len(members) != 1 or not members[0].isfile(): raise ValueError('invalid saved-image manifest')
        manifests = json.load(archive.extractfile(members[0]))
        if len(manifests) != 1: raise ValueError('one immutable image per export required')
        name = manifests[0]['Config']; relative(name)
        configs = [m for m in archive.getmembers() if m.name == name]
        if len(configs) != 1 or not configs[0].isfile(): raise ValueError('invalid saved-image config')
        if 'sha256:' + hashlib.sha256(archive.extractfile(configs[0]).read()).hexdigest() != image: raise ValueError('export image identity mismatch')
        namespace = {'/': dict(kind='directory')}
        for layer in manifests[0]['Layers']:
            relative(layer); matches = [m for m in archive.getmembers() if m.name == layer]
            if len(matches) != 1 or not matches[0].isfile(): raise ValueError('invalid image layer')
            entries = []; deletions = []; opaque = []
            with tarfile.open(fileobj=archive.extractfile(matches[0]), mode='r|*') as contents:
                for member in contents:
                    name = member.name.removeprefix('./').rstrip('/')
                    if name in {'', '.'}: continue
                    relative(name); full = '/'+name; basename = posixpath.basename(full)
                    if basename == '.wh..wh..opq': opaque.append(posixpath.dirname(full)); continue
                    if basename.startswith('.wh.'): deletions.append(posixpath.join(posixpath.dirname(full),basename[4:])); continue
                    if member.issym(): row = dict(kind='symlink', target=member.linkname)
                    # Index hardlinks as target references for namespace lookup
                    # only. The daemon loads original image bytes/alias semantics.
                    elif member.islnk(): row = dict(kind='symlink', target='/'+member.linkname.removeprefix('./'))
                    else: row = dict(kind='directory' if member.isdir() else 'file')
                    entries.append((full,row))
            for key in list(namespace):
                if any(key == p or key.startswith(p+'/') for p in deletions) or any(key.startswith(p+'/') for p in opaque): del namespace[key]
            namespace.update(entries)
        return namespace


def capture(recipe, output, image_export=None, seconds=300):
    recipe_check(recipe); output = Path(output).absolute()
    for m in recipe['mounts']:
        source = Path(m['path']).absolute()
        if source.is_symlink() or not source.exists() or output.resolve().is_relative_to(source.resolve()): raise ValueError('unsafe input/output root')
    output = fresh(output); started = time.monotonic(); costs = {}
    control(output, 'capture-started', sourceReproductionCertified=False)
    try:
        objects = output / 'objects'; objects.mkdir(); trees = {}
        for mount in recipe['mounts']: trees[mount['name']] = scan(mount['path'], objects)
        costs['declaredTreeCaptureSeconds'] = time.monotonic()-started
        image = output / 'image.tar'
        image_started = time.monotonic()
        if image_export is None: docker(['image', 'save', '--output', str(image), recipe['imageId']], seconds)
        else:
            image_export = Path(image_export)
            if image_export.is_symlink() or not image_export.is_file(): raise ValueError('regular retained image export required')
            try: os.link(image_export, image)
            except OSError: shutil.copyfile(image_export, image)
        costs['imageExportOrReuseSeconds'] = time.monotonic()-image_started
        checking_started = time.monotonic()
        tree_check(recipe, trees, image_check(image, recipe['imageId']))
        costs['initialImageNamespaceValidationSeconds'] = time.monotonic()-checking_started
        controllers = output/'controllers'; controllers.mkdir(); controller_hashes = {}
        for name in ['reproduction-bundle.py','provider-execution.py']:
            shutil.copyfile(Path(__file__).with_name(name), controllers/name); controller_hashes[name] = sha(controllers/name)
        manifest = dict(format=FORMAT, recipe=recipe, trees=trees, aliasGroups=alias_groups(trees), controllers=controller_hashes, image=dict(sha256=sha(image), bytes=image.stat().st_size), hardlinkPolicy=HARDLINK_POLICY, consumptionCertified=False, sourceReproductionCertified=False)
        write(output / 'manifest.json', manifest)
        checked = verify(output)
        control(output, 'capture-complete', seconds=time.monotonic()-started, costs=costs, manifestSha256=sha(output/'manifest.json'), **checked)
        return checked
    except BaseException as error:
        control(output, 'capture-failed', error=repr(error), costs=costs, seconds=time.monotonic()-started); raise


def verify(bundle):
    bundle = Path(bundle)
    if bundle.is_symlink() or not bundle.is_dir(): raise ValueError('aliased bundle')
    for path in (bundle/'manifest.json', bundle/'image.tar', bundle/'objects', bundle/'controllers'):
        if path.is_symlink() or path.resolve().parent != bundle.resolve(): raise ValueError('aliased bundle component')
    if not all((bundle/name).is_file() for name in ['manifest.json','image.tar']) or not all((bundle/name).is_dir() for name in ['objects','controllers']): raise ValueError('nonregular bundle component')
    manifest = json.loads((bundle/'manifest.json').read_text()); recipe_check(manifest['recipe'])
    if manifest['format'] != FORMAT or manifest['consumptionCertified'] is not False or manifest['sourceReproductionCertified'] is not False: raise ValueError('unsupported certification')
    if manifest['hardlinkPolicy'] not in {HARDLINK_POLICY,'MATERIALIZED_INDEPENDENT_FILES'}: raise ValueError('unsupported alias policy')
    groups = alias_groups(manifest['trees'])
    if manifest['hardlinkPolicy'] == HARDLINK_POLICY and groups != manifest.get('aliasGroups'): raise ValueError('alias-group inventory differs')
    if set(manifest['controllers']) != {'reproduction-bundle.py','provider-execution.py'}: raise ValueError('missing execution controllers')
    for name,digest in manifest['controllers'].items():
        path = bundle/'controllers'/name
        if path.is_symlink() or not path.is_file() or sha(path) != digest: raise ValueError('controller bytes differ')
    objects = {}
    for rows in manifest['trees'].values():
        for row in rows:
            if row['kind'] == 'file':
                obj = bundle/'objects'/row['sha256']
                if obj.is_symlink() or not obj.is_file() or obj.stat().st_size != row['size']: raise ValueError('missing/aliased object')
                if row['sha256'] not in objects and sha(obj) != row['sha256']: raise ValueError('corrupt object')
                objects[row['sha256']] = row['size']
    image = bundle/'image.tar'
    if image.stat().st_size != manifest['image']['bytes'] or sha(image) != manifest['image']['sha256']: raise ValueError('corrupt image export')
    tree_check(manifest['recipe'], manifest['trees'], image_check(image, manifest['recipe']['imageId']))
    return dict(state='DECLARED_BYTES_VERIFIED', objects=len(objects), uniqueFileBytes=sum(objects.values()), imageBytes=image.stat().st_size, hardlinkGroups=len(groups), aliasRestorationSupported=all(g['restorationSupported'] for g in groups), consumptionCertified=False, sourceReproductionCertified=False)


def restore(bundle, output):
    verify(bundle); bundle = Path(bundle); manifest = json.loads((bundle/'manifest.json').read_text())
    groups = alias_groups(manifest['trees'])
    if groups and manifest['hardlinkPolicy'] != HARDLINK_POLICY: raise ValueError('legacy bundle does not preserve alias semantics')
    if any(not g['restorationSupported'] for g in groups): raise ValueError('incomplete or overlapping hardlink groups cannot be restored with preserved alias semantics')
    linked = {}
    output = fresh(output); started = time.monotonic(); control(output, 'restore-started')
    try:
        for mount in manifest['recipe']['mounts']:
            root = output / mount['name']; rows = manifest['trees'][mount['name']]
            for row in sorted(rows, key=lambda r: (len(PurePosixPath(r['path']).parts), r['path'])):
                path = root if row['path'] == '.' else root.joinpath(*PurePosixPath(row['path']).parts)
                if row['kind'] == 'directory': path.mkdir()
                elif row['kind'] == 'file':
                    group = row.get('originalAliasGroup')
                    if group is not None and group in linked: os.link(linked[group],path)
                    else:
                        with (bundle/'objects'/row['sha256']).open('rb') as src, path.open('xb') as dst: shutil.copyfileobj(src, dst, 1 << 20)
                        if group is not None: linked[group] = path
                else: path.symlink_to(row['target'])
            for row in sorted(rows, key=lambda r: len(PurePosixPath(r['path']).parts), reverse=True):
                path = root if row['path'] == '.' else root.joinpath(*PurePosixPath(row['path']).parts)
                if row['kind'] != 'symlink': path.chmod(row['mode'])
                os.utime(path, ns=(row['mtimeNs'], row['mtimeNs']), follow_symlinks=False)
        check_restored_aliases(output, groups)
        control(output, 'restore-complete', seconds=time.monotonic()-started, manifestSha256=sha(bundle/'manifest.json'), hardlinkGroupsPreserved=len(groups))
        return dict(state='DECLARED_MOUNTS_RESTORED', seconds=time.monotonic()-started)
    except BaseException as error:
        control(output, 'restore-failed', error=repr(error), seconds=time.monotonic()-started); raise


def execute(bundle, restored, output, load_seconds=300):
    if os.name != 'posix': raise ValueError('Linux execution required')
    verify(bundle); bundle = Path(bundle); restored = Path(restored); manifest = json.loads((bundle/'manifest.json').read_text()); recipe = manifest['recipe']
    destination = Path(output).resolve()
    if destination.is_relative_to(bundle.resolve()) or destination.is_relative_to(restored.resolve()): raise ValueError('execution output must be outside retained/restored inputs')
    if sha(Path(__file__)) != manifest['controllers']['reproduction-bundle.py']: raise ValueError('execute using retained bundle controller version')
    groups = alias_groups(manifest['trees'])
    if groups and manifest['hardlinkPolicy'] != HARDLINK_POLICY: raise ValueError('legacy bundle does not preserve alias semantics')
    if restored.is_symlink() or not (restored/'state-restore-complete.json').is_file(): raise ValueError('completed restoration required')
    binding = json.loads((restored/'state-restore-complete.json').read_text())
    if binding['manifestSha256'] != sha(bundle/'manifest.json'): raise ValueError('restoration belongs to different manifest')
    for m in recipe['mounts']:
        actual = scan(restored/m['name']); expected = manifest['trees'][m['name']]
        normalize = lambda rows: [{k:v for k,v in r.items() if k not in {'originalAliasGroup','originalAliasMember'}} for r in rows]
        if normalize(actual) != normalize(expected): raise ValueError('restored tree metadata/bytes changed')
    check_restored_aliases(restored, groups)
    output = fresh(output); started = time.monotonic(); receipt = dict(state='FAILED', stages=[], consumptionCertified=False, sourceReproductionCertified=False, manifestSha256=sha(bundle/'manifest.json'))
    try:
        before = time.monotonic(); docker(['image', 'load', '--input', str(bundle/'image.tar')], load_seconds)
        if docker(['image', 'inspect', '--format', '{{.Id}}', recipe['imageId']], 15).strip() != recipe['imageId']: raise ValueError('loaded image identity differs')
        receipt['imagePreparationSeconds'] = time.monotonic()-before
        receipt['host'] = dict(uname=list(os.uname()), dockerVersion=docker(['version','--format','{{json .}}'],15))
        base = ['docker','run','--rm','--read-only','--network=none','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,nodev','--memory',recipe['resources']['memory'],'--pids-limit',str(recipe['resources']['pidsLimit']),'--user',recipe['resources']['user'],'--workdir',recipe['workingDirectory']]
        for m in recipe['mounts']:
            host = str((restored/m['name']).absolute())
            if ',' in host or '\n' in host: raise ValueError('unsupported mount host path')
            base += ['--mount','type=bind,src='+host+',dst='+m['target']+',readonly']
        base += ['--mount','type=bind,src='+str(output)+',dst='+recipe['outputTarget']]
        for key,value in sorted(recipe['environment'].items()): base += ['--env',key+'='+value]
        path = bundle/'controllers/provider-execution.py'; spec = importlib.util.spec_from_file_location('reproduction_executor',path); executor = importlib.util.module_from_spec(spec); spec.loader.exec_module(executor)
        receipt['executorSha256'] = sha(path)
        for stage in recipe['stages']:
            row = executor.run_stage(base, recipe['imageId'], stage['argv'], output/(stage['name']+'.log'), stage['seconds'])
            receipt['stages'].append(dict(name=stage['name'], **row))
            if row['exit'] not in stage['acceptedExits']: raise ValueError('stage failed: '+stage['name'])
        receipt['state'] = 'DECLARED_RECIPE_EXECUTED'
    except BaseException as error:
        receipt['error'] = repr(error); raise
    finally:
        receipt['seconds'] = time.monotonic()-started; write(output/'execution.json', receipt)
    return receipt


def main():
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('action',choices=['capture','verify','restore','execute']); p.add_argument('--bundle',type=Path,required=True); p.add_argument('--recipe',type=Path); p.add_argument('--output',type=Path); p.add_argument('--restored',type=Path); p.add_argument('--image-export',type=Path)
    a = p.parse_args()
    if a.action == 'capture': result = capture(json.loads(a.recipe.read_text()),a.bundle,a.image_export)
    elif a.action == 'verify': result = verify(a.bundle)
    elif a.action == 'restore': result = restore(a.bundle,a.output)
    else: result = execute(a.bundle,a.restored,a.output)
    print(json.dumps(result)); return 0


if __name__ == '__main__': raise SystemExit(main())
