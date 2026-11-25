"""A stand-in for the car's Android 4.2 adbd over TCP, strict about what the old one checks:
header magic, byte-sum checksum, 4 KB payloads, the RSA token signature and the public key struct.
Connection n plays scenario n: 1 new key (pubkey + prompt), 2 known key (signature) and a signature
clash on the dashboard, 3 uninstall (☆ points at '내 앱': read only, never changed), 4 a navi that is
not jsn_kr, 5 jsn_kr on API 19 (no install), 6 install on another build number (warned), 7 the same install again (pm path + RECV
return what is installed), 8 diagnostics (log lines with a MAC, a phone number, a mail address, the
serial), 9 install from a cached APK. Installed packages carry over between connections.
Adapted from sung9155/i30-pd-dashboard (i30pd_port/installer/android/test, MIT).
usage: fake_adbd.py PORT LOG SERVER_DIR"""
import os, socket, struct, sys, base64

CNXN, AUTH, OPEN, OKAY, CLSE, WRTE = 0x4e584e43, 0x48545541, 0x4e45504f, 0x59414b4f, 0x45534c43, 0x45545257
SHA1_INFO = bytes([0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14])
port, logpath, server = int(sys.argv[1]), sys.argv[2], sys.argv[3]
known = []
installed = {}   # package -> APK bytes, across connections
PKG = lambda p: 'com.veloster.dashboard' if 'Dashboard' in p or 'dashboard' in p else 'com.veloster.carplayhub'
VERIFIED = 'eng.vmadmin.20250902.081552'
SERIAL = 'FAKESERIAL42'
LOGCAT = ('11-02 16:18:22.123 I/VehicleDash( 812): draw avg 9.1 ms\n'
          '11-02 16:18:23.000 I/VNCarPlayHub( 901): bt peer 00:1A:7D:DA:71:13 name Galaxy 010-1234-5678 kim@example.com\n'
          '11-02 16:18:23.500 I/VNQuickEntry( 901): device FAKESERIAL42 ready\n'
          '11-02 16:18:24.000 E/AndroidRuntime( 812): FATAL EXCEPTION: main\n')

def reply(s, rid, local, data):
    """adbd's way: at most 4 KB per WRTE, each acknowledged before the next."""
    for i in range(0, len(data), 4096):
        send(s, WRTE, rid, local, data[i:i + 4096])
        assert recv(s)[0] == OKAY

def log(m):
    with open(logpath, 'a', encoding='utf-8') as f: f.write(m + '\n')

def send(s, cmd, a0, a1, d=b''):
    s.sendall(struct.pack('<6I', cmd, a0, a1, len(d), sum(d) & 0xffffffff, cmd ^ 0xffffffff) + d)

def exact(s, n):
    b = b''
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c: raise EOFError
        b += c
    return b

def recv(s):
    cmd, a0, a1, n, crc, magic = struct.unpack('<6I', exact(s, 24))
    assert magic == cmd ^ 0xffffffff, 'magic'
    assert n <= 4096, 'payload over 4096: %d' % n
    d = exact(s, n)
    assert sum(d) & 0xffffffff == crc, 'checksum'
    return cmd, a0, a1, d

def pubkey(d):
    raw = base64.b64decode(d.rstrip(b'\0').split(b' ')[0])
    words, n0inv = struct.unpack_from('<2I', raw)
    assert words == 64
    le = lambda off: sum(w << (32 * i) for i, w in enumerate(struct.unpack_from('<64I', raw, off)))
    n, rr, e = le(8), le(264), struct.unpack_from('<I', raw, 520)[0]
    assert (n0inv * n) % 2**32 == 2**32 - 1, 'n0inv'
    assert rr == pow(2**2048, 2, n), 'rr'
    return n, e

def session(s, no):
    cmd, a0, a1, d = recv(s)
    assert (cmd, a0, a1, d) == (CNXN, 0x01000000, 4096, b'host::\0'), 'cnxn'
    token = os.urandom(20)
    send(s, AUTH, 1, 0, token)
    cmd, a0, _, sig = recv(s)
    assert (cmd, a0) == (AUTH, 2)
    em = b'\0\1' + b'\xff' * (256 - 3 - len(SHA1_INFO) - 20) + b'\0' + SHA1_INFO + token
    if any(pow(int.from_bytes(sig, 'big'), e, n).to_bytes(256, 'big') == em for n, e in known):
        log('auth signature')
    else:
        send(s, AUTH, 1, 0, os.urandom(20))
        cmd, a0, _, d = recv(s)
        assert (cmd, a0) == (AUTH, 3)
        known.append(pubkey(d))
        log('auth pubkey')
    send(s, CNXN, 0x01000000, 4096, b'device::ro.product.name=jsn_kr;\0')
    files, rid, failed_once = {}, 100, False
    star = 'com.veloster.carplayhub.STAR_KEY' if no == 3 else 'com.mobis.intent.action.CUSTOM_SHORTCUT'   # 3: ☆ points at '내 앱'
    while True:
        try: cmd, a0, a1, d = recv(s)
        except EOFError: return
        if cmd != OPEN: continue
        rid += 1; local = a0; svc = d.rstrip(b'\0').decode()
        send(s, OKAY, rid, local)
        if svc.startswith('shell:'):
            c = svc[6:]; log('shell ' + c)
            out = ''
            if c == 'getprop ro.product.name': out = 'jsn_kr2' if no == 4 else 'jsn_kr'
            elif c == 'getprop ro.product.device': out = 'jsn_kr'
            elif c == 'getprop ro.build.version.sdk': out = '19' if no == 5 else '17'
            elif c == 'getprop ro.serialno': out = SERIAL
            elif c == 'getprop ro.build.version.incremental': out = 'eng.vmadmin.20260114.084130' if no in (5, 6) else VERIFIED
            elif c == 'getprop persist.sys.key.custom': out = star
            elif c.startswith('pm install -r '):
                p = c[14:]
                assert files[p] == open(os.path.join(server, os.path.basename(p)), 'rb').read(), 'pushed bytes differ'
                if no == 2 and 'Dashboard' in p and not failed_once:
                    failed_once = True; out = 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'
                else: out = '\tpkg: %s\r\nSuccess' % p; installed[PKG(p)] = files[p]
            elif c.startswith('pm uninstall '): out = 'Success'; installed.pop(c[13:], None)
            elif c.startswith('pm path '): out = 'package:/data/app/%s-1.apk' % c[8:] if c[8:] in installed else ''
            elif c.startswith('dumpsys package '):
                out = ('Packages:\r\n  Package [%s] (41a0):\r\n    codePath=/data/app/%s-1.apk\r\n    versionCode=10 targetSdk=17\r\n    versionName=1.9\r\n'
                       '    firstInstallTime=2026-11-02 16:00:00\r\n    lastUpdateTime=2026-11-02 16:10:00\r\n' % (c[16:], c[16:])) if c[16:] in installed else ''
            elif c == 'date': out = 'Mon Nov  2 16:20:00 KST 2026'
            elif c == 'run-as com.veloster.dashboard cat files/diagnostics/errors.log': out = 'VN-ERROR 1 1794880000000 rear_view\r\ntype java.lang.IllegalStateException\r\n at com.hkmc.pgs.PgsManager._init_:12\r\n'
            elif c.startswith('run-as com.veloster.carplayhub '): out = "run-as: Package 'com.veloster.carplayhub' is unknown"
            elif c.startswith('run-as '): out = '/system/bin/sh: cat: files/diagnostics/errors.previous.log: No such file or directory'
            elif c.startswith('logcat -d -v time -s '): out = LOGCAT.replace('\n', '\r\n') * 2000
            elif c.startswith('rm -f '): files.pop(c[6:])
            elif c.startswith('am start '): out = 'Starting: Intent { cmp=com.veloster.carplayhub/.HubActivity }'
            if out: reply(s, rid, local, (out + '\r\n').encode())
            send(s, CLSE, rid, local)
        elif svc == 'sync:':
            buf = b''
            while True:
                cmd, _, _, d = recv(s)
                if cmd != WRTE: continue
                buf += d; send(s, OKAY, rid, local)
                if buf.startswith(b'QUIT'): send(s, CLSE, rid, local); break
                if len(buf) < 8: continue
                if buf[:4] == b'RECV':
                    n = struct.unpack_from('<I', buf, 4)[0]
                    if len(buf) < 8 + n: continue
                    path = buf[8:8 + n].decode(); buf = buf[8 + n:]; log('pull ' + path)
                    data = installed.get(PKG(path)) if path.startswith('/data/app/') else None
                    if data is None: out = b'FAIL' + struct.pack('<I', 14) + b'No such file\0\0'
                    else:
                        out = b''.join(b'DATA' + struct.pack('<I', len(data[i:i + 65536])) + data[i:i + 65536] for i in range(0, len(data), 65536))
                        out += b'DONE' + struct.pack('<I', 0)
                    reply(s, rid, local, out); continue
                assert buf[:4] == b'SEND', 'sync stream'
                n = struct.unpack_from('<I', buf, 4)[0]
                path = buf[8:8 + n].decode().split(',')[0]; i = 8 + n; data = b''
                while buf[i:i + 4] == b'DATA' and len(buf) >= i + 8:
                    k = struct.unpack_from('<I', buf, i + 4)[0]
                    assert k <= 65536
                    data += buf[i + 8:i + 8 + k]; i += 8 + k
                if len(buf) < i + 8: continue   # not all here yet
                assert buf[i:i + 4] == b'DONE', 'sync stream'
                files[path] = data; log('push %s %d' % (path, len(data)))
                send(s, WRTE, rid, local, b'OKAY\0\0\0\0'); buf = b''

srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('127.0.0.1', port)); srv.listen(1)
log('ready')
no = 0
while True:
    s, _ = srv.accept(); no += 1; log('== connection %d' % no)
    try: session(s, no)
    except Exception as e: log('FAKE ERROR %r' % e)
    s.close()
