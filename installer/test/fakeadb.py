import sys,os,shutil
# Fake adb for the PC installers (see run.sh). State next to this file: inst/<pkg>.apk (installed), tmp/ (pushed).
# FAKE_ABSENT=n: the first n 'devices' calls see no car. FAKE_BUILD: the head unit's build (default the verified one).
# FAKE_GONE=1: the cable comes out before the uninstall (uninstall and pm path answer with adb's error).
d=os.path.dirname(os.path.abspath(__file__));a=sys.argv[1:]
inst=os.path.join(d,'inst');tmp=os.path.join(d,'tmp');os.makedirs(inst,exist_ok=True);os.makedirs(tmp,exist_ok=True)
open(os.path.join(d,'calls.log'),'a',encoding='utf-8').write(' '.join(a)+'\n')
if a and a[0]=='-s':a=a[2:]
PKG=lambda p:'com.veloster.dashboard' if 'Dashboard' in p else 'com.veloster.carplayhub'
if not a:sys.exit(0)
if os.environ.get('FAKE_GONE')=='1' and (a[0]=='uninstall' or a[:3]==['shell','pm','path']):print("error: device 'FAKE123' not found");sys.exit(1)
if a[0]=='version':print('Android Debug Bridge version 1.0.41')
elif a[0]=='devices' and open(os.path.join(d,'calls.log'),encoding='utf-8').read().count('devices')<=int(os.environ.get('FAKE_ABSENT','0')):print('List of devices attached\n')
elif a[0]=='devices':print('List of devices attached\nFAKE123\tdevice usb:1-1 product:jsn_kr model:x device:jsn_kr transport_id:1\n')
elif a[0]=='uninstall':
    f=os.path.join(inst,a[1]+'.apk')
    if os.path.exists(f):os.remove(f)
    print('Success')
elif a[0]=='push':shutil.copy(a[1],os.path.join(tmp,os.path.basename(a[2])));print('1 file pushed')
elif a[0]=='pull':
    f=os.path.join(inst,os.path.basename(a[1]).replace('-1.apk','.apk'))
    if not os.path.exists(f):print("adb: error: remote object '%s' does not exist"%a[1]);sys.exit(1)
    shutil.copy(f,a[2]);print('1 file pulled')
elif a[0]=='shell':
    c=a[1:]
    if c[:1]==['getprop']:
        k=c[1] if len(c)>1 else ''
        print({'ro.product.name':'jsn_kr','ro.product.device':'jsn_kr','ro.build.version.sdk':'17','ro.build.version.incremental':os.environ.get('FAKE_BUILD','eng.vmadmin.20250902.081552'),'sys.usb.state':'adb'}.get(k,''))
    elif c[:2]==['pm','path']:
        if os.path.exists(os.path.join(inst,c[2]+'.apk')):print('package:/data/app/%s-1.apk\r'%c[2])
    elif c[:3]==['pm','install','-r']:shutil.copy(os.path.join(tmp,os.path.basename(c[3])),os.path.join(inst,PKG(c[3])+'.apk'));print('\tpkg: %s\r\nSuccess\r'%c[3])
    elif c[:2]==['rm','-f']:pass
    elif c[:2]==['am','start']:print('Starting: Intent { cmp=com.veloster.carplayhub/.HubActivity }')
    elif c[:1]==['date']:print('Mon Nov  2 16:20:00 KST 2026\r')
    elif c[:2]==['dumpsys','package']:
        if os.path.exists(os.path.join(inst,c[2]+'.apk')):
            print('Packages:\r\n  Package [%s] (41a0):\r\n    codePath=/data/app/%s-1.apk\r\n    versionCode=10 targetSdk=17\r\n    versionName=%s\r\n    lastUpdateTime=2026-11-02 16:10:00\r'%(c[2],c[2],os.environ.get('FAKE_VER','1.9')))   # FAKE_VER: the version on the car
    elif c[:1]==['logcat']:
        print(('11-02 16:18:22.123 I/VehicleDash( 812): draw avg 9.1 ms\r\n'
               '11-02 16:18:23.000 I/VNCarPlayHub( 901): bt peer 00:1A:7D:DA:71:13 name Galaxy 010-1234-5678 kim@example.com\r\n')*2000,end='')
