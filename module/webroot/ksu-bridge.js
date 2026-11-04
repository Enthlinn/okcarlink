/* 让 KernelSU 那套 WebUI 在普通浏览器里也能用的兼容层（本机面板专用）。
 * 页面的 exec 三参协议原样实现：kernelsu.exec(cmd, optionsJson, callbackName) → 命令经
 * cgi-bin/ctl?do=exec 以 root 执行 → window[callbackName](errno, stdout, stderr)。
 * 面板只监听 127.0.0.1 且要求 token，所以这条通道等价于管理器 WebUI 的命令面。 */
(function () {
  /* 管理器 WebView 里已经注入过原生桥（ksu/kernelsu 至少一个有 exec），这时**必须什么都不做**：
   * 一旦覆盖，命令就会走本机 http 面板那条 fetch→CGI 路径，而 WebView 里没有 CGI，
   * 回调永远不触发 —— 现场表现就是"命令没有回音"（实测 2026-10-03，KernelSU/217）。 */
  var native = null;
  try {
    if (window.ksu && typeof window.ksu.exec === 'function') native = window.ksu;
    else if (window.kernelsu && typeof window.kernelsu.exec === 'function') native = window.kernelsu;
  } catch (e) { native = null; }
  if (native) return;   // 原生桥在位：本层完全退出

  var token = new URLSearchParams(location.search).get('token') || '';
  var moduleDir = window.__OKCAR_MODULE_DIR || '';
  function b64(text) {
    // UTF-8 安全：先转成字节再 base64，避免命令里的中文被 btoa 抛错
    var bytes = new TextEncoder().encode(text);
    var bin = '';
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    // base64url、无填充：这样 encodeURIComponent 完全不改动它，shell 侧也不用做任何手工解码
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function unb64(text) {
    if (!text) return '';
    try {
      var t = String(text).replace(/\s+/g, '').replace(/-/g, '+').replace(/_/g, '/');
      while (t.length % 4) t += '=';
      var bin = atob(t);
      var bytes = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      return new TextDecoder().decode(bytes);
    } catch (e) { return ''; }
  }
  function exec(cmd, optionsJson, callbackName) {
    var name = typeof optionsJson === 'string' && callbackName === undefined ? optionsJson : callbackName;
    var url = 'cgi-bin/ctl?do=exec&token=' + encodeURIComponent(token)
            + '&b64=' + encodeURIComponent(b64(cmd));
    fetch(url).then(function (r) { return r.json(); }).then(function (j) {
      if (name && typeof window[name] === 'function') {
        window[name](j.errno, unb64(j.stdout), unb64(j.stderr));
      }
    }).catch(function (e) {
      if (name && typeof window[name] === 'function') window[name](1, '', String(e));
    });
  }
  // 页面探测的是 kernelsu，但真正调用的是 ksu（app.js:147）。真机 WebView 里两个名字
  // 都存在（管理器都注入），所以这里必须都定义——只定义一个就表现为“命令没有回音”（实测）。
  var api = window.kernelsu || {};
  api.exec = exec;
  api.toast = api.toast || function (m) {
    var el = document.createElement('div');
    el.textContent = m; el.style.cssText = 'position:fixed;left:0;right:0;bottom:12px;text-align:center;'
      + 'background:#1d4ed8;color:#fff;padding:8px;border-radius:8px;margin:0 12px;z-index:99';
    document.body.appendChild(el); setTimeout(function () { el.remove(); }, 2000);
  };
  window.kernelsu = api;
  window.ksu = api;
  window.__OKCAR_PANEL = { moduleDir: moduleDir };
})();
