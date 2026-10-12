// The toolbar popup: the same switches the page script reads (kotoba.web.*), kept in chrome.storage.local.
const $ = id => document.getElementById(id), S = chrome.storage.local;
const ALL = ['ja', 'ko', 'zh', 'th', 'ru'];
let host = '';

async function pingKotoba(cfg) {
  const port = cfg['kotoba.port'] || '47823', key = cfg['kotoba.key'];
  try {
    const up = await fetch(`http://127.0.0.1:${port}/helper/ping`);
    if (!up.ok) throw 0;
  } catch (e) { return ['bad', 'Open Kotoba for Mac']; }
  if (!key) return ['bad', 'Not connected'];
  try {
    const r = await fetch(`http://127.0.0.1:${port}/helper/lookup`, { method: 'POST', headers: { 'X-Kotoba-Key': key, 'Content-Type': 'application/json' }, body: JSON.stringify({ text: '日' }) });
    return r.status === 403 ? ['bad', 'Code no longer valid'] : ['ok', 'Connected'];
  } catch (e) { return ['bad', 'Open Kotoba for Mac']; }
}

async function paint() {
  const cfg = await S.get(null);
  $('enabled').checked = cfg['kotoba.web.enabled'] !== false;
  $('video').checked = cfg['kotoba.video.enabled'] !== false;
  const off = (() => { try { return JSON.parse(cfg['kotoba.web.off'] || '[]'); } catch (e) { return []; } })();
  $('site').checked = !off.includes(host);
  $('site').disabled = !host;
  let langs = ALL; try { const a = JSON.parse(cfg['kotoba.web.langs'] || ''); if (Array.isArray(a)) langs = a; } catch (e) {}
  document.querySelectorAll('#langs input').forEach(i => { i.checked = langs.includes(i.value); });
  document.querySelectorAll('#han input').forEach(i => { i.checked = i.value === (cfg['kotoba.web.han'] || 'ja'); });
  const [state, text] = await pingKotoba(cfg);
  $('status').className = 'status ' + state; $('status-text').textContent = text;
  $('pair').open = state === 'bad' && text !== 'Open Kotoba for Mac' ? true : $('pair').open;
}

$('enabled').onchange = () => S.set({ 'kotoba.web.enabled': $('enabled').checked });
$('video').onchange = () => S.set({ 'kotoba.video.enabled': $('video').checked });
$('site').onchange = async () => {
  const cfg = await S.get('kotoba.web.off');
  let off = []; try { off = JSON.parse(cfg['kotoba.web.off'] || '[]'); } catch (e) {}
  off = off.filter(h => h !== host); if (!$('site').checked) off.push(host);
  S.set({ 'kotoba.web.off': JSON.stringify(off) });
};
document.querySelectorAll('#langs input').forEach(i => i.onchange = () => {
  S.set({ 'kotoba.web.langs': JSON.stringify([...document.querySelectorAll('#langs input:checked')].map(x => x.value)) });
});
document.querySelectorAll('#han input').forEach(i => i.onchange = () => S.set({ 'kotoba.web.han': i.value }));
$('connect').onclick = async () => {
  const m = /^\s*kotoba:(\d{2,5}):([0-9a-f]{16,})\s*$/i.exec($('code').value);
  if (!m) { $('pair-msg').textContent = 'That isn’t a Kotoba code. Copy it from Video › Browser extension code.'; return; }
  await S.set({ 'kotoba.port': m[1], 'kotoba.key': m[2] });
  $('code').value = ''; $('pair-msg').textContent = ''; paint();
};

(async () => {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tab && /^https?:/.test(tab.url || '')) { host = new URL(tab.url).hostname; $('host').textContent = host; }
  } catch (e) {}
  if (!host) $('host').textContent = 'this site';
  paint();
})();
