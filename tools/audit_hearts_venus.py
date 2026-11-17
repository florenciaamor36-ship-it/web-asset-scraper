import asyncio,json
from pathlib import Path
from playwright.async_api import async_playwright
URL='https://florenciaamor36-ship-it.github.io/halloween-spins/hearts-of-venus-5x4-preview.html'; OUT=Path('ui-audit-output');OUT.mkdir(exist_ok=True)
SELECTORS=['#game','.reels','.display.balance','.display.bet','.display.win','.display.lines','.display.jackpot','#spin','#minus','#plus','#lineUp','#lineDown','#autoSpin','#menu','.announcement-panel','.orientation-warning','#paytableModal','#closePaytable']
async def snap(page,name):
 await page.wait_for_timeout(300);await page.screenshot(path=str(OUT/f'{name}.png'),full_page=True)
 return await page.evaluate('''(selectors)=>{const r=e=>{const b=e.getBoundingClientRect(),s=getComputedStyle(e);return {id:e.id,cls:typeof e.className==='string'?e.className:'',text:(e.innerText||e.getAttribute('aria-label')||'').trim().replace(/\\s+/g,' ').slice(0,80),x:+b.x.toFixed(1),y:+b.y.toFixed(1),w:+b.width.toFixed(1),h:+b.height.toFixed(1),right:+b.right.toFixed(1),bottom:+b.bottom.toFixed(1),display:s.display,visibility:s.visibility,opacity:s.opacity,fontSize:s.fontSize,z:s.zIndex,pointer:s.pointerEvents,overflow:s.overflow}};const out={viewport:{w:innerWidth,h:innerHeight},items:[],overlaps:[],values:{balance:document.querySelector('#balance')?.innerText,bet:document.querySelector('#bet')?.innerText,lines:document.querySelector('#lines')?.innerText,win:document.querySelector('#win')?.innerText,auto:document.querySelector('#autoSpin')?.getAttribute('aria-pressed'),loadingHidden:document.querySelector('#loadingScreen')?.classList.contains('is-hidden'),paytableOpen:document.querySelector('#paytableModal')?.getAttribute('aria-hidden')==='false'}};for(const q of selectors){const e=document.querySelector(q);if(e)out.items.push({selector:q,...r(e)})}const es=[...document.querySelectorAll('.display,#spin,#minus,#plus,#lineUp,#lineDown,#autoSpin,#menu,.announcement-panel')].filter(e=>e.getClientRects().length&&getComputedStyle(e).display!=='none'&&getComputedStyle(e).visibility!=='hidden'&&parseFloat(getComputedStyle(e).opacity)>.05);for(let i=0;i<es.length;i++)for(let j=i+1;j<es.length;j++){if(es[i].contains(es[j])||es[j].contains(es[i]))continue;const a=es[i].getBoundingClientRect(),b=es[j].getBoundingClientRect(),w=Math.max(0,Math.min(a.right,b.right)-Math.max(a.left,b.left)),h=Math.max(0,Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top));if(w>3&&h>3)out.overlaps.push({a:es[i].id||es[i].className,b:es[j].id||es[j].className,w:+w.toFixed(1),h:+h.toFixed(1),area:+(w*h).toFixed(0)})}return out}''',SELECTORS)
async def view(browser,w,h,label):
 page=await browser.new_page(viewport={'width':w,'height':h},device_scale_factor=1);errs=[];page.on('pageerror',lambda e:errs.append(str(e)));page.on('console',lambda m:errs.append(m.text) if m.type=='error' else None);resp=await page.goto(URL,wait_until='domcontentloaded',timeout=90000);await page.wait_for_function("window.SLOT_GAME_CONFIG?.id==='hearts-of-venus-5x4'",timeout=30000);await page.wait_for_timeout(4500);d={'label':label,'status':resp.status,'initial':await snap(page,label+'-initial'),'actions':[],'spins':[],'errors':errs}
 for sel,act in ([] if label=='landscape' else [('#minus','bet down'),('#plus','bet up'),('#lineDown','lines down'),('#lineUp','lines up')]):
  before=await page.evaluate("({bet:document.querySelector('#bet').innerText,lines:document.querySelector('#lines').innerText})");
  try:await page.locator(sel).click(force=True,timeout=3000)
  except Exception as e:d['actions'].append({'action':act,'error':str(e)})
  await page.wait_for_timeout(120);after=await page.evaluate("({bet:document.querySelector('#bet').innerText,lines:document.querySelector('#lines').innerText})");d['actions'].append({'action':act,'before':before,'after':after,'changed':before!=after})
 d['controls']=await snap(page,label+'-controls')
 try:
  await page.locator('#menu').click(force=True,timeout=3000);await page.wait_for_timeout(200);d['paytable']=await snap(page,label+'-paytable');await page.locator('#closePaytable').click(force=True,timeout=3000);d['paytableClosed']=await page.locator('#paytableModal').get_attribute('aria-hidden')
 except Exception as e:d['paytableError']=str(e)
 await page.evaluate("window.__auditEvents=[];for(const n of ['spin-start','reel-stop','spin-result'])window.addEventListener('slot:'+n,e=>window.__auditEvents.push({type:n,detail:e.detail}))")
 for i in range(2):
  try:
   if i: await page.wait_for_timeout(2400)
   await page.locator('#spin').click(force=True,timeout=3000);await page.wait_for_function(f"window.__auditEvents.filter(e=>e.type==='spin-result').length>={i+1}",timeout=18000);d['spins'].append({'spin':i+1,'ok':True,'events':await page.evaluate("window.__auditEvents.map(e=>e.type)"),'values':await page.evaluate("({balance:document.querySelector('#balance').innerText,bet:document.querySelector('#bet').innerText,lines:document.querySelector('#lines').innerText,win:document.querySelector('#win').innerText})")});await page.wait_for_timeout(450)
  except Exception as e:d['spins'].append({'spin':i+1,'ok':False,'error':str(e)})
 d['audio']=await page.evaluate("window.__VENUS_AUDIO_TEST||null")
 d['afterSpins']=await snap(page,label+'-after-spins')
 try:
  await page.locator('#autoSpin').click(force=True);await page.wait_for_timeout(120);d['autoOn']=await page.locator('#autoSpin').get_attribute('aria-pressed');await page.locator('#autoSpin').click(force=True);await page.wait_for_timeout(150);d['autoOff']=await page.locator('#autoSpin').get_attribute('aria-pressed')
 except Exception as e:d['autoError']=str(e)
 d['errors']=errs;await page.close();return d
async def main():
 async with async_playwright() as p:
  b=await p.chromium.launch(headless=True,args=['--no-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required']);rep={'url':URL,'viewports':[]}
  for w,h,l in [(430,932,'portrait'),(932,430,'landscape'),(1365,900,'desktop')]:
   try:rep['viewports'].append(await view(b,w,h,l))
   except Exception as e:rep['viewports'].append({'label':l,'fatal':str(e)})
  await b.close()
 (OUT/'report.json').write_text(json.dumps(rep,ensure_ascii=False,indent=2));issues=[]
 for v in rep['viewports']:
  if v.get('fatal'):issues.append(f"{v['label']} FATAL {v['fatal']}");continue
  for o in v['initial']['overlaps']:
   if o['area']>120:issues.append(f"{v['label']} overlap {o}")
  for a in v.get('actions',[]):
   if 'changed'in a and not a['changed']:issues.append(f"{v['label']} control failed {a['action']}")
  for s in v.get('spins',[]):
   if not s['ok']:issues.append(f"{v['label']} spin failed {s}")
  if v.get('errors'):issues.append(f"{v['label']} browser errors {v['errors']}")
  if v['label'] in ('portrait','desktop'):
   a=v.get('audio') or {}
   if not a.get('ready'):issues.append(f"{v['label']} audio JSON did not load")
   if a.get('soundId')!='0864ff107a030e5489388b8012f6af94':issues.append(f"{v['label']} audio ID mismatch {a.get('soundId')}")
   if not any(x.get('playing') or x.get('playPromise')=='resolved' for x in a.get('plays',[])):issues.append(f"{v['label']} source GUI audio did not start {a}")
  if v.get('autoOff')!='false':issues.append(f"{v['label']} auto-stop issue {v.get('autoOff')}")
 (OUT/'issues.txt').write_text('\n'.join(issues) if issues else 'No flagged issues.')
 print('FLAGGED',len(issues));print('\n'.join(issues));
 for v in rep['viewports']:
  if v.get('fatal'):print('FATAL',v['label'],v['fatal']);continue
  print('VIEW',v['label'],v['status'],'overlaps',v['initial']['overlaps'],'actions',v['actions'],'paytable',v.get('paytableClosed'),'spins',[(s.get('ok'),s.get('values')) for s in v['spins']],'auto',v.get('autoOn'),v.get('autoOff'),'audio',v.get('audio'),'errors',v.get('errors'))
asyncio.run(main())
