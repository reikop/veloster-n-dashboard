// 벨로스터 N 계기판 창 설치기 — macOS. scripts/build_mac_app.py가 이 파일을 osacompile(JavaScript, stay-open)로
// "N 계기판 설치.app"으로 만들고, 설치.command · 두 APK · bundle.txt를 Contents/Resources/kit/에 넣는다.
// 창 하나(터미널·Enter 없음)에서 [설치] / [삭제] / [진단 기록]을 누르면 kit를 ~/Library/Application Support/vn-installer/kit에
// 복사해(앱 번들은 읽기 전용일 수 있다: Gatekeeper 격리 실행) 그곳의 설치.command를 --gui로 돌리고 출력을 줄 단위로 보여 준다.
// 설치 규칙은 모두 설치.command에 있고 이 파일은 화면만 맡는다.
// 설치.command --gui 상태 줄: @@STEP <제목>  @@WAIT <초> <안내>  @@ASK <질문>(NSAlert → y/n을 표준 입력으로)  @@FILE <경로>  @@DONE ok|fail <내용>
// 시험용 환경 변수: VN_GUI_SMOKE=<파일>  창을 띄운 뒤 그 파일에 창 제목·버전 줄을 쓰고 끝낸다.
//                  VN_GUI_AUTORUN=install|uninstall|diag  창이 뜨면 바로 그 단추를 누른다(대답은 VN_GUI_ANSWER=y|n, 없으면 n).
//                  VN_GUI_ADB=<adb 경로>  설치.command --adb 로 넘긴다(가짜 adb).  끝나면 VN_GUI_SMOKE 파일에 기록 칸 전체를 쓰고 끝낸다.
ObjC.import('Cocoa');

var USB_OFF = "USB 디버깅 끄기: 시동을 껐다 켜면 저절로 꺼집니다. 바로 끄려면 Factory Test에서 0A 01 → 65 00 → 0A 02 (또는 '내 앱' → USB 디버깅 → OFF)";
var env = $.NSProcessInfo.processInfo.environment;
function getenv(k) { var v = env.objectForKey(k); return v.isNil() ? '' : ObjC.unwrap(v); }
var fm = $.NSFileManager.defaultManager;
var KIT_SRC = ObjC.unwrap($.NSBundle.mainBundle.resourcePath) + '/kit';
var WORK = getenv('VN_GUI_WORK') || (ObjC.unwrap($.NSHomeDirectory()) + '/Library/Application Support/vn-installer/kit');   // VN_GUI_WORK: 시험용

var win, logView, statusLabel, bannerLabel, versionLabel, buttons = {}, fileBtn, copyBtn, target, timer;
var task = null, stdinPipe = null, outPath = '', outPos = 0, pending = '', done = false, mode = '', resultFile = '';
var waitMsg = null, waitBase = 0, waitAt = 0, logText = '';

function readBundle(dir) {
  var s = $.NSString.stringWithContentsOfFileEncodingError(dir + '/bundle.txt', $.NSUTF8StringEncoding, null);
  if (s.isNil()) return '앱 목록(bundle.txt)이 없습니다. 설치기 ZIP을 다시 받아 주세요.';
  var v = {};
  ObjC.unwrap(s).split('\n').forEach(function (l) { if (l && l[0] !== '#') { var f = l.split('\t'); if (f.length >= 5) v[f[0]] = f[3]; } });
  return "N 계기판 " + (v['N-Dashboard-API17.apk'] || '?') + " · '내 앱' " + (v['My-Apps-API17.apk'] || '?');
}

function label(text, x, y, w, h, size, bold, color) {
  var l = $.NSTextField.wrappingLabelWithString(text);
  l.frame = $.NSMakeRect(x, y, w, h);
  l.font = bold ? $.NSFont.boldSystemFontOfSize(size) : $.NSFont.systemFontOfSize(size);
  if (color) l.textColor = color;
  win.contentView.addSubview(l);
  return l;
}
function button(title, sel, x, y, w) {
  var b = $.NSButton.buttonWithTitleTargetAction(title, target, sel);
  b.frame = $.NSMakeRect(x, y, w, 36);
  b.bezelStyle = $.NSBezelStyleRounded;
  win.contentView.addSubview(b);
  return b;
}
function say(m) {
  logText += m + '\n';
  logView.textStorage.mutableString.appendString(m + '\n');
  logView.scrollRangeToVisible($.NSMakeRange(logView.string.length, 0));
}
function idle(on) {
  ['install', 'uninstall', 'diag'].forEach(function (k) { buttons[k].enabled = on; });
  buttons.cancel.enabled = !on;
}
function finish(ok, msg) {
  if (done) return;
  done = true; waitMsg = null; statusLabel.stringValue = '';
  bannerLabel.textColor = ok ? $.NSColor.systemGreenColor : $.NSColor.systemRedColor;
  bannerLabel.stringValue = (ok ? '완료했습니다. ' + msg : '중단했습니다: ' + msg + '\n기록 칸 내용과 기록 파일을 함께 보내 주시면 원인을 볼 수 있습니다.') + '\n' + USB_OFF;
  idle(true);
  var smoke = getenv('VN_GUI_SMOKE');
  if (smoke && getenv('VN_GUI_AUTORUN')) {
    $(logText + '\nBANNER ' + (ok ? 'ok' : 'fail') + ' ' + msg + '\n').writeToFileAtomicallyEncodingError(smoke, true, $.NSUTF8StringEncoding, null);
    $.NSApp.terminate(null);
  }
}
function line(l) {
  l = l.replace(/\x1b\[[0-9;]*m/g, '').replace(/\r/g, '');
  var m;
  if ((m = /^@@STEP (.*)$/.exec(l))) { statusLabel.stringValue = m[1]; waitMsg = null; return; }
  if ((m = /^@@WAIT (\d+) (.*)$/.exec(l))) { waitBase = +m[1]; waitMsg = m[2]; waitAt = Date.now(); return; }
  if ((m = /^@@ASK (.*)$/.exec(l))) {
    var yes;
    if (getenv('VN_GUI_AUTORUN')) yes = getenv('VN_GUI_ANSWER') === 'y';
    else {
      var alert = $.NSAlert.alloc.init;
      alert.messageText = m[1];
      alert.addButtonWithTitle('예'); alert.addButtonWithTitle('아니오');
      yes = alert.runModal === $.NSAlertFirstButtonReturn;
    }
    say(m[1] + ' → ' + (yes ? '예' : '아니오'));
    stdinPipe.fileHandleForWriting.writeData($(yes ? 'y\n' : 'n\n').dataUsingEncoding($.NSUTF8StringEncoding));
    return;
  }
  if ((m = /^@@FILE (.*)$/.exec(l))) { resultFile = m[1]; fileBtn.hidden = false; return; }
  if (/^@@DONE ok/.test(l)) {
    finish(true, mode === 'diag' ? '진단 기록 파일을 만들었습니다([파일 보기]).' : mode === 'uninstall' ? '두 앱을 지웠습니다.' : "차 화면에 '내 앱'이 열립니다.");
    return;
  }
  if ((m = /^@@DONE fail ?(.*)$/.exec(l))) { finish(false, m[1]); return; }
  waitMsg = null;
  say(l);
}

// 출력 파일에서 새로 생긴 완전한 줄만 읽는다(UTF-8 글자가 중간에 잘리지 않게 마지막 줄바꿈까지만).
function drain() {
  if (!outPath) return;
  var h = $.NSFileHandle.fileHandleForReadingAtPath(outPath);
  if (h.isNil()) return;
  h.seekToFileOffset(outPos);
  var data = h.readDataToEndOfFile;
  h.closeFile;
  if (Number(data.length) === 0) return;
  var nl = data.rangeOfDataOptionsRange($('\n').dataUsingEncoding($.NSUTF8StringEncoding), $.NSDataSearchBackwards, $.NSMakeRange(0, data.length));
  var end = Number(nl.location) + 1, len = Number(data.length);   // the bridge may hand NSRange fields over as strings
  if (!(end > 0 && end <= len)) return;   // NSNotFound: no full line yet
  var chunk = data.subdataWithRange($.NSMakeRange(0, end));
  outPos += end;
  var s = $.NSString.alloc.initWithDataEncoding(chunk, $.NSUTF8StringEncoding);
  if (s.isNil()) return;
  ObjC.unwrap(s).split('\n').slice(0, -1).forEach(line);
}

function copyKit() {
  fm.createDirectoryAtPathWithIntermediateDirectoriesAttributesError(WORK, true, $(), null);
  var names = ObjC.deepUnwrap(fm.contentsOfDirectoryAtPathError(KIT_SRC, null)) || [];
  names.forEach(function (n) {
    var dst = WORK + '/' + n;
    fm.removeItemAtPathError(dst, null);
    fm.copyItemAtPathToPathError(KIT_SRC + '/' + n, dst, null);
  });
  return names.length > 0;
}

function start(m) {
  if (task && task.running) return;
  mode = m; done = false; resultFile = ''; fileBtn.hidden = true; bannerLabel.stringValue = ''; logText = '';
  logView.string = ''; idle(false); copyBtn.title = '복사';
  if (!copyKit()) { say('앱 안의 설치 파일(kit)을 찾지 못했습니다: ' + KIT_SRC); finish(false, '설치 파일 없음'); return; }
  outPath = WORK + '/../gui_out.txt'; outPos = 0;
  fm.createFileAtPathContentsAttributes(outPath, $.NSData.data, $());
  var args = [WORK + '/설치.command', '--gui'];
  if (m === 'uninstall') args.push('--uninstall');
  if (m === 'diag') args.push('--diag');
  if (getenv('VN_GUI_ADB')) args.push('--adb', getenv('VN_GUI_ADB'));
  var e = env.mutableCopy;
  e.setObjectForKey('/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:' + getenv('PATH'), 'PATH');   // Finder로 연 앱은 PATH가 짧다
  task = $.NSTask.alloc.init;
  task.launchPath = '/bin/bash';
  task.arguments = $(args);
  task.environment = e;
  task.currentDirectoryPath = WORK;
  var out = $.NSFileHandle.fileHandleForWritingAtPath(outPath);
  task.standardOutput = out; task.standardError = out;
  stdinPipe = $.NSPipe.pipe; task.standardInput = stdinPipe;
  task.launch;
}

function cancel() {
  if (task && task.running) {
    var k = $.NSTask.alloc.init; k.launchPath = '/usr/bin/pkill'; k.arguments = $(['-TERM', '-P', String(task.processIdentifier)]); k.launch; k.waitUntilExit;
    task.terminate;
  }
  say('취소했습니다. 설치 중이었다면 차에서는 그 단계가 끝날 수 있습니다. 다시 실행하면 이어서 맞춥니다.');
  finish(false, '취소');
}

function tick() {
  drain();
  if (task && !task.running) {
    drain();
    if (!done) finish(false, '설치기가 끝났습니다(종료 코드 ' + task.terminationStatus + ').');
    task = null;
  }
  if (waitMsg) statusLabel.stringValue = '⏳ ' + waitMsg + '  ' + (waitBase + Math.floor((Date.now() - waitAt) / 1000)) + '초';
}

ObjC.registerSubclass({
  name: 'VNInstallerTarget',
  superclass: 'NSObject',
  protocols: ['NSWindowDelegate'],
  methods: {
    'doInstall:': { types: ['void', ['id']], implementation: function () { start('install'); } },
    'doDiag:': { types: ['void', ['id']], implementation: function () { start('diag'); } },
    'doUninstall:': { types: ['void', ['id']], implementation: function () {
      var a = $.NSAlert.alloc.init;
      a.messageText = '두 앱을 지울까요?';
      a.informativeText = "차에서 N 계기판과 '내 앱', 그 설정을 지웁니다.\n\n'내 앱'에서 ☆ 버튼을 계기판으로 써 왔다면 지우기 전에 '내 앱'에서 ☆를 끄세요. " +
        '이미 지웠다면 ☆를 길게 눌러 나오는 순정 설정에서 원래 기능을 고르면 됩니다.';
      a.addButtonWithTitle('삭제'); a.addButtonWithTitle('취소');
      if (getenv('VN_GUI_AUTORUN') || a.runModal === $.NSAlertFirstButtonReturn) start('uninstall');
    } },
    'doCancel:': { types: ['void', ['id']], implementation: function () { cancel(); } },
    'doCopy:': { types: ['void', ['id']], implementation: function () {
      var pb = $.NSPasteboard.generalPasteboard; pb.clearContents; pb.setStringForType('0A 01\n65 01\n0A 02', $.NSPasteboardTypeString);
      copyBtn.title = '복사됨';
    } },
    'doFile:': { types: ['void', ['id']], implementation: function () {
      if (resultFile) $.NSWorkspace.sharedWorkspace.activateFileViewerSelectingURLs($([$.NSURL.fileURLWithPath(resultFile)]));
    } },
    'tick:': { types: ['void', ['id']], implementation: function () { tick(); } },
    'autorun:': { types: ['void', ['id']], implementation: function () {
      var a = getenv('VN_GUI_AUTORUN'), s = getenv('VN_GUI_SMOKE');
      if (a) { if (a === 'uninstall') start('uninstall'); else start(a); return; }
      if (s) {
        $(ObjC.unwrap(win.title) + '\n' + ObjC.unwrap(versionLabel.stringValue) + '\n').writeToFileAtomicallyEncodingError(s, true, $.NSUTF8StringEncoding, null);
        $.NSApp.terminate(null);
      }
    } },
    'windowWillClose:': { types: ['void', ['id']], implementation: function () { if (task && task.running) cancel(); $.NSApp.terminate(null); } }
  }
});

function build() {
  target = $.VNInstallerTarget.alloc.init;
  win = $.NSWindow.alloc.initWithContentRectStyleMaskBackingDefer($.NSMakeRect(0, 0, 720, 780),
    $.NSWindowStyleMaskTitled | $.NSWindowStyleMaskClosable | $.NSWindowStyleMaskMiniaturizable, $.NSBackingStoreBuffered, false);
  win.title = '벨로스터 N 계기판 설치';
  win.releasedWhenClosed = false;
  win.delegate = target;
  var W = 720, x = 20, w = W - 40;
  label('벨로스터 N 계기판 설치', x, 735, w, 28, 20, true);
  versionLabel = label('이 설치기에 든 버전: ' + readBundle(KIT_SRC), x, 710, w, 20, 13, true, $.NSColor.systemBlueColor);
  label('현대자동차 공식 앱이 아닙니다. 보증/AS 불이익이 있을 수 있고 책임은 본인에게 있습니다. 정차 · 시동 켠 상태에서 하세요.',
    x, 670, w, 36, 12, false, $.NSColor.systemOrangeColor);
  label('차 화면에서 먼저 (1·2단계, 직접)', x, 642, w, 20, 14, true);
  label('1. 설정 → SW 정보/업데이트 → 아래 업데이트 버튼 줄의 왼쪽 숨은 영역 5번 → 오른쪽 1번 (10초 안에)\n' +
    '    암호: 2026년 0567 (2027년 1278)\n' +
    '2. Diagnosis → Factory Test 에서 오른쪽 세 줄을 한 줄씩 입력하고 매번 SEND\n' +
    '3. USB 케이블로 차와 이 맥을 연결한 뒤 아래 [설치]\n' +
    "    ('내 앱'이 이미 있으면 '내 앱' → USB 디버깅 → ON 으로 대신할 수 있습니다)", x, 545, 520, 95, 12, false);
  var codes = $.NSTextField.labelWithString('0A 01\n65 01\n0A 02');
  codes.frame = $.NSMakeRect(560, 570, 120, 70);
  codes.font = $.NSFont.monospacedSystemFontOfSizeWeight(17, 0.4);
  codes.selectable = true;
  win.contentView.addSubview(codes);
  copyBtn = button('복사', 'doCopy:', 560, 535, 100);
  buttons.install = button('설치', 'doInstall:', x, 480, 120);
  buttons.install.keyEquivalent = '\r';
  buttons.uninstall = button('삭제', 'doUninstall:', x + 130, 480, 120);
  buttons.diag = button('진단 기록', 'doDiag:', x + 260, 480, 120);
  buttons.cancel = button('취소', 'doCancel:', x + 390, 480, 120);
  fileBtn = button('파일 보기', 'doFile:', x + 520, 480, 120);
  fileBtn.hidden = true;
  statusLabel = label('', x, 452, w, 22, 13, true, $.NSColor.systemOrangeColor);
  bannerLabel = label('', x, 395, w, 55, 12, true);
  var sv = $.NSTextView.scrollableTextView;
  sv.frame = $.NSMakeRect(x, 20, w, 370);
  logView = sv.documentView;
  logView.editable = false;
  logView.font = $.NSFont.monospacedSystemFontOfSizeWeight(11, 0);
  win.contentView.addSubview(sv);
  say('[설치]는 차(jsn_kr · 검증 빌드)에만 두 앱을 설치합니다. 차가 아닌 기기·다른 빌드에는 아무것도 하지 않습니다.');
  say('[진단 기록]은 차를 읽기만 해서 문제를 알릴 때 보낼 파일을 바탕화면에 만듭니다.');
  idle(true);
  win.center;
  win.makeKeyAndOrderFront(null);
  $.NSApp.activateIgnoringOtherApps(true);
  timer = $.NSTimer.scheduledTimerWithTimeIntervalTargetSelectorUserInfoRepeats(0.2, target, 'tick:', null, true);
  if (getenv('VN_GUI_SMOKE') || getenv('VN_GUI_AUTORUN'))
    $.NSTimer.scheduledTimerWithTimeIntervalTargetSelectorUserInfoRepeats(1.0, target, 'autorun:', null, false);
}

function run() { build(); }
function reopen() { if (win) win.makeKeyAndOrderFront(null); }
