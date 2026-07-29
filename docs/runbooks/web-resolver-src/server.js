const express = require('express');
const crypto = require('crypto');
const { chromium } = require('playwright');

const PORT = 8123;
const PUBLIC_BASE = 'https://jackett.comparadorinternet.co'; // por el que el celu alcanza este resolver
const UA = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36';
const NAV_TIMEOUT = 30000;
const VIDEO_RX = /\.m3u8(\?|$)|\.mp4(\?|$)|\/manifest|master\.txt|videoplayback|\/hls\//i;
const SUB_RX = /\.vtt(\?|$)|\.srt(\?|$)/i;

// ---------- embed69: descifrado portado de Alfa (crylink AES + Proof-of-Work) ----------
function crylink(b64, keyIn) {
  try {
    const enc = Buffer.from(b64, 'base64');
    let key = Buffer.isBuffer(keyIn) ? keyIn : Buffer.from(keyIn, 'utf-8');
    if (![16, 24, 32].includes(key.length)) key = Buffer.concat([key, Buffer.alloc(32)]).subarray(0, 32);
    const iv = enc.subarray(0, 16), ct = enc.subarray(16);
    const d = crypto.createDecipheriv('aes-' + (key.length * 8) + '-cbc', key, iv);
    d.setAutoPadding(true);
    return Buffer.concat([d.update(ct), d.final()]).toString('utf-8');
  } catch (e) { return null; }
}
function solvePow({ challenge, difficulty, salt }) {
  const prefix = '0'.repeat(difficulty);
  for (let nonce = 0; nonce < 5e7; nonce++) {
    const h = crypto.createHash('sha256').update(`${challenge}${nonce}`, 'utf-8').digest('hex');
    if (h.startsWith(prefix)) return salt ? crypto.createHash('sha256').update(`${challenge}${nonce}${salt}`, 'utf-8').digest() : null;
  }
  return null;
}
function bypassEmbed69(data) {
  let clave = null;
  const m = data.match(/decryptLink\(server\.link,\s*'(.+?)'\),/);
  if (m) clave = m[1];
  if (!clave) {
    const pm = data.match(/POW_CHALLENGE\s*=\s*'([^']+)';[\s\S]*?POW_DIFFICULTY\s*=\s*(\d+);[\s\S]*?POW_SALT\s*=\s*'([^']+)';/);
    if (pm) clave = solvePow({ challenge: pm[1], difficulty: parseInt(pm[2], 10), salt: pm[3] });
  }
  const dlm = data.match(/dataLink\s*=\s*(\[[\s\S]*?\])\s*;/) || data.match(/dataLink\s*=\s*([^;]+)/);
  if (!dlm) return [];
  let dataLink; try { dataLink = JSON.parse(dlm[1].replace(/\\\//g, '/')); } catch (e) { return []; }
  const out = [];
  for (const sec of (dataLink || [])) {
    const lang = sec.video_language || 'LAT';
    for (const emb of (sec.sortedEmbeds || [])) {
      if (emb.servername === 'download') continue;
      const url = clave ? crylink(emb.link, clave) : null;
      if (url && /^https?:\/\//.test(url)) out.push({ lang, servername: emb.servername || '', url });
    }
  }
  return out;
}

// ---------- allcalidad: el stream viene del player-API JSON (/api/rest/player), no del HTML ----------
function parseAllcalidadEmbeds(jsonText) {
  let j; try { j = JSON.parse(jsonText); } catch (e) { return []; }
  const embeds = (j && j.data && Array.isArray(j.data.embeds)) ? j.data.embeds : [];
  const out = [];
  for (const e of embeds) {
    if (e && typeof e.url === 'string' && /^https?:\/\//.test(e.url)) {
      out.push({ lang: e.lang || '', url: e.url });
    }
  }
  // latino primero (igual criterio que el branch embed69)
  out.sort((a, b) => (/lat/i.test(b.lang) ? 1 : 0) - (/lat/i.test(a.lang) ? 1 : 0));
  return out;
}

// Devuelve el primer stream sniffeado reproducible, DECIDIENDO de entrada directo-vs-proxy para no
// tener el hipo de "abrir directo, fallar, reintentar proxy":
//  - Reproducir DIRECTO del CDN es ~25× más rápido que proxear cada segmento por blog (2 CPU + túnel)
//    (medido: directo 6.4 Mbps vs proxy 0.25 Mbps). VLC manda el Referer por :http-referrer.
//  - PERO si el master HLS trae variantes/segmentos con URLs RELATIVAS (ej. vibuxer: "index-f1-v1-a1.m3u8"),
//    libVLC en Android no siempre las resuelve → EndReached en 0. Ahí el PROXY va de PRINCIPAL: reescribe
//    esas URLs a absolutas + hornea el Referer, y reproduce bien. Si son ABSOLUTAS (ej. vimeos con token),
//    va directo. `proxyUrl` viaja siempre como respaldo (casting/DLNA, o si el directo falla en vivo).
async function firstPlayable(found, subsAll, fetchImpl = fetch) {
  const sorted = found.slice().sort((a, b) => (/\.m3u8/i.test(b.url) ? 1 : 0) - (/\.m3u8/i.test(a.url) ? 1 : 0));
  for (const cand of sorted) {
    const referer = (cand.headers && cand.headers['referer']) || '';
    const r = await fetchImpl(cand.url, { headers: { Referer: referer, 'User-Agent': UA } }).catch(() => null);
    if (!(r && r.status >= 200 && r.status < 400)) {
      try { if (r && r.body && r.body.cancel) await r.body.cancel(); } catch (_) {}
      continue;   // token muerto / no 2xx → siguiente candidato
    }
    const proxied = `${PUBLIC_BASE}/proxy?url=${encodeURIComponent(cand.url)}&referer=${encodeURIComponent(referer)}`;
    const ct = (r.headers && r.headers.get && r.headers.get('content-type')) || '';
    const isM3u8 = /\.m3u8(\?|$)/i.test(cand.url) || /mpegurl|m3u8/i.test(ct);
    if (isM3u8) {
      // Leer el master (chico) y ver si sus referencias (variantes/segmentos) son absolutas o relativas.
      const text = await (r.text ? r.text() : Promise.resolve('')).catch(() => '');
      const refs = text.split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#'));
      if (!refs.length) continue;                                   // m3u8 sin variantes/segmentos = roto
      const allAbsolute = refs.every(l => /^https?:\/\//i.test(l));
      const primary = allAbsolute ? cand.url : proxied;             // relativas → proxy de principal
      return { ok: true, streamUrl: primary, proxyUrl: proxied, headers: { 'User-Agent': UA, Referer: referer }, subtitles: (subsAll || []).slice(0, 8) };
    }
    // no-m3u8 (mp4/archivo único): la directa suele andar → directo, proxy de respaldo.
    try { if (r.body && r.body.cancel) await r.body.cancel(); } catch (_) {}
    return { ok: true, streamUrl: cand.url, proxyUrl: proxied, headers: { 'User-Agent': UA, Referer: referer }, subtitles: (subsAll || []).slice(0, 8) };
  }
  return null;
}

const app = express();
let browser = null;
async function getBrowser() { if (!browser || !browser.isConnected()) browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] }); return browser; }
let busy = Promise.resolve();
function serialize(fn) { const p = busy.then(fn, fn); busy = p.catch(() => {}); return p; }

async function sniffPage(ctx, targetUrl) {
  const page = await ctx.newPage();
  const found = []; const subs = [];
  const cap = (u, h) => { if (VIDEO_RX.test(u)) found.push({ url: u, headers: h || {} }); else if (SUB_RX.test(u)) subs.push({ lang: '', url: u }); };
  page.on('request', (r) => cap(r.url(), r.headers()));
  page.on('response', (r) => cap(r.url()));
  try {
    await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT });
    const deadline = Date.now() + 18000;
    while (found.length === 0 && Date.now() < deadline) {
      for (const f of page.frames()) {
        await f.evaluate(() => {
          const v = document.querySelector('video'); if (v) { v.muted = true; v.play && v.play().catch(() => {}); }
          document.querySelectorAll('img[src*=LAT i],[class*=lang i],.server,.server-btn,[class*=server i],.play,.vjs-big-play-button,[class*=play i],button').forEach(e => { try { e.click(); } catch (_) {} });
        }).catch(() => {});
      }
      await page.waitForTimeout(1500);
    }
  } finally { await page.close().catch(() => {}); }
  return { found, subs };
}

async function resolveOne(pageUrl) {
  const b = await getBrowser();
  const ctx = await b.newContext({ userAgent: UA });
  // allcalidad: el stream está en su API JSON (/api/rest/player), no en HTML. Traemos el JSON con
  // el navegador real (pasa Cloudflare tomando cookie de baseUrl) y sniffeamos cada embed (Drive incl.).
  if (/\/api\/rest\/player/i.test(pageUrl)) {
    try {
      let origin = ''; try { origin = new URL(pageUrl).origin; } catch (_) {}
      const warm = await ctx.newPage();
      await warm.goto(origin || pageUrl, { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT }).catch(() => {});
      await warm.waitForTimeout(1500);
      await warm.close().catch(() => {});
      const resp = await ctx.request.get(pageUrl, { headers: { Referer: origin, 'User-Agent': UA } }).catch(() => null);
      const body = resp ? await resp.text() : '';
      const embeds = parseAllcalidadEmbeds(body);
      if (embeds.length) {
        let subsAll = [];
        for (const t of embeds.slice(0, 4).map(e => e.url)) {
          const { found, subs } = await sniffPage(ctx, t);
          subsAll = subsAll.concat(subs);
          if (found.length) {
            const res = await firstPlayable(found, subsAll);
            if (res) return res;   // si ninguno reproduce, seguir al próximo embed
          }
        }
      }
      return { ok: false, error: 'allcalidad: sin stream en embeds' };
    } finally { await ctx.close().catch(() => {}); }
  }
  try {
    const probe = await ctx.newPage();
    await probe.goto(pageUrl, { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT });
    await probe.waitForTimeout(2500);
    // Muchos sitios (sololatino/xupalace) cargan el embed69 real SOLO al clickear una opción de
    // servidor (botones con data-player-token cifrado estilo Laravel). Clickeamos las opciones y
    // esperamos a que aparezca el frame embed69/vidurl; reintentamos un par de veces.
    let iframeSrc = null;
    const SERVER_OPT = '[data-server-btn],.server-btn,.dooplay_player_option,#playeroptionsul li,li[data-nume],[data-type][data-post],.server,[class*=server i]';
    for (let attempt = 0; attempt < 3 && !iframeSrc; attempt++) {
      for (const f of probe.frames()) { const u = f.url(); if (/embed69|\/vidurl\//i.test(u)) { iframeSrc = u; break; } }
      if (iframeSrc) break;
      await probe.evaluate((sel) => {
        document.querySelectorAll(sel).forEach(e => { try { e.click(); } catch (_) {} });
      }, SERVER_OPT).catch(() => {});
      await probe.waitForTimeout(2500);
    }
    if (!iframeSrc) iframeSrc = await probe.$eval('iframe', el => el.src).catch(() => null);
    // Todos los <iframe> del player (incluye los lazy con data-src) para el desempaque DooPlay.
    const frameSrcs = await probe.$$eval('iframe', els =>
      els.map(e => e.getAttribute('data-src') || e.getAttribute('src') || '').filter(Boolean)).catch(() => []);
    await probe.close().catch(() => {});

    let targets = [pageUrl];
    if (iframeSrc && /embed69|\/vidurl\//i.test(iframeSrc)) {
      const resp = await ctx.request.get(iframeSrc, { headers: { Referer: pageUrl } }).catch(() => null);
      const html = resp ? await resp.text() : '';
      const embeds = bypassEmbed69(html);
      if (embeds.length) { const lat = embeds.filter(e => /LAT/i.test(e.lang)); targets = (lat.length ? lat : embeds).map(e => e.url); }
      else if (iframeSrc) targets = [iframeSrc];
    } else {
      // DooPlay "?trembed=": cada iframe del player es un wrapper same-origin (ej. seriesmega
      // /?trembed=1&trid=…) que embebe el host real (vidhidepre/doodstream/etc.). El sniff directo
      // sobre la página no lo arranca; hay que seguir la cadena: bajar cada wrapper y extraer su
      // <iframe src> real. Sniffeamos esos hosts (vidhidepre resuelve; el que no, se descarta).
      const trembeds = frameSrcs.filter(u => /[?&]trembed=/i.test(u));
      if (trembeds.length) {
        const hosts = [];
        for (const tu of trembeds.slice(0, 6)) {
          const abs = /^https?:\/\//i.test(tu) ? tu : new URL(tu, pageUrl).href;
          const resp = await ctx.request.get(abs, { headers: { Referer: pageUrl } }).catch(() => null);
          const html = resp ? await resp.text() : '';
          const im = html.match(/<iframe[^>]+src=["']([^"']+)["']/i);
          if (im && /^https?:\/\//.test(im[1]) && !hosts.includes(im[1])) hosts.push(im[1]);
        }
        if (hosts.length) targets = hosts;
      } else {
        // Caso general: apuntar directo a los hosts del player en vez de sniffear la página.
        // (a) iframes EXTERNOS que aparecieron tras clickear play/servidor (ej. pelisflix →
        //     nupload.top/watch/…): el host embebido no arranca bien anidado, sí resuelve directo.
        // (b) URLs de embed listadas en texto plano en el HTML (ej. dipelis:
        //     <div class="server">https://host/e/CODE</div>).
        let pageHost = ''; try { pageHost = new URL(pageUrl).host; } catch (_) {}
        const extFrames = frameSrcs
          .map(u => { try { return new URL(u, pageUrl).href; } catch (_) { return null; } })
          .filter(u => u && /^https?:/i.test(u))
          .filter(u => { try { return new URL(u).host !== pageHost; } catch (_) { return false; } });
        const pageHtml = await ctx.request.get(pageUrl, { headers: { Referer: pageUrl } })
          .then(r => r.text()).catch(() => '');
        const rx = /https?:\/\/[a-z0-9.-]+\/(?:e|embed)\/[A-Za-z0-9_-]{6,}/gi;
        const listed = pageHtml.match(rx) || [];
        // extFrames primero (ya cargados por la UI = la opción activa), luego los listados.
        const all = [...new Set([...extFrames, ...listed])];
        if (all.length) targets = all;
      }
    }

    // Fallback (pelisflix y similares): si no encontramos ningún target específico, cargar la
    // página, clickear el botón de PLAY y capturar el iframe EXTERNO del host real (ej.
    // nupload.top/watch/…) que recién ahí se inyecta por JS. Aislado acá para no interferir con
    // el flujo embed69 (donde clickear play rompía la detección del frame cifrado).
    if (targets.length === 1 && targets[0] === pageUrl) {
      const pp = await ctx.newPage();
      try {
        await pp.goto(pageUrl, { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT });
        await pp.waitForTimeout(2000);
        await pp.evaluate(() => {
          document.querySelectorAll('.playbt,[class*=play i],.bstd,button').forEach(e => { try { e.click(); } catch (_) {} });
        }).catch(() => {});
        await pp.waitForTimeout(4000);
        let pageHost = ''; try { pageHost = new URL(pageUrl).host; } catch (_) {}
        const ext = (await pp.$$eval('iframe', els =>
          els.map(e => e.src || e.getAttribute('data-src') || '').filter(Boolean)).catch(() => []))
          .filter(u => /^https?:/i.test(u))
          .filter(u => { try { return new URL(u).host !== pageHost; } catch (_) { return false; } });
        if (ext.length) targets = [...new Set(ext)];
      } finally { await pp.close().catch(() => {}); }
    }

    let subsAll = [];
    for (const t of targets.slice(0, 4)) {
      const { found, subs } = await sniffPage(ctx, t);
      subsAll = subsAll.concat(subs);
      if (found.length) {
        // Devolver la URL PROXEADA: el celu la reproduce por el tunnel; blog fetchea el stream desde
        // su IP (aceptada) + agrega el Referer server-side. Arregla el 403 geo/IP y el cast/DLNA.
        const res = await firstPlayable(found, subsAll);
        if (res) return res;   // si ninguno reproduce, seguir al próximo target
      }
    }
    return { ok: false, error: 'no se detectó stream' };
  } finally { await ctx.close().catch(() => {}); }
}

// ---------- PROXY HLS: reescribe playlists + pipea segmentos con el Referer ----------
function rewriteM3u8(text, baseUrl, referer) {
  const absol = (u) => { try { return new URL(u, baseUrl).href; } catch (e) { return u; } };
  const prox = (u) => `${PUBLIC_BASE}/proxy?url=${encodeURIComponent(absol(u))}&referer=${encodeURIComponent(referer)}`;
  return text.split('\n').map((line) => {
    const t = line.trim();
    if (!t) return line;
    if (t.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (m, u) => `URI="${prox(u)}"`);
    return prox(t);
  }).join('\n');
}
app.get('/proxy', async (req, res) => {
  const url = req.query.url, referer = req.query.referer || '';
  if (!/^https?:\/\//.test(url || '')) return res.status(400).end('bad url');
  try {
    const r = await fetch(url, { headers: { Referer: referer, 'User-Agent': UA } });
    const ct = r.headers.get('content-type') || '';
    if (/mpegurl|m3u8/i.test(ct) || /\.m3u8(\?|$)/i.test(url)) {
      const text = await r.text();
      res.set('Content-Type', 'application/vnd.apple.mpegurl');
      res.set('Access-Control-Allow-Origin', '*');
      return res.status(r.status).send(rewriteM3u8(text, url, referer));
    }
    // Segmentos/otros: STREAMING (pipe) — reenviar los chunks apenas llegan del CDN, en vez de bajar el
    // .ts entero a RAM antes de mandarlo. Baja la latencia por segmento (bajar y mandar se solapan) y el
    // uso de memoria. Con backpressure (esperar 'drain') para no acumular.
    res.set('Access-Control-Allow-Origin', '*');
    if (!r.body) { res.status(r.status); return res.end(); }
    const reader = r.body.getReader();
    res.on('close', () => { reader.cancel().catch(() => {}); });   // si el player corta, dejamos de leer
    const write = (chunk) => new Promise((resolve) => { if (res.write(chunk)) resolve(); else res.once('drain', resolve); });
    // Espiar SOLO el arranque para el anti-leech PNG (minochinos disfraza el TS con un prefijo PNG). Si
    // no arranca en PNG, con el 1er chunk basta y empezamos a streamear ya; si es PNG, acumulamos lo
    // justo para hallar el sync TS y descartar el prefijo.
    const isPng = (b) => b.length >= 4 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47;
    const NEED = 188 * 4 + 8192;
    let head = Buffer.alloc(0), done = false, ctType = ct;
    while (!done) {
      const { value, done: d } = await reader.read();
      done = d;
      if (value) head = Buffer.concat([head, Buffer.from(value)]);
      if (head.length >= 4 && !isPng(head)) break;   // no anti-leech → arrancar a streamear ya
      if (isPng(head) && head.length >= NEED) break;  // suficiente para hallar el sync
      if (head.length === 0 && done) break;
    }
    if (isPng(head)) {
      let start = -1;
      const lim = Math.min(head.length - 188 * 4, 8192);
      for (let off = 0; off < lim; off++) {
        if (head[off] === 0x47 && head[off + 188] === 0x47 && head[off + 376] === 0x47 && head[off + 564] === 0x47) { start = off; break; }
      }
      if (start >= 0) { head = head.subarray(start); ctType = 'video/mp2t'; }
    }
    res.status(r.status);
    if (ctType) res.set('Content-Type', ctType);
    if (head.length) await write(head);
    while (!done) {
      const { value, done: d } = await reader.read();
      done = d;
      if (value) await write(Buffer.from(value));
    }
    return res.end();
  } catch (e) { if (res.headersSent) return res.end(); return res.status(502).end(String((e && e.message) || e)); }
});

// Caché de resolves por pageUrl. El resolver SERIALIZA (1 a la vez) y cada resolve tarda 10-60s
// (sniff Playwright por embed); si el app corta a 60s pero el resolve termina, el retry pega acá y es
// INSTANTÁNEO (salta la cola). TTL corto porque los tokens del stream duran ~12h — no servir cerca del
// vencimiento. Se cachea solo el éxito.
const resolveCache = new Map(); // url -> { result, expires }
const RESOLVE_TTL_MS = 8 * 60 * 1000;

app.get('/resolve', async (req, res) => {
  const url = req.query.url;
  if (!url || !/^https?:\/\//.test(url)) return res.status(400).json({ ok: false, error: 'url inválida' });
  const hit = resolveCache.get(url);
  if (hit && hit.expires > Date.now()) return res.json(hit.result);   // hit: instantáneo, no encola
  try {
    const result = await serialize(() => resolveOne(url));
    if (result && result.ok) resolveCache.set(url, { result, expires: Date.now() + RESOLVE_TTL_MS });
    res.json(result);
  } catch (e) { res.json({ ok: false, error: String((e && e.message) || e) }); }
});
app.get('/health', (_req, res) => res.json({ ok: true }));
// Guard: al deployar corre como servicio (`node server.js` directo, require.main === module) y
// escucha normalmente. Al ser require()ado desde server.test.js (para probar parseAllcalidadEmbeds
// sin dependencias del navegador) NO levanta el listener, así el proceso de test puede terminar.
if (require.main === module) {
  app.listen(PORT, '127.0.0.1', () => console.log('web-resolver on 127.0.0.1:' + PORT));
}
module.exports = { parseAllcalidadEmbeds, firstPlayable };
