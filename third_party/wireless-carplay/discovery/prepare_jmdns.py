#!/usr/bin/env python3
"""Reproduce the API17 single-interface port of pinned upstream JmDNS 3.6.5."""
import argparse,difflib,hashlib,json,re
from pathlib import Path
COMMIT='2570b99a40ccaa3d3ef8352c20f25cca0ca3d477'
def sha(raw):return hashlib.sha256(raw).hexdigest()
def replace(text,old,new,count=1):
    assert text.count(old)==count,(old,text.count(old));return text.replace(old,new)
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--upstream',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    src=a.upstream/'src/main/java';digest=hashlib.sha256()
    for path in sorted((src/'javax').rglob('*.java')):digest.update(str(path.relative_to(a.upstream)).encode()+b'\0'+path.read_bytes())
    assert digest.hexdigest()=='6689618e15d1f154afbb5d4b972656625f9056eb1d6ad2a7033bf7eb081df10b','Upstream source differs from pinned commit'
    a.out.mkdir(parents=True,exist_ok=False);files={};patch=[]
    excluded={'javax/jmdns/JmmDNS.java','javax/jmdns/impl/JmmDNSImpl.java'}
    for path in sorted((src/'javax').rglob('*.java')):
        relative=str(path.relative_to(src))
        if relative in excluded:continue # The receiver binds exactly one observed AP interface.
        original=path.read_text();text=original
        text=text.replace('import org.slf4j.Logger;','import javax.jmdns.impl.compat.Logger;').replace('import org.slf4j.LoggerFactory;','import javax.jmdns.impl.compat.LoggerFactory;')
        text=text.replace('import java.nio.charset.StandardCharsets;','import java.nio.charset.Charset;').replace('StandardCharsets.UTF_8','Charset.forName("UTF-8")')
        text=text.replace('import java.util.Objects;','import javax.jmdns.impl.compat.Objects;')
        if path.name=='DNSMessage.java':
            for field,kind in [('_questions','DNSQuestion'),('_answers','DNSRecord'),('_authoritativeAnswers','DNSRecord'),('_additionals','DNSRecord')]:
                text=replace(text,field+' = Collections.synchronizedList(new LinkedList<>());',field+' = Collections.synchronizedList(new LinkedList<'+kind+'>());')
        if path.name=='JmDNSImpl.java':
            # On Unix a specifically bound UDP socket wins unicast delivery over
            # the wildcard receiver. Read that socket too; otherwise legacy/QU
            # queries accumulate unread on the address-bound send socket.
            text=replace(text,'private Thread _incomingListener;',
                'private Thread _incomingListener;\n    private Thread _incomingUnicastListener;')
            text=replace(text,'            _incomingListener.start();\n        }',
                '            _incomingListener.start();\n        }\n        if (_sendSocket != null && _incomingUnicastListener == null) {\n            _incomingUnicastListener = new SocketListener(this, _sendSocket);\n            _incomingUnicastListener.start();\n        }')
            text=replace(text,'            _sendSocket.close();\n            _sendSocket = null;',
                '            _sendSocket.close();\n            while (_incomingUnicastListener != null && _incomingUnicastListener.isAlive()) {\n                synchronized (this) {\n                    try { this.wait(1000); } catch (InterruptedException ignored) { }\n                }\n            }\n            _incomingUnicastListener = null;\n            _sendSocket = null;')
            for old,new in [
                ('_listeners = Collections.synchronizedList(new ArrayList<>());','_listeners = Collections.synchronizedList(new ArrayList<DNSListener>());'),
                ('_typeListeners = Collections.synchronizedSet(new HashSet<>());','_typeListeners = Collections.synchronizedSet(new HashSet<ServiceTypeListenerStatus>());'),
                ('_serviceListeners.putIfAbsent(loType, new LinkedList<>())','_serviceListeners.putIfAbsent(loType, new LinkedList<ServiceListenerStatus>())'),
                ('map.put(subtype, new ArrayList<>(10));','map.put(subtype, new ArrayList<ServiceInfo>(10));')]:text=replace(text,old,new)
        if path.name=='DNSCache.java':
            text=replace(text,'result.removeIf(testDNSEntry -> !testDNSEntry.matchRecordType(type) || (!testDNSEntry.matchRecordClass(recordClass)));',
                'for (java.util.Iterator<DNSEntry> it = result.iterator(); it.hasNext();) {\n            DNSEntry entry = it.next();\n            if (!entry.matchRecordType(type) || !entry.matchRecordClass(recordClass)) it.remove();\n        }')
            # Locked.close only releases its lock and declares no exception.
            pattern='try (Locked lock = _lm.lock(key)) {'
            assert text.count(pattern)==9
            while pattern in text:
                at=text.index(pattern);begin=at+len(pattern);end=begin;depth=1
                while depth:
                    if text[end]=='{':depth+=1
                    elif text[end]=='}':depth-=1
                    end+=1
                text=text[:at]+'{ Locked lock = _lm.lock(key); try {'+text[begin:end]+' finally { lock.close(); } }'+text[end:]
        if path.name=='DNSIncoming.java':
            text=replace(text,'names.forEach((index, startAt) -> _names.put(index, name.substring(startAt)));',
                'for (java.util.Map.Entry<Integer, Integer> entry : names.entrySet()) _names.put(entry.getKey(), name.substring(entry.getValue()));')
        if path.name=='DNSOutgoing.java':
            text=replace(text,'writeInt((now == 0) ? rec.getTTL() : rec.getRemainingTTL(now));',
                'int ttl = (now == 0) ? rec.getTTL() : rec.getRemainingTTL(now);\n            // RFC 6762 section 6.7: legacy clients cannot observe cache invalidation.\n            writeInt(_out.isMulticast() ? ttl : Math.min(10, ttl));')
        if path.name=='ServiceInfoImpl.java':
            text,n=re.subn(r'qualifiedNameMap.getOrDefault\((Fields\.\w+), "[^\"]*"\)',r'qualifiedNameMap.get(\1)',text);assert n==5
            for version in [4,6]:
                text=replace(text,'this._ipv'+str(version)+'Addresses = Collections.synchronizedSet(new LinkedHashSet<>());',
                    'this._ipv'+str(version)+'Addresses = Collections.synchronizedSet(new LinkedHashSet<Inet'+str(version)+'Address>());',2)
            text=replace(text,'properties.keySet() : Collections.emptySet()','properties.keySet() : Collections.<String>emptySet()')
            text=replace(text,'_props : Collections.emptyMap()','_props : Collections.<String, byte[]>emptyMap()')
        if path.name=='SocketListener.java':
            text=replace(text,'private final JmDNSImpl _jmDNSImpl;',
                'private final JmDNSImpl _jmDNSImpl;\n    private final java.net.MulticastSocket _receiveSocket;')
            text=replace(text,'SocketListener(JmDNSImpl jmDNSImpl) {',
                'SocketListener(JmDNSImpl jmDNSImpl) { this(jmDNSImpl, jmDNSImpl.getSocket()); }\n\n    SocketListener(JmDNSImpl jmDNSImpl, java.net.MulticastSocket socket) {')
            text=replace(text,'this._jmDNSImpl = jmDNSImpl;',
                'this._jmDNSImpl = jmDNSImpl;\n        this._receiveSocket = socket;')
            text=replace(text,'this._jmDNSImpl.getSocket().receive(packet);','this._receiveSocket.receive(packet);')
            text=replace(text,'if (packet.getPort() != DNSConstants.MDNS_PORT || msg.getQuestions().stream().anyMatch(DNSEntry::isUnique)) {',
                'boolean unicast = packet.getPort() != DNSConstants.MDNS_PORT;\n                            for (DNSQuestion question : msg.getQuestions()) unicast |= question.isUnique();\n                            if (unicast) {')
        if path.name=='Responder.java':
            for table,key in [('recordsByName','answer.getName()'),('pointersByAlias','alias')]:
                old=table+'.computeIfAbsent('+key+', name -> new LinkedHashSet<>()).add(answer);'
                text=replace(text,old,'if (!'+table+'.containsKey('+key+')) '+table+'.put('+key+', new LinkedHashSet<DNSRecord>());\n            '+table+'.get('+key+').add(answer);')
        if path.name=='JmDNS.java':
            start=text.index('    public static String VERSION;');end=text.index('    /**',start)
            text=text[:start]+'    public static String VERSION = "3.6.5-vn-api17";\n\n'+text[end:]
        target=a.out/'src'/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text)
        files[relative]={'upstream_sha256':sha(path.read_bytes()),'ported_sha256':sha(target.read_bytes())}
        if text!=original:patch.extend(difflib.unified_diff(original.splitlines(True),text.splitlines(True),fromfile='a/'+relative,tofile='b/'+relative))
    for name in ['LICENSE','NOTICE.txt']:
        path=a.upstream/name
        if path.exists():(a.out/name).write_bytes(path.read_bytes())
    (a.out/'api17.patch').write_text(''.join(patch))
    (a.out/'provenance.json').write_text(json.dumps({'repository':'jmdns/jmdns','commit':COMMIT,'version':'3.6.5','excluded':sorted(excluded),'files':files},indent=2)+'\n')
    print(json.dumps({'files':len(files),'modified':len({line for line in patch if line.startswith('--- ')})}))
if __name__=='__main__':main()
