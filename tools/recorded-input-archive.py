#!/usr/bin/env python3
"""Retain recorded input bytes, independently of formal replay or source reproduction.

Only explicitly mapped roots may be read. Receipt digests authorize byte identity,
not claims about build roles, complete consumption, or a reproducible environment.
"""
from pathlib import Path,PurePosixPath
import argparse,hashlib,json,os,re,tempfile,xml.etree.ElementTree as ET

FORMAT='recorded-input-bytes-v1'
HASH=re.compile(r'[0-9a-f]{64}')

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1<<20),b''):h.update(block)
    return h.hexdigest()

def relative(value):
    path=PurePosixPath(value)
    if not value or '\\' in value or '\0' in value or path.is_absolute() or '..' in path.parts or str(path)!=value:
        raise ValueError('unsafe relative input path')
    return path

def inventory(data):
    root=ET.fromstring(data);version=root.get('schemaVersion')
    if version not in {'14','15','16','17'} or root.tag!='{urn:metamodel-conformance:observation:'+version+'}ObservationSet':
        raise ValueError('unsupported observation schema')
    files=[];unavailable=[]
    def visit(node,location,context=''):
        context=node.get('contextId',context)
        if node.tag in {'consumedInputs','backingFiles'}:
            knowledge=node.get('knowledge')
            if knowledge not in {'KNOWN','UNKNOWN','UNSUPPORTED','AMBIGUOUS'}:raise ValueError('invalid input receipt knowledge')
            if knowledge!='KNOWN':
                if len(node):raise ValueError('unavailable receipt contains positive input values')
                unavailable.append(dict(receipt=location,contextId=context,knowledge=knowledge,reason=node.get('reason','')))
        if 'path' in node.attrib and 'sha256' in node.attrib:
            path=node.get('path');digest=node.get('sha256')
            if not HASH.fullmatch(digest):raise ValueError('invalid recorded input hash')
            if not path or '\0' in path:raise ValueError('invalid recorded input path')
            if not PurePosixPath(path).is_absolute():relative(path)
            files.append(dict(receipt=location,contextId=context,path=path,sha256=digest))
        for i,child in enumerate(node):visit(child,location+'/'+child.tag+'['+str(i)+']',context)
    visit(root,'ObservationSet')
    return version,files,unavailable

def mappings(source_root,rows):
    result=[];seen=set()
    if source_root is not None:result.append(dict(label='source',logicalRoot=None,physicalRoot=str(Path(source_root).resolve(strict=True))))
    for i,(logical,physical) in enumerate(rows):
        path=PurePosixPath(logical)
        if not path.is_absolute() or '..' in path.parts or str(path)!=logical or '\\' in logical or logical in seen:
            raise ValueError('invalid or duplicate logical mapping root')
        seen.add(logical)
        result.append(dict(label='map-%04d'%i,logicalRoot=logical,physicalRoot=str(Path(physical).resolve(strict=True))))
    if any(not Path(m['physicalRoot']).is_dir() for m in result):raise ValueError('mapping root must be a directory')
    return result

def locate(path,maps):
    logical=PurePosixPath(path)
    if not logical.is_absolute():
        relative(path)
        match=next((m for m in maps if m['label']=='source'),None)
        return (match,path) if match else (None,None)
    matches=[m for m in maps if m['logicalRoot'] is not None and logical.is_relative_to(PurePosixPath(m['logicalRoot']))]
    if not matches:return None,None
    match=max(matches,key=lambda m:len(PurePosixPath(m['logicalRoot']).parts))
    return match,logical.relative_to(PurePosixPath(match['logicalRoot'])).as_posix()

def recorded_state(files,unavailable):
    return 'RECORDED_FILE_BYTES_RETAINED' if files and not unavailable and all(f['state']=='RETAINED' for f in files) else 'PARTIAL'

def retain(observation,output,source_root,rows,max_bytes=None):
    if max_bytes is not None and (type(max_bytes)!=int or max_bytes<0):raise ValueError('invalid operational byte budget')
    data=Path(observation).read_bytes();version,files,unavailable=inventory(data);maps=mappings(source_root,rows)
    output=Path(output).absolute()
    if output.exists() or output.is_symlink():raise FileExistsError(output)
    if any(output.resolve().is_relative_to(Path(m['physicalRoot'])) for m in maps):raise ValueError('archive output must be outside mapped input roots')
    output.mkdir(parents=True);objects=output/'objects';objects.mkdir();(output/'observation.xmi').write_bytes(data)
    total=0;retained=set()
    for row in files:
        row.update(state='UNAVAILABLE',reason='UNMAPPED',mapping=None,relativePath=None)
        mapping,rel=locate(row['path'],maps)
        if mapping is None:continue
        row.update(mapping=mapping['label'],relativePath=rel)
        path=Path(mapping['physicalRoot']).joinpath(*PurePosixPath(rel).parts)
        temporary=None
        try:
            if not path.resolve(strict=True).is_relative_to(Path(mapping['physicalRoot'])) or path.is_symlink():
                row['reason']='UNSAFE_PATH';continue
            if not path.is_file():row['reason']='NOT_A_REGULAR_FILE';continue
            # Every occurrence is checked against its actual mapped bytes. Shared
            # content objects deduplicate storage without merging contextual rows.
            fd,name=tempfile.mkstemp(prefix='.input-',dir=objects);temporary=Path(name);h=hashlib.sha256();size=0
            with os.fdopen(fd,'wb') as target,path.open('rb') as source:
                for block in iter(lambda:source.read(1<<20),b''):
                    size+=len(block)
                    if max_bytes is not None and row['sha256'] not in retained and total+size>max_bytes:
                        row['reason']='BYTE_BUDGET_EXCEEDED';break
                    h.update(block);target.write(block)
                else:
                    if h.hexdigest()!=row['sha256']:row['reason']='HASH_MISMATCH'
                    else:
                        destination=objects/row['sha256']
                        if destination.exists():
                            if sha(destination)!=row['sha256']:raise ValueError('existing object hash mismatch')
                        else:temporary.replace(destination);total+=size;retained.add(row['sha256'])
                        row.update(state='RETAINED',reason='',bytes=size)
        except FileNotFoundError:row['reason']='MISSING'
        except OSError as error:row.update(reason='IO_ERROR',error=type(error).__name__)
        finally:
            if temporary is not None and temporary.exists():temporary.unlink()
    receipt=dict(format=FORMAT,state=recorded_state(files,unavailable),observationSha256=hashlib.sha256(data).hexdigest(),schemaVersion=version,sourceReproductionCertified=False,consumptionCertified=False,mappings=maps,files=files,unavailable=unavailable,objects=len(retained),retainedBytes=total,maxBytes=max_bytes,
        limitations='Recorded byte identities only; synthetic units, unmapped paths, opaque receipts and unreported inputs may remain. No formal verdict, build-role or complete environment claim.')
    (output/'manifest.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
    return receipt

def read_json(path):
    def unique(pairs):
        result={}
        for k,v in pairs:
            if k in result:raise ValueError('duplicate manifest key')
            result[k]=v
        return result
    return json.loads(path.read_text(encoding='utf-8'),object_pairs_hook=unique)

def verify(folder):
    folder=Path(folder)
    if folder.is_symlink() or not folder.is_dir() or (folder/'objects').is_symlink() or not (folder/'objects').is_dir() or any((folder/name).is_symlink() or not (folder/name).is_file() for name in ('manifest.json','observation.xmi')):
        raise ValueError('archive layout must contain its own regular metadata and object directory')
    r=read_json(folder/'manifest.json')
    if r.get('format')!=FORMAT or r.get('sourceReproductionCertified') is not False or r.get('consumptionCertified') is not False:
        raise ValueError('unsupported archive format or certification')
    if sha(folder/'observation.xmi')!=r['observationSha256']:raise ValueError('observation hash mismatch')
    version,files,unknown=inventory((folder/'observation.xmi').read_bytes())
    if version!=r['schemaVersion'] or unknown!=r['unavailable'] or len(files)!=len(r['files']):raise ValueError('receipt inventory mismatch')
    maps=r['mappings'];labels={m['label'] for m in maps}
    if len(labels)!=len(maps):raise ValueError('duplicate archive mapping')
    objects=set();total=0
    for expected,row in zip(files,r['files']):
        if any(row[k]!=v for k,v in expected.items()):raise ValueError('recorded input changed')
        mapping,rel=locate(row['path'],maps)
        if row['mapping']!=(mapping['label'] if mapping else None) or row['relativePath']!=rel:raise ValueError('input mapping mismatch')
        if row['state']=='RETAINED':
            if row['reason'] or row['mapping'] not in labels:raise ValueError('invalid retained record')
            path=folder/'objects'/row['sha256']
            if path.is_symlink() or not path.is_file() or sha(path)!=row['sha256']:raise ValueError('object hash mismatch')
            if path.stat().st_size!=row['bytes']:raise ValueError('object size mismatch')
            if row['sha256'] not in objects:objects.add(row['sha256']);total+=row['bytes']
        elif row['state']!='UNAVAILABLE' or not row['reason']:raise ValueError('invalid unavailable record')
    if {p.name for p in (folder/'objects').iterdir()}!=objects:raise ValueError('unexpected archive objects')
    if r['objects']!=len(objects) or r['retainedBytes']!=total:raise ValueError('object ledger mismatch')
    if r['maxBytes'] is not None and (type(r['maxBytes'])!=int or r['maxBytes']<0 or total>r['maxBytes']):raise ValueError('invalid operational byte budget ledger')
    if r['state']!=recorded_state(r['files'],r['unavailable']):raise ValueError('archive state mismatch')
    return dict(valid=True,state=r['state'],objects=len(objects),sourceReproductionCertified=False)

def restore(folder,label,output,allow_partial=False):
    folder=Path(folder);verify(folder);r=read_json(folder/'manifest.json')
    if label not in {m['label'] for m in r['mappings']}:raise ValueError('unknown mapping label')
    selected=[f for f in r['files'] if f['mapping']==label]
    omitted=[f for f in selected if f['state']!='RETAINED']
    if not selected or omitted and not allow_partial:raise ValueError('requested mapping has unavailable recorded bytes')
    selected=[f for f in selected if f['state']=='RETAINED']
    if not selected:raise ValueError('requested mapping has no retained bytes')
    files={}
    for row in selected:
        relative(row['relativePath']);old=files.setdefault(row['relativePath'],row['sha256'])
        if old!=row['sha256']:raise ValueError('conflicting contextual bytes cannot form one restored tree')
    output=Path(output)
    if output.exists() or output.is_symlink():raise FileExistsError(output)
    restore_receipt=output.with_name(output.name+'.restore.json')
    if restore_receipt.exists() or restore_receipt.is_symlink():raise FileExistsError(restore_receipt)
    if output.resolve().is_relative_to(folder.resolve()):raise ValueError('restore output must be outside archive')
    output.mkdir(parents=True)
    for name,digest in files.items():
        target=output.joinpath(*PurePosixPath(name).parts);target.parent.mkdir(parents=True,exist_ok=True)
        with (folder/'objects'/digest).open('rb') as source,target.open('xb') as destination:
            for block in iter(lambda:source.read(1<<20),b''):destination.write(block)
        if sha(target)!=digest:raise ValueError('restored object hash mismatch')
    receipt=dict(state='PARTIAL_RECORDED_SUBTREE_RESTORED' if omitted else 'RECORDED_SUBTREE_RESTORED',files=len(files),omitted=omitted,sourceReproductionCertified=False)
    # Keep this next to the restored tree; adding a metadata file to the source
    # tree itself would change the re-observed input population.
    with restore_receipt.open('x',encoding='utf-8') as stream:stream.write(json.dumps(receipt,indent=2)+'\n')
    return receipt

def main():
    parser=argparse.ArgumentParser(description=__doc__);commands=parser.add_subparsers(dest='command',required=True)
    capture=commands.add_parser('archive');capture.add_argument('--observation',type=Path,required=True);capture.add_argument('--output',type=Path,required=True);capture.add_argument('--source-root',type=Path);capture.add_argument('--map',action='append',default=[]);capture.add_argument('--max-bytes',type=int)
    check=commands.add_parser('verify');check.add_argument('--archive',type=Path,required=True)
    restore_parser=commands.add_parser('restore');restore_parser.add_argument('--archive',type=Path,required=True);restore_parser.add_argument('--mapping',required=True);restore_parser.add_argument('--output',type=Path,required=True);restore_parser.add_argument('--allow-partial',action='store_true',help='restore available recorded bytes and report omissions; never certify a complete source tree')
    args=parser.parse_args()
    try:
        if args.command=='archive':
            rows=[]
            for item in args.map:
                logical,separator,physical=item.partition('=')
                if not separator or not physical:raise ValueError('mapping must be logical-root=physical-root')
                rows.append((logical,physical))
            result=retain(args.observation,args.output,args.source_root,rows,args.max_bytes)
            print(json.dumps({k:v for k,v in result.items() if k not in {'files','mappings','unavailable'}}))
            return 0 if result['state']=='RECORDED_FILE_BYTES_RETAINED' else 3
        result=verify(args.archive) if args.command=='verify' else restore(args.archive,args.mapping,args.output,args.allow_partial)
        print(json.dumps(result));return 3 if result.get('omitted') else 0
    except (ValueError,OSError,ET.ParseError,KeyError,TypeError) as error:
        print(json.dumps(dict(state='ARCHIVE_ERROR',error=str(error))));return 3

if __name__=='__main__':raise SystemExit(main())
