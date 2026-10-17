/* SPDX-License-Identifier: GPL-3.0-or-later */
'use strict';
const $=id=>document.getElementById(id);
function savedToken(){try{return localStorage.getItem('natroToken')||sessionStorage.getItem('natroToken')||'';}catch(error){return '';}}
function clearToken(){token='';try{localStorage.removeItem('natroToken');}catch(error){}try{sessionStorage.removeItem('natroToken');}catch(error){}}
let token=savedToken(),busy=false,upload=null,cancelled=false;
const tell=message=>{$('message').textContent=message;};
function showPaired(value){$('pairing').hidden=value;$('workspace').hidden=!value;}
async function api(path,body){
 const response=await fetch(path,{method:body===undefined?'GET':'POST',headers:{'Authorization':'Bearer '+token,'X-Natro-Client':'1',...(body===undefined?{}:{'Content-Type':'application/json'})},body:body===undefined?undefined:JSON.stringify(body),cache:'no-store'});
 const result=await response.json();
 if(response.status===401){clearToken();showPaired(false);}
 if(!response.ok)throw Error(result.error||'Запрос не выполнен');return result;
}
async function status(){const result=await api('/api/status');showPaired(true);$('connection').textContent='Подключено · '+location.host;$('adbState').textContent=result.adb+(result.commandsAllowed?' · удалённые команды разрешены':' · удалённые команды выключены на магнитоле');$('execute').disabled=busy||!result.commandsAllowed;}
function action(id,fn){$(id).addEventListener('click',async()=>{try{await fn();}catch(error){tell(error.message);}});}
$('pairForm').addEventListener('submit',async event=>{event.preventDefault();try{const remember=$('remember').checked;const result=await api('/api/pair',{pin:$('pin').value,remember});clearToken();token=result.token;let saved=false;try{(result.remembered?localStorage:sessionStorage).setItem('natroToken',token);saved=true;}catch(error){}$('pin').value='';await status();await files();tell(saved?(result.remembered?'Телефон сохранён. Сервер будет запускаться после загрузки магнитолы.':'Временное сопряжение выполнено.'):'Подключено, но браузер запретил сохранение ключа. После закрытия потребуется новый код.');}catch(error){tell(error.message);}});
action('paste',async()=>{if(navigator.clipboard&&window.isSecureContext){$('text').value=await navigator.clipboard.readText();}else{$('text').focus();tell('Удерживайте поле текста и выберите «Вставить».');}});
action('copy',async()=>{const text=$('text');if(navigator.clipboard&&window.isSecureContext)await navigator.clipboard.writeText(text.value);else{text.focus();text.select();text.setSelectionRange(0,text.value.length);if(!document.execCommand('copy')){tell('Текст выделен. Выберите «Скопировать» в меню iPhone.');return;}}tell('Текст скопирован.');});
action('sendText',async()=>{await api('/api/text',{text:$('text').value});tell('Текст передан в буфер магнитолы.');});
action('getText',async()=>{$('text').value=(await api('/api/text')).text;tell('Получен буфер магнитолы.');});
action('draft',async()=>{tell((await api('/api/draft',{text:$('text').value})).message);});
function uid(){const bytes=new Uint8Array(16);crypto.getRandomValues(bytes);bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;const hex=Array.from(bytes,v=>v.toString(16).padStart(2,'0')).join('');return hex.slice(0,8)+'-'+hex.slice(8,12)+'-'+hex.slice(12,16)+'-'+hex.slice(16,20)+'-'+hex.slice(20);}
async function poll(id){busy=true;$('execute').disabled=true;$('resumeJob').hidden=false;try{for(let i=0;i<150;i++){const result=await api('/api/job/'+id);$('output').textContent=result.output||'Выполнение…';if(result.done){sessionStorage.removeItem('natroJob');$('resumeJob').hidden=true;return;}await new Promise(resolve=>setTimeout(resolve,700));}throw Error('Ожидание прервано. Проверьте результат кнопкой ниже; команда автоматически не повторяется.');}finally{busy=false;await status().catch(()=>{});}}
action('execute',async()=>{const command=$('text').value.trim();if(!command)throw Error('Сначала введите команду в поле текста.');if(!confirm('Выполнить на магнитоле?\n\n'+command.slice(0,500)))return;const id=uid();sessionStorage.setItem('natroJob',id);busy=true;$('execute').disabled=true;$('output').textContent='Отправка…';try{await api('/api/command',{id,command});await poll(id);}catch(error){$('resumeJob').hidden=false;tell(error.message+' Команда не отправляется повторно автоматически.');}finally{busy=false;await status().catch(()=>{});}});
action('resumeJob',async()=>{const id=sessionStorage.getItem('natroJob');if(id)await poll(id);});
async function renderFiles(endpoint,listId,downloadPath){const result=await api(endpoint);$(listId).replaceChildren();for(const file of result.files){const li=document.createElement('li'),label=document.createElement('span'),small=document.createElement('small'),button=document.createElement('button');label.textContent=file.name;small.textContent=(file.size/1024/1024).toFixed(2)+' МиБ';label.append(small);button.textContent='Скачать';button.className='secondary';button.disabled=file.size>256*1024*1024;if(button.disabled)button.textContent='Больше 256 МиБ';button.onclick=async()=>{button.disabled=true;try{const response=await fetch(downloadPath+encodeURIComponent(file.id),{headers:{Authorization:'Bearer '+token}});if(!response.ok)throw Error('Не удалось скачать файл; проверьте сопряжение');const blob=await response.blob();if(blob.size!==Number(response.headers.get('Content-Length')))throw Error('Файл скачан не полностью');const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=file.name;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);}catch(error){tell(error.message);}finally{button.disabled=false;}};li.append(label,button);$(listId).append(li);}}
async function natroFiles(){try{$('natroFolderState').textContent='Чтение папки…';await renderFiles('/api/natro-files','natroFileList','/api/natro-file/');$('natroFolderState').textContent=$('natroFileList').children.length?'':'Папка пуста. Сохраните файлы в Download/Natro на магнитоле.';}catch(error){$('natroFileList').replaceChildren();$('natroFolderState').textContent=error.message;}}
async function files(){await renderFiles('/api/files','fileList','/api/file/');await natroFiles();}
action('refreshNatroFiles',natroFiles);
action('refreshFiles',files);
let logPolling=false;
async function exportLogs(format){
 if(logPolling)return;
 if(format)await api('/api/logs',{id:uid(),format});
 logPolling=true;$('logsTxt').disabled=true;$('logsZip').disabled=true;
 try{
  for(let attempt=0;attempt<120;attempt++){
   const result=await api('/api/logs');
   if(!result.id){$('logsState').textContent='Нет экспорта в этом сеансе сервера.';return;}
   if(!result.done){$('logsState').textContent=result.progress+' · Можно вернуться к проверке экспорта позже.';await new Promise(resolve=>setTimeout(resolve,1000));continue;}
   if(result.error)throw Error(result.error);
   if(result.size>256*1024*1024){
    const ticket=await api('/api/logs/download-ticket',{id:result.id});
    const link=document.createElement('a');link.href=ticket.url;link.download=result.name;link.textContent='Скачать '+result.name;
    $('logsState').replaceChildren(document.createTextNode((result.progress||'')+' · Архив готов. '),link);
    link.click();return;
   }
   $('logsState').textContent='Скачивание '+result.name+'…';
   const response=await fetch('/api/logs/download',{headers:{Authorization:'Bearer '+token,'X-Natro-Export':result.id},cache:'no-store'});
   if(!response.ok)throw Error('Экспорт недоступен. Проверьте результат ещё раз.');
   const blob=await response.blob();if(blob.size!==result.size)throw Error('Скачивание неполное. Повторите скачивание, не сбор.');
   const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=result.name;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);
   $('logsState').textContent=(result.progress||'')+' · Файл передан браузеру. На iPhone выберите «Сохранить в Файлы».';return;
  }
  throw Error('Экспорт ещё выполняется. Нажмите «Проверить экспорт / скачать».');
 }finally{logPolling=false;$('logsTxt').disabled=false;$('logsZip').disabled=false;}
}
action('logsTxt',()=>exportLogs('txt'));action('logsZip',()=>exportLogs('zip'));action('logsResume',()=>exportLogs());
action('systemDump',async()=>{if(confirm('Снять Android/QNX дамп без перезагрузки? Только чтение; сбор может занять длительное время. Выполняйте на стоянке.'))await exportLogs('dump');});
action('vehicleDump',async()=>{if(confirm('Доскачать пропуски QNX? Файлы из полного и частичного дампов 17 октября исключены при совпадении размера; свежие логи включены. Android целиком не копируется. Понадобятся время и свободное место. Выполняйте на стоянке.'))await exportLogs('vehicle');});
action('cancelDump',async()=>tell((await api('/api/logs/cancel',{})).message));
async function updateState(action){
 const value=await api('/api/update',action?{action}:undefined);
 $('updateState').textContent='Установлена '+value.installed+' · '+value.message;
 $('updateCheck').disabled=value.busy;$('updateDownload').disabled=!value.canDownload;$('updateInstall').disabled=!value.canInstall;
 return value;
}
action('updateCheck',()=>updateState('check'));action('updateDownload',()=>updateState('download'));action('updateStatus',()=>updateState());
action('updateInstall',async()=>{if(confirm('Обновить Natro на магнитоле? Выполняйте на стоянке. Подтверждение установки может потребоваться на экране магнитолы.'))await updateState('install');});
function sendFile(file){return new Promise((resolve,reject)=>{const xhr=new XMLHttpRequest();upload=xhr;xhr.open('POST','/api/upload');xhr.setRequestHeader('Authorization','Bearer '+token);xhr.setRequestHeader('X-Natro-Client','1');xhr.setRequestHeader('X-File-Name',encodeURIComponent(file.name));xhr.timeout=300000;xhr.upload.onprogress=event=>{if(event.lengthComputable)$('progress').value=event.loaded/event.total*100;};xhr.onload=()=>{upload=null;try{const result=JSON.parse(xhr.responseText);if(xhr.status!==200)throw Error(result.error||'Файл не принят');resolve(result);}catch(error){reject(error);}};xhr.onerror=()=>reject(Error('Связь оборвалась. Передача не подтверждена.'));xhr.ontimeout=()=>reject(Error('Таймаут передачи.'));xhr.onabort=()=>reject(Error('Передача отменена.'));xhr.send(file);});}
action('upload',async()=>{const selected=Array.from($('files').files);if(!selected.length)throw Error('Выберите файл.');if(selected.some(file=>file.size>256*1024*1024))throw Error('Максимум 256 МиБ на файл.');cancelled=false;$('upload').disabled=true;$('cancelUpload').disabled=false;try{for(const file of selected){if(cancelled)break;$('progress').value=0;$('uploadStatus').textContent='Передача: '+file.name;const result=await sendFile(file);$('uploadStatus').textContent='Сохранено: '+result.name+' · '+result.size+' байт';}await files();}finally{upload=null;$('upload').disabled=false;$('cancelUpload').disabled=true;}});
action('cancelUpload',()=>{cancelled=true;if(upload)upload.abort();});
action('logout',async()=>{await api('/api/unpair',{});clearToken();showPaired(false);tell('Сопряжение отозвано на магнитоле и забыто на телефоне.');});
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&token)status().catch(error=>tell(error.message));});
let checkingStatus=false;
setInterval(async()=>{if(document.hidden||!token||checkingStatus)return;checkingStatus=true;try{await status();await updateState();}catch(error){$('connection').textContent='Нет подтверждения связи · '+location.host;}finally{checkingStatus=false;}},5000);
$('resumeJob').hidden=!sessionStorage.getItem('natroJob');
if(token)status().then(files).catch(error=>tell(error.message));else showPaired(false);

action('vehicleDumpAll',async()=>{if(confirm('Повторно скачать все выбранные файлы QNX, включая уже полученные? Понадобятся время и свободное место.'))await exportLogs('vehicle_all');});
