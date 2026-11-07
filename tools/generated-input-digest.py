#!/usr/bin/env python3
"""Digest a generated file/tree by relative paths and physical file contents.

Independent of checkout location and enumeration order. Unsupported links and
special files fail rather than disappear from the generator determinism check.
This check does not certify arbitrary generator execution or source ownership.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat


def inventory(root):
    mode=root.lstat().st_mode
    if stat.S_ISREG(mode):return [(root.name,root)]
    if not stat.S_ISDIR(mode):raise ValueError('generated root must be a physical file or directory')
    files=[]
    def failed(error):raise error
    for directory,dirs,names in os.walk(root,followlinks=False,onerror=failed):
        for name in dirs:
            path=Path(directory)/name
            if not stat.S_ISDIR(path.lstat().st_mode):raise ValueError('unsupported generated directory: '+str(path))
        for name in names:
            path=Path(directory)/name
            if not stat.S_ISREG(path.lstat().st_mode):raise ValueError('unsupported generated file: '+str(path))
            files.append((path.relative_to(root).as_posix(),path))
    return sorted(files)


def fingerprint(metadata):
    return (metadata.st_dev,metadata.st_ino,metadata.st_mode,metadata.st_size,metadata.st_mtime_ns,metadata.st_ctime_ns)


def generated_digest(root):
    files=inventory(root);entries=[]
    for relative,path in files:
        before=path.lstat();digest=hashlib.sha256()
        with path.open('rb') as stream:
            if fingerprint(before)!=fingerprint(os.fstat(stream.fileno())):
                raise ValueError('generated input changed before reading: '+str(path))
            for block in iter(lambda:stream.read(1<<20),b''):digest.update(block)
            if fingerprint(before)!=fingerprint(os.fstat(stream.fileno())):
                raise ValueError('generated input changed while reading: '+str(path))
        if fingerprint(before)!=fingerprint(path.lstat()):raise ValueError('generated input changed after reading: '+str(path))
        entries.append([relative,digest.hexdigest()])
    if files!=inventory(root):raise ValueError('generated tree population changed while reading')
    encoded=json.dumps(['generated-relative-inputs-v1',entries],ensure_ascii=True,separators=(',',':')).encode('ascii')
    return hashlib.sha256(encoded).hexdigest()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('root',type=Path)
    args=parser.parse_args()
    try:print(generated_digest(args.root))
    except (OSError,ValueError) as failure:parser.exit(1,'generated input digest failed: '+str(failure)+'\n')
