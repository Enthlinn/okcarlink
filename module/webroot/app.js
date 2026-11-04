/*
 * OkCarLink module WebUI.
 *
 * Runs in the KernelSU manager's WebView, which injects `kernelsu`:
 *   kernelsu.exec(command) -> {errno, stdout, stderr} (root); kernelsu.toast(msg)
 *
 * Rules learned the hard way, kept from the previous page:
 *
 *  1. `exec`'s result shape differs between managers (some return a plain string, some
 *     {code,out,err}).  Reading `.stdout` blindly rendered an empty status table, so every result
 *     is normalised first.
 *  2. Never call the module's helpers by bare name: the module's system/ overlay is not mounted on
 *     every install (/system/bin/okcar-linkd absent, /data/adb/modules/<id>/system/bin/... runs).
 *     This page ships with both variants, so it probes every module id it can be installed as.
 *  3. Keep the status commands cheap: everything the dashboard needs is collected by ONE shell
 *     probe per tick, not a dozen sequential dumpsys/logcat calls (that made the page look dead).
 *  4. One broken element must not kill the page: clicks are delegated on `document` and every
 *     action re-enables its button in `finally`.
 *  5. The variant is data, not a second page: `webroot/variant` says lite/full, and the lite
 *     distribution build has no car-screen app and no display of its own.
 *
 * New in this revision, per the product requirements:
 *  - Title only says OkCarLink; the description paragraph is replaced by three modes, one of which
 *    (紫优的carplay++) stays disabled until its own module is installed.
 *  - Line 3 is the overall module state and line 4 the Bluetooth state; while healthy they say
 *    运行中 in green.  Every category row below also shows only 运行中 while healthy; the real
 *    values and the restart/stop controls live in a panel that opens when the row is clicked.
 *  - Every switch is a left/right sliding toggle (the three Bluetooth identity switches included).
 *  - The screen-casting app picker is its own area with an app selector and, once an app is chosen,
 *    an activity sub-menu; by default the first installed of 百度地图/高德地图/腾讯地图 is used.
 *  - Day/night display mode lives at the very bottom and follows the system by default.
 *
 * The page also runs the unattended watchdog: on every tick it re-checks the daemon, the session,
 * the switches and the Bluetooth adapter, and corrects what it is allowed to correct (rate
 * limited), which is what keeps a head unit from meeting a dead daemon after a reboot.
 */

const out = document.getElementById('out');
const catsEl = document.getElementById('cats');
let detailEl = document.getElementById('cat-detail');
const stModule = document.getElementById('st-module');
const stBt = document.getElementById('st-bt');
const pickPkg = document.getElementById('pick-pkg');
const pickActivity = document.getElementById('pick-activity');
const fieldActivity = document.getElementById('field-activity');
const appNote = document.getElementById('app-note');

/* 绝对路径只在这里定义一次；下面一律用变量拼接，不允许再出现裸的 /data/local/tmp。 */

const TMP = '/data/local/tmp';

const MODULE_CANDIDATES = [
  '/data/adb/modules/okcar-port', '/data/adb/modules_update/okcar-port',
  '/data/adb/modules/okcar-port-lite', '/data/adb/modules_update/okcar-port-lite',
  '/data/adb/modules/okcarlink', '/data/adb/modules_update/okcarlink',
];
const CARPP_DIR = TMP + '/okcar-carplaypp';
const CARPP_PKG = 'com.okcar.carplaypp';
const CARWITH_PKG = 'com.miui.carlink';   /* 小米 CarWith（随 ROM 装在 /product/app/CarWith） */
/* CarWith 没有任何 LAUNCHER Activity，投屏时必须显式指定一个可启动的 activity，
 * 否则 placement 直接报 "cannot find a launch entry"（2026-10-08 实测修正）。 */
const CARWITH_UI = 'com.miui.carlink/com.carwith.launcher.home.HomeActivity';
const CARPP_MODULE_PROP = '/data/adb/modules/okcar-carplaypp/module.prop';
const THEME_FILE = TMP + '/okcar-port-theme';
/* 绝对路径只在这里定义一次；下面一律用变量拼接，不允许再出现裸的 /data/local/tmp。 */
const MODE_FILE = TMP + '/okcar-port-mode';
const APPS_TSV = TMP + '/okcar-apps.tsv';
const ACTIVITIES_TSV = TMP + '/okcar-activities.tsv';
/* 两档超时：探针/路径这类只读命令必须快，卡住就说明桥没回音，页面要马上说出来；
 * 用户动作（重载蓝牙、apply、重启会话）本身就要好几秒，给足时间。 */
const EXEC_TIMEOUT_MS = 4000;
const EXEC_TIMEOUT_ACTION_MS = 40000;
const PROBE_INTERVAL_MS = 5000;
/* Auto-correction is rate limited per category: a correction that does not stick must not turn the
 * watchdog into a restart loop. */
const FIX_COOLDOWN_MS = 60000;

/* The default navigation apps, matched by label or by package.  Order is the documented priority:
 * 百度地图 first, then 高德地图, then 腾讯地图. */
const NAV_DEFAULTS = [
  { pkg: 'com.baidu.BaiduMap', labels: ['百度地图', 'Baidu Maps'] },
  { pkg: 'com.autonavi.minimap', labels: ['高德地图', 'Amap'] },
  { pkg: 'com.tencent.map', labels: ['腾讯地图', 'Tencent Maps'] },
];

let paths = null;
let lite = false;
let overlayState = '未检测';
let ksuAvailable = false;

const state = {
  probe: {},
  apps: [],
  activities: {},
  pickedPkg: '',
  pickedActivity: '',
  mode: '',
  theme: 'auto',
  carppModule: false,
  carwithApp: false,
  autoFix: true,
  detailId: '',
  logs: [],
  lastFix: {},
  timer: null,
};

/* ------------------------------------------------------------------ plumbing */

function say(text) { if (out) { out.value = text; out.scrollTop = out.scrollHeight; } }
function append(text) { if (out) { out.value += text; out.scrollTop = out.scrollHeight; } }

function ksuApi() {
  if (typeof kernelsu !== 'undefined' && kernelsu && typeof kernelsu.exec === 'function') return kernelsu;
  if (typeof ksu !== 'undefined' && ksu && typeof ksu.exec === 'function') return ksu;
  return null;
}

/** Rule 1: 一条命令的结果，不管桥交给页面的是什么形状，都还原成 {errno, stdout, stderr}。 */
function normalize(raw) {
  if (typeof raw === 'string') {
    /* 有的管理器把整段结果做成 JSON 字符串交给回调（或 promise 解析成字符串）。 */
    const text = raw.trim();
    if (text.startsWith('{')) {
      try {
        const obj = JSON.parse(text);
        if (obj && typeof obj === 'object' && (obj.stdout !== undefined || obj.stderr !== undefined
          || obj.errno !== undefined || obj.code !== undefined)) {
          return normalize(obj);
        }
      } catch (e) { /* 不是 JSON，当普通输出 */ }
    }
    return { errno: 0, stdout: raw, stderr: '' };
  }
  if (raw && typeof raw === 'object') {
    /* errno 若是非数字字符串，说明整段输出被塞进了第一个参数：那是输出，不是退出码。 */
    if (typeof raw.errno === 'string' && !/^-?\d+$/.test(raw.errno.trim())) {
      return { errno: 0, stdout: raw.errno, stderr: raw.stderr !== undefined ? String(raw.stderr) : '' };
    }
    return {
      errno: raw.errno !== undefined ? raw.errno : (raw.code !== undefined ? raw.code : 0),
      stdout: raw.stdout !== undefined ? raw.stdout : (raw.out !== undefined ? raw.out : ''),
      stderr: raw.stderr !== undefined ? raw.stderr : (raw.err !== undefined ? raw.err : ''),
    };
  }
  return { errno: -1, stdout: '', stderr: String(raw) };
}

/* ------------------------------------------------------------------ exec 协议
 *
 * KernelSU 3.x 的正式协议（官方 npm 包 kernelsu@3 就是这么调的）：
 *     window[cbName] = (errno, stdout, stderr) => {...};
 *     ksu.exec(command, JSON.stringify(options), cbName);
 * 第三个参数是**回调函数名的字符串**，不是函数。
 *
 * 1.x 与一些独立 WebUI 宿主则是 exec(command, callbackFn)。
 * 两种混用会直接把桥卡死 —— 把函数塞进字符串参数，命令永远不回来（现场表现为
 * “命令超时 40s”，连 echo 都超时）。所以先用一条无害命令探测一次，再固定用一种。
 */
let execProtocol = '';          /* '' 未定 | 'v3' | 'v1' */
let execProtocolPromise = null;
let cbSeq = 0;

function execV3(api, command) {
  return new Promise((resolve) => {
    const name = '__okcarKsuCb_' + (++cbSeq) + '_' + Date.now();
    let settled = false;
    const done = (value) => {
      if (settled) return;
      settled = true;
      try { delete window[name]; } catch (e) { /* 无所谓 */ }
      resolve(value);
    };
    window[name] = (errno, stdout, stderr) => done(normalize({ errno: errno, stdout: stdout, stderr: stderr }));
    let ret;
    try {
      ret = api.exec(command, '{}', name);
    } catch (e) {
      done({ errno: -1, stdout: '', stderr: String(e) });
      return;
    }
    if (ret && typeof ret.then === 'function') {
      ret.then((v) => done(normalize(v)), (e) => done({ errno: -1, stdout: '', stderr: String(e) }));
    } else if (ret !== undefined && ret !== null) {
      done(normalize(ret));
    }
    /* 其余情况等 window[name] 被调用，这就是 3.x 的正常路径 */
  });
}

function execV1(api, command) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (value) => { if (!settled) { settled = true; resolve(value); } };
    let ret;
    try {
      ret = api.exec(command, (...args) => {
        if (args.length <= 1) {
          const one = args[0];
          if (typeof one === 'number') { done({ errno: one, stdout: '', stderr: '' }); return; }
          done(normalize(one === undefined || one === null ? '' : String(one)));
          return;
        }
        if (args.length === 2) { done(normalize({ errno: args[0], stdout: args[1], stderr: '' })); return; }
        done(normalize({ errno: args[0], stdout: args[1], stderr: args[2] }));
      });
    } catch (e) {
      done({ errno: -1, stdout: '', stderr: String(e) });
      return;
    }
    if (ret && typeof ret.then === 'function') {
      ret.then((v) => done(normalize(v)), (e) => done({ errno: -1, stdout: '', stderr: String(e) }));
    } else if (ret !== undefined && ret !== null) {
      done(normalize(ret));
    }
  });
}

function withDeadline(promise, ms) {
  return Promise.race([
    promise,
    new Promise((resolve) => setTimeout(() => resolve(undefined), ms)),
  ]);
}

async function detectExecProtocol(api) {
  const mark = '__okcar_bridge__';
  const delivered = (out) => !!out && String(out.stdout || '').indexOf(mark) >= 0;
  const probe = 'echo ' + mark;
  if (delivered(await withDeadline(execV3(api, probe), 2500))) {
    execProtocol = 'v3';
    return execProtocol;
  }
  if (delivered(await withDeadline(execV1(api, probe), 2500))) {
    execProtocol = 'v1';
    return execProtocol;
  }
  /* 两种都不回音：仍按官方协议继续，并让页面把这件事说出来。 */
  execProtocol = 'v3';
  reportBridgeSilent();
  return execProtocol;
}

function ensureExecProtocol() {
  const api = ksuApi();
  if (!api) return Promise.resolve('');
  if (!execProtocolPromise) execProtocolPromise = detectExecProtocol(api);
  return execProtocolPromise;
}

async function run(command, opts) {
  const api = ksuApi();
  if (!api) {
    return { errno: -1, stdout: '', stderr: T('不在 KernelSU 管理器里：没有 ksu.exec') };
  }
  await ensureExecProtocol();
  const limit = (opts && opts.timeoutMs) || EXEC_TIMEOUT_MS;
  try {
    const raw = await Promise.race([
      execProtocol === 'v1' ? execV1(api, command) : execV3(api, command),
      new Promise((resolve) => setTimeout(() => resolve({
        errno: -1, stdout: '', stderr: T('命令超时（') + (limit / 1000) + T('s）'),
      }), limit)),
    ]);
    const out = normalize(raw);
    /* 只读探针连续超时两次 = 执行桥没回音，页面要立刻把这件事说出来，而不是一直转。 */
    if (out.stderr && out.stderr.indexOf(T('命令超时')) === 0 && limit === EXEC_TIMEOUT_MS) {
      execTimeouts++;
      if (execTimeouts === 2) reportBridgeSilent();
    } else if (String(out.stdout || '').trim()) {
      execTimeouts = 0;
    }
    return out;
  } catch (e) {
    return { errno: -1, stdout: '', stderr: String(e) };
  }
}

/* 执行桥连续没回音时的统一说法：给出能自己验证的动作，而不是只说"失败"。 */
function reportBridgeSilent() {
  envBanner = { key: 'bridgeSilent' };
  renderEnvBanner();
}

/* 顶部提示条：文案在渲染时才查表，切语言时按 key 重画。动态部分（启动失败的原因）另存。 */
let envBanner = null;
function renderEnvBanner() {
  const banner = document.getElementById('env');
  if (!banner || !envBanner) return;
  if (envBanner.key === 'bridgeSilent') {
    banner.textContent = T('命令没有回音：管理器没有把执行结果交回页面。请确认本页是从 KernelSU 管理器')
      + T(' → 模块 → 本模块 → 打开 WebUI 进入的（浏览器或桌面快捷方式没有执行桥）；')
      + T('也可以点「守护进程 → 测试执行桥（先点这个）」验证，或在手机终端手动执行 su -c \'id\'。');
  } else if (envBanner.key === 'openInManager') {
    banner.textContent = T('本页要在 KernelSU 管理器的模块 WebUI 里打开：普通浏览器没有 kernelsu.exec。');
  } else if (envBanner.key === 'bootFailed') {
    banner.textContent = T('页面启动失败：') + (envBanner.detail === undefined ? '' : envBanner.detail);
  }
  banner.classList.remove('hidden');
}

/* 提示条、base64、全选与导出。这几个函数曾被一次误删（删"上传诊断"用的正则吃掉了后面的
 * 代码），结果按钮点下去是调用不存在的函数——页面报错、全选无效都由此而来。 */
function toast(message) {
  let el = document.getElementById('toast');
  if (!el) {
    el = document.createElement('div');
    el.id = 'toast';
    el.className = 'toast';
    document.body.appendChild(el);
  }
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => el.classList.remove('show'), 2200);
}

function b64encode(text) {
  try { return btoa(String.fromCharCode.apply(null, new TextEncoder().encode(text))); }
  catch (e) {
    try { return btoa(unescape(encodeURIComponent(text))); } catch (e2) { return null; }
  }
}

/* 复制被 HyperOS 的选择/剪贴板钩子拦住（logcat 里是 SmartSelection/HyperOSCustFeatureResolve），
 * 所以给一条不靠剪贴板的路：写到设备文件，文件会被 okcar-logup 采集进快照带走。 */
const DIAG_FILE = TMP + '/okcar-diag.txt';
/* 导出命令自己回显的确认字样：日志/命令回显按设计不翻译，所以它的判据也必须跟着命令走，
 * 不能写成会被 T() 翻掉的字面量（否则英文模式下导出成功也会被判成失败）。 */
const EXPORT_DONE_MARK = '已写入';

async function exportOutputToFile() {
  const text = (out && out.value) || '';
  if (!text.trim()) { toast(T('没有内容可导出')); return; }
  const b64 = b64encode(text);
  if (!b64) { toast(T('编码失败')); return; }
  const r = await run('echo ' + q(b64) + ' | base64 -d > ' + q(DIAG_FILE)
    + ' && echo "' + EXPORT_DONE_MARK + ' ' + DIAG_FILE + ' ($(wc -c < ' + DIAG_FILE + ') 字节)"', 30000);
  append(format(r) + '\n');
  toast(String(r.stdout || '').indexOf(EXPORT_DONE_MARK) >= 0 ? T('已导出到 ') + DIAG_FILE : T('导出失败'));
}

/* 全选：textarea 的 select() 在部分 WebView 里不生效，再补 execCommand('selectAll')。 */
/* 码率与帧率的四个旋钮：滑条与手动输入双向同步；无线/有线两套（有线以后接通时读自己那套）。
 * 值写进 /data/local/tmp/okcar-port-video.conf，0 = 不覆盖（用与车机协商的值）。 */
const QOS_FILE = TMP + '/okcar-port-video.conf';
const QOS_FIELDS = [
  { key: 'wireless_bitrate', range: 'qos-wl-br-range', num: 'qos-wl-br-num', min: 1000, max: 30000, step: 500 },
  { key: 'wireless_fps', range: 'qos-wl-fps-range', num: 'qos-wl-fps-num', min: 15, max: 60, step: 1 },
  { key: 'wired_bitrate', range: 'qos-usb-br-range', num: 'qos-usb-br-num', min: 2000, max: 100000, step: 1000 },
  { key: 'wired_fps', range: 'qos-usb-fps-range', num: 'qos-usb-fps-num', min: 15, max: 60, step: 1 },
  /* 音画缓冲（视频后置多少毫秒再发）：补配件自带的音频延迟，顺手把突发吸收掉。
   * 默认 0 = 不延迟（2026-10-07 用户要求默认不延迟；旧版本默认 300）。0 是合法值，不是"不覆盖"。 */
  { key: 'video_delay_ms', range: 'qos-delay-range', num: 'qos-delay-num', min: 0, max: 2000, step: 10, def: 0 },
  /* app 模式渲染比例：100 = 按车机分辨率（默认，不改显示效果）；调小省合成/编码，代价是画面被放大。 */
  { key: 'app_render_scale', range: 'qos-scale-range', num: 'qos-scale-num', min: 25, max: 100, step: 5, def: 100 },
];

/*
 * 0 = 不覆盖（自动）。滑条上必须也能表达 0，否则"自动"会被画成 1000/2000/15/15，而数字框里
 * 还是 0 —— 面板自己前后矛盾（2026-10-20 现场：点"恢复自动"后滑条停在 1000，看着像设了
 * 1000kbps，实际是 auto）。所以滑条的 min 也是 0；落在 (0, min) 之间的值一律抬到 min，
 * 与 daemon 侧 VideoQos.clamp() 的语义一致（0 保持 0，非 0 则夹到 [min,max]）。
 */
function qosClampValue(field, value) {
  let v = Number(value);
  if (!isFinite(v) || v <= 0) return 0;
  if (v < field.min) v = field.min;
  if (v > field.max) v = field.max;
  if (field.step > 1) v = Math.round(v / field.step) * field.step;
  return v;
}

function qosSet(field, value) {
  const v = qosClampValue(field, value);
  const num = document.getElementById(field.num);
  const range = document.getElementById(field.range);
  if (num) num.value = String(v);
  if (range) range.value = String(v);
}

function qosValues() {
  const out = {};
  for (const f of QOS_FIELDS) {
    const num = document.getElementById(f.num);
    out[f.key] = num ? Number(num.value) || 0 : 0;
  }
  return out;
}

async function qosLoad() {
  const r = await run('cat ' + q(QOS_FILE) + ' 2>/dev/null', 8000);
  const values = {};
  for (const line of String(r.stdout || '').split('\n')) {
    const body = line.split('#')[0].trim();
    const eq = body.indexOf('=');
    if (eq > 0) values[body.slice(0, eq).trim()] = body.slice(eq + 1).trim();
  }
  for (const f of QOS_FIELDS) {
    const raw = values[f.key];
    const v = raw === undefined ? (f.def || 0) : (Number(raw) || 0);
    qosSet(f, v);
  }
  renderQosNote();
}

function renderQosNote() {
  const note = document.getElementById('qos-note');
  if (!note) return;
  const parts = [
    T('码率/帧率 0 = 不覆盖（用与车机协商的值），下次会话生效；'),
    T('音画缓冲（ms）默认 0（不延迟；视频比声音早/晚时可调，改完约 1 秒生效）；'),
    T('应用渲染比例 100% = 按车机分辨率渲染（默认，不改显示效果），调小可减轻合成与编码负载。'),
  ];
  const applied = parseKV(String((state.probe && state.probe.video_applied) || '').replace(/\|/g, '\n'));
  if (applied.fps || applied.bitrate_kbps) {
    /* 把 daemon 真正交给编码器的值说出来：只显示"存下来的愿望"时，用户分不清
     * 「保存了但这次会话用的是旧值」「被取值范围夹了」「被 max_fps 夹了」三种情况。
     * 详情部分是纯拉丁/数字，整句只走一个 T()，避免拼接出无法翻译的中文碎片。 */
    const detail = [
      applied.transport === 'wired' ? 'wired' : (applied.transport ? 'wireless' : ''),
      applied.bitrate_kbps ? applied.bitrate_kbps + ' kbps' : '',
      applied.fps ? applied.fps + ' fps' : '',
      applied.video_delay_ms !== undefined ? 'delay ' + applied.video_delay_ms + ' ms' : '',
    ].filter(Boolean).join(' ');
    parts.push(T('实际生效（会话开始时读取，改完要重新连接）：') + detail);
    /* 手机侧上限 persist.okcar.max_fps 现在是"可选"的：未设/0 = 不设限，跟着车机协商值走
     * （2026-10-21 之前它默认 30，导致车机广播 maxFPS=60 也照样跑 30）。所以这里只在真的
     * 设了上限、且实际值确实小于愿望时才解释，不能再说"自动一定被夹到 30"。 */
    const cap = Number((state.probe && state.probe.max_fps) || 0);
    const appliedFps = Number(applied.fps || 0);
    const wishFps = Number((document.getElementById('qos-wl-fps-num') || {}).value
        || (document.getElementById('qos-usb-fps-num') || {}).value || 0);
    if (appliedFps > 0 && wishFps > 0 && appliedFps < wishFps) {
      const why = cap > 0
        ? T('注意：这次会话实际是 {fps} fps，比你设的 {want} fps 低。手机侧上限 persist.okcar.max_fps={cap} 正在生效；帧率上限在 Android 上只是给编码器的提示，平台不一定照着限。')
            .split('{cap}').join(String(cap))
        : T('注意：这次会话实际是 {fps} fps，比你设的 {want} fps 低，而手机侧没有设上限（persist.okcar.max_fps 为空 = 跟随车机）。多半是车机在 /info 里广播的 maxFPS 更低，或编码器跟不上；帧率上限在 Android 上只是给编码器的提示。');
      parts.push(why
          .split('{fps}').join(String(appliedFps))
          .split('{want}').join(String(wishFps)));
    }
  } else {
    parts.push(T('实际生效：还没有会话应用过（连接一次后这里会显示编码器真正拿到的值）。'));
  }
  note.textContent = parts.join('');
}

async function qosSave() {
  const v = qosValues();
  const text = [
    '# okcar-port 视频码率/帧率/音画缓冲（码率与帧率 0 = 不覆盖，用与车机协商的值）',
    '# 无线与有线分开：无线链路承受能力低，有线高得多。',
    'wireless_bitrate=' + v.wireless_bitrate,
    'wireless_fps=' + v.wireless_fps,
    'wired_bitrate=' + v.wired_bitrate,
    'wired_fps=' + v.wired_fps,
    'video_delay_ms=' + (v.video_delay_ms === undefined ? 0 : v.video_delay_ms),
    'app_render_scale=' + (v.app_render_scale === undefined ? 100 : v.app_render_scale),
    '',
  ].join('\n');
  await writeFileText(QOS_FILE, text);
  toast(T('已保存，下次会话生效'));
}

/* 应用优先级：面板直接读写 /data/local/tmp/okcar-port-priority.conf（一行一个包名）。
 * 守护进程每 3 秒读一次，把列出的应用进程 renice 到 -10 并加入 top-app cpuset。 */
const PRIO_FILE = TMP + '/okcar-port-priority.conf';

async function prioLoad() {
  const r = await run('cat ' + q(PRIO_FILE) + ' 2>/dev/null', 8000);
  const box = document.getElementById('prio-list');
  if (box) box.value = String(r.stdout || '').replace(/^#.*$/gm, '').trim();
  renderPrioNote();
}

function renderPrioNote() {
  const note = document.getElementById('prio-note');
  if (note) note.textContent = T('守护进程每 3 秒生效一次；进程重启后会重新拔高。');
}

async function prioSave() {
  const box = document.getElementById('prio-list');
  const body = (box ? box.value : '').split('\n')
      .map((line) => line.trim()).filter((line) => line && line[0] !== '#').join('\n');
  const text = '# okcar-port 应用优先级：一行一个包名（留空表示不拔高任何应用）\n'
      + (body ? body + '\n' : '');
  await writeFileText(PRIO_FILE, text);
  toast(body ? T('已保存 ') + body.split('\n').length + T(' 个应用') : T('已清空（不拔高任何应用）'));
}

function bindPrio() {
  const save = document.getElementById('btn-save-prio');
  if (save) save.addEventListener('click', async () => {
    save.disabled = true;
    try { await prioSave(); } finally { save.disabled = false; }
  });
  const reload = document.getElementById('btn-reload-prio');
  if (reload) reload.addEventListener('click', prioLoad);
  prioLoad();
}

function bindQos() {
  for (const f of QOS_FIELDS) {
    const num = document.getElementById(f.num);
    const range = document.getElementById(f.range);
    if (range && num) {
      /* 拖动过程中只同步数字框（值先过一遍范围夹取，否则滑条 500 会写进"无线码率"，
       * 而 daemon 会把它夹到 1000 —— 又变成面板与实际不符）；松手时把滑条也吸附到位。 */
      range.addEventListener('input', () => { num.value = String(qosClampValue(f, range.value)); });
      range.addEventListener('change', () => qosSet(f, range.value));
      num.addEventListener('change', () => qosSet(f, Number(num.value) || 0));
    }
  }
  const save = document.getElementById('btn-save-qos');
  if (save) save.addEventListener('click', async () => {
    save.disabled = true;
    try { await qosSave(); } finally { save.disabled = false; }
  });
  const reset = document.getElementById('btn-reset-qos');
  if (reset) reset.addEventListener('click', () => {
    for (const f of QOS_FIELDS) qosSet(f, f.def || 0);
    toast(T('已恢复默认（码率/帧率 0 = 不覆盖，音画缓冲 0ms = 不延迟）'));
  });
  qosLoad();
}

function selectOutputText() {
  if (!out) return;
  out.focus();
  try { out.select(); } catch (e) { /* ignore */ }
  try { out.setSelectionRange(0, out.value.length); } catch (e) { /* ignore */ }
  try { document.execCommand('selectAll', false, null); } catch (e) { /* ignore */ }
}

function format(result) {
  const parts = [];
  if (result.stdout) parts.push(String(result.stdout).trimEnd());
  if (result.stderr) parts.push('[stderr] ' + String(result.stderr).trimEnd());
  if (!parts.length) parts.push('(无输出, exit=' + result.errno + ')');
  return parts.join('\n');
}

async function execAndShow(command) {
  say('$ ' + command + '\n');
  const result = await run(command, { timeoutMs: EXEC_TIMEOUT_ACTION_MS });
  append(format(result) + '\n');
  return result;
}

/* Shell single-quoting for the few values this page interpolates (module paths, package names). */
function q(value) {
  return "'" + String(value).replace(/'/g, "'\\''") + "'";
}

/** Only package/component shaped strings ever reach the shell; anything else is refused loudly. */
function safeComponent(value) {
  const text = String(value || '').trim();
  if (text === '-' || text === '') return text;
  if (!/^[A-Za-z0-9_.]+(\/[A-Za-z0-9_.$]+)?$/.test(text)) return null;
  return text;
}

function writeFileText(path, text) {
  return run('printf %s ' + q(text) + ' > ' + q(path) + ' 2>&1; echo wrote ' + q(path));
}

/* ------------------------------------------------------------ path discovery */

/* 页面就是从这个模块的 webroot 里加载的（管理器用 /data/adb/modules/<id>/webroot），所以
 * 模块目录可以直接从 location 得到 —— 这条不依赖任何命令，执行桥没回音时也不会指错包。
 * 盲信 MODULE_CANDIDATES[0] 曾在只装 lite 包的机器上把命令全打到 /data/adb/modules/okcar-port。 */
function moduleDirFromLocation() {
  /* 本机面板（Magisk 等没有 WebUI 桥的安装方式）里页面不在模块目录下，URL 推不出来；
   * 由 webroot/ksu-bridge.js 注入真实模块目录，这里优先用它。 */
  if (window.__OKCAR_PANEL && window.__OKCAR_PANEL.moduleDir) return window.__OKCAR_PANEL.moduleDir;
  const href = String((window.location && (window.location.href || window.location.pathname)) || '');
  const m = href.match(/\/data\/adb\/modules(?:_update)?\/[^/]+\/webroot/);
  return m ? m[0].replace(/\/webroot$/, '') : '';
}

async function resolvePaths() {
  if (paths) return paths;
  const probe = 'for d in ' + MODULE_CANDIDATES.map((d) => q(d)).join(' ') + '; do '
    + '[ -x "$d/system/bin/okcar-linkd" ] && { echo "$d"; break; }; done; '
    + '[ -x /system/bin/okcar-linkd ] && echo overlay || echo nooverlay';
  const result = await run(probe);
  const lines = String(result.stdout || '').split('\n').map((l) => l.trim()).filter(Boolean);
  const fromUrl = moduleDirFromLocation();
  const probed = lines.filter((l) => l.startsWith('/'))[0] || '';
  const dir = fromUrl || probed || MODULE_CANDIDATES[0];
  const overlay = lines.indexOf('overlay') >= 0;
  overlayState = overlay ? '已挂载' : '未挂载（用模块内路径）';
  /* Which product this page is: the distribution build writes `lite` into webroot/variant and has
   * no car-screen app and no display of its own, so those rows are hidden. */
  const variant = await run('cat ' + q(dir + '/webroot/variant') + ' 2>/dev/null');
  lite = String(variant.stdout || '').trim() === 'lite';
  paths = {
    dir: dir,
    linkd: overlay ? '/system/bin/okcar-linkd' : dir + '/system/bin/okcar-linkd',
    ctl: overlay ? '/system/bin/okcar-port-ctl' : dir + '/system/bin/okcar-port-ctl',
    carkit: overlay ? '/system/bin/okcar-carkit' : dir + '/system/bin/okcar-carkit',
  };
  return paths;
}

/* ------------------------------------------------------------------ status probe */

/*
 * One shell line collects everything the dashboard judges.  The daemon is asked for a fresh status
 * snapshot first (`send status`), so the snapshot file is never a stale leftover presented as live
 * state; then every value is printed as KEY=VALUE and parsed here.
 */
function probeCommand(p) {
  return [
    q(p.linkd) + ' send status >/dev/null 2>&1',
    'sleep 1',
    'echo "linkd_status=$(' + q(p.linkd) + ' status 2>&1 | head -n1)"',
    'echo "statusfile=$(grep -v \'^#\' ' + TMP + '/okcar-linkd.status 2>/dev/null | tr \'\\n\' \'|\')"',
    'echo "logtail=$(grep -E \'linkd: \' ' + TMP + '/okcar-linkd.log 2>/dev/null | tail -n 6 | tr \'\\n\' \'|\')"',
    'echo "switches=$(grep -v \'^#\' ' + TMP + '/okcar-port-switches.conf 2>/dev/null | tr \'\\n\' \'|\')"',
    'echo "did=$(getprop bluetooth.device_id.vendor_id)"',
    'echo "profiles=$(for pp in bluetooth.profile.opp.enabled bluetooth.profile.sap.server.enabled '
      + 'bluetooth.profile.map.server.enabled bluetooth.profile.pan.nap.enabled '
      + 'bluetooth.profile.pan.panu.enabled; do printf \'%s=\' "$(getprop $pp)"; done)"',
    'echo "btaddr=$(settings get secure bluetooth_address 2>/dev/null)"',
    'echo "btproc=$(pidof com.android.bluetooth 2>/dev/null)"',
    'echo "wifi=$(settings get global wifi_on 2>/dev/null)"',
    'echo "app=$(cat ' + TMP + '/okcar-port-app.conf 2>/dev/null)"',
    'echo "appui=$(cat ' + TMP + '/okcar-port-app-display.conf 2>/dev/null)"',
    'echo "carpp_module=$( [ -f ' + q(CARPP_MODULE_PROP) + ' ] && echo yes || echo no)"',
    'echo "carwith_app=$( ( [ -f /product/app/CarWith/CarWith.apk ] || pm path ' + CARWITH_PKG + ' >/dev/null 2>&1 ) && echo yes || echo no)"',
    'echo "carpp_mode=$(cat ' + q(CARPP_DIR + '/mode') + ' 2>/dev/null)"',
    'echo "carpp_apps=$(cat ' + q(CARPP_DIR + '/apps.conf') + ' 2>/dev/null | grep -vc \'^#\')"',
    'echo "carpp_extra=$(grep -v \'^#\' ' + q(CARPP_DIR + '/extra.conf') + ' 2>/dev/null | tr \'\\n\' \'|\')"',
    'echo "carpp_status=$(grep -v \'^#\' ' + q(CARPP_DIR + '/status') + ' 2>/dev/null | tail -n 4 | tr \'\\n\' \'|\')"',
    'echo "standalone=$(cat ' + q(CARPP_DIR + '/standalone') + ' 2>/dev/null)"',
    /* 这台机器是什么（root 框架 / Android / WebUI / SE / bt.mac / DID）：换机时先看这一行。 */
    /* 开机自启的**武装状态**（ok / disabled / pending / fails=n）。连续两次没站住时
     * wired-boot 会写 okcar-wired-boot.disabled 并从此静默不拉起 —— 面板必须说出来，
     * 否则表现就是"有线开关开着、重启之后永远 Starting..."，看起来像模块坏了。 */
    'echo "wire_boot=$(' + q(p.ctl) + ' wired-boot-state 2>/dev/null)"',
    'echo "wire_state=$(cat ' + TMP + '/okcar-carkit.state 2>/dev/null)"',
    /* 有线模式的**事实**（哪个 gadget 正自称 Apple 且绑着 UDC），与开关文件里的"意图"分开。
     * 两者会分叉：2026-10-11 用 .dev 脚本直接改了框架的 g1，于是 wired=off 而 g1 上挂着
     * 05ac:12a8 + ffs.okcar + ncm.okcar，有线真的能连，面板却显示"关闭"——只看意图就永远看不到。
     *
     * 2026-10-21：这三行以前各调一次 `carkit status`，也就是**一次探针跑三遍 status**。
     * 而 status 本身要做二十多次 configfs 读（每个 35~70ms，实测单次 1.3 秒），于是探针光这三行
     * 就要 4 秒，而探针周期只有 5 秒 —— 面板永远在追着自己跑，表现就是"加载不出来"。
     * 改成**只调一次**、把三行从同一份输出里切出来。
     * 口是不是被别的用途占着（ICCOA 车机走 AOA 时 sys.usb.config=accessory，内核会把真控制器
     * 从 /sys/class/udc 摘掉）。有线连不上时，这一行能直接区分"口被占"和"模块坏了"。 */
    '_wire_st=$(sh ' + q(p.carkit) + ' status 2>/dev/null)',
    'echo "wire_fact=$(printf \'%s\\n\' "$_wire_st" | sed -n \'s/^fact=//p\' | tail -1)"',
    'echo "wire_port=$(printf \'%s\\n\' "$_wire_st" | sed -n \'s/^port=//p\' | tail -1)"',
    'echo "wire_owner=$(printf \'%s\\n\' "$_wire_st" | sed -n \'s/^owner=//p\' | tail -1)"',
    /* 这台机器实际给了什么（KernelSU + configfs/udc/usb_role/usbfs/uinput）。本模块只面向已解锁+KernelSU 的机器，
     * 所以这是前提而不是锦上添花：缺了它，失败会在很久以后才以"会话起不来"的形式出现。 */
    'echo "caps=$(' + q(p.ctl) + ' caps --oneline 2>/dev/null)"',
    'echo "platform=$(' + q(p.ctl) + ' platform 2>/dev/null | sed -n \'s/^platform: //p\')"',
    'echo "mode_file=$(cat ' + q(MODE_FILE) + ' 2>/dev/null)"',
    /* 守护进程**正在跑**的模式（取自它自己的 --mode 启动参数）。文件是意图，这个是事实；两者不
     * 同步时页面必须显示事实，否则就是在替一个没生效的选择背书 —— 2026-10-18 现场：文件 app、
     * 进程 --mode mirror，面板写着应用投屏、车机上却是镜像。 */
    'echo "mode_running=$(sh ' + q(p.linkd) + ' status 2>/dev/null | sed -n \'s/^okcar-linkd: mode=//p\' | tail -1)"',
    'echo "theme_file=$(cat ' + q(THEME_FILE) + ' 2>/dev/null)"',
    /* 编码器**实际**用上的码率/帧率（daemon 在 setQos 时写这个文件）。面板把"存下来的意图"
     * 和这份"事实"并排显示：两者不一样时，用户要能看出是哪一边、为什么，而不是怀疑滑条没用。 */
    'echo "video_applied=$(grep -v \'^#\' ' + TMP + '/okcar-port-video.applied 2>/dev/null | tr \'\\n\' \'|\')"',
    /* 手机侧的帧率上限。**现在是可选的**：未设/0 = 不设限，跟随车机在 /info 里广播的 maxFPS
     * （2026-10-21 之前它默认 30，把广播 60 的车机也压成 30，这是"设了 60 实际 30"的根因）。
     * 仍然读回来，因为用户可能显式设过它，面板要能解释"为什么被夹"。 */
    'echo "max_fps=$(getprop persist.okcar.max_fps)"',
    /* 哨兵：看不到它就说明整段输出没回到页面（管理器没交回、被截断或超时），
     * 这比"某些键为空"更能区分"设备状态"和"执行通道"两类问题。 */
    'echo "probe_end=1"',
  ].join('; ');
}

function parseKV(text) {
  const map = {};
  for (const line of String(text || '').split('\n')) {
    const i = line.indexOf('=');
    if (i <= 0) continue;
    map[line.slice(0, i).trim()] = line.slice(i + 1);
  }
  return map;
}

function parseSwitches(text) {
  const map = { linkd: 'on', session: 'on', did: 'on', oui: 'off', trim: 'off' };
  for (const part of String(text || '').split('|')) {
    const i = part.indexOf('=');
    if (i > 0) map[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return map;
}

async function probe() {
  const p = await resolvePaths();
  const result = await run(probeCommand(p));
  if (result.stderr && result.stderr.indexOf(T('命令超时')) === 0) reportBridgeSilent();
  const hasSentinel = /(^|\n)probe_end=1\s*(\n|$)/.test(String(result.stdout || ''));
  if (result.stdout && !hasSentinel) {
    state.probe = Object.assign(parseKV(result.stdout), {
      probe_error: T('输出不完整（缺少 probe_end 哨兵）：命令结果可能被截断，先看输出面板'),
      sw: parseSwitches(parseKV(result.stdout).switches),
    });
    return state.probe;
  }
  if (result.errno !== 0 && !result.stdout) {
    /* 探测失败时不要伪造开关状态：默认值会让页面显示成「守护进程开关开着」而实际什么都没在跑，
     * 现场就会被误读成「开关点不动」。这里只留错误信息，开关行按「未知」渲染。 */
    state.probe = {
      probe_error: String(result.stderr || 'probe failed'),
      sw: {},
      switches: '',
      linkd_status: '',
    };
    return state.probe;
  }
  const kv = parseKV(result.stdout);
  kv.sw = parseSwitches(kv.switches);
  kv.linkd_running = /okcar-linkd: running/.test(kv.linkd_status || '');
  kv.session_listening = /listening as/.test(kv.statusfile || '');
  kv.session_active = kv.session_listening
    || /connected to|AirPlay channels ready|session offered by the accessory/.test(kv.statusfile || '');
  /* 配件正在接入时守护进程会先把状态写成 accessory=/joining/AirPlay，而 RFCOMM 的
   * "listening as" 暂时不在文件里——那不是掉线。分开判定，别把接入过程报成"未监听"。 */
  /* 真的接上了配件才有这几样；只有 RFCOMM 在监听不算"已连接"。 */
  /* "已连接上车机"必须有活的会话作证：配件字段（accessory=/session=）在断联后仍留在状态里，
   * 只看它会误报"已连接"（实测 2026-10-03）。 */
  kv.accessory_connected = /accessory=/.test(kv.statusfile || '')
    && /AirPlay channels ready|connected to/.test(kv.statusfile || '');
  kv.accessory_connecting = !kv.session_listening
    && /accessory=|joining |AirPlay channels|event channel/.test(kv.statusfile || '');
  const display = /display=(\d+)/.exec(kv.statusfile || '');
  kv.display_id = display ? Number(display[1]) : -1;
  /* 触摸空间对不上时（状态里的 touch 与 display 不一致=MISMATCH），唯一不改基础代码的补救
   * 就是把接收端的分辨率钉进触摸空间：daemon 自己的提示就是 `okcar-linkd send size WxH`。 */
  const dsize = /display=\d+ (\d+x\d+)/.exec(kv.statusfile || '');
  kv.display_size = dsize ? dsize[1] : '';
  kv.display_mismatch = /MISMATCH/.test(kv.statusfile || '');
  kv.trim_all_off = /opp\.enabled=false/.test(kv.profiles || '')
    && /sap\.server\.enabled=false/.test(kv.profiles || '')
    && /map\.server\.enabled=false/.test(kv.profiles || '')
    && /pan\.nap\.enabled=false/.test(kv.profiles || '')
    && /pan\.panu\.enabled=false/.test(kv.profiles || '');
  state.probe = kv;
  state.carppModule = kv.carpp_module === 'yes';
  state.carwithApp = kv.carwith_app === 'yes';
  return kv;
}

/* ------------------------------------------------------------------ category model */

function ok(text) { return { level: 'ok', text: T(text || '运行中') }; }
function bad(text) { return { level: 'bad', text: T(text) }; }
function warn(text) { return { level: 'warn', text: T(text) }; }

/*
 * One entry per subsystem that must be 运行中.  `health` returns {level,text}; only the level and a
 * short text are shown in the row, the full values live in the detail panel (requirement: the
 * category row shows 运行中 and the real status is one click in).  `switchKey` renders the left/right
 * toggle on the row; `warnings` are the product-level explanations the operator must see before
 * flipping that switch.
 */
/* ---- 界面语言 ---------------------------------------------------------------------------------
 * 页面源码里的字符串保持英文，中文是**盖在上面**的一层，只覆盖看得见的界面骨架
 * （行名、按钮文案、警告、状态词、模式按钮）。选择存在设备上，重载/重启后仍然有效，
 * 选择器放在标题栏右上角。默认中文（2026-10-07 用户要求改回来）。
 * 长的动态值——日志、包名、原始属性 dump——一律不翻译。 */
const LANG_FILE = TMP + '/okcar-port-lang';
let LANG = 'zh';
const ZH = {
  "On": "已打开",
  "App": "应用",
  "Day": "白天",
  "Off": "已关闭",
  "Back": "返回",
  "Lite": "精简版",
  "Stop": "停止",
  "apps": "个应用",
  "Clear": "清屏",
  "Close": "关闭",
  "Night": "黑夜",
  "Start": "启动",
  "saved": "已保存",
  "Daemon": "守护进程",
  "Output": "输出",
  "Reload": "重新读取",
  "failed": "失败",
  "‹ Back": "‹ 返回",
  "Chinese": "中文",
  "English": "English",
  "Not set": "未指定",
  "Restart": "重启",
  "Running": "运行中",
  "Turn on": "打开",
  "Unknown": "未知",
  "details": "详情",
  "mounted": "已挂载",
  "status=": "状态=",
  "Started": "已启动",
  "App cast": "应用投屏",
  "Checking": "一直检测中",
  "Language": "语言",
  "(unknown)": "(未知)",
  ") bytes)\"": ") 字节)\"",
  "Apply now": "立即应用",
  "Checking…": "检测中…",
  "— details": "— 具体状态",
  "A/V buffer": "音画缓冲",
  "Categories": "分类状态",
  "Daemon off": "守护进程已关闭",
  "Retry cast": "重试投放",
  "Select all": "全选日志文字",
  "written to": "已写入",
  "Cast target": "投放目标",
  "Environment": "环境",
  "Mirror cast": "镜像投屏",
  "Not all off": "未全部关掉",
  "Not applied": "未生效",
  "Not created": "未创建",
  "Not running": "未运行",
  "Session off": "会话已关闭",
  "Starting...": "正在启动…",
  "\\n\\nRun it?": "\\n\\n执行？",
  "exported to": "已导出到",
  "(no matches)": "（没有匹配项）",
  "Display mode": "显示模式",
  "Not applied:": "未生效:",
  "Read failed:": "读取出错:",
  "Search apps…": "搜索应用…",
  "Session stop": "会话 stop",
  "Wired status": "有线状态",
  "\\n[auto-fix]": "\\n[自动修正]",
  "not detected": "未检测",
  "Check failed:": "检查出错:",
  "Follow system": "跟随系统",
  "Module status": "模块总状态",
  "Not listening": "未监听",
  "One-key start": "一键启动",
  "Probe failed:": "探测失败：",
  "Reset to auto": "恢复自动",
  "Session start": "会话 start",
  "Wired bitrate": "有线码率",
  "display mode:": "显示模式：",
  "export failed": "导出失败",
  "no HID at all": "压根没有 HID",
  "Default match:": "默认匹配：",
  "Export to file": "导出到文件",
  "OUI switch off": "OUI 开关关闭",
  "RFCOMM listener": "RFCOMM 监听",
  "Release display": "释放屏",
  "Virtual display": "虚拟屏",
  "\\n[WebUI error]": "\\n[WebUI 出错]",
  "encoding failed": "编码失败",
  "trim switch off": "裁剪开关关闭",
  "App render scale": "应用渲染比例",
  "Refresh app list": "刷新应用列表",
  "Restore original": "还原原值",
  "Restore profiles": "还原 profile",
  "Wired frame rate": "有线帧率",
  "Wireless bitrate": "无线码率",
  "Wireless session": "无线会话",
  "Zuiyou carplay++": "紫优的carplay++",
  "[startup failed]": "[启动失败]",
  "daemon switch on": "守护进程开关打开",
  "(no app list yet)": "（还没有应用清单）",
  "(no output, exit=": "(无输出, exit=",
  "Bluetooth MAC OUI": "蓝牙 MAC OUI",
  "SDP DID switch on": "SDP DID 开关打开",
  "command timed out": "命令超时",
  "nothing to export": "没有内容可导出",
  "who executes this": "谁来执行",
  "(no state to show)": "（没有可显示的状态）",
  "Daemon not running": "守护进程未运行",
  "Pre-create display": "预建屏",
  "SDP DID switch off": "SDP DID 开关关闭",
  "Save priority list": "保存优先级列表",
  "Search activities…": "搜索 activity…",
  "apps.conf entries=": "apps.conf 条目=",
  "&& echo \"written to": "&& echo \"已写入",
  "(no helper log yet)": "(还没有 helper 日志)",
  "Restore factory MAC": "还原出厂 MAC",
  "Trim (leave one on)": "裁剪(少关一条)",
  "Wireless frame rate": "无线帧率",
  "command timed out (": "命令超时（",
  "Accessory connecting": "正在连接配件",
  "Apply app + activity": "应用这两个项",
  "Module not installed": "模块未安装",
  "Tuned for Fruit Link": "专为水果互联适配",
  "(results appear here)": "（操作结果会显示在这里）",
  "== daemon log tail ==": "== daemon 日志尾 ==",
  "== one-key start ==\\n": "== 一键启动 ==\\n",
  "Back to phone desktop": "回本机桌面",
  "Page failed to start:": "页面启动失败：",
  "Touch target mismatch": "触摸目标不一致",
  "\\n[self-check failed]": "\\n[自检出错]",
  "(tap to choose an app)": "（点这里选择应用）",
  "Cast to Zuiyou display": "投紫优虚拟屏",
  "Listener not published": "监听未发布",
  "Rebuild the USB gadget": "重建 USB gadget",
  "\\n[mode switch failed]": "\\n[切换模式出错]",
  "== app cast target ==\\n": "== 应用投屏目标 ==\\n",
  "Give USB back to Android": "把 USB 交还 Android",
  "Turn off and restore now": "关闭并立即还原",
  "== touch lines (logcat) ==": "== 触摸行（logcat）=",
  "wireless session switch on": "无线会话开关打开",
  "(default launcher activity)": "（默认 launcher activity）",
  "(tap to choose an activity)": "（点这里选择 activity）",
  "== event channel / SETUP ==": "== 事件通道 / SETUP ==",
  "but best left auto-starting": "但建议保持开机自启",
  "cleared (nothing is raised)": "已清空（不拔高任何应用）",
  "echo \"== daemon log tail ==\"": "echo \"== daemon 日志尾 ==\"",
  "== screen/injection device ==": "== 屏幕/注入设备 ==",
  "Every command echo lands here": "命令回显都在这里",
  "Save bitrate &amp; frame rate": "保存码率与帧率",
  "Trim (all five off, measured)": "裁剪(全关·实测)",
  "\\n[WebUI unhandled rejection]": "\\n[WebUI 未处理异常]",
  "\\n[display mode switch failed]": "\\n[切换显示模式出错]",
  "Not inside the KernelSU manager": "未在 KernelSU 管理器内",
  "Zuiyou carplay++ virtual display": "紫优的carplay++ 虚拟屏",
  "saved; takes effect next session": "已保存，下次会话生效",
  "== session lines (logcat tail) ==": "== 会话行（logcat 尾部）=",
  "SDP DID (the production head unit detection)": "SDP DID（车机认机）",
  "Test exec bridge (tap this first)": "测试执行桥（先点这个）",
  "daemon is gone, starting it again": "守护进程不在，重新拉起",
  "echo \"== touch lines (logcat) ==\"": "echo \"== 触摸行（logcat）=\"",
  "echo \"== event channel / SETUP ==\"": "echo \"== 事件通道 / SETUP ==\"",
  "echo \"== pinning the touch space to": "echo \"== 把触摸空间钉到",
  "trim switch on (used while pairing)": "裁剪开关打开（配对阶段用）",
  "Diagnostics (copy for the developer)": "诊断（复制给开发者）",
  "echo \"== screen/injection device ==\"": "echo \"== 屏幕/注入设备 ==\"",
  "Wired link (this phone as the iPhone)": "有线连接（本机作为 iPhone）",
  "On (not tracked by the switch)": "已开启（不归开关管）",
  "USB port taken by another function": "USB 口被其它功能占用",
  "Reclaim a stray Apple gadget": "回收游离的 Apple gadget",
  "Unbinds and clears any gadget that claims to be an iPhone (including one built outside this module), then hands USB back to Android.": "解绑并清掉任何自称 iPhone 的 gadget（含模块之外建出来的），然后把 USB 交还 Android。",
  "Not opened inside the KernelSU manager": "未在 KernelSU 管理器内打开",
  "Tap a row for details and restart/stop": "点一行进去看具体状态与重启/停止",
  "not mounted (using the in-module path)": "未挂载（用模块内路径）",
  "Default match (Baidu/Amap/Tencent Maps)": "默认匹配（百度/高德/腾讯地图）",
  "Pin touch space (fix touch=-1/MISMATCH)": "钉住触摸空间（修 touch=-1/MISMATCH）",
  "echo \"== session lines (logcat tail) ==\"": "echo \"== 会话行（logcat 尾部）=\"",
  "saved (takes effect in the next session)": "已保存（下次会话生效）",
  "== crash evidence (kernel/memory/crash) ==": "== 死机证据（内核/内存/崩溃）==",
  "OUI switch on (modifies the Bluetooth MAC)": "OUI 开关打开（会修改蓝牙 MAC）",
  "Touch diagnostics (copy for the developer)": "触摸诊断（复制给开发者）",
  "not inside a KernelSU manager: no ksu.exec": "不在 KernelSU 管理器里：没有 ksu.exec",
  "== Bluetooth/wireless log lines (logcat) ==": "== 蓝牙/无线日志行（logcat）==",
  "== injection/size lines in daemon stdout ==": "== daemon stdout 里的注入/尺寸 ==",
  "== pinning the touch space to ' + size + ' ==": "== 把触摸空间钉到 ' + size + ' ==",
  "wireless session switch off (listening stops)": "无线会话开关关闭（停止监听）",
  "the SDP never turns off, however long you wait": "SDP 等好久都不关",
  "applied $pkg (takes effect in the next session)": "已应用 $pkg（下次会话生效）",
  "Auto animation suppression": "投屏自动关动画",
  "echo \"== crash evidence (kernel/memory/crash) ==\"": "echo \"== 死机证据（内核/内存/崩溃）==\"",
  "echo \"== Bluetooth/wireless log lines (logcat) ==\"": "echo \"== 蓝牙/无线日志行（logcat）==\"",
  "echo \"== injection/size lines in daemon stdout ==\"": "echo \"== daemon stdout 里的注入/尺寸 ==\"",
  "Bluetooth profile trim (required for the production head unit pairing)": "蓝牙 profile 裁剪（车机配对所必须）",
  "Releasing the virtual display ends the current cast.": "释放虚拟屏会结束当前投放。",
  ") -- whichever navigation app is installed gets cast.": "）— 装了哪个导航就投哪个。",
  "Restores the backed-up bt.mac and restarts Bluetooth.": "还原备份的 bt.mac 并重启蓝牙。",
  "== rebuilding RFCOMM/SDP (drops the current session) ==": "== 正在重建 RFCOMM/SDP（会断开当前会话）==",
  "auto animation suppression off (system animations kept)": "自动关动画关闭（保留系统动画）",
  "== master switch on: restore exactly what was recorded (": "== 打开总开关：按记录原样开回来（",
  "Turns off only four (MAP stays on); not a measured level.": "只关四个（保留 MAP）；非实测档。",
  "daemon switch off (the head unit will not find the phone)": "守护进程开关关闭（车机将找不到手机）",
  "No app selected and no navigation app matched by default.\\n": "没有选中应用，也没有默认匹配到导航应用。\\n",
  "/okcar-uinputd.log 2>/dev/null || echo \"(no helper log yet)\"": "/okcar-uinputd.log 2>/dev/null || echo \"(还没有 helper 日志)\"",
  "echo \"== rebuilding RFCOMM/SDP (drops the current session) ==\"": "echo \"== 正在重建 RFCOMM/SDP（会断开当前会话）==\"",
  "# okcar-port video bitrate and frame rate (0 = do not override)": "# okcar-port 视频码率与帧率（0 = 不覆盖，用与车机协商的值）",
  "written to ' + DIAG_FILE + ' ($(wc -c < ' + DIAG_FILE + ') bytes)": "已写入 ' + DIAG_FILE + ' ($(wc -c < ' + DIAG_FILE + ') 字节)",
  "Unbinds and re-creates the gadget, then re-enumerates as an iPhone.": "解绑并重建 gadget，然后重新枚举成 iPhone。",
  "The Zuiyou carplay++ module currently decides which apps appear on the": "当前由「紫优的carplay++」模块决定虚拟屏上显示哪些应用；",
  "switch state not read yet: tapping it prints the command and result below": "还没读到开关状态：点一下会在下面输出命令与结果",
  "the SDP DID was changed back (the default switch is on), writing it again": "SDP DID 被改回去了（默认开关是打开），重新写入",
  "Forces window/transition/animator scales to 0 during mirror/cast sessions.": "镜像/投屏会话期间把 window/transition/animator 三个动画时长强制置 0。",
  "Unbinds the gadget, restores sys.usb.config=adb and turns the Java side off.": "解绑 gadget，还原 sys.usb.config=adb，并关掉 Java 侧。",
  "With listening stopped the head unit cannot connect until you start it again.": "停掉监听后车机会连不上，直到再 start。",
  "defaults restored (bitrate/frame rate 0 = do not override, A/V buffer 0 ms = no delay)": "已恢复默认（码率/帧率 0 = 不覆盖，音画缓冲 0ms = 不延迟）",
  "# okcar-port app priority: one package name per line (empty = raise nothing)\\n": "# okcar-port 应用优先级：一行一个包名（留空表示不拔高任何应用）\\n",
  "auto animation suppression on (system transitions forced off during a session)": "自动关动画打开（会话期间压掉系统转场动画）",
  "== master switch off: remember which switches are on, then turn them all off ==\\n": "== 关闭总开关：先记住已开启的开关，再全部关闭 ==\\n",
  "The package name or activity contains invalid characters; the command was refused.\\n": "包名或 activity 含非法字符，已拒绝下发。\\n",
  "The daemon applies this every 3 seconds; a process restart raises the priority again.": "守护进程每 3 秒生效一次；进程重启后会重新拔高。",
  "# Wireless and wired are separate: a wireless link carries much less than a wired one.": "# 无线与有线分开：无线链路承受能力低，有线高得多。",
  "Enabling this changes the Bluetooth MAC address and may cause unpredictable bugs. Enable it?": "启用后会修改蓝牙MAC地址，可能会导致不可预知的bug，确认启用吗？",
  "Rebuild listener (drops the current cast; use only when the head unit cannot find the phone)": "重建监听（会断开当前投屏，找不到手机时才用）",
  "Baidu Maps / Amap / Tencent Maps are all missing: pick an app manually in the selector above.": "百度地图 / 高德地图 / 腾讯地图都没装：请在上面的选择器里手动选一个应用。",
  "This modifies the Bluetooth MAC address and restarts Bluetooth; paired devices must pair again.": "会修改蓝牙 MAC 地址并重启蓝牙，已配对设备需重新配对。",
  "Open this page in the KernelSU manager's module WebUI: an ordinary browser has no kernelsu.exec.": "本页要在 KernelSU 管理器的模块 WebUI 里打开：普通浏览器没有 kernelsu.exec。",
  "virtual display; the app selected below is only a fallback target when that module is unavailable.": "下面选中的应用只作为模块不可用时的备用目标。",
  "Bitrate/frame rate 0 = do not override (use the value negotiated with the head unit); takes effect next session.": "码率/帧率 0 = 不覆盖（用与车机协商的值），下次会话生效；",
  "A/V buffer (ms) defaults to 0 (no delay; raise it when video runs ahead of or behind audio; changes apply in about 1 s).": "音画缓冲（ms）默认 0（不延迟；视频比声音早/晚时可调，改完约 1 秒生效）；",
  "Actually applied (read when the session starts; reconnect after saving): ": "实际生效（会话开始时读取，改完要重新连接）：",
  "Note: this session really ran at {fps} fps, below the {want} fps you asked for. The phone-side cap persist.okcar.max_fps={cap} is in force; on Android the frame-rate cap is only a hint to the encoder, so the platform may not honour it.": "注意：这次会话实际是 {fps} fps，比你设的 {want} fps 低。手机侧上限 persist.okcar.max_fps={cap} 正在生效；帧率上限在 Android 上只是给编码器的提示，平台不一定照着限。",
  "Note: this session really ran at {fps} fps, below the {want} fps you asked for, and the phone side has no cap set (persist.okcar.max_fps empty = follow the head unit). Most likely the head unit advertises a lower maxFPS in /info, or the encoder cannot keep up; on Android the frame-rate cap is only a hint to the encoder.": "注意：这次会话实际是 {fps} fps，比你设的 {want} fps 低，而手机侧没有设上限（persist.okcar.max_fps 为空 = 跟随车机）。多半是车机在 /info 里广播的 maxFPS 更低，或编码器跟不上；帧率上限在 Android 上只是给编码器的提示。",
  "Actually applied: no session has applied it yet (connect once and the values the encoder really got will show here).": "实际生效：还没有会话应用过（连接一次后这里会显示编码器真正拿到的值）。",
  "incomplete output (probe_end sentinel missing): the command result may have been truncated; check the output panel first": "输出不完整（缺少 probe_end 哨兵）：命令结果可能被截断，先看输出面板",
  "USB adb stops while it is on (the module keeps adb over TCP, port 5555).  Turning the switch off unbinds the gadget and hands USB back to Android as an adb device.": "开着的时候 USB adb 会停（模块保留 TCP 5555 的 adb）；关掉会把 gadget 解绑，把 USB 交回 Android 当 adb 设备。",
  "The wired session carries the same stack as the wireless one: lockdown, the carkit service, the iAP2 session, then AirPlay over the NCM link.  It needs a TLS identity on the phone (/data/local/tmp/lockdown-cert.pem + lockdown-key.pem); if the pair is missing the handshake dies at EnableSessionSSL, so the module generates a self-signed one when it starts.": "有线会话跑的是和无线同一套：lockdown、carkit service、iAP2 会话，然后走 NCM 链路的 AirPlay。它需要手机上有一份 TLS 身份（/data/local/tmp/lockdown-cert.pem + lockdown-key.pem）；没有的话握手会死在 EnableSessionSSL，所以模块启动时会自己生成一份自签名的。",
  "Regenerate the TLS identity": "重新生成证书",
  "Re-arm boot auto-start": "恢复开机自启",
  "Clears the disabled/fails/pending markers so the next boot starts wired mode again.": "清掉停用/失败/待定标记，下次开机会重新拉起有线模式。",
  "Boot auto-start disabled after two failed boots - re-arm it below": "连续两次开机自启没站住，已自动停用——用下面的按钮恢复",
  "Rewrites /data/local/tmp/lockdown-cert.pem and lockdown-key.pem with a fresh self-signed pair; a running wired link is rebuilt so the daemon loads it.": "用一对新的自签名证书覆盖 /data/local/tmp/lockdown-cert.pem 与 lockdown-key.pem；有线正在跑时会重建链路，让守护进程加载新的这一对。",
  "What it does: while a mirror/cast session runs, the three system animation scales (window/transition/animator) are forced to 0 and the values found before the session are restored when it ends. It removes the frame spike when opening an app in mirror mode.": "作用：镜像/投屏会话期间把系统三个动画时长（window/transition/animator）强制置 0，会话结束时把会话前读到的原值写回。用来去掉镜像模式下开应用那一帧的尖峰。",
  "On: this phone re-enumerates over USB as an Apple device (05ac:12a8) with the USBMUX interface and NCM, so a CarPlay host (a head unit, or the emulator on a second phone) can run wired CarPlay against it.  Off by default because it takes the whole USB port over.": "打开：本机在 USB 上重新枚举成 Apple 设备（05ac:12a8，带 USBMUX 接口和 NCM），说 CarPlay 的 USB 主机（车机，或第二台手机上的模拟器）就能对它跑有线 CarPlay。默认关闭，因为它会占掉整个 USB 口。",
  "It defaults to OFF: it used to default to on, so it \"suddenly\" took effect whenever the switch file was rebuilt, and this panel had no row for it (reported 2026-10-07). Turning the switch off also restores your saved scales immediately, without waiting for the next session.": "默认关闭：它以前默认开，所以每次开关文件重建后都会\"突然生效\"，而面板上又没有这一行（2026-10-07 反馈）。关掉这个开关会立即把保存的动画比例还原，不用等下一次会话。",
};
/* 反查表：中文（源码里的中文字面量，7.21 恢复的那套）→ 英文。中文模式走 ZH，英文模式走 EN。
 * ZH 是权威表（英文 key → 中文），能反查的部分**自动**从这里导出，两张表因此不可能各自漂移
 * ——上一版把约 300 条 EN 手抄/按翻译提交逐行对齐，多行文案一错位就整段错配（启动/停止都反了）。
 * 只有 ZH 里查不到的中文字面量才写在下面的 EN_EXTRA 里。 */
const EN = {};
for (const key in ZH) {
  if (Object.prototype.hasOwnProperty.call(ZH, key)
      && !Object.prototype.hasOwnProperty.call(EN, ZH[key])) {
    EN[ZH[key]] = key;
  }
}
/* ZH 里没有的中文字面量：中文 → 英文（整段警告、状态词、页面自己生成的提示与标签）。 */
const EN_EXTRA = {
  "命令没有回音：管理器没有把执行结果交回页面。请确认本页是从 KernelSU 管理器": "Commands get no reply: the manager is not handing execution results back to the page. ",
  " → 模块 → 本模块 → 打开 WebUI 进入的（浏览器或桌面快捷方式没有执行桥）；": "Make sure this page was opened from the KernelSU manager -> Modules -> this module -> Open WebUI (a browser or a desktop shortcut has no exec bridge). ",
  "也可以点「守护进程 → 测试执行桥（先点这个）」验证，或在手机终端手动执行 su -c 'id'。": "You can also verify with \"Daemon -> Test exec bridge (tap this first)\", or run su -c 'id' manually in a phone terminal.",
  "s）": "s)",
  "已导出到 ": "exported to ",
  "应用渲染比例 100% = 按车机分辨率渲染（默认，不改显示效果），调小可减轻合成与编码负载。": "App render scale 100% = render at the head unit resolution (the default, no change in picture); lowering it reduces composition and encoding load.",
  "已保存 ": "saved ",
  " 个应用": " apps",
  "未生效: ": "Not applied: ",
  "检查出错: ": "Check failed: ",
  "读取出错: ": "Read failed: ",
  "启动未完成": "Startup incomplete",
  "已连接上车机": "Connected to head unit",
  "已准备好连接车机": "Ready to connect",
  "已准备好连接车机（OUI 未生效）": "Ready to connect (OUI not applied)",
  "— 装了哪个导航就投哪个。": " -- whichever navigation app is installed gets cast.",
  "切换投屏模式需要重启守护进程，会断开当前投屏。\n确认切换并重启吗？": "Switching the cast mode restarts the daemon and drops the current cast.\nSwitch and restart?",
  "执行？": "Run?",
  "（": " (",
  "）": ")",
  "这是整机主开关：CarPlay 会话与虚拟屏投放都由 okcar-linkd 提供，关掉它车机就找不到手机。": "This is the master switch for the whole stack: CarPlay sessions and virtual-display casting are both provided by okcar-linkd, and with it off the head unit cannot find the phone.",
  "关掉会同时停进程、且开机不再自起；再打开会立刻拉起一个受监督的实例，不需要重启。": "Turning it off also stops the process and keeps it from auto-starting at boot; turning it back on immediately starts a supervised instance, with no reboot needed.",
  "关掉后守护进程照常运行，但停止 RFCOMM 监听：车机在此状态下找不到手机，直到再打开。": "With it off the daemon keeps running but stops listening on RFCOMM: in that state the head unit cannot find the phone until it is turned on again.",
  "一般不需要关，排查「换端口／另一个程序要占蓝牙」时才用。": "Normally there is no need to turn it off; it is for debugging \"another port / another program needs Bluetooth\".",
  "停止守护进程会断开当前会话，车机将找不到手机。": "Stopping the daemon drops the current session and the head unit will not find the phone.",
  "关掉五个未用 profile（配对后车机仍认 CarPlay 的实测档）；会重载蓝牙。": "Turns off the five unused profiles (the measured level where the head unit still recognises CarPlay after pairing); reloads Bluetooth.",
  "适配水果互联：小米车机上的 iPhone 互联 App 拿蓝牙 MAC 的 OUI 段去查厂商表，本机原厂 OUI 被判成 Xiaomi 而结构性拒绝，改名字 / SDP DID / CoD 都无效，只有把 OUI 段换成 Apple（f0:18:98）才会被当成 iPhone。": "For Fruit Link: the iPhone-connect app on Xiaomi head units looks the Bluetooth MAC OUI segment up in the vendor table, and this machine's factory OUI is judged Xiaomi and structurally rejected; renaming / SDP DID / CoD are all ineffective, and only an Apple OUI segment (f0:18:98) is treated as an iPhone.",
  "警告：这个开关会修改蓝牙的 MAC 地址。已配对过的设备都要重新配对；地址每次开机可能被 nv_mac 从 QMI NV 重建，所以开机后可能需要重新应用一次。只对水果互联有意义，车机不需要它。": "WARNING: this switch modifies the Bluetooth MAC address. Every already-paired device must pair again, and nv_mac may rebuild the address from QMI NV on every boot, so it may need re-applying after boot. Only meaningful for Fruit Link; the production head unit does not need it.",
  "如果真要日常使用建议开启，关闭后系统启动时会强力污染配对过的蓝牙使其不再认为自己具有 carplay 能力，导致需要重新配对才能连接。": "If you really use it day to day, leave it on: with it off, system startup aggressively taints the paired Bluetooth so it no longer considers itself CarPlay-capable, and a re-pair is needed before it can connect.",
  "原因：车机的发现数据库装不下整份 SDP 应答（约 1600 B），记录一多就把末尾两条（我们自己的）挤掉，车机因此缓存不到 128 位 UUID、报不出无线 CarPlay 支持；把 EIR 14→9、SDP 18→13 之后 supportCarplay 才会翻回 true。": "Why: the production head unit (the integrator) head unit's discovery database cannot hold the whole SDP response (~1600 B), and with too many records it drops the last two (ours), so the head unit caches no 128-bit UUID and reports no wireless CarPlay support; shrinking EIR 14->9 and SDP 18->13 is what flips supportCarplay back to true.",
  "它只在配对阶段需要：车机把 SDP 缓存下来之后就不再需要裁剪，配对成功后可以关掉这个开关（关掉会还原五个 profile）。裁过头也是真的——更激进的一档会让车机干脆不把本机当手机分类。": "It is only needed while pairing: once the head unit has cached the SDP no trimming is needed, and after a successful pair this switch can be turned off (turning it off restores the five profiles). Over-trimming is real too -- a more aggressive level makes the head unit stop classifying this phone as a phone.",
  "当前由「紫优的carplay++」模块决定虚拟屏上显示哪些应用；": "The Zuiyou carplay++ module currently decides which apps appear on the ",
  "下面选中的应用只作为模块不可用时的备用目标。": "virtual display; the app selected below is only a fallback target when that module is unavailable.",
};
Object.assign(EN, EN_EXTRA);

/* 一条界面字符串的双语查表：中文模式查 ZH（英文 key → 原中文），英文模式查 EN（中文字面量 → 英文）。
 * 两个表里都没有的字符串与动态值原样透传（日志、包名、原始 dump 不翻译）。 */
function T(text) {
  if (!text) return text;
  const table = LANG === 'zh' ? ZH : EN;
  return Object.prototype.hasOwnProperty.call(table, text) ? table[text] : text;
}
/* index.html 的静态部分用 data-en/data-zh 双语并存，切换时直接换文本。
 * 占位符不是文本节点，单独用 data-en-placeholder / data-zh-placeholder 走一遍；
 * 输出面板那个 textarea 里可能已经有日志，只在它还是本页写的占位文案时才改写。 */
function applyStaticLang() {
  const useZh = LANG === 'zh';
  const nodes = document.querySelectorAll('[data-en][data-zh]');
  for (let i = 0; i < nodes.length; i++) {
    const value = nodes[i].getAttribute(useZh ? 'data-zh' : 'data-en');
    if (!value) continue;
    if (nodes[i].tagName === 'TEXTAREA') {
      const other = nodes[i].getAttribute(useZh ? 'data-en' : 'data-zh');
      if (nodes[i].value === '' || nodes[i].value === other || nodes[i].value === value) {
        nodes[i].value = value;
        nodes[i].defaultValue = value;
      }
      continue;
    }
    nodes[i].textContent = value;
  }
  const holders = document.querySelectorAll('[data-en-placeholder][data-zh-placeholder]');
  for (let i = 0; i < holders.length; i++) {
    holders[i].setAttribute('placeholder', holders[i].getAttribute(useZh ? 'data-zh-placeholder' : 'data-en-placeholder'));
  }
  const sel = document.getElementById('lang');
  if (sel) sel.value = LANG;
  document.documentElement.setAttribute('lang', useZh ? 'zh-CN' : 'en');
}
async function setLang(next) {
  LANG = next === 'en' ? 'en' : 'zh';
  applyStaticLang();
  await run('printf %s ' + q(LANG) + ' > ' + q(LANG_FILE) + ' 2>/dev/null');
  renderCats();
  /* 切语言要立刻看到效果：凡是按语言渲染过的可见骨架都要重画一遍，不能等下一次 5 秒自检。
   * 状态行必须重算（state.header 里存的是上一门语言已经翻好的字），所以走 renderStatusLines；
   * 探针还没回来时它没有 s.sw 可读，退回只重画已有内容。
   * 二级页只在它确实开着时才重画——刷新路径不重画二级页是刻意的，别把它变成每 5 秒折叠一次。 */
  if (state.probe && state.probe.sw) renderStatusLines(); else renderStatusRows();
  renderModes();
  renderPickers();
  renderQosNote();
  renderPrioNote();
  renderEnvBanner();
  if (state.detailId) renderDetail();
  say('== language: ' + LANG + ' ==\n');
}

const CATS = [
  {
    id: 'daemon',
    name: '守护进程',
    switchKey: 'linkd',
    warnings: [
      '这是整机主开关：CarPlay 会话与虚拟屏投放都由 okcar-linkd 提供，关掉它车机就找不到手机。',
      '关掉会同时停进程、且开机不再自起；再打开会立刻拉起一个受监督的实例，不需要重启。',
    ],
    health: (s) => (s.sw.linkd !== 'on' ? warn('已关闭')
      : (s.linkd_running ? ok() : bad('未运行'))),
    raw: (s) => [s.linkd_status, s.logtail, 'platform: ' + (s.platform || T('(未知)'))],
    actions: [
      /* 先点这个：命令能回来就说明管理器的执行桥正常，问题在设备侧；什么都不回来说明页面
       * 不在能执行的环境里（浏览器/快捷方式打开、或管理器版本不兼容）。 */
      { label: '测试执行桥（先点这个）', cmd: () => 'echo bridge_ok; id 2>&1 | head -1' },
      { label: '重启', cmd: (p) => q(p.linkd) + ' stop && sleep 2 && ' + q(p.linkd) + ' start' },
      { label: '停止', danger: true, warn: '停止守护进程会断开当前会话，车机将找不到手机。', cmd: (p) => q(p.linkd) + ' stop' },
      { label: '启动', cmd: (p) => q(p.linkd) + ' start' },
      /* 远程排查用：一次把「装的哪个包、激活没有、ctl/守护进程在不在、开关值、开机钩子跑到哪、
       * daemon 日志尾」全打出来。装完没重启、模块没启用、payload 缺失，一眼就能分辨。 */
      { label: '诊断（复制给开发者）', cmd: (p) => [
        'echo "== 模块目录 =="', 'ls -d /data/adb/modules*/okcar-port* 2>/dev/null || echo "(没有任何 okcar-port* 模块目录)"',
        'echo "== 激活状态 =="', 'ls /data/adb/modules/okcar-port/module.prop /data/adb/modules/okcar-port-lite/module.prop /data/adb/modules_update/okcar-port/module.prop /data/adb/modules_update/okcar-port-lite/module.prop /data/adb/modules/okcarlink/module.prop /data/adb/modules_update/okcarlink/module.prop 2>&1',
        'echo "== 解析 =="', 'echo "page_dir=' + moduleDirFromLocation() + ' resolved=' + p.dir + ' overlay=' + overlayState + '"',
        'echo "== ctl/守护进程 =="', 'ls -l ' + q(p.ctl) + ' ' + q(p.linkd) + ' 2>&1',
        'echo "== 开关 =="', q(p.ctl) + ' switches 2>&1',
        'echo "== 守护进程状态 =="', q(p.linkd) + ' status 2>&1 | head -3',
        'echo "== 开机钩子日志尾 =="', 'tail -12 ' + TMP + '/okcar-port.log 2>&1',
        /* 只 tail 尾部抓不到关键那几行：状态是每 5 秒被探针刷一次的，真正的失败行在更早处。 */
        'echo "== 会话起停结果（文件）=="',
        'grep -nE "start=|not running|cannot listen|core: |stopped" + TMP + "/okcar-linkd.log 2>/dev/null | tail -15',
        'echo "== 死机证据（内核/内存/崩溃）=="',
        'cat /proc/loadavg; uptime',
        'dmesg 2>/dev/null | grep -aiE "hang_detect|hung task|blocked for more than" | tail -10',
        'for p in $(pgrep -f com.okcar.link.daemon.LinkDaemon); do echo "daemon pid=$p"; grep -E "VmRSS|Threads" /proc/$p/status; done',
        'logcat -b crash -d 2>/dev/null | tail -8',
        'logcat -d 2>/dev/null | grep -aiE "lowmemorykiller|lmkd|ANR in|WATCHDOG" | tail -8',
        'echo "== 蓝牙/无线日志行（logcat）=="',
        'logcat -d -s OkCarLink/Event 2>/dev/null | grep -aE "wireless|core:|Bluetooth|adapter" | tail -15',
        'echo "== daemon 日志尾 =="', 'tail -6 ' + TMP + '/okcar-linkd.log 2>&1',
      ].join('; ') },
    ],
  },
  {
    id: 'session',
    name: '无线会话',
    nameLines: ['无线会话', 'RFCOMM 监听'],
    switchKey: 'session',
    warnings: [
      '关掉后守护进程照常运行，但停止 RFCOMM 监听：车机在此状态下找不到手机，直到再打开。',
      '一般不需要关，排查「换端口／另一个程序要占蓝牙」时才用。',
    ],
    health: (s) => (s.sw.session !== 'on' ? warn('已关闭')
      : (s.session_active ? ok()
        : (s.accessory_connecting ? warn('正在连接配件') : bad('未监听')))),
    raw: (s) => [s.statusfile],
    actions: [
      /* 触摸不通时先点这个：一次把"收发是否有 HID 报文、注入是否成功、注入设备在不在"打出来。
       * 接收端（车机/模拟器）那边只能证明它发了；手机侧这一半必须看这些行。 */
      { label: '钉住触摸空间（修 touch=-1/MISMATCH）', cmd: (p) => {
        const size = (state.probe && state.probe.display_size) || '1080x2400';
        return [
          'echo "== 把触摸空间钉到 ' + size + ' =="',
          q(p.linkd) + ' send size ' + size,
          'sleep 2',
          'grep -a "touch=" ' + TMP + '/okcar-linkd.status 2>/dev/null | tail -2',
        ];
      } },
      { label: '重建监听（会断开当前投屏，找不到手机时才用）', cmd: (p) => [
        'echo "== 正在重建 RFCOMM/SDP（会断开当前会话）=="',
        q(p.linkd) + ' restart 2>&1 | tail -3',
        'sleep 3',
        'logcat -d -s OkCarLink/Event 2>/dev/null | grep -aE "RFCOMM listener up|SDP record|listening as|core: cannot" | tail -5',
      ] },
      { label: '触摸诊断（复制给开发者）', cmd: (p) => [
        'echo "== 屏幕/注入设备 =="', 'wm size',
        'ls -l /dev/uinput 2>&1',
        /* 触摸/会话这些状态行进的是 logcat（tag OkCarLink/Event），不是 daemon 的 stdout 文件：
         * 只 grep 文件会得到一片空白，看着像"压根没有 HID"。两处都要看。 */
        'echo "== 触摸行（logcat）="',
        'logcat -d -s OkCarLink/Event 2>/dev/null | grep -aE "HID from|touch injected|injector|no screen known|airplay: /info" | tail -20',
        /* HID 报文走接收端的事件通道，端口来自 SETUP 应答里的 eventPort；没有它或连接失败，
         * 就永远不会有 "HID from" —— 光看 HID 行会误判成"设备不支持触摸"。这里把通道本身也抓出来。 */
        /* uinput 后端是 Android 17 上唯一的注入路径（隐藏的 InputManager 已移除）。它写的是
         * 文本协议，helper 一死写入照样"成功"——所以必须把 helper 存活、设备节点、IDC 与它自己
         * 的日志都摊开看，否则只能看到 injected=true 而屏幕上毫无反应。 */
        'echo "== uinput 后端体检 =="',
        'echo "helper pid: $(pgrep -f \'[o]kcar-uinputd\' | tr \'\\n\' \' \')"',
        'ls -l /dev/uinput 2>&1',
        'tail -8 ' + TMP + '/okcar-uinputd.log 2>/dev/null || echo "(还没有 helper 日志)"',
        'ls -l /system/usr/idc/okcar-uinput-touch.idc 2>&1',
        'cat /proc/bus/input/devices 2>/dev/null | grep -A8 -i "okcar-uinput" | head -20',
        'getevent -pl 2>/dev/null | grep -A8 -i "okcar-uinput" | head -24',
        'echo "== 事件通道 / SETUP =="',
        'logcat -d -s OkCarLink/Event 2>/dev/null | grep -aE "session: base SETUP|event channel|eventPort|command|hidReport" | tail -15',
        'echo "== 会话行（logcat 尾部）="',
        'logcat -d -s OkCarLink/Event 2>/dev/null | tail -12',
        'echo "== daemon stdout 里的注入/尺寸 =="',
        'grep -nE "injector|uinput|inject|size pinned|video size" + TMP + "/okcar-linkd.log 2>/dev/null | tail -10',
      ].join('; ') },
      { label: '会话 start', cmd: (p) => q(p.linkd) + ' send start' },
      { label: '会话 stop', danger: true, warn: '停掉监听后车机会连不上，直到再 start。', cmd: (p) => q(p.linkd) + ' send stop' },
    ],
  },
  {
    id: 'display',
    name: '虚拟屏',
    full: true,
    health: (s) => (s.display_id >= 0
      ? (s.display_mismatch ? warn('触摸目标不一致') : ok())
      : warn('未创建')),
    raw: (s) => [s.statusfile],
    actions: [
      { label: '预建屏', cmd: (p) => q(p.linkd) + ' send display up 2400x1080' },
      { label: '重试投放', cmd: (p) => q(p.linkd) + ' send place' },
      { label: '释放屏', danger: true, warn: '释放虚拟屏会结束当前投放。', cmd: (p) => q(p.linkd) + ' send display down' },
    ],
  },
  {
    id: 'target',
    name: '投放目标',
    full: true,
    health: (s) => (s.app ? ok() : warn('未指定')),
    raw: (s) => ['app=' + (s.app || '-'), 'app-ui=' + (s.appui || '-')],
    actions: [
      { label: '重试投放', cmd: (p) => q(p.linkd) + ' send place' },
      { label: '回本机桌面', cmd: (p) => q(p.linkd) + ' send desktop' },
    ],
  },
  {
    id: 'did',
    name: 'SDP DID（车机认机）',
    switchKey: 'did', nameLines: true,
    health: (s) => (s.sw.did !== 'on' ? warn('已关闭')
      : (/^0x0*4c$/i.test(String(s.did || '')) ? ok() : bad(T('未生效: ') + (s.did || '-'))))
    ,
    raw: (s) => ['bluetooth.device_id.vendor_id=' + (s.did || '-')],
    actions: [
      { label: '立即应用', cmd: (p) => q(p.ctl) + ' did on' },
      { label: '还原原值', cmd: (p) => q(p.ctl) + ' did off' },
    ],
  },
  {
    id: 'oui',
    name: '蓝牙 MAC OUI', nameLines: ['蓝牙 MAC OUI', '专为水果互联适配', '但建议保持开机自启'],
    switchKey: 'oui', nameLines: true,
    warnings: [
      '适配水果互联：小米车机上的 iPhone 互联 App 拿蓝牙 MAC 的 OUI 段去查厂商表，'
        + '本机原厂 OUI 被判成 Xiaomi 而结构性拒绝，改名字 / SDP DID / CoD 都无效，只有把 OUI 段'
        + '换成 Apple（f0:18:98）才会被当成 iPhone。',
      '警告：这个开关会修改蓝牙的 MAC 地址。已配对过的设备都要重新配对；地址每次开机可能被 nv_mac '
        + '从 QMI NV 重建，所以开机后可能需要重新应用一次。只对水果互联有意义，车机不需要它。',
          '如果真要日常使用建议开启，关闭后系统启动时会强力污染配对过的蓝牙使其不再认为自己具有 carplay 能力，导致需要重新配对才能连接。',
],
    health: (s) => (s.sw.oui !== 'on' ? warn('已关闭')
      : (/^f0:18:98/i.test(String(s.btaddr || '')) ? ok() : bad(T('未生效: ') + (s.btaddr || '-')))),
    raw: (s) => ['bluetooth_address=' + (s.btaddr || '-')],
    actions: [
      { label: '立即应用', warn: '会修改蓝牙 MAC 地址并重启蓝牙，已配对设备需重新配对。', cmd: (p) => q(p.ctl) + ' spoof-bt' },
      { label: '还原出厂 MAC', warn: '还原备份的 bt.mac 并重启蓝牙。', cmd: (p) => q(p.ctl) + ' restore-bt' },
    ],
  },
  {
    id: 'anim',
    name: 'Auto animation suppression',
    switchKey: 'anim', nameLines: false,
    warnings: [
      'What it does: while a mirror/cast session runs, the three system animation scales '
        + '(window/transition/animator) are forced to 0 and the values found before the session are '
        + 'restored when it ends. It removes the frame spike when opening an app in mirror mode.',
      'It defaults to OFF: it used to default to on, so it "suddenly" took effect whenever the switch '
        + 'file was rebuilt, and this panel had no row for it (reported 2026-10-07). Turning the switch '
        + 'off also restores your saved scales immediately, without waiting for the next session.',
    ],
    health: (s) => (s.sw.anim === 'on' ? warn('On') : ok('Off')),
    raw: (s) => ['anim=' + (s.sw.anim || '-')],
    actions: [
      { label: 'Turn on', warn: 'Forces window/transition/animator scales to 0 during mirror/cast sessions.', cmd: (p) => q(p.ctl) + ' switch anim on' },
      { label: 'Turn off and restore now', cmd: (p) => q(p.ctl) + ' switch anim off' },
    ],
  },
  {
    id: 'trim',
    name: '蓝牙 profile 裁剪（车机配对所必须）',
    switchKey: 'trim', nameLines: true,
    warnings: [
      '原因：车机的发现数据库装不下整份 SDP 应答（约 1600 B），记录一多就把末尾两条'
        + '（我们自己的）挤掉，车机因此缓存不到 128 位 UUID、报不出无线 CarPlay 支持；把 EIR 14→9、'
        + 'SDP 18→13 之后 supportCarplay 才会翻回 true。',
      '它只在配对阶段需要：车机把 SDP 缓存下来之后就不再需要裁剪，配对成功后可以关掉这个开关'
        + '（关掉会还原五个 profile）。裁过头也是真的——更激进的一档会让车机干脆不把本机当手机分类。',
    ],
    health: (s) => (s.sw.trim !== 'on' ? warn('已关闭')
      : (s.trim_all_off ? ok() : bad('未全部关掉'))),
    raw: (s) => ['profiles=' + (s.profiles || '-')],
    actions: [
      { label: '裁剪(全关·实测)', warn: '关掉五个未用 profile（配对后车机仍认 CarPlay 的实测档）；会重载蓝牙。', cmd: (p) => q(p.ctl) + ' bt-sdp trim-full' },
      { label: '裁剪(少关一条)', warn: '只关四个（保留 MAP）；非实测档。', cmd: (p) => q(p.ctl) + ' bt-sdp trim-min' },
      { label: '还原 profile', cmd: (p) => q(p.ctl) + ' bt-sdp restore' },
    ],
  },
  {
    id: 'wired',
    name: 'Wired link (this phone as the iPhone)',
    switchKey: 'wired', nameLines: true,
    warnings: [
      'On: this phone re-enumerates over USB as an Apple device (05ac:12a8) with the USBMUX interface '
        + 'and NCM, so a CarPlay host (a head unit, or the emulator on a second phone) can run wired CarPlay '
        + 'against it.  Off by default because it takes the whole USB port over.',
      'USB adb stops while it is on (the module keeps adb over TCP, port 5555).  Turning the switch off '
        + 'unbinds the gadget and hands USB back to Android as an adb device.',
      'The wired session carries the same stack as the wireless one: lockdown, the carkit service, the '
        + 'iAP2 session, then AirPlay over the NCM link.  It needs a TLS identity on the phone '
        + '(/data/local/tmp/lockdown-cert.pem + lockdown-key.pem); if the pair is missing the handshake '
        + 'dies at EnableSessionSSL, so the module generates a self-signed one when it starts.',
    ],
    health: (s) => {
      const f = s.wire_fact || '';
      /* fact= 说的是"哪个 gadget 正自称 Apple 且绑着 UDC"，不是"它是不是我们建的"：我们自己
       * start 出来的 g1（switches.conf 里 gadget=g1）同样报 apple=.../g1 udc=yes stray=no。
       * 只认 stray 会把这种**我们自己跑起来的**链路判成关闭 —— 2026-10-18 用户反馈：有线连着、
       * 面板却写"未启用"（当时是命令行直接 start，开关文件里还留着 wired=off）。
       * 所以先看 UDC 上有没有自称 Apple 的 gadget，再看开关位。 */
      const live = /apple=\/config\/usb_gadget\/\S+\s+udc=yes/.test(f);
      if (s.sw.wired === 'on') {
        if (s.wire_state === 'started') return ok('Started');
        /* 口被 ICCOA 车机（AOA 配件模式）占着：这不是"模块坏了"，而是同一个 USB 口只能有一个用途。
         * 单独报出来，别让人去反复点"重建 gadget"（点了也抢不到，还会踢掉车机正在用的会话）。
         * 注意判据是 wire_port（能不能抢），不是 wire_owner：框架的 g1 占着 UDC 是**可接管的正常
         * 状态**，把它也报成"被占用"会让有线开关看起来永远起不来（2026-10-11 现场）。 */
        if (/taken/.test(s.wire_port || '')) return bad('USB port taken by another function');
        if (/^failed/.test(s.wire_state || '')) return bad(s.wire_state);
        /* 开关是开的，但开机自启被两次失败自动停用了：不是"正在起"，是**不会再起**了。
         * 直接把原因和恢复入口说出来（下面的 Re-arm boot auto-start 动作）。 */
        if (s.wire_boot === 'disabled') return warn('Boot auto-start disabled after two failed boots - re-arm it below');
        return warn('Starting...');
      }
      /* 开关说关，但 UDC 上真有个自称 Apple 的 gadget 绑着 —— 有线确实通着，报"关闭"就是在骗人。
       * 说清楚是"事实与开关不一致"，而不是把它算成正常的关闭状态（2026-10-11 / 10-18 用户反馈）。 */
      if (live || /stray=yes/.test(f)) return warn('On (not tracked by the switch)');
      return warn('Off');
    },
    raw: (s) => ['state=' + (s.wire_state || '-'), 'fact=' + (s.wire_fact || '-'),
      'port=' + (s.wire_port || '-'), 'owner=' + (s.wire_owner || '-'), 'caps=' + (s.caps || '-'),
      'boot=' + (s.wire_boot || '-')],
    actions: [
      { label: 'Wired status', cmd: (p) => 'sh ' + q(p.carkit) + ' status 2>&1 | tail -12' },
      { label: 'Rebuild the USB gadget', warn: 'Unbinds and re-creates the gadget, then re-enumerates as an iPhone.', cmd: (p) => 'sh ' + q(p.carkit) + ' start full 2>&1 | tail -12' },
      { label: 'Give USB back to Android', warn: 'Unbinds the gadget, restores sys.usb.config=adb and turns the Java side off.', cmd: (p) => 'sh ' + q(p.carkit) + ' stop 2>&1 | tail -6' },
      { label: 'Re-arm boot auto-start', warn: 'Clears the disabled/fails/pending markers so the next boot starts wired mode again.', cmd: (p) => q(p.ctl) + ' wired-boot-arm 2>&1 | tail -4' },
      { label: 'Regenerate the TLS identity', warn: 'Rewrites /data/local/tmp/lockdown-cert.pem and lockdown-key.pem with a fresh self-signed pair; a running wired link is rebuilt so the daemon loads it.', cmd: (p) => q(p.ctl) + ' identity regen 2>&1 | tail -12' },
      { label: 'Reclaim a stray Apple gadget', warn: 'Unbinds and clears any gadget that claims to be an iPhone (including one built outside this module), then hands USB back to Android.', cmd: (p) => 'sh ' + q(p.carkit) + ' reclaim 2>&1 | tail -8' },
    ],
  },
  {
    id: 'carpp',
    name: '紫优的carplay++ 虚拟屏',
    requiresCarpp: true,
    health: (s) => (s.carpp_module === 'yes' ? ok() : bad('模块未安装')),
    raw: (s) => [
      T('apps.conf 条目=') + (s.carpp_apps || '0'),
      'extra.conf=' + (s.carpp_extra || '-'),
      'standalone=' + (s.standalone || 'off'),
      T('状态=') + (s.carpp_status || '-'),
    ],
    actions: [
      { label: '投紫优虚拟屏', cmd: (p) => q(p.linkd) + ' send app ' + CARPP_PKG + ' && ' + q(p.linkd) + ' send app-ui - && ' + q(p.linkd) + ' send mode desktop' },
      { label: '回本机桌面', cmd: (p) => q(p.linkd) + ' send desktop' },
    ],
  },
];

function visibleCats() {
  return CATS.filter((c) => !(lite && c.full) && !(c.requiresCarpp && !state.carppModule));
}

function cell(text) {
  const td = document.createElement('td');
  td.textContent = text;
  return td;
}

function makeToggle(checked, onChange, title) {
  const label = document.createElement('label');
  label.className = 'sw';
  if (title) label.title = title;
  const input = document.createElement('input');
  input.type = 'checkbox';
  input.checked = !!checked;
  input.addEventListener('change', (event) => {
    event.stopPropagation();
    input.disabled = true;
    Promise.resolve(onChange(input.checked)).finally(() => {
      input.disabled = false;
    });
  });
  const track = document.createElement('span');
  track.className = 'track';
  const knob = document.createElement('span');
  knob.className = 'knob';
  track.appendChild(knob);
  label.appendChild(input);
  label.appendChild(track);
  /* The row itself opens the detail panel; the switch must not. */
  label.addEventListener('click', (event) => event.stopPropagation());
  return label;
}

/* 把「SDP DID（…）」「蓝牙 MAC OUI（…）」「蓝牙 profile 裁剪（…）」这类名字拆成两行。 */
function splitNameLines(name) {
  const m = String(name).match(/^(.*?)[（(](.+)[）)]\s*$/);
  return m ? [m[1].trim(), m[2].trim()] : [name];
}

/* 两行状态（总状态 + 投屏状态）与分类行同结构：一个 .cat 行 = 名字 / 值 / 开关三列。
 * 总状态行的开关与分类行的开关因此天然同宽同高。 */
function statusRow(name, value, level, toggle) {
  const row = document.createElement('div');
  row.className = 'cat status';
  const main = document.createElement('div');
  main.className = 'cat-main';
  const n = document.createElement('span');
  n.className = 'cat-name';
  n.textContent = name;
  const v = document.createElement('span');
  v.className = 'cat-val center ' + level;
  v.textContent = value;
  main.appendChild(n);
  main.appendChild(v);
  row.appendChild(main);
  if (toggle) {
    const box = document.createElement('span');
    box.className = 'sw-box';
    const on = !!(state.probe && state.probe.sw && state.probe.sw.linkd === 'on');
    box.appendChild(makeToggle(on, (checked) => setMaster(checked), ''));
    row.appendChild(box);
  }
  return row;
}

function renderStatusRows() {
  const host = document.getElementById('status-cats');
  if (!host) return;
  const s = state.probe || {};
  host.innerHTML = '';
  /* moduleState/btState 是在探针回调里算出来的局部量，这里读它们的缓存。 */
  const head = state.header || {};
  /* 只保留总状态行。投屏状态行已按需求删除：它总是和真实情况对不上，留着只会误导。 */
  host.appendChild(statusRow(T('模块总状态'), (head.module || { text: T('检测中…'), level: 'dim' }).text,
    (head.module || { level: 'dim' }).level, true));
}

function renderCats() {
  if (!catsEl) return;
  const s = state.probe;
  catsEl.innerHTML = '';
  /* 需求：二级内容各做单独页面——点一行进入该行自己的页面，页面里有返回。 */
  const page = document.getElementById('cat-page');
  const section = document.getElementById('sec-cats');
  const open = state.detailId ? CATS.filter((c) => c.id === state.detailId)[0] : null;
  if (page && section) {
    if (open) {
      section.classList.add('page-open');
      catsEl.innerHTML = '';
      page.innerHTML = '';
      const back = document.createElement('button');
      back.className = 'back';
      back.textContent = T('‹ 返回');
      const title = document.createElement('h2');
      title.textContent = T(open.name);
      page.appendChild(back);
      page.appendChild(title);
      const box = document.createElement('div');
      box.className = 'detail';
      page.appendChild(box);
      const prev = detailEl;
      detailEl = box;
      try { renderDetail(); } finally { detailEl = prev; }
      back.addEventListener('click', () => { state.detailId = ''; renderCats(); });
      return;
    }
    section.classList.remove('page-open');
    page.innerHTML = '';
  }
  if (!ksuAvailable) {
    const row = document.createElement('div');
    row.className = 'cat';
    row.appendChild(cell(T('环境')));
    row.appendChild(cell(T('未在 KernelSU 管理器内打开')));
    catsEl.appendChild(row);
    return;
  }
  for (const cat of visibleCats()) {
    let health;
    try {
      health = cat.health(s);
    } catch (e) {
      health = bad(T('检查出错: ') + e);
    }
    const row = document.createElement('div');
    row.className = 'cat' + (state.detailId === cat.id ? ' open' : '');

    const main = document.createElement('div');
    main.className = 'cat-main';
    const name = document.createElement('span');
    name.className = 'cat-name';
    if (cat.nameLines) {
      /* 竖屏下这些名字超长会把状态挤成两行：主名一行、括号里的说明单独一行。 */
      const lines = Array.isArray(cat.nameLines) ? cat.nameLines.map((line) => T(line)) : splitNameLines(T(cat.name));
      for (const line of lines) {
        const ln = document.createElement('span');
        ln.className = 'cat-name-line';
        ln.textContent = line;
        name.appendChild(ln);
      }
    } else {
      name.textContent = T(cat.name);
    }
    const value = document.createElement('span');
    value.className = 'cat-val ' + health.level;
    /* 只有最上方的模块主状态行显示运行状态文字。各能力行以前在这里重复一遍，
     * 读起来是噪声，对"不是进程"的开关更是错的（动画开关曾显示成 Running ✗，2026-10-07 反馈）。
     * 开关本身已经显示开/关；完整取值留在详情面板里。 */
    value.textContent = '';
    const chev = document.createElement('span');
    chev.className = 'chev';
    chev.textContent = state.detailId === cat.id ? '▾' : '›';
    main.appendChild(name);
    main.appendChild(value);
    main.appendChild(chev);
    main.addEventListener('click', () => {
      state.detailId = state.detailId === cat.id ? '' : cat.id;
      renderCats();
    });
    row.appendChild(main);

    if (cat.switchKey) {
      const swLabel = document.createElement('span');
      swLabel.className = 'sw-label';
      swLabel.textContent = cat.switchKey;
      row.appendChild(swLabel);
      /* 读不到状态也保持可点：禁用会让人以为"开关坏了"，而点一下的输出去向本来就是
       * 输出面板 —— 那里才是能说明问题的证据。 */
      const known = s.sw && s.sw[cat.switchKey] !== undefined;
      row.appendChild(makeToggle(known && s.sw[cat.switchKey] === 'on',
        (checked) => {
          /* 8：改 MAC 有真实风险，先确认；3：守护进程与无线会话绑在一起。 */
          if (cat.switchKey === 'oui' && checked
              && !window.confirm(T('启用后会修改蓝牙MAC地址，可能会导致不可预知的bug，确认启用吗？'))) {
            return;
          }
          if (cat.switchKey === 'linkd' || cat.switchKey === 'session') return setSwitchBound(cat.switchKey, checked);
          return setSwitch(cat.switchKey, checked);
        },
        known ? '' : T('还没读到开关状态：点一下会在下面输出命令与结果')));
    }
    catsEl.appendChild(row);
  }
}

function renderDetail() {
  if (!detailEl) return;
  const s = state.probe;
  const cat = CATS.filter((c) => c.id === state.detailId)[0];
  if (!cat) {
    detailEl.classList.add('hidden');
    detailEl.innerHTML = '';
    return;
  }
  detailEl.classList.remove('hidden');
  detailEl.innerHTML = '';

  const title = document.createElement('h3');
  title.textContent = T(cat.name) + ' — ' + T('details');
  detailEl.appendChild(title);

  for (const rawWarning of cat.warnings || []) {
    const warning = T(rawWarning);
    const box = document.createElement('div');
    box.className = 'warnbox';
    box.textContent = warning;
    detailEl.appendChild(box);
  }

  let rawLines = [];
  try {
    rawLines = (cat.raw ? cat.raw(s) : []).filter((line) => line !== undefined && line !== null && line !== '');
  } catch (e) {
    rawLines = [T('读取出错: ') + e];
  }
  const raw = document.createElement('div');
  raw.className = 'raw';
  raw.textContent = rawLines.length ? rawLines.join('\n') : T('（没有可显示的状态）');
  detailEl.appendChild(raw);

  const actions = document.createElement('div');
  actions.className = 'row';
  for (const action of cat.actions || []) {
    const button = document.createElement('button');
    button.textContent = T(action.label);
    if (action.danger) button.classList.add('danger');
    if (action.warn) button.dataset.warn = T(action.warn);   /* 确认框也要跟着语言走 */
    button.dataset.catAction = cat.id + ':' + action.label;
    actions.appendChild(button);
  }
  detailEl.appendChild(actions);
}

/* ------------------------------------------------------------------ health lines */

function renderStatusLines() {
  const s = state.probe;
  if (!ksuAvailable) {
    setLine(stModule, T('未在 KernelSU 管理器内'), 'bad');
    setLine(stBt, T('未知'), 'dim');
    return;
  }
  let moduleState;
  if (s.probe_error) moduleState = bad(T('探测失败：') + s.probe_error);
  else if (s.sw.linkd !== 'on') moduleState = warn('守护进程已关闭');
  else if (!s.linkd_running) moduleState = bad('守护进程未运行');
  else if (s.sw.session !== 'on') moduleState = warn('会话已关闭');
  else if (!s.session_active) moduleState = bad('监听未发布');
  else moduleState = ok();
  state.header = { module: moduleState };
  setLine(stModule, T(moduleState.text), moduleState.level);

  let btState;
  /* 蓝牙这一行要回答"现在能不能连车机"，不是回答"蓝牙进程在不在"：
   *   已连接上车机  = 配件已经接进来（会话状态里有 accessory=）
   *   启动未完成    = 该起的程序还差（守护进程没跑 / 开关开着但 RFCOMM 还没发布）
   *   已准备好连接车机 = 其它情况（进程、地址、监听都就绪） */
  if (!s.btproc || !s.btaddr || s.btaddr === 'null') btState = bad('启动未完成');
  else if (s.accessory_connected) btState = ok('已连接上车机');
  else if (s.sw.linkd === 'on' && !s.linkd_running) btState = bad('启动未完成');
  else if (s.sw.session === 'on' && !s.session_active) btState = warn('启动未完成');
  else btState = ok('已准备好连接车机');
  if (s.sw.oui === 'on' && !/^f0:18:98/i.test(String(s.btaddr))) btState = warn('已准备好连接车机（OUI 未生效）');
  if (state.header) state.header.bt = btState; else state.header = { bt: btState };
  setLine(stBt, T(btState.text), btState.level);
  renderStatusRows();
}

function setLine(element, text, level) {
  if (!element) return;
  element.textContent = text;
  element.className = 'v ' + level;
  if (element === stModule) {
    /* 总状态行自带与守护进程同款的左右滑动开关；它以守护进程开关为准，点了就联动会话。 */
    const box = document.getElementById('sw-master');
    if (box) {
      box.innerHTML = '';
      const on = !!(state.probe && state.probe.sw && state.probe.sw.linkd === 'on');
      box.appendChild(makeToggle(on, (checked) => setMaster(checked), ''));
    }
  }
}

/* ------------------------------------------------------------------ modes */

function renderModes() {
  const carppButton = document.getElementById('mode-carpp');
  if (carppButton) carppButton.disabled = !state.carppModule;
  const carwithButton = document.getElementById('mode-carwith');
  if (carwithButton) carwithButton.disabled = !state.carwithApp;
  for (const button of document.querySelectorAll('#modes .mode')) {
    button.classList.toggle('active', button.dataset.mode === state.mode);
  }
  if (appNote) {
    if (!state.carppModule) {
      appNote.textContent = '';
    } else if (state.mode === 'carplaypp') {
      appNote.textContent = T('当前由「紫优的carplay++」模块决定虚拟屏上显示哪些应用；')
        + T('下面选中的应用只作为模块不可用时的备用目标。');
    } else {
      const match = autoMatchApp();
      appNote.textContent = match
        ? T('默认匹配：') + match.label + T('（') + match.pkg + T('）') + T('— 装了哪个导航就投哪个。')
        : T('百度地图 / 高德地图 / 腾讯地图都没装：请在上面的选择器里手动选一个应用。');
    }
  }
}

function autoMatchApp() {
  for (const candidate of NAV_DEFAULTS) {
    const found = state.apps.filter((a) => a.pkg === candidate.pkg)[0];
    if (found) return { pkg: found.pkg, label: found.label || candidate.labels[0] };
    const byLabel = state.apps.filter((a) => candidate.labels.indexOf(a.label) >= 0)[0];
    if (byLabel) return { pkg: byLabel.pkg, label: byLabel.label };
  }
  return null;
}

async function setMode(mode, apply) {
  if (mode === 'carplaypp' && !state.carppModule) return;
  if (mode === 'carwith' && !state.carwithApp) return;
  const p = await resolvePaths();
  state.mode = mode;
  renderModes();
  /* 模式文件必须在两种分支都写：apply=true 之前漏了这一步，于是点"镜像"只改了运行中的实例，
   * 重启后守护进程又从旧模式文件读回 app —— 现场就是"切镜像无效，还是应用投屏"。 */
  await writeFileText(MODE_FILE, mode);
  if (state.carppModule && mode !== 'carwith') await writeFileText(CARPP_DIR + '/mode', mode);
  if (!apply) return;
  say('== 切换模式: ' + mode + ' ==\n');
  /* 模式是在守护进程启动时按 okcar-port-mode 注入的，所以切模式后必须重启守护进程才能真正
   * 生效（否则运行中的实例仍按旧模式投放——现场就是"选了应用投屏还是镜像"）。 */
  if (mode === 'mirror') {
    await execAndShow(q(p.linkd) + ' send mode mirror');
    await execAndShow(q(p.linkd) + ' restart');
  } else if (mode === 'app') {
    const chosen = state.pickedPkg || (autoMatchApp() || {}).pkg || '';
    if (!chosen) {
      append('没有可选的应用：先在「投屏应用选择」里选一个应用。\n');
      return;
    }
    const pkg = safeComponent(chosen);
    const ui = safeComponent(activityForPackage(state.pickedActivity, chosen));
    if (pkg === null || ui === null) {
      append('包名或 activity 含非法字符，已拒绝下发。\n');
      return;
    }
    await execAndShow(q(p.linkd) + ' send app ' + pkg);
    await execAndShow(q(p.linkd) + ' send app-ui ' + ui);
    await execAndShow(q(p.linkd) + ' send mode desktop');
    await execAndShow(q(p.linkd) + ' restart');
  } else if (mode === 'carplaypp') {
    await execAndShow(q(p.linkd) + ' send app ' + CARPP_PKG);
    await execAndShow(q(p.linkd) + ' send app-ui -');
    await execAndShow(q(p.linkd) + ' send mode desktop');
    await execAndShow(q(p.linkd) + ' restart');
  } else {
    /* CarWith 投屏：把 CarWith（小米车机互联）本身作为固定目标投到车机上，和紫优模式的接法一致。
     * 必须显式给 activity：CarWith 零 LAUNCHER Activity，只传包名时 placement 会报
     * "cannot find a launch entry for com.miui.carlink"，车机屏永远是空的（2026-10-08 现场）。
     * 同时写 app-ui conf，否则重启守护进程后 launcher 从 conf 取不到它。 */
    await execAndShow(q(p.linkd) + ' send app ' + CARWITH_PKG);
    await execAndShow(q(p.linkd) + ' send app-ui ' + CARWITH_UI);
    await writeFileText(TMP + '/okcar-port-app-display.conf', CARWITH_UI);
    await execAndShow(q(p.linkd) + ' send mode desktop');
    await execAndShow(q(p.linkd) + ' restart');
  }
  await writeFileText(MODE_FILE, mode);
  if (state.carppModule && mode !== 'carwith') await writeFileText(CARPP_DIR + '/mode', mode);
  /* 镜像模式下把残留的应用目标清空：否则下一次重连（比如旋转导致会话重建）会带着旧目标，
   * 变成"应用投屏"——而 Android 17 上只剩 uinput 注入，触摸会落在手机屏上（实测 2026-10-03）。 */
  if (mode === 'mirror') {
    await writeFileText(TMP + '/okcar-port-app.conf', '');
    await writeFileText(TMP + '/okcar-port-app-display.conf', '');
  }
  await refresh();
}

/* ------------------------------------------------------------------ app picker */

function parseTsv(text, columns) {
  const rows = [];
  for (const line of String(text || '').split('\n')) {
    if (!line.trim()) continue;
    const parts = line.split('\t');
    if (parts.length < columns) continue;
    rows.push(parts);
  }
  return rows;
}

async function loadApps(askDaemon) {
  const p = await resolvePaths();
  if (askDaemon) {
    await execAndShow(q(p.linkd) + ' send apps');
    await new Promise((resolve) => setTimeout(resolve, 1500));
  }
  const result = await run('echo "@@APPS"; cat ' + q(APPS_TSV) + ' 2>/dev/null; '
    + 'echo "@@ACTS"; cat ' + q(ACTIVITIES_TSV) + ' 2>/dev/null');
  const text = String(result.stdout || '');
  const appsPart = text.split('@@APPS')[1] ? text.split('@@APPS')[1].split('@@ACTS')[0] : '';
  const actsPart = text.split('@@ACTS')[1] || '';
  restoreAppChoice();
  state.apps = parseTsv(appsPart, 2).map((parts) => ({
    pkg: parts[0].trim(),
    label: (parts[1] || parts[0]).trim(),
    activity: (parts[2] || '').trim(),
  }));
  state.activities = {};
  for (const parts of parseTsv(actsPart, 2)) {
    const pkg = parts[0].trim();
    if (!state.activities[pkg]) state.activities[pkg] = [];
    state.activities[pkg].push({ name: parts[1].trim(), label: (parts[2] || parts[1]).trim() });
  }
  renderPickers();
  if (!state.apps.length) {
    append('\n读不到应用清单 ' + APPS_TSV + '：守护进程还没生成过它。点「刷新应用列表」让守护进程重新导出，'
      + '或在「输出」里看 `okcar-linkd send apps` 的报错。\n');
  }
}

function renderPickers() {
  if (!pickPkg) return;
  const previous = state.pickedPkg;
  pickPkg.innerHTML = '';
  if (!state.apps.length) {
    const option = document.createElement('option');
    option.value = '';
    option.textContent = T('（还没有应用清单）');
    pickPkg.appendChild(option);
  } else {
    const auto = document.createElement('option');
    auto.value = '';
    auto.textContent = T('默认匹配（百度/高德/腾讯地图）');
    pickPkg.appendChild(auto);
    for (const app of state.apps) {
      const option = document.createElement('option');
      option.value = app.pkg;
      option.textContent = app.label + T('（') + app.pkg + T('）');
      pickPkg.appendChild(option);
    }
  }
  if (previous && state.apps.some((a) => a.pkg === previous)) pickPkg.value = previous;
  renderActivityPicker();
}


/* 自带搜索的下拉：点按钮开面板，面板里输入即过滤，点条目即选中；选中的值同步写进隐藏的
 * 原生 select，既有代码照原样读 select.value。应用与 Activity 共用这一份实现。 */
function initPicker(opts) {
  const btn = document.getElementById(opts.button);
  const panel = document.getElementById(opts.panel);
  const search = document.getElementById(opts.search);
  const list = document.getElementById(opts.list);
  const select = document.getElementById(opts.select);
  if (!btn || !panel || !list || !select) return null;
  /* 字段名由调用方显式给出：应用是 {pkg,label}，activity 是 {name,label}，不猜、不兜底。 */
  const valueOf = opts.value;
  const labelOf = opts.label || ((item) => item.label || '');
  function textOf(item) {
    const v = valueOf(item);
    const l = labelOf(item);
    return (l ? l + T('（') + v + T('）') : v);
  }
  function render() {
    const q = (search && search.value || '').trim().toLowerCase();
    list.innerHTML = '';
    for (const item of opts.items()) {
      const text = textOf(item);
      if (q && text.toLowerCase().indexOf(q) < 0) continue;
      const row = document.createElement('div');
      row.className = 'picker-item' + (select.value === valueOf(item) ? ' active' : '');
      row.textContent = text;
      row.addEventListener('click', (ev) => {
        ev.stopPropagation();
        select.value = valueOf(item);
        btn.textContent = text;
        panel.classList.add('hidden');
        if (opts.onPick) opts.onPick(valueOf(item));
      });
      list.appendChild(row);
    }
    if (!list.childElementCount) {
      const none = document.createElement('div');
      none.className = 'picker-item empty';
      none.textContent = T('（没有匹配项）');
      list.appendChild(none);
    }
  }
  btn.addEventListener('click', (ev) => {
    ev.stopPropagation();
    const opening = panel.classList.contains('hidden');
    document.querySelectorAll('.picker-panel').forEach((other) => {
      if (other !== panel) other.classList.add('hidden');
    });
    panel.classList.toggle('hidden');
    if (opening) { if (search) search.value = ''; render(); if (search) search.focus(); }
  });
  if (search) search.addEventListener('input', render);
  panel.addEventListener('click', (ev) => ev.stopPropagation());
  return render;
}

/* 点面板外面（或按 Esc）收回下拉：选了不选都要能收。 */
document.addEventListener('click', () => {
  document.querySelectorAll('.picker-panel').forEach((p) => p.classList.add('hidden'));
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') document.querySelectorAll('.picker-panel').forEach((p) => p.classList.add('hidden'));
});

let renderAppPicker = null;
let renderActPicker = null;

/* app-ui 是"两段启动"里那个画车机屏的 Activity（应用自己的启动 Activity 留在手机上当引擎宿主）。
 * 它**必须属于被投的那个应用**。完整版（非 lite）的开机钩子会把 app-ui 默认写成我们自己的桌面
 * （com.okcar.desktop/.DesktopActivity），于是用户只换应用、不动 Activity 时，那个旧值会被原样
 * 下发，车机就一直停在旧界面上——"重选应用也换不掉它"（2026-10-04 现场）。lite 没有这个默认值，
 * 所以只有完整版犯。这里统一收口：ui 的包名与 pkg 不一致就当成没选，下发 "-" 清掉。 */
function activityForPackage(ui, pkg) {
  const s = String(ui || '').trim();
  if (!s || s === '-') return '-';
  const slash = s.indexOf('/');
  if (slash <= 0) return '-';
  return s.slice(0, slash) === String(pkg || '') ? s : '-';
}

/* 页面重开/重启后把已持久化的选择回填到选择框：应用与 activity 都存在 /data/local/tmp 的
 * conf 里（守护进程启动时也会读它们），但页面以前不回填，看起来像"每次都要重选"。
 * 这里只回填，不写回，避免把用户的临时点击变成持久选择。 */
async function restoreAppChoice() {
  const appConf = await run('cat ' + q(TMP + '/okcar-port-app.conf') + ' 2>/dev/null', 8000);
  const pkg = String(appConf.stdout || '').trim();
  if (!pkg) return;
  const sel = document.getElementById('pick-pkg');
  const btn = document.getElementById('picker-app-btn');
  const found = (state.apps || []).filter((a) => a.pkg === pkg)[0];
  state.pickedPkg = pkg;
  if (sel) sel.value = pkg;
  if (btn) btn.textContent = found ? (found.label || pkg) + T('（') + pkg + T('）') : pkg;
  renderActivityPicker();
  if (renderActPicker) renderActPicker();
  const uiConf = await run('cat ' + q(TMP + '/okcar-port-app-display.conf') + ' 2>/dev/null', 8000);
  const ui = String(uiConf.stdout || '').trim();
  if (!ui) return;
  // 不属于这个应用的 app-ui（完整版开机种的桌面 Activity）不回填：回填了就会被原样下发，
  // 用户重选应用时它又冒出来。
  if (activityForPackage(ui, pkg) === '-') return;
  const asel = document.getElementById('pick-activity');
  const abtn = document.getElementById('picker-act-btn');
  state.pickedActivity = ui;
  if (asel) asel.value = ui;
  if (abtn) {
    const act = ((state.activities || {})[pkg] || []).filter((a) => a.name === ui)[0];
    abtn.textContent = act ? (act.label || ui) + T('（') + ui + T('）') : ui;
  }
}

function renderActivityPicker() {
  if (!fieldActivity || !pickActivity) return;
  const pkg = state.pickedPkg || (autoMatchApp() || {}).pkg || '';
  const list = state.activities[pkg] || [];
  // 换了应用就把不属于新应用的旧选择清掉：否则它会一直挂在下拉里，被当成"用户选过的 Activity"
  // 原样下发（完整版默认种的是桌面的 Activity，症状就是"重选应用也换不掉它"）。
  if (state.pickedActivity && activityForPackage(state.pickedActivity, pkg) === '-') {
    state.pickedActivity = '';
  }
  pickActivity.innerHTML = '';
  fieldActivity.classList.toggle('hidden', list.length === 0);
  if (!list.length) {
    state.pickedActivity = '';
    return;
  }
  const none = document.createElement('option');
  none.value = '';
  none.textContent = T('（默认 launcher activity）');
  pickActivity.appendChild(none);
  for (const activity of list) {
    const option = document.createElement('option');
    option.value = activity.name;
    option.textContent = activity.label + T('（') + activity.name + T('）');
    pickActivity.appendChild(option);
  }
  if (state.pickedActivity && list.some((a) => a.name === state.pickedActivity)) {
    pickActivity.value = state.pickedActivity;
  }
}

/* ------------------------------------------------------------------ switches */

const SWITCH_LABELS = {
  linkd: { on: '守护进程开关打开', off: '守护进程开关关闭（车机将找不到手机）' },
  session: { on: '无线会话开关打开', off: '无线会话开关关闭（停止监听）' },
  did: { on: 'SDP DID 开关打开', off: 'SDP DID 开关关闭' },
  oui: { on: 'OUI 开关打开（会修改蓝牙 MAC）', off: 'OUI 开关关闭' },
  trim: { on: '裁剪开关打开（配对阶段用）', off: '裁剪开关关闭' },
  anim: { on: '自动关动画打开（会话期间压掉系统转场动画）', off: '自动关动画关闭（保留系统动画）' },
};

/* 总开关：关掉时把下面所有开关一起关掉，但先把"用户原本开着哪些"记到设备文件里；
 * 再打开时按记录原样开回来（没有记录就按出厂默认：linkd/session/did 开，oui/trim 关）。
 * 记录用文件而不是内存，因为页面会被关掉、手机会重启。 */
const MASTER_MEMORY = TMP + '/okcar-port-master-memory.conf';
const MASTER_KEYS = ['linkd', 'session', 'did', 'oui', 'trim', 'anim', 'wired'];
const MASTER_DEFAULTS = ['linkd', 'session', 'did'];

function switchIsOn(key) {
  return !!(state.probe && state.probe.sw && state.probe.sw[key] === 'on');
}

async function setMaster(on) {
  if (!on) {
    const wasOn = MASTER_KEYS.filter(switchIsOn);
    say('== 关闭总开关：先记住已开启的开关，再全部关闭 ==\n');
    const saved = await run('printf %s ' + q((wasOn.length ? wasOn : MASTER_DEFAULTS).join(' '))
      + ' > ' + q(MASTER_MEMORY));
    append(format(saved) + '\n');
    await Promise.all(MASTER_KEYS.map((key) => (switchIsOn(key) ? setSwitch(key, false) : Promise.resolve())));
    return;
  }
  const read = await run('cat ' + q(MASTER_MEMORY) + ' 2>/dev/null');
  const saved = String(read.stdout || '').trim().split(/\s+/).filter(Boolean);
  const want = saved.length ? saved : MASTER_DEFAULTS;
  say('== 打开总开关：按记录原样开回来（' + want.join(' ') + '） ==\n');
  await Promise.all(want.map((key) => setSwitch(key, true)));
  await run('rm -f ' + q(MASTER_MEMORY));
}

/* 守护进程与无线会话是同一个东西的两半：会话监听由守护进程提供。用户明确要求"要死一起死"
 * —— 关任意一个就两个都关；打开守护进程时把会话一并打开。 */
async function setSwitchBound(key, checked) {
  if (!checked) {
    /* 关的时候必须把这一把真的关掉：原来这里只处理 linkd/session，于是 did/oui/trim 永远
     * 关不掉（"SDP 等好久都不关"就是这个）。 */
    const jobs = [setSwitch(key, false)];
    if (key === 'linkd') jobs.push(setSwitch('session', false));
    if (key === 'session') jobs.push(setSwitch('linkd', false));
    await Promise.all(jobs);
    return;
  }
  if (key === 'linkd' || key === 'session') {
    /* 打开时守护进程与会话一起起来（并行），否则会话开关点了没反应。 */
    await Promise.all([setSwitch('linkd', true), setSwitch('session', true)]);
    return;
  }
  await setSwitch(key, true);
}

async function setSwitch(key, checked) {
  const p = await resolvePaths();
  const value = checked ? 'on' : 'off';
  const label = SWITCH_LABELS[key] || { on: key + ' on', off: key + ' off' };
  say('== ' + (checked ? label.on : label.off) + ' ==\n');
  const command = q(p.ctl) + ' switch ' + key + ' ' + value;
  const result = await run(command, { timeoutMs: EXEC_TIMEOUT_ACTION_MS });
  append(format(result) + '\n');
  /* 空手而归时要说清楚是"谁来执行"这一步的问题，别让人以为开关本身坏了。 */
  if (result.errno !== 0 || !String(result.stdout || '').trim()) {
    append('提示：命令没有回结果。请确认本页是从 KernelSU 管理器的模块页打开的（页面需要\n'
      + '      管理器的 kernelsu.exec 才能执行命令）；也可以在手机终端手动跑：\n'
      + '      su -c \'' + command + '\'\n');
  }
  await refresh();
}

/* ------------------------------------------------------------------ watchdog */

async function refresh() {
  await probe();
  renderStatusLines();
  renderModes();
  renderCats();
  renderCats();
  /* 码率/帧率那行要跟着 probe 走：里面的「实际生效」来自 daemon 写的 applied 文件，
   * 只在打开页面时渲染一次的话，用户改了设置、重连之后看到的还是旧值。 */
  renderQosNote();
  runWatchdog();
}

/*
 * Auto-correction: the product requirement is that the module state is re-checked on a timer and
 * corrected.  Only corrections that cannot surprise the operator are made, each at most once per
 * FIX_COOLDOWN_MS, and every one is written to the output panel.
 */
async function correct(key, description, command) {
  const now = Date.now();
  if (now - (state.lastFix[key] || 0) < FIX_COOLDOWN_MS) return;
  state.lastFix[key] = now;
  append('\n[自动修正] ' + description + '\n$ ' + command + '\n');
  const result = await run(command);
  append(format(result) + '\n');
}

async function runWatchdog() {
  if (!ksuAvailable || !state.autoFix) return;
  const s = state.probe;
  const p = await resolvePaths();
  /* 开关关着就别去「修正」——那是用户的选择，不是故障。 */
  if (s.sw.linkd === 'on' && !s.linkd_running) {
    await correct('daemon', '守护进程不在，重新拉起', q(p.linkd) + ' start >/dev/null 2>&1; echo done');
  }
  /* 这里原来有一条"进程还活着但监听没了就自动 stop+start"的规则，2026-10-03 实测把它去掉了：
   * 投屏成功后守护进程会把状态文件改写成会话描述（不再以 "listening as" 开头），自检因此误判
   * "监听没了"，于是在投屏中途重启守护进程——正在跑的会话被打断，而且"监听重启"这个现象就是它。
   * 重建 RFCOMM/SDP 必须由人决定（会话详情里的「重建监听」按钮），绝不自动做。 */
  if (s.linkd_running && s.sw.did === 'on' && !/^0x0*4c$/i.test(String(s.did || ''))) {
    await correct('did', 'SDP DID 被改回去了（默认开关是打开），重新写入',
      q(p.ctl) + ' did on');
  }
}

function startWatchdog() {
  if (state.timer) clearInterval(state.timer);
  state.timer = setInterval(() => {
    refresh().catch((e) => append('\n[自检出错] ' + e + '\n'));
  }, PROBE_INTERVAL_MS);
}

/* ------------------------------------------------------------------ theme */

function applyTheme(choice) {
  state.theme = choice;
  const effective = choice === 'auto'
    ? (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'night' : 'day')
    : choice;
  document.documentElement.dataset.theme = effective;
  for (const button of document.querySelectorAll('#theme-seg .seg-item')) {
    button.classList.toggle('active', button.dataset.theme === choice);
  }
}

async function setTheme(choice) {
  applyTheme(choice);
  await writeFileText(THEME_FILE, choice);
  if (state.carppModule) {
    /* The 紫优 virtual screen app reads its own copy, so the two stay in step. */
    await writeFileText(CARPP_DIR + '/theme', choice);
  }
  say('显示模式：' + ({ auto: '跟随系统', day: '白天', night: '黑夜' }[choice] || choice) + '\n');
}

/* ------------------------------------------------------------------ events */

document.addEventListener('click', async (event) => {
  const modeButton = event.target.closest('#modes .mode');
  if (modeButton) {
    if (modeButton.disabled) return;
    modeButton.disabled = true;
    /* 模式是在守护进程启动时注入的：只写模式文件不改运行中的实例，所以必须重启守护进程才生效。
     * 之前是"切换后静默重启"，用户看不到、也常以为没生效（现场：以为必须手动重启）。现在先问一句。 */
    if (!window.confirm(T('切换投屏模式需要重启守护进程，会断开当前投屏。\n确认切换并重启吗？'))) {
      modeButton.disabled = false;
      return;
    }
    try {
      await setMode(modeButton.dataset.mode, true);
    } catch (e) {
      append('\n[切换模式出错] ' + e + '\n');
    } finally {
      modeButton.disabled = false;
    }
    return;
  }

  const themeButton = event.target.closest('#theme-seg .seg-item');
  if (themeButton) {
    try {
      await setTheme(themeButton.dataset.theme);
    } catch (e) {
      append('\n[切换显示模式出错] ' + e + '\n');
    }
    return;
  }

  const button = event.target.closest('button');
  if (!button) return;
  const warn = button.dataset.warn;
  if (warn && !window.confirm(warn + '\n\n' + T('执行？'))) return;
  button.disabled = true;
  try {
    if (button.id === 'btn-enable') {
      await enableAll();
    } else if (button.id === 'btn-apply-app') {
      await applyAppChoice();
    } else if (button.id === 'btn-refresh-apps') {
      await loadApps(true);
      await refresh();
    } else if (button.id === 'btn-clear') {
      say('');
    } else if (button.dataset.catAction) {
      const index = button.dataset.catAction.indexOf(':');
      const catId = button.dataset.catAction.slice(0, index);
      const actionLabel = button.dataset.catAction.slice(index + 1);
      const cat = CATS.filter((c) => c.id === catId)[0];
      const action = cat && (cat.actions || []).filter((a) => a.label === actionLabel)[0];
      const p = await resolvePaths();
      if (action) await execAndShow(action.cmd(p));
      await refresh();
    }
  } catch (e) {
    append('\n[WebUI 出错] ' + e + '\n');
  } finally {
    button.disabled = false;
  }
});

if (pickPkg) {
  pickPkg.addEventListener('change', () => {
    state.pickedPkg = pickPkg.value;
    state.pickedActivity = '';
    renderActivityPicker();
  });
}
if (pickActivity) {
  pickActivity.addEventListener('change', () => { state.pickedActivity = pickActivity.value; });
}

/* ------------------------------------------------------------------ one-key start */

/*
 * The one button an installer presses: apply the switch set, then the daemon.
 *
 * The order matters and was wrong once (measured on 217, 2026-10-03): the `bluetooth.profile.*`
 * props are read when the Bluetooth stack initialises, so trimming *after* the radio cycle left the
 * running stack advertising the full record set — the phone looked trimmed in the WebUI while the
 * head unit still reported no CarPlay after pairing.  `apply` does the switches (including their own
 * radio cycle), then the daemon starts last so the RFCOMM listener is published against the stack
 * that finally came up.
 */
async function enableAll() {
  const p = await resolvePaths();
  say('== 一键启动 ==\n');
  await execAndShow(q(p.ctl) + ' apply');
  await execAndShow(q(p.linkd) + ' start');
  await new Promise((resolve) => setTimeout(resolve, 4000));
  await execAndShow(q(p.linkd) + ' send status');
  await new Promise((resolve) => setTimeout(resolve, 1500));
  await refresh();
}

async function applyAppChoice() {
  const p = await resolvePaths();
  const pkg = safeComponent(state.pickedPkg || (autoMatchApp() || {}).pkg || '');
  const ui = safeComponent(activityForPackage(state.pickedActivity, pkg));
  if (pkg === null || ui === null) {
    append('包名或 activity 含非法字符，已拒绝下发。\n');
    return;
  }
  if (pkg === '') {
    append('没有选中应用，也没有默认匹配到导航应用。\n');
    return;
  }
  say('== 应用投屏目标 ==\n');
  await execAndShow(q(p.linkd) + ' send app ' + pkg);
  await execAndShow(q(p.linkd) + ' send app-ui ' + ui);
  await writeFileText(MODE_FILE, 'app');
  if (state.carppModule) await writeFileText(CARPP_DIR + '/mode', 'app');
  /* 写文件不等于生效：模式是守护进程的**启动参数**。正在跑镜像时这里不重启，面板就切成"应用
   * 投屏"而车机上还是镜像（2026-10-18 现场）。 */
  if (state.probe && state.probe.mode_running && state.probe.mode_running !== 'app') {
    say('当前以 ' + state.probe.mode_running + ' 运行，重启守护进程让这个选择生效…\n');
    await execAndShow(q(p.linkd) + ' restart');
  }
  await refresh();
}

/* ------------------------------------------------------------------ variant */

let variantApplied = false;
function applyVariant() {
  if (!lite || variantApplied) return;
  variantApplied = true;
  const badge = document.getElementById('variant');
  if (badge) badge.classList.remove('hidden');
  for (const id of ['btn-enable']) {
    /* 一键启动 stays: the lite build has the same switches and the same daemon. */
    const el = document.getElementById(id);
    if (el) el.classList.remove('hidden');
  }
}

/* ------------------------------------------------------------------ boot */

/* 首屏渲染前先把语言读回来，然后让选择器保持同步。在这里读（而不是模块加载时）是为了让其余常量保持同步：
 * 只有页面跑在管理器里，桥才可用。 */
async function initLang() {
  try {
    const r = await run('cat ' + q(LANG_FILE) + ' 2>/dev/null');
    const v = String((r && r.stdout) || '').trim();
    if (v === 'en' || v === 'zh') LANG = v;
  } catch (e) {
    /* 默认中文 */
  }
  applyStaticLang();
  const sel = document.getElementById('lang');
  if (sel) sel.addEventListener('change', () => { setLang(sel.value); });
}

window.addEventListener('error', (event) => append('\n[WebUI 错误] ' + (event && event.message ? event.message : event) + '\n'));
window.addEventListener('unhandledrejection', (event) => append('\n[WebUI 未处理异常] ' + (event && event.reason ? event.reason : event) + '\n'));

if (window.matchMedia) {
  try {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
      if (state.theme === 'auto') applyTheme('auto');
    });
  } catch (e) {
    /* older WebView without addEventListener on MediaQueryList: the timer keeps the page usable */
  }
}

(async function boot() {
  ksuAvailable = ksuApi() !== null;
  applyTheme('auto');
  /* 先把语言读回来：后面所有渲染（行、动作按钮、状态词）都要过 T()。
   * 读语言文件和挂选择器都不需要执行桥，所以放在桥判断之前——浏览器（没有
   * kernelsu.exec）里也要能用语言选择器，不能只在管理器里生效。 */
  try { await initLang(); } catch (e) { /* 读不回来就用默认中文 */ }
  if (!ksuAvailable) {
    envBanner = { key: 'openInManager' };
    renderEnvBanner();
    renderStatusLines();
    return;
  }
  try {
  await ensureExecProtocol();
  await resolvePaths();
  applyVariant();
  await loadApps(false);
  await refresh();
  /* The stored choices come from the probe (files on disk), so the page always shows what the
   * device is actually configured with rather than what this browser session last clicked. */
  const s = state.probe;
  if (s.theme_file === 'day' || s.theme_file === 'night' || s.theme_file === 'auto') {
    applyTheme(s.theme_file);
  }
  if (s.mode_running === 'mirror' || s.mode_running === 'app' || s.mode_running === 'carplaypp') {
    state.mode = s.mode_running;
  } else if (s.mode_file === 'mirror' || s.mode_file === 'app' || s.mode_file === 'carplaypp'
      || (s.mode_file === 'carwith' && state.carwithApp)) {
    state.mode = s.mode_file;
  } else if (state.carppModule && s.carpp_mode === 'carplaypp') {
    state.mode = 'carplaypp';
  } else {
    /* 新装没有模式文件、也没有应用目标：能投的就是手机屏幕本身，所以兜底是 mirror —— 与
     * okcar-linkd 缺省时写进文件的值一致，两边不会一个说应用投屏一个在镜像（2026-10-18）。 */
    state.mode = 'mirror';
  }
  state.pickedPkg = s.app && state.apps.some((a) => a.pkg === s.app) ? s.app : '';
  renderModes();
  renderPickers();
  startWatchdog();
  } catch (e) {
    /* 启动阶段任何异常都要说出来：静默失败在界面上就是"一直检测中"。 */
    envBanner = { key: 'bootFailed', detail: (e && e.message ? e.message : e) };
    renderEnvBanner();
    say('[启动失败] ' + (e && e.message ? e.message : e) + '\n');
  }
  {
    renderAppPicker = initPicker({
      picker: 'picker-app', button: 'picker-app-btn', panel: 'picker-app-panel',
      search: 'picker-app-search', list: 'picker-app-list', select: 'pick-pkg',
      items: () => state.apps,
      value: (app) => app.pkg,
      label: (app) => app.label || '',
      onPick: (pkg) => { state.pickedPkg = pkg; renderActivityPicker(); if (renderActPicker) renderActPicker(); },
    });
    renderActPicker = initPicker({
      picker: 'picker-act', button: 'picker-act-btn', panel: 'picker-act-panel',
      search: 'picker-act-search', list: 'picker-act-list', select: 'pick-activity',
      items: () => state.activities[state.pickedPkg] || [],
      value: (act) => act.name,
      label: (act) => act.label || '',
    });
    const sb = document.getElementById('btn-select-all');
    if (sb) sb.addEventListener('click', selectOutputText);
    bindQos();
    bindPrio();
    const ex = document.getElementById('btn-export-out');
    if (ex) ex.addEventListener('click', async () => {
      ex.disabled = true;
      try { await exportOutputToFile(); } finally { ex.disabled = false; }
    });
  }

})();
