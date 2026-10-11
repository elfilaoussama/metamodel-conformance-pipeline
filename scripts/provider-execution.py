"""Shared bounded Docker stage execution; no language or semantic decisions."""
import os
import signal
import subprocess
import time
import uuid

def run_stage(base,image,arguments,log,seconds):
    name='conformance-provider-'+uuid.uuid4().hex
    command=base+['--name',name,image]+arguments
    before=time.monotonic()
    with log.open('x') as stream:
        process=subprocess.Popen(command,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
        try:code=process.wait(timeout=seconds)
        except subprocess.TimeoutExpired:
            # A Docker client exit does not terminate its container.
            try:
                subprocess.run(['docker','rm','--force',name],stdout=stream,stderr=subprocess.STDOUT,timeout=15,check=True)
            finally:
                if process.poll() is None:
                    try:os.killpg(process.pid,signal.SIGKILL)
                    except ProcessLookupError:pass
                process.wait()
            code=124
    return dict(command=command,exit=code,seconds=round(time.monotonic()-before,3))
