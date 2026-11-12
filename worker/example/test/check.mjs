// The example inside workerd: `npm test` builds both bundles and runs this
// against `wrangler dev`, with a storage of its own. No network. It reads
// the frame stream as a browser does, a POST held open, and posts commands
// next to the page.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import http from 'node:http';
import { tmpdir } from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import transit from 'transit-js';

const port = 8797;
const base = `http://127.0.0.1:${port}`;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const writer = transit.writer('json');
const reader = transit.reader('json');
const kw = transit.keyword;

// transit data as plain JSON text, keywords as ":name", to match against
const plain = (x) => {
  if (Array.isArray(x)) return x.map(plain);
  if (transit.isKeyword(x)) return x.toString();
  // a seq, what `for` answers, travels as a list
  if (transit.isTaggedValue(x)) return plain(x.rep);
  if (transit.isMap(x)) {
    const out = {};
    x.forEach((v, k) => { out[plain(k)] = plain(v); });
    return out;
  }
  return x;
};

// a tab: the frame stream of the page, read as it comes
function open(tab, acceptEncoding) {
  const tabState = { frames: [], wire: 0, text: '', ended: false, headers: null, status: null };
  let decoder = null;
  const feed = (text) => {
    tabState.text += text;
    for (;;) {
      const end = tabState.text.indexOf('\n\n');
      if (end < 0) return;
      const block = tabState.text.slice(0, end);
      tabState.text = tabState.text.slice(end + 2);
      const data = block.split('\n').filter((line) => line.startsWith('data: ')).map((line) => line.slice(6)).join('\n');
      if (block.startsWith('event: frame')) tabState.frames.push(JSON.stringify(plain(reader.read(data))));
    }
  };
  const request = http.request(`${base}/`, { method: 'POST', headers: acceptEncoding ? { 'Accept-Encoding': acceptEncoding } : {} }, (response) => {
    tabState.headers = response.headers;
    tabState.status = response.statusCode;
    const encoding = response.headers['content-encoding'];
    if (encoding === 'br') decoder = zlib.createBrotliDecompress();
    if (encoding === 'gzip') decoder = zlib.createGunzip();
    if (decoder) decoder.on('data', (chunk) => feed(chunk.toString('utf8')));
    response.on('data', (chunk) => {
      tabState.wire += chunk.length;
      if (decoder) decoder.write(chunk); else feed(chunk.toString('utf8'));
    });
    response.on('close', () => { tabState.ended = true; });
  });
  request.on('error', () => { tabState.ended = true; });
  request.end(writer.write(transit.map([kw('tabid'), tab])));
  tabState.close = () => request.destroy();
  tabState.last = () => tabState.frames.at(-1);
  // the frame that follows `count` frames, or a failure after two seconds
  tabState.frameAfter = async (count) => {
    for (let i = 0; i < 400 && tabState.frames.length <= count; i++) await sleep(5);
    assert.ok(tabState.frames.length > count, `tab ${tab}: no frame came`);
    return tabState.last();
  };
  return tabState;
}

async function command(kind, data) {
  const pairs = [kw('command/kind'), kw(kind)];
  if (data) pairs.push(kw('command/data'), transit.map(Object.entries(data).flatMap(([k, v]) => [kw(k), v])));
  const response = await fetch(`${base}/command`, { method: 'POST', body: writer.write(transit.map(pairs)) });
  assert.equal(response.status, 200);
  return plain(reader.read(await response.text()));
}

const count = (frame) => Number(frame.match(/\[":strong",(\d+)\]/)[1]);
const wokeAt = (frame) => frame.match(/woke at ",\[":code","([^"]+)"\]/)[1];

async function checks() {
  const step = (name) => console.log(`  ${name}`);

  step('the shell of the page and its assets');
  const shell = await fetch(`${base}/`);
  assert.equal(shell.status, 200);
  const html = await shell.text();
  assert.match(html, /data-rframes-view/);
  assert.match(html, /src="\/js\/main\.js"/);
  assert.equal((await fetch(`${base}/js/main.js`)).status, 200);
  assert.equal((await fetch(`${base}/app.css`)).status, 200);
  assert.equal((await fetch(`${base}/nothing`)).status, 404);

  step('a tab gets the page as its first frame, compressed with brotli');
  const anna = open('anna', 'gzip, deflate, br');
  const first = await anna.frameAfter(0);
  assert.equal(anna.status, 200);
  assert.equal(anna.headers['content-type'], 'text/event-stream');
  assert.equal(anna.headers['content-encoding'], 'br');
  assert.match(first, /"Clicked ",\[":strong",0\]/);
  assert.match(first, /from tab ",\[":code","anna"\]/);

  step('a command changes the state, the frame shows it and costs a few bytes');
  anna.wire = 0;
  assert.deepEqual(await command('count/increment'), { ':success?': true });
  assert.equal(count(await anna.frameAfter(1)), 1);
  await sleep(50);
  assert.ok(anna.wire < 100, `the frame of one changed number took ${anna.wire} bytes`);

  step('a second tab shares the state; gzip for a browser without brotli');
  const ben = open('ben', 'gzip');
  assert.equal(count(await ben.frameAfter(0)), 1);
  assert.equal(ben.headers['content-encoding'], 'gzip');
  assert.match(ben.last(), /from tab ",\[":code","ben"\]/);
  assert.deepEqual(await command('note/add', { text: '  Ship it  ' }), { ':success?': true });
  assert.match(await anna.frameAfter(2), /\[":li",\{":replicant\/key":0\},"Ship it"\]/);
  assert.match(await ben.frameAfter(1), /\[":li",\{":replicant\/key":0\},"Ship it"\]/);

  step('a stream without compression for a client that asks for none');
  const curl = open('curl', null);
  assert.equal(count(await curl.frameAfter(0)), 1);
  assert.equal(curl.headers['content-encoding'], undefined);
  curl.close();

  step('a rejected command says why and changes nothing');
  const framesBefore = anna.frames.length;
  assert.deepEqual(await command('note/add', { text: '   ' }), { ':success?': false, ':result': { ':error': ':empty-note' } });
  assert.deepEqual(await command('note/unknown'), { ':error': ':unknown-command-kind' });
  await sleep(300);
  assert.equal(anna.frames.length, framesBefore, 'a page a command left as it was gets no frame');

  step('the Object sleeps after a while, the streams stay open (25 s) ...');
  const before = wokeAt(anna.last());
  await sleep(25000);
  assert.equal(anna.ended, false);
  assert.equal(ben.ended, false);
  assert.deepEqual(await command('count/increment'), { ':success?': true });
  const woken = await anna.frameAfter(framesBefore);
  assert.equal(count(woken), 2, 'the state came back from the storage');
  assert.match(woken, /"Ship it"/);
  assert.notEqual(wokeAt(woken), before, 'the Object did not sleep');
  assert.equal(count(await ben.frameAfter(2)), 2, 'the other tab is still served');

  anna.close();
  ben.close();
}

const storage = mkdtempSync(path.join(tmpdir(), 'rframes-example-'));
const wrangler = spawn('node_modules/.bin/wrangler', ['dev', '--port', String(port), '--persist-to', storage],
                       { env: { ...process.env, WRANGLER_SEND_METRICS: 'false' }, stdio: ['ignore', 'pipe', 'pipe'] });
let log = '';
wrangler.stdout.on('data', (chunk) => { log += chunk; });
wrangler.stderr.on('data', (chunk) => { log += chunk; });

let failed = false;
try {
  for (let i = 0; i < 120 && !log.includes('Ready on'); i++) await sleep(250);
  assert.ok(log.includes('Ready on'), `wrangler dev did not start:\n${log}`);
  console.log('the example inside workerd');
  await checks();
  console.log('all checks passed');
} catch (error) {
  failed = true;
  console.error(error);
  console.error(log.split('\n').filter((line) => /error|exception/i.test(line)).slice(-10).join('\n'));
} finally {
  wrangler.kill('SIGTERM');
  await sleep(500);
  rmSync(storage, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
