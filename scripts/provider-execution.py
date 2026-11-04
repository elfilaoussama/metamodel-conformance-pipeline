"""Bounded Docker execution. Lifecycle facts never grant semantic capabilities."""
import os
import re
import signal
import subprocess
import time
import uuid


def _run_process(command,log,seconds):
    before=time.monotonic()
    with log.open('x') as stream:
        if seconds<=0:
            stream.write('Deadline expired before process start\n')
            return dict(command=command,exit=124,seconds=0.0,timedOut=True)
        process=subprocess.Popen(command,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
        try:code=process.wait(timeout=seconds)
        except subprocess.TimeoutExpired:
            if process.poll() is None:
                try:os.killpg(process.pid,signal.SIGKILL)
                except ProcessLookupError:pass
            process.wait()
            return dict(command=command,exit=124,seconds=round(time.monotonic()-before,3),timedOut=True)
        except BaseException:
            if process.poll() is None:
                try:os.killpg(process.pid,signal.SIGKILL)
                except ProcessLookupError:pass
            process.wait()
            raise
    return dict(command=command,exit=code,seconds=round(time.monotonic()-before,3))


def _remove_container(name,seconds=15):
    """Confirm absence after removal; never interpret a daemon error as absence."""
    before=time.monotonic();deadline=before+seconds;attempts=[]
    command=['docker','rm','--force',name]
    try:
        removed=subprocess.run(command,capture_output=True,text=True,timeout=max(.001,deadline-time.monotonic()))
        attempts.append(dict(command=command,exit=removed.returncode,stdout=removed.stdout,stderr=removed.stderr))
    except (OSError,subprocess.SubprocessError) as failure:
        attempts.append(dict(command=command,error=repr(failure)))
    while time.monotonic()<deadline:
        query=['docker','container','ls','--all','--filter','name=^/'+name+'$','--format','{{.ID}}']
        try:
            result=subprocess.run(query,capture_output=True,text=True,timeout=max(.001,deadline-time.monotonic()))
            attempts.append(dict(command=query,exit=result.returncode,stdout=result.stdout,stderr=result.stderr))
            if result.returncode!=0:break
            if not result.stdout.strip():
                return dict(status='ABSENT',name=name,seconds=round(time.monotonic()-before,3),attempts=attempts)
        except (OSError,subprocess.SubprocessError) as failure:
            attempts.append(dict(command=query,error=repr(failure)));break
        time.sleep(min(.1,max(0,deadline-time.monotonic())))
    return dict(status='UNCONFIRMED',name=name,seconds=round(time.monotonic()-before,3),attempts=attempts)


def run_stage(base,image,arguments,log,seconds,*,container_name=None):
    name=container_name or 'conformance-provider-'+uuid.uuid4().hex
    if not re.fullmatch(r'conformance-provider-[0-9a-f]{32}',name) or '--name' in base:
        raise ValueError('unique owned container name required')
    before=time.monotonic()
    try:result=_run_process(base+['--name',name,image]+arguments,log,seconds)
    except BaseException:
        _remove_container(name)
        raise
    if result.get('timedOut'):
        # --rm may already be removing a completed container. Preserve124 either way.
        result['cleanup']=_remove_container(name)
    result['seconds']=round(time.monotonic()-before,3)
    return result


class ContainerSession:
    """One owned immutable-root container; each stage starts a fresh process."""
    def __init__(self,base,image,lifetime_seconds):
        if base[:2]!=['docker','run']:raise ValueError('expected Docker run execution plan')
        self.name='conformance-provider-'+uuid.uuid4().hex
        self.command=base[:2]+['-d']+[item for item in base[2:] if item!='--rm']+[
            '--name',self.name,image,'/bin/sleep',str(max(1,int(lifetime_seconds)))]
        self.started=False

    def start(self,log,seconds):
        result=_run_process(self.command,log,seconds)
        self.started=result['exit']==0
        result['name']=self.name
        return result

    def run(self,arguments,log,seconds):
        if not self.started:raise ValueError('container session has not started')
        return _run_process(['docker','exec',self.name]+arguments,log,seconds)

    def close(self,seconds=15):
        return _remove_container(self.name,seconds)
