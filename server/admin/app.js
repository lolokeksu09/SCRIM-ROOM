'use strict';
const $=id=>document.getElementById(id);
const ranks={BELOW_MASTER:'Алмаз и ниже',MASTER:'Мастер',GRANDMASTER:'Грандмастер',CHALLENGER:'Претендент',SOVEREIGN:'Суверен'};
const roles={BARON:'Барон',JUNGLE:'Лес',MID:'Мид',DRAGON:'ADC',SUPPORT:'Поддержка'};
const labels={PENDING_REVIEW:'На проверке',APPROVED:'Допущен',REJECTED:'Отклонён',READY_CHECK:'Подтверждение',ACTIVE:'Активный',DISPUTED:'Спорный результат'};
let key='',accounts=[],matches=[],sessions=[],complaints=[],qualityHistory=[],competitive=null,operations=null,selected=null,epoch=0,busy=false,images=[];
const node=(tag,value,cls)=>{const n=document.createElement(tag);if(value!==undefined)n.textContent=value;if(cls)n.className=cls;return n};
function say(message){$('notice').textContent=message}
function releaseImages(){if($('preview').open)$('preview').close();$('fullImage').removeAttribute('src');images.forEach(URL.revokeObjectURL);images=[]}
function logout(message=''){epoch++;key='';accounts=[];matches=[];sessions=[];complaints=[];qualityHistory=[];competitive=null;operations=null;selected=null;busy=false;releaseImages();$('list').replaceChildren();$('detail').replaceChildren();$('matchList').replaceChildren();$('qualityHistory').replaceChildren();$('serverStatus').replaceChildren();$('auditList').replaceChildren();for(const id of ['seasonList','cupList','fixtureList','clubTeams','cupTeams','cupSeason'])$(id).replaceChildren();$('seasonForm').reset();$('cupForm').reset();$('sessionList').replaceChildren();$('complaintList').replaceChildren();$('sessionForm').reset();$('metrics').replaceChildren();$('key').value='';$('workspace').hidden=true;$('logout').hidden=true;$('login').hidden=false;say(message)}
async function api(path,options={}){
 const session=key;const version=epoch;
 const response=await fetch('/v1/admin'+path,{...options,cache:'no-store',credentials:'omit',headers:{'X-Admin-Key':session,...options.headers}});
 if(session!==key||version!==epoch)throw new Error('Запрос отменён');
 if(response.status===403){logout('Доступ запрещён. Проверьте ключ.');throw new Error('Доступ запрещён')}
 if(!response.ok){let body={};try{body=await response.json()}catch{}throw new Error(typeof body.detail==='string'?body.detail:'HTTP '+response.status)}
 return response;
}
async function load(){
 const session=key;const results=await Promise.all([api('/accounts').then(r=>r.json()),api('/matches').then(r=>r.json()),api('/sessions').then(r=>r.json()),api('/complaints').then(r=>r.json()),api('/match-quality').then(r=>r.json()),api('/competitive').then(r=>r.json()),api('/operations').then(r=>r.json())]);
 if(!key||key!==session)return;[accounts,matches]=results;sessions=results[2].sessions;complaints=results[3].reports;qualityHistory=results[4].matches;competitive=results[5];operations=results[6];render();if(selected){const current=accounts.find(a=>a.id===selected.id);if(current)await detail(current);else{selected=null;$('detail').replaceChildren(node('p','Выберите заявку.'))}}
}
function render(){
 $('metrics').replaceChildren(...[['На проверке',accounts.filter(a=>a.status==='PENDING_REVIEW').length],['Допущены',accounts.filter(a=>a.status==='APPROVED').length],['Отклонены',accounts.filter(a=>a.status==='REJECTED').length],['Матчи',matches.length]].map(([label,count])=>{const n=node('div',undefined,'metric');n.append(node('strong',String(count)),node('small',label));return n}));
 const f=$('filter').value,q=$('search').value.toLocaleLowerCase().trim();
 const filtered=accounts.filter(a=>(f==='ALL'||f===a.status)&&(!q||(a.riot_id+' '+a.region).toLocaleLowerCase().includes(q)));
 $('list').replaceChildren(...filtered.map(a=>{const b=node('button',a.riot_id,'account');b.type='button';b.setAttribute('aria-pressed',String(selected?.id===a.id));b.append(node('small',(labels[a.status]||a.status)+' · '+a.evidence_count+' фото · '+a.region));b.onclick=()=>{if(!busy)detail(a).catch(e=>{if(key)say(e.message)})};return b}));
 if(!filtered.length)$('list').append(node('p','Нет заявок по выбранному фильтру.'));
 $('matchList').replaceChildren(...matches.map(m=>{const card=node('article',undefined,'match');card.append(node('h2',(labels[m.status]||m.status)+' · '+m.region),node('p','Готовность: '+m.ready_count+'/10'));const teams=node('div',undefined,'teams');for(const [name,players] of [['Синие',m.blue],['Красные',m.red]]){const team=node('section');team.append(node('strong',name));players.forEach(p=>team.append(node('p',(roles[p.role]||p.role)+' · '+p.riot_id+(p.ready?' ✓':''))));teams.append(team)}card.append(teams);if(m.phase_expires_at)card.append(node('p','Срок стадии: '+new Date(m.phase_expires_at*1000).toLocaleString()));if(m.quality)card.append(qualityCard(m.quality));if(m.lobby_room)card.append(node('p','Лобби: '+m.lobby_room+' · Отметились '+m.lobby_arrivals+'/10'));for(const side of (m.evidence_sides||[])){const b=node('button','Скриншот: '+(side==='BLUE'?'синие':'красные'));b.onclick=async()=>{const version=epoch;try{const res=await api('/matches/'+encodeURIComponent(m.id)+'/evidence/'+encodeURIComponent(side));const blob=await res.blob();if(version!==epoch||!key)return;const url=URL.createObjectURL(blob);images.push(url);$('fullImage').src=url;$('preview').showModal()}catch(e){if(key)say(e.message)}};card.append(b)}if(m.status==='ACTIVE'){card.append(node('p','Подтверждений капитанов: '+m.report_count+'/2'));const b=node('button','Аннулировать без очков','danger');b.onclick=()=>mutate('/matches/'+encodeURIComponent(m.id)+'/close',null,'Аннулировать матч без изменения оценки?');card.append(b)}if(m.status==='DISPUTED'){card.append(node('p','Капитан синих: '+m.reports.BLUE+' · Капитан красных: '+m.reports.RED),node('p','Причина проверки: '+(m.review_reason||'CAPTAIN_CONFLICT')),node('p','Проверьте прикреплённые скриншоты и обстоятельства перед решением.'));const reason=node('textarea');reason.placeholder='Основание решения';reason.maxLength=500;reason.setAttribute('aria-label','Основание решения по матчу');card.append(reason);const a=node('div',undefined,'actions');for(const [winner,label] of [['BLUE','Победа синих'],['RED','Победа красных'],['VOID','Аннулировать']]){const b=node('button',label);b.onclick=()=>{if(reason.value.trim().length<3){say('Укажи основание решения');return}mutate('/matches/'+encodeURIComponent(m.id)+'/resolve',null,'Подтвердить решение: '+label+'?',{winner,note:reason.value.trim()})};a.append(b)}card.append(a)}return card}));
 if(!matches.length)$('matchList').append(node('p','Назначенных матчей нет.'));
 renderSessions();renderComplaints();renderQualityHistory();renderCompetitive();renderOperations();
}
async function detail(a){
 releaseImages();selected=a;const version=++epoch;render();
 const d=$('detail');d.replaceChildren(node('h2',a.riot_id),node('p',(labels[a.status]||a.status)+' · '+a.region));
 const meta=node('div',undefined,'meta');for(const [label,value] of [['Текущий ранг',ranks[a.current_rank]],['Прошлый ранг',ranks[a.previous_rank]],['Основная роль',roles[a.primary]],['Вторая роль',roles[a.secondary]],['Стартовая оценка',String(a.rating_seed)],['Скриншоты',String(a.evidence_count)]]){const n=node('div');n.append(node('small',label),node('strong',value));meta.append(n)}d.append(meta,node('h2','Подтверждающие документы'),node('p','Сверьте Riot ID, сезон, ранги и статистику. Скриншот не доказывает владение аккаунтом.'));
 const grid=node('div',undefined,'evidence');d.append(grid);if(a.duplicate_slots?.length)d.append(node('p','Внимание: совпадающие документы в других анкетах: '+a.duplicate_slots.join(', ')+'. Проверьте вручную.','danger'));if(a.discipline)d.append(node('p','Отказы и пропуски готовности: '+a.discipline.incidents));
 const actions=node('div',undefined,'actions'),approve=node('button','Допустить','primary'),reject=node('button','Отклонить','danger');
 approve.disabled=true;reject.disabled=a.status==='REJECTED';approve.onclick=()=>mutate('/accounts/'+encodeURIComponent(a.id)+'/approve',a.review_revision,'Подтверждаете проверку личности и документов?');reject.onclick=()=>mutate('/accounts/'+encodeURIComponent(a.id)+'/reject',a.review_revision,'Отклонить заявку?');actions.append(approve,reject);d.append(actions);
 let loaded=0;
 for(let i=1;i<=a.evidence_count;i++){try{const response=await api('/accounts/'+encodeURIComponent(a.id)+'/evidence/'+i);const blob=await response.blob();if(version!==epoch||!key)return;const url=URL.createObjectURL(blob);images.push(url);const b=node('button');const img=node('img');img.src=url;img.alt='Документ '+i; b.append(img,node('span','Открыть документ '+i));b.onclick=()=>{$('fullImage').src=url;$('preview').showModal()};const wrap=node('div');wrap.append(b);const scan=node('button','Распознать статистику');const result=node('p','');scan.onclick=async()=>{scan.disabled=true;try{const r=await api('/accounts/'+encodeURIComponent(a.id)+'/evidence/'+i+'/ocr',{method:'POST'});const data=await r.json();if(version!==epoch)return;const v=data.suggestions;result.textContent='OCR, не проверено: '+[v.matches!==null?'Матчи: '+v.matches:null,v.win_rate!==null?'Винрейт: '+v.win_rate+'%':null,v.kda!==null?'KDA: '+v.kda:null,...v.rank_candidates.map(x=>ranks[x]||x)].filter(Boolean).join(' · ')}catch(e){if(version===epoch)result.textContent=e.message}finally{scan.disabled=false}};wrap.append(scan,result);grid.append(wrap);loaded++}catch(e){if(version!==epoch||!key)return;grid.append(node('p','Документ '+i+': '+e.message))}}
 if(version===epoch)approve.disabled=a.status==='APPROVED'||loaded<2||loaded!==a.evidence_count;
}
async function mutate(path,revision,prompt,body){
 if(busy||!key||!window.confirm(prompt))return;busy=true;
 const buttons=[...$('workspace').querySelectorAll('button')],disabled=buttons.map(b=>b.disabled);buttons.forEach(b=>b.disabled=true);
 try{await api(path,{method:'POST',headers:{...(revision?{'X-Review-Revision':revision}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});say('Изменение сохранено.');await load()}catch(e){if(key){say(e.message);try{await load()}catch{}}}finally{busy=false;buttons.forEach((b,i)=>{if(b.isConnected)b.disabled=disabled[i]})}
}
$('loginForm').onsubmit=async event=>{event.preventDefault();if(busy)return;key=$('key').value;$('key').value='';busy=true;try{await load();if(key){$('login').hidden=true;$('workspace').hidden=false;$('logout').hidden=false;say('')}}catch(e){logout(e.message)}finally{busy=false}};
$('logout').onclick=()=>logout();$('refresh').onclick=()=>{if(!busy)load().catch(e=>{if(key)say(e.message)})};$('filter').onchange=render;$('search').oninput=render;
function tab(name){for(const n of ['accounts','matches','sessions','complaints','competitive','operations']){$(n).hidden=n!==name;$(n+'Tab').setAttribute('aria-pressed',String(n===name))}}
$('accountsTab').onclick=()=>tab('accounts');$('matchesTab').onclick=()=>tab('matches');$('sessionsTab').onclick=()=>tab('sessions');$('complaintsTab').onclick=()=>tab('complaints');$('competitiveTab').onclick=()=>tab('competitive');$('operationsTab').onclick=()=>tab('operations');$('closePreview').onclick=()=>$('preview').close();
function renderSessions(){
 const statuses={UPCOMING:'Предстоит',OPEN:'Открыта',ENDED:'Завершена',CANCELLED:'Отменена'};
 $('sessionList').replaceChildren(...sessions.map(s=>{
   const card=node('article',undefined,'match');
   card.append(node('h2',s.title),node('p',statuses[s.status]+' · '+s.region+' · '+new Date(s.starts_at*1000).toLocaleString()+' — '+new Date(s.ends_at*1000).toLocaleString()),node('p','Записано '+s.registered+'/'+s.capacity+' · В очереди '+s.waiting),node('p',Object.entries(roles).map(([r,label])=>label+': '+(s.roles[r]||0)).join(' · ')));
   if(s.status==='OPEN'||s.status==='UPCOMING'){const b=node('button','Отменить сессию','danger');b.onclick=()=>mutate('/sessions/'+encodeURIComponent(s.id)+'/cancel',null,'Отменить игровое окно? Ожидающие выйдут из очереди, назначенные матчи сохранятся.');card.append(b)}
   return card;
 }));
 if(!sessions.length)$('sessionList').append(node('p','Нет игровых сессий.'));
}
$('sessionForm').onsubmit=event=>{
 event.preventDefault();
 const starts=Math.floor(new Date($('sessionStart').value).getTime()/1000);
 if(!Number.isFinite(starts)||starts<=Date.now()/1000){say('Выбери будущее время начала');return}
 mutate('/sessions',null,'Создать игровое окно?',{title:$('sessionTitle').value,region:$('sessionRegion').value,starts_at:starts,ends_at:starts+Number($('sessionDuration').value)*60,capacity:Number($('sessionCapacity').value)});
};

function renderComplaints(){
 const categories={AFK:'AFK',ABUSE:'Оскорбления',CHEATING:'Читы',GRIEFING:'Саботаж',OTHER:'Другое'};
 const statuses={PENDING:'На проверке',UPHELD:'Подтверждена',DISMISSED:'Отклонена'};
 $('complaintList').replaceChildren(...complaints.map(r=>{
   const a=node('article',undefined,'match');
   a.append(node('h2',categories[r.category]+' · '+statuses[r.status]),node('p','От: '+r.reporter_riot_id+' · На: '+r.target_riot_id),node('p','Матч: '+r.match_id),node('p',r.note||'Без описания'));
   if(r.status==='PENDING'){
     const input=node('textarea');input.maxLength=500;input.placeholder='Основание решения';input.setAttribute('aria-label','Основание решения');
     const actions=node('div',undefined,'actions');
     for(const [decision,label] of [['UPHELD','Подтвердить жалобу'],['DISMISSED','Отклонить жалобу']]){
       const b=node('button',label);b.onclick=()=>{if(input.value.trim().length<3){say('Укажи основание решения');return}mutate('/complaints/'+encodeURIComponent(r.id)+'/review',null,'Сохранить решение?',{decision,note:input.value.trim()})};actions.append(b)
     }
     a.append(input,actions)
   }else a.append(node('p','Решение: '+(r.moderator_note||'')));
   return a
 }));
 if(!complaints.length)$('complaintList').append(node('p','Жалоб пока нет.'));
}

function qualityCard(q){
 const n=node('section');
 n.append(node('strong','Диагностика подбора · '+q.algorithm),node('p','Средняя разница оценки: '+q.average_gap+' · Вторых ролей: '+q.secondary_count+' · Повторных пар: '+q.repeated_pairs),node('p','Разница неопределённости: '+q.uncertainty_gap+' · Целевая функция: '+q.score+' · Ожидание: '+q.oldest_wait_seconds+' с'),node('p','Разница по ролям: '+Object.entries(q.role_gaps).map(([r,v])=>(roles[r]||r)+': '+v).join(' · ')));
 return n
}
function renderQualityHistory(){
 const el=$('qualityHistory');
 el.replaceChildren(...qualityHistory.map(m=>{
   const a=node('article',undefined,'match');
   a.append(node('strong',m.region+' · '+new Date(m.created_at*1000).toLocaleString()),node('p',m.id+' · '+m.status+(m.winner?' · Результат: '+m.winner:'')),qualityCard(m.quality));
   return a
 }));
 if(!qualityHistory.length)el.append(node('p','Нет новых матчей с диагностикой.'));
}

function option(id,label){const o=node('option',label);o.value=id;return o}
function renderCupTeams(){
 const s=competitive?.seasons.find(s=>s.id===$('cupSeason').value);
 const old=[...$('cupTeams').querySelectorAll('select')].map(x=>x.value);
 const eligible=competitive?.teams.filter(t=>t.region===s?.region&&t.eligible&&!t.locked)||[];
 $('cupTeams').replaceChildren(...Array.from({length:4},(_,i)=>{
  const label=node('label','Команда '+(i+1));const sel=node('select');sel.required=true;sel.append(option('','Выбрать'),...eligible.map(t=>option(t.id,t.name)));if(eligible.some(t=>t.id===old[i]))sel.value=old[i];label.append(sel);return label
 }));
}
function renderCompetitive(){
 if(!competitive)return;
 const old=$('cupSeason').value;
 $('cupSeason').replaceChildren(option('','Выбрать сезон'),...competitive.seasons.filter(s=>s.ends_at>Date.now()/1000).map(s=>option(s.id,s.name+' · '+s.region)));
 if([...$('cupSeason').options].some(o=>o.value===old))$('cupSeason').value=old;
 renderCupTeams();
 $('seasonList').replaceChildren(...competitive.seasons.map(s=>{const a=node('article',undefined,'match');a.append(node('h2',s.name+' · '+s.region),node('p',new Date(s.starts_at*1000).toLocaleDateString()+' — '+new Date(s.ends_at*1000).toLocaleDateString()));const rows=competitive.standings?.[s.id]||[];for(const row of rows)a.append(node('p',row.name+' · '+row.wins+' побед / '+row.losses+' поражений'));if(!rows.length)a.append(node('p','Нет подтверждённых встреч'));return a}));
 $('clubTeams').replaceChildren(...competitive.teams.map(t=>{const a=node('article',undefined,'match');a.append(node('h2',t.name+' · '+t.region),node('p',t.members.length+'/5 · Победы '+t.wins+'/'+t.played),node('p',t.members.map(m=>m.riot_id+(m.id===t.captain?' (К)':'')).join(' · ')));return a}));
 $('cupList').replaceChildren(...competitive.cups.map(c=>{
  const a=node('article',undefined,'match');a.append(node('h2',c.name),node('p',c.status));if(c.status==='RUNNING'){const b=node('button','Отменить кубок','danger');b.onclick=()=>mutate('/competitive/cups/'+c.id+'/cancel',null,'Отменить турнир?');a.append(b)}return a
 }));
 $('fixtureList').replaceChildren(...competitive.fixtures.map(f=>{
  const a=node('article',undefined,'match');a.append(node('h2',f.team_a_name+' — '+f.team_b_name),node('p',f.stage+' · '+f.status+' · '+new Date(f.starts_at*1000).toLocaleString()));
  if(f.winner_id)a.append(node('p','Победитель: '+(f.winner_id===f.team_a?f.team_a_name:f.team_b_name)+' · '+(f.note||'')));
  if(f.status==='SCHEDULED'){
    const reason=node('textarea');reason.placeholder='Основание результата';reason.maxLength=500;reason.setAttribute('aria-label','Основание результата');
    a.append(reason);
    for(const [id,label] of [[f.team_a,f.team_a_name],[f.team_b,f.team_b_name]]){const b=node('button','Победа: '+label);b.disabled=Date.now()/1000<f.starts_at;b.onclick=()=>{if(reason.value.trim().length<3){say('Укажи основание');return}mutate('/competitive/fixtures/'+f.id+'/resolve',null,'Зафиксировать победителя?',{winner_id:id,note:reason.value.trim()})};a.append(b)}
  }
  if(!f.cup_id&&['PENDING','SCHEDULED'].includes(f.status)){const b=node('button','Отменить встречу','danger');b.onclick=()=>mutate('/competitive/fixtures/'+f.id+'/cancel',null,'Отменить встречу?');a.append(b)}
  return a
 }));
 if(!competitive.fixtures.length)$('fixtureList').append(node('p','Встреч пока нет.'));
}
$('cupSeason').onchange=renderCupTeams;
$('seasonForm').onsubmit=e=>{e.preventDefault();const start=Math.floor(new Date($('seasonStart').value).getTime()/1000);if(!Number.isFinite(start)){say('Укажи дату');return}mutate('/competitive/seasons',null,'Создать сезон?',{name:$('seasonName').value,region:$('seasonRegion').value,starts_at:start,ends_at:start+Number($('seasonDays').value)*86400})};
$('cupForm').onsubmit=e=>{e.preventDefault();const start=Math.floor(new Date($('cupStart').value).getTime()/1000);const teams=[...$('cupTeams').querySelectorAll('select')].map(s=>s.value);if(!Number.isFinite(start)||teams.some(t=>!t)||new Set(teams).size!==4){say('Выбери четыре разные команды и дату');return}mutate('/competitive/cups',null,'Создать турнир с фиксированными составами?',{name:$('cupName').value,season_id:$('cupSeason').value,starts_at:start,teams})};

function renderOperations(){
 if(!operations)return;
 const n=node('article');n.append(node('h2','База данных: '+operations.database));
 for(const [k,v] of Object.entries(operations.counts))n.append(node('p',k+': '+v));
 $('serverStatus').replaceChildren(n);
 $('auditList').replaceChildren(...operations.audit.map(a=>{
   const card=node('article',undefined,'match');
   card.append(node('strong',(a.actor||'legacy')+' · '+a.method+' '+a.route+' · HTTP '+a.status),node('p',new Date(a.at*1000).toLocaleString()+(a.resource?' · '+a.resource:'')));return card
 }));
 if(!operations.audit.length)$('auditList').append(node('p','Нет записей.'));
}
