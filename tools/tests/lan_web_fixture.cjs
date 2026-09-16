/* SPDX-License-Identifier: GPL-3.0-or-later */
// UI-only fixture. No ADB, shell execution, credentials, Android clipboard or vehicle.
// Run from repository root: node tools/tests/lan_web_fixture.cjs
// /?unpaired=1 shows the pairing form. / seeds a synthetic, non-authenticating UI session.
'use strict';
const http = require('node:http'), fs = require('node:fs'), path = require('node:path');
const root = path.resolve(__dirname, '../..');
const actualAssets = path.join(root, 'app/src/main/assets/lan');
let text = '', commands = false, executions = 0;
const jobs = new Map();
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1:9876');
  const json = (status, data) => { res.writeHead(status, {'Content-Type':'application/json','Cache-Control':'no-store'}); res.end(JSON.stringify(data)); };
  try {
    const name = {'/':'index.html','/client.js':'client.js','/client.css':'client.css'}[url.pathname];
    if (name) {
      let body = fs.readFileSync(path.join(actualAssets, name), 'utf8');
      if (name === 'client.js') body = "if(location.search.includes('unpaired'))sessionStorage.removeItem('natroToken');else sessionStorage.setItem('natroToken','UI-FIXTURE-NO-CREDENTIAL');\n" + body;
      if (name === 'index.html') body = body.replace('<h1>', '<h1>СТЕНД БЕЗ МАГНИТОЛЫ · ');
      res.writeHead(200, {'Content-Type': name.endsWith('js') ? 'text/javascript' : name.endsWith('css') ? 'text/css' : 'text/html; charset=utf-8'});
      res.end(body); return;
    }
    const chunks = []; let length = 0;
    for await (const chunk of req) { length += chunk.length; if (length > 1024 * 1024) throw Error('Fixture limit 1 MiB'); chunks.push(chunk); }
    const bytes = Buffer.concat(chunks);
    const body = () => JSON.parse(bytes.toString('utf8') || '{}');
    if (url.pathname === '/fixture/commands' && req.method === 'POST') { commands = body().enabled === true; return json(200, {commands}); }
    if (url.pathname === '/fixture/state') return json(200, {text, executions});
    if (url.pathname === '/api/status') return json(200, {adb:'TEST: без ADB',commandsAllowed:commands});
    if (url.pathname === '/api/files') return json(200, {files:[]});
    if (url.pathname === '/api/text') { if (req.method === 'POST') text = body().text; return json(200, {text}); }
    if (url.pathname === '/api/draft') { text = body().text; return json(200, {message:'Тестовый черновик принят; запуска нет'}); }
    if (url.pathname === '/api/command') {
      if (!commands) return json(403, {error:'Команды выключены на тестовом стенде'});
      const b = body(); if (!jobs.has(b.id)) { executions++; jobs.set(b.id, {done:true,output:'СИМУЛЯЦИЯ: '+b.command+'\nНа устройстве ничего не запускалось.'}); }
      return json(200, {id:b.id});
    }
    if (url.pathname.startsWith('/api/job/')) return json(200, jobs.get(url.pathname.slice(9)) || {done:true,output:'Не найдено'});
    return json(404, {error:'Only a UI fixture; this route is not implemented'});
  } catch (error) { json(400, {error:error.message}); }
});
server.listen(9876, '127.0.0.1', () => process.stdout.write('UI fixture only: http://127.0.0.1:9876\n'));
process.on('SIGTERM', () => server.close());
