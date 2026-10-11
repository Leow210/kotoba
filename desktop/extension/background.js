// The page can't call Kotoba on 127.0.0.1 itself (a secure page may not reach http, and the key must stay out of the page),
// so the content script asks this worker, which makes the request with the extension's own permissions.
chrome.runtime.onMessage.addListener((m, _sender, reply) => {
  if (!m || m.t !== 'http') return;
  const ctl = new AbortController(), timer = setTimeout(() => ctl.abort(), m.timeout || 8000);
  fetch(m.url, { method: m.method || 'GET', headers: m.headers || {}, body: m.data, signal: ctl.signal })
    .then(async r => ({ ok: true, status: r.status, text: await r.text() }))
    .catch(() => ({ ok: false }))
    .then(r => { clearTimeout(timer); reply(r); });
  return true;
});
