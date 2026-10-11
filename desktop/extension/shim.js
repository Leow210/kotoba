// Runs the page-lookup script (kotoba-web.user.js) as an extension content script: Tampermonkey's GM_* calls are
// answered from chrome.storage, and requests to Kotoba go through the background worker.
(async () => {
  if (window.__kotobaExtension) return;
  window.__kotobaExtension = true;
  const __cfg = await chrome.storage.local.get(null);
  chrome.storage.onChanged.addListener(changes => { for (const k in changes) __cfg[k] = changes[k].newValue; });
  const GM_getValue = (k, d) => (k in __cfg && __cfg[k] !== undefined ? __cfg[k] : d);
  const GM_setValue = (k, v) => { __cfg[k] = v; chrome.storage.local.set({ [k]: v }); };
  const GM_registerMenuCommand = () => {};
  const GM_xmlhttpRequest = o => {
    chrome.runtime.sendMessage({ t: 'http', url: o.url, method: o.method, data: o.data, headers: o.headers, timeout: o.timeout })
      .then(r => { if (!r || !r.ok) return o.onerror && o.onerror(); o.onload({ status: r.status, responseText: r.text }); })
      .catch(() => o.onerror && o.onerror());
  };
