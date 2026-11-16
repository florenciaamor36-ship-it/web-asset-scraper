#!/usr/bin/env python3
"""Short-lived GitHub Actions worker for publicly available demo games."""
import asyncio, json, os, re, time, zipfile
from pathlib import Path
from urllib.parse import urlsplit
from playwright.async_api import async_playwright

MAX_FILES=300
MAX_FILE=16*1024*1024
MAX_TOTAL=96*1024*1024
OUT=Path('capture-output')
OUT.mkdir(exist_ok=True)
assets={}; total=0; errors=[]
ALLOWED={'betragaperras.es','demogamesfree.pragmaticplay.net','clienthub.pragmaticplay.com'}

def safe_host(url):
    h=(urlsplit(url).hostname or '').lower()
    return h in ALLOWED or h.endswith('.pragmaticplay.net')

async def main():
    global total
    target=os.getenv('TARGET_URL','').strip()
    if not target or urlsplit(target).scheme not in ('http','https') or not safe_host(target):
        raise SystemExit('URL outside approved public demo hosts')
    seconds=int(os.getenv('PLAY_SECONDS','300'))
    async with async_playwright() as p:
        browser=await p.chromium.launch(headless=True,args=['--no-sandbox','--disable-dev-shm-usage','--disable-gpu'])
        context=await browser.new_context(viewport={'width':1280,'height':900},accept_downloads=False)
        page=await context.new_page()
        async def on_response(resp):
            nonlocal_dummy=None
            global total
            u=resp.url
            if u in assets or len(assets)>=MAX_FILES or not safe_host(u): return
            status=resp.status
            try:
                headers=await resp.all_headers(); mime=headers.get('content-type','application/octet-stream')
                rec={'url':u,'status':status,'mime':mime,'bytes':0,'available':False}
                if status==206: rec['note']='partial range response; not reassembled'
                elif status<200 or status>=300: rec['note']='HTTP response not successful'
                else:
                    n=int(headers.get('content-length','0') or '0')
                    if n>MAX_FILE: rec['note']='per-file size cap'
                    else:
                        body=await resp.body()
                        if len(body)>MAX_FILE or total+len(body)>MAX_TOTAL: rec['note']='capture size cap'
                        else:
                            total+=len(body);rec['bytes']=len(body);rec['available']=True
                            ext=Path(urlsplit(u).path).suffix
                            if not ext: ext='.bin'
                            name=f'{len(assets):04d}_{re.sub("[^A-Za-z0-9._-]","_",Path(urlsplit(u).path).name)[:80] or "resource"}{ext if not Path(urlsplit(u).path).suffix else ""}'
                            rec['file']=name
                            (OUT/name).write_bytes(body)
                assets[u]=rec
            except Exception as e:
                assets[u]={'url':u,'status':status,'mime':'','bytes':0,'available':False,'note':type(e).__name__}
        page.on('response',lambda r: asyncio.create_task(on_response(r)))
        print('Opening',target,flush=True)
        await page.goto(target,wait_until='domcontentloaded',timeout=60000)
        await page.wait_for_timeout(7000)
        # Public article: press a visible free-demo launcher if offered.
        if urlsplit(target).hostname=='betragaperras.es':
            for label in ['Juega Gratis','Jugar gratis','Juega gratis','Play demo','Play for free']:
                try:
                    loc=page.get_by_text(label,exact=False).first
                    if await loc.is_visible(timeout=800):
                        await loc.click(timeout=3000);print('Clicked public demo launcher',label,flush=True);break
                except Exception: pass
            await page.wait_for_timeout(5000)
        # Follow the publicly exposed iframe when it was opened by the launcher.
        frames=[f for f in page.frames if f.url and safe_host(f.url) and ('pragmaticplay' in f.url or 'demogames' in f.url)]
        game_page=None
        if frames:
            game_page=page
            print('Game frame detected',[(f.url[:180]) for f in frames],flush=True)
        # If the parent exposes an iframe URL, open its public URL directly in same browser context.
        if urlsplit(target).hostname=='betragaperras.es' and not frames:
            try:
                html=await page.content();m=re.search(r'iframe_url:\\?\s*"([^"]+)"',html)
                if m:
                    u=m.group(1).replace('\\u0026','&').replace('\\u003d','=')
                    if safe_host(u):
                        page=await context.new_page();page.on('response',lambda r: asyncio.create_task(on_response(r)))
                        await page.goto(u,wait_until='domcontentloaded',timeout=60000);await page.wait_for_timeout(8000);game_page=page
                        print('Opened public demo iframe URL',flush=True)
            except Exception as e: print('iframe fallback',type(e).__name__,flush=True)
        if game_page is None: game_page=page
        # Let the demo finish initializing; record the visual state for audit.
        await game_page.screenshot(path=str(OUT/'before-play.jpg'),type='jpeg',quality=55)
        print('Title:',await game_page.title(),'URL:',game_page.url,flush=True)
        # Best effort to start an explicitly public free/demo session; no login/payment.
        for label in ['Juega Gratis','Jugar','Play','Continue','Start','Spin']:
            try:
                b=game_page.get_by_role('button',name=re.compile(label,re.I)).first
                if await b.is_visible(timeout=500):
                    await b.click(timeout=1500);print('Clicked game button',label,flush=True);await game_page.wait_for_timeout(1500)
                    if label.lower()=='spin': break
            except Exception: pass
        # Play demo for specified duration by pressing the visible Spin control near the lower-right center.
        # Click coordinates are sampled from viewport dimensions; this is simulation credit only.
        end=time.monotonic()+seconds; clicks=0
        while time.monotonic()<end:
            try:
                dims=await game_page.evaluate('({w:innerWidth,h:innerHeight})')
                await game_page.mouse.click(dims['w']*0.84,dims['h']*0.91);clicks+=1
                await game_page.wait_for_timeout(6500)
            except Exception as e:
                errors.append('play:'+type(e).__name__);await asyncio.sleep(2)
        try: await game_page.screenshot(path=str(OUT/'after-play.jpg'),type='jpeg',quality=55)
        except Exception as e: errors.append('screenshot:'+type(e).__name__)
        await asyncio.sleep(3)
        manifest={'sourceUrl':target,'finalUrl':game_page.url,'title':await game_page.title(),'playSeconds':seconds,'clicks':clicks,'capturedAssets':len(assets),'capturedBytes':total,'assets':list(assets.values()),'errors':errors}
        (OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
        with zipfile.ZipFile(OUT/'capture.zip','w',zipfile.ZIP_DEFLATED,compresslevel=3) as z:
            for f in OUT.iterdir():
                if f.is_file() and f.name!='capture.zip': z.write(f,f'assets/{f.name}')
        print(json.dumps({k:manifest[k] for k in ('title','finalUrl','playSeconds','clicks','capturedAssets','capturedBytes','errors')},ensure_ascii=False),flush=True)
        await context.close();await browser.close()

asyncio.run(main())
