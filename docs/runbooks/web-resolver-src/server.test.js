const { test } = require('node:test');
const assert = require('node:assert');
const { parseAllcalidadEmbeds, firstPlayable } = require('./server.js');

test('parseAllcalidadEmbeds saca urls y pone latino primero', () => {
  const json = JSON.stringify({ data: { embeds: [
    { lang: 'Castellano', quality: 'HD', url: 'https://streamtape.com/e/AAA' },
    { lang: 'Latino', quality: 'HD', url: 'https://drive.google.com/file/d/XYZ/preview' },
  ] } });
  const out = parseAllcalidadEmbeds(json);
  assert.strictEqual(out.length, 2);
  assert.match(out[0].url, /drive\.google/);          // latino primero
  assert.strictEqual(out[0].lang, 'Latino');
});

test('parseAllcalidadEmbeds tolera json basura y embeds vacíos', () => {
  assert.deepStrictEqual(parseAllcalidadEmbeds('no json'), []);
  assert.deepStrictEqual(parseAllcalidadEmbeds(JSON.stringify({ data: {} })), []);
});

const noHdr = { get: () => '' };
const m3u8Hdr = { get: () => 'application/vnd.apple.mpegurl' };

test('firstPlayable salta el 403 y devuelve el archivo (mp4) DIRECTO + Referer', async () => {
  const found = [
    { url: 'https://p2.vimeos.zip/hls2/master.m3u8?t=x', headers: { referer: 'https://vimeos.net/' } },
    { url: 'https://streamtape.com/get_video?id=Y.mp4', headers: { referer: 'https://streamtape.com/' } },
  ];
  const fake = async (u) => ({ status: /vimeos/.test(u) ? 403 : 200, headers: noHdr, body: { cancel: async () => {} } });
  const res = await firstPlayable(found, [], fake);
  assert.ok(res && res.ok);
  assert.strictEqual(res.streamUrl, 'https://streamtape.com/get_video?id=Y.mp4');   // saltó vimeos (403), mp4 directo
  assert.strictEqual(res.headers.Referer, 'https://streamtape.com/');
  assert.match(res.proxyUrl, /^https:\/\/jackett\.comparadorinternet\.co\/proxy\?url=/);
});

test('firstPlayable: m3u8 con variantes ABSOLUTAS → va DIRECTO (rápido)', async () => {
  const found = [{ url: 'https://vimeos.zip/hls2/master.m3u8?t=x', headers: { referer: 'https://vimeos.net/' } }];
  const master = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=700000\nhttps://vimeos.zip/hls2/index.m3u8?t=x\n';
  const fake = async () => ({ status: 200, headers: m3u8Hdr, text: async () => master, body: { cancel: async () => {} } });
  const res = await firstPlayable(found, [], fake);
  assert.strictEqual(res.streamUrl, 'https://vimeos.zip/hls2/master.m3u8?t=x');       // absolutas → directo
  assert.match(res.proxyUrl, /\/proxy\?url=/);
});

test('firstPlayable: m3u8 con variantes RELATIVAS → PROXY de principal (evita el hipo)', async () => {
  const found = [{ url: 'https://vibuxer.com/stream/x/master.m3u8', headers: { referer: 'https://vibuxer.com/e/z' } }];
  const master = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=700000\nindex-f1-v1-a1.m3u8\n';
  const fake = async () => ({ status: 200, headers: m3u8Hdr, text: async () => master, body: { cancel: async () => {} } });
  const res = await firstPlayable(found, [], fake);
  assert.match(res.streamUrl, /^https:\/\/jackett\.comparadorinternet\.co\/proxy\?url=/);  // relativas → proxy
});

test('firstPlayable devuelve null si todos fallan', async () => {
  const found = [{ url: 'https://x/y.m3u8', headers: {} }];
  const fake = async () => ({ status: 403, headers: noHdr, body: { cancel: async () => {} } });
  assert.strictEqual(await firstPlayable(found, [], fake), null);
});
