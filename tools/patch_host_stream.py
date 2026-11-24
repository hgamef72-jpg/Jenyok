#!/usr/bin/env python3
"""Patch the recovered Jenyok Host capture pacing with strict classfile checks."""
from __future__ import annotations
import io, pathlib, struct, sys, zipfile

APP_JAR = "app/saturn-pc-remote.jar"
CLASS_PATH = "com/saturn/remote/HostApp.class"
OLD_MS = 120
NEW_MS = 33

class Reader:
    def __init__(self, data: bytes): self.data, self.p = data, 0
    def u1(self): v=self.data[self.p]; self.p+=1; return v
    def u2(self): v=struct.unpack_from('>H',self.data,self.p)[0]; self.p+=2; return v
    def u4(self): v=struct.unpack_from('>I',self.data,self.p)[0]; self.p+=4; return v
    def take(self,n): v=self.data[self.p:self.p+n]; self.p+=n; return v

def patch_host_app_class(data: bytes) -> bytes:
    r=Reader(data)
    if r.u4()!=0xCAFEBABE: raise ValueError('HostApp.class is not a JVM class file')
    r.u2(); r.u2(); cp_count=r.u2()
    cp=[None]*cp_count; long_offsets=[]
    i=1
    while i<cp_count:
        tag=r.u1(); start=r.p
        if tag==1:
            n=r.u2(); cp[i]=('utf8',r.take(n).decode('utf-8','replace'))
        elif tag in (3,4): cp[i]=(tag,r.take(4))
        elif tag in (5,6):
            raw=r.take(8); value=struct.unpack('>q' if tag==5 else '>d',raw)[0]
            cp[i]=(tag,value)
            if tag==5 and value==OLD_MS: long_offsets.append((i,start,raw))
            i+=1
        elif tag in (7,8,16,19,20): cp[i]=(tag,r.u2())
        elif tag in (9,10,11,12,17,18): cp[i]=(tag,r.u2(),r.u2())
        elif tag==15: cp[i]=(tag,r.u1(),r.u2())
        else: raise ValueError(f'Unsupported constant-pool tag {tag} at #{i}')
        i+=1
    if len(long_offsets)!=1: raise ValueError(f'Expected one long {OLD_MS} constant, found {len(long_offsets)}')
    def utf(idx):
        v=cp[idx]
        if not v or v[0]!='utf8': raise ValueError(f'CP #{idx} is not UTF8')
        return v[1]
    def class_name(idx):
        v=cp[idx]
        return utf(v[1]) if v and v[0]==7 else None
    r.u2(); this_class=r.u2(); r.u2()
    if class_name(this_class)!='com/saturn/remote/HostApp': raise ValueError('Unexpected class identity')
    for _ in range(r.u2()): r.u2()
    def skip_members(is_method):
        found=[]
        for _ in range(r.u2()):
            r.u2(); name_idx=r.u2(); desc_idx=r.u2(); attr_count=r.u2()
            name,desc=utf(name_idx),utf(desc_idx)
            for _ in range(attr_count):
                attr_name=utf(r.u2()); length=r.u4(); payload_offset=r.p; payload=r.take(length)
                if is_method and name=='runHostSession' and desc=='(Lcom/saturn/remote/HostState;Ljava/lang/String;)V' and attr_name=='Code':
                    cr=Reader(payload); cr.u2(); cr.u2(); code_len=cr.u4(); code=cr.take(code_len)
                    found.append((payload_offset+8,code))
        return found
    skip_members(False)
    target=skip_members(True)
    if len(target)!=1: raise ValueError(f'Expected runHostSession Code attribute once, found {len(target)}')
    cp_idx, cp_off, _=long_offsets[0]
    sleep_refs=[]
    for idx,v in enumerate(cp):
        if v and v[0]==10:
            cls=class_name(v[1]); nt=cp[v[2]]
            if cls=='java/lang/Thread' and nt and nt[0]==12 and utf(nt[1])=='sleep' and utf(nt[2])=='(J)V': sleep_refs.append(idx)
    if len(sleep_refs)!=1: raise ValueError(f'Expected one Thread.sleep(J)V ref, found {sleep_refs}')
    needle=b'\x14'+struct.pack('>H',cp_idx)+b'\xb8'+struct.pack('>H',sleep_refs[0])
    if target[0][1].count(needle)!=1: raise ValueError('Expected capture pacing sleep instruction not found exactly once')
    patched=bytearray(data)
    struct.pack_into('>q',patched,cp_off,NEW_MS)
    return bytes(patched)

def patch_jar(payload: bytes) -> bytes:
    src=zipfile.ZipFile(io.BytesIO(payload),'r'); out=io.BytesIO()
    with src, zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as dst:
        seen=False
        for info in src.infolist():
            data=src.read(info.filename)
            if info.filename==CLASS_PATH:
                data=patch_host_app_class(data); seen=True
            dst.writestr(info,data)
    if not seen: raise ValueError(f'Missing {CLASS_PATH} in app JAR')
    result=out.getvalue()
    with zipfile.ZipFile(io.BytesIO(result)) as check:
        if check.testzip() is not None: raise ValueError('Patched app JAR failed ZIP integrity')
        patched_class=check.read(CLASS_PATH)
        global OLD_MS
        previous=OLD_MS; OLD_MS=NEW_MS
        try: patch_host_app_class(patched_class)
        finally: OLD_MS=previous
    return result

def patch_package(source: pathlib.Path, destination: pathlib.Path):
    with zipfile.ZipFile(source,'r') as zin, zipfile.ZipFile(destination,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as zout:
        seen=False
        for info in zin.infolist():
            payload=zin.read(info.filename)
            if info.filename==APP_JAR:
                payload=patch_jar(payload); seen=True
            zout.writestr(info,payload)
    if not seen: raise ValueError(f'Missing {APP_JAR} in {source.name}')
    with zipfile.ZipFile(destination) as check:
        bad=check.testzip()
        if bad: raise ValueError(f'Outer archive integrity failure: {bad}')
        with zipfile.ZipFile(io.BytesIO(check.read(APP_JAR))) as jar:
            if jar.testzip(): raise ValueError('Nested JAR integrity failure')
    print(f'PATCHED {destination} ({destination.stat().st_size} bytes)')

if __name__=='__main__':
    if len(sys.argv)!=3: raise SystemExit('usage: patch_host_stream.py input.zip output.zip')
    patch_package(pathlib.Path(sys.argv[1]),pathlib.Path(sys.argv[2]))
