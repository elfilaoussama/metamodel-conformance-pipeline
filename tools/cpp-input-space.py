"""Explicit retained trees in their original compiler-visible coordinates.

Capture labels identify storage/layout only, never generated provenance or roles.
The manifest supplies inventories; a caller supplies the existing tree digester.
"""
from pathlib import Path
import hashlib,json,posixpath,re

FORMAT='cpp-captured-input-space-v1'

def unique(pairs):
    value={}
    for key,item in pairs:
        if key in value:raise ValueError('duplicate captured-space field')
        value[key]=item
    return value

def coordinate(value):
    if not isinstance(value,str) or not value.startswith('/') or value.startswith('//') or posixpath.normpath(value)!=value or any(c in value for c in '\0\t\r\n\\'):
        raise ValueError('canonical absolute captured coordinate required')
    return value

def below(path,root):return path==root or path.startswith(root.rstrip('/')+'/')

class CapturedSpace:
    def __init__(self,spec,digest):
        if not isinstance(spec,dict) or set(spec)!={'format','observationRoot','roots'} or spec['format']!=FORMAT:
            raise ValueError('unsupported captured-space specification')
        self.observation_root=coordinate(spec['observationRoot'])
        if not isinstance(spec['roots'],list) or not spec['roots']:raise ValueError('captured roots required')
        self.roots=[];self.physical={};ids=set()
        for row in spec['roots']:
            if not isinstance(row,dict) or set(row)!={'id','path','kind','retainedPath'}:raise ValueError('exact retained root fields required')
            identifier=row['id'];logical=coordinate(row['path'])
            if not isinstance(identifier,str) or not re.fullmatch('[A-Za-z][A-Za-z0-9_-]*',identifier) or identifier in ids or row['kind'] not in ('SOURCE','BUILD','DEPENDENCY'):
                raise ValueError('invalid captured root identity/kind')
            if not below(logical,self.observation_root) or any(below(logical,other['path']) or below(other['path'],logical) for other in self.roots):
                raise ValueError('outside or overlapping captured root')
            physical=Path(coordinate(row['retainedPath']))
            if not physical.is_dir() or physical.is_symlink() or physical.resolve(strict=True)!=physical:
                raise ValueError('retained root unavailable or aliased')
            if any(physical.is_relative_to(other) or other.is_relative_to(physical) for other in self.physical.values()):
                raise ValueError('overlapping retained materializations')
            identity=digest(physical)
            if not re.fullmatch('[0-9a-f]{64}',identity):raise ValueError('invalid captured tree digest')
            self.roots.append(dict(id=identifier,path=logical,kind=row['kind'],treeSha256=identity))
            self.physical[identifier]=physical;ids.add(identifier)
        self.value=dict(format=FORMAT,observationRoot=self.observation_root,roots=self.roots)
        text='CPP_INPUT_SPACE_V1\0'+self.observation_root+'\n'
        for row in sorted(self.roots,key=lambda r:r['id']):
            text+='\0'.join((row['id'],row['path'],row['kind'],row['treeSha256']))+'\n'
        self.binding_sha256=hashlib.sha256(text.encode('utf-8')).hexdigest()

    @classmethod
    def read(cls,path,digest):
        if path.is_symlink() or not path.is_file() or path.stat().st_size>1024*1024:
            raise ValueError('regular bounded captured-space specification required')
        return cls(json.loads(path.read_text(encoding='utf-8'),object_pairs_hook=unique),digest)

    def _root(self,absolute):
        matches=[row for row in self.roots if below(absolute,row['path'])]
        if len(matches)!=1:raise ValueError('undeclared compiler-visible input root: '+absolute)
        return matches[0]

    def relative(self,value,directory):
        absolute=posixpath.normpath(value if value.startswith('/') else posixpath.join(directory,value))
        coordinate(absolute);row=self._root(absolute)
        physical=self.physical[row['id']]/posixpath.relpath(absolute,row['path'])
        if not physical.exists() or not physical.resolve(strict=True).is_relative_to(self.physical[row['id']]):
            raise ValueError('missing or escaping captured input: '+value)
        return posixpath.relpath(absolute,self.observation_root)

    def materialized(self,relative):
        if not isinstance(relative,str) or relative.startswith('/') or relative in ('','.') or '..' in relative.split('/') or posixpath.normpath(relative)!=relative:
            raise ValueError('canonical observation-relative input required')
        absolute=posixpath.join(self.observation_root,relative);row=self._root(absolute)
        return self.physical[row['id']]/posixpath.relpath(absolute,row['path'])

    def validate_model(self,model,logical_source,physical_source):
        if model is None:raise ValueError('captured roots require configured build facts')
        paths=model.get('codemodel',{}).get('paths',{})
        for kind,key in (('SOURCE','source'),('BUILD','build')):
            if not any(row['kind']==kind and row['path']==paths.get(key) for row in self.roots):
                raise ValueError('configured '+kind+' root differs from captured space')
        source=self._root(logical_source)
        if source['kind']!='SOURCE' or source['path']!=logical_source or self.physical[source['id']]!=physical_source.resolve(strict=True):
            raise ValueError('source transport differs from captured SOURCE root')
