import asyncio, json, os
from pathlib import Path
from playwright.async_api import async_playwright

URL = 'https://florenciaamor36-ship-it.github.io/halloween-spins/hearts-of-venus-5x4-preview.html'
OUT = Path('validation-output')
OUT.mkdir(exist_ok=True)

async def main():
    errors=[]
    failed=[]
    async with async_playwright() as p:
        browser=await p.chromium.launch(headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
        page=await browser.new_page(viewport={'width':430,'height':932},device_scale_factor=1)
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.on('console', lambda m: errors.append('console: '+m.text) if m.type=='error' else None)
        page.on('requestfailed', lambda r: failed.append({'url':r.url,'failure':r.failure}))
        response=await page.goto(URL,wait_until='domcontentloaded',timeout=90000)
        if not response or response.status!=200: raise RuntimeError(f'preview HTTP status {response.status if response else None}')
        await page.wait_for_function("window.SLOT_GAME_CONFIG && window.SLOT_GAME_CONFIG.id === 'hearts-of-venus-5x4'",timeout=30000)
        await page.wait_for_function("window.Phaser && document.querySelector('#spin')",timeout=30000)
        await page.wait_for_timeout(7000)
        config=await page.evaluate("""() => ({
          title:document.title,id:window.SLOT_GAME_CONFIG.id,
          cols:window.SLOT_GAME_CONFIG.rules.cols,rows:window.SLOT_GAME_CONFIG.rules.rows,
          paylines:window.SLOT_GAME_CONFIG.rules.paylines.length,
          symbolScale:window.SLOT_GAME_CONFIG.visual.symbolScale,
          engine:[...document.scripts].map(s=>s.src).find(s=>s.includes('hearts-of-venus-5x4-engine'))||null,
          loadingHidden:document.querySelector('#loadingScreen')?.classList.contains('is-hidden'),
          spinExists:!!document.querySelector('#spin'),
          gameCanvas:!!document.querySelector('#reels canvas'),
          balance:document.querySelector('#balance')?.textContent,
          symbolAssets:[...document.querySelectorAll('#reels img')].length
        })""")
        await page.screenshot(path=str(OUT/'before-spin.png'),full_page=True)
        await page.evaluate("""() => {
          window.__venusEvents=[];
          for (const event of ['spin-start','reel-stop','spin-result'])
            window.addEventListener('slot:'+event,e=>window.__venusEvents.push({type:event,detail:e.detail}));
        }""")
        await page.locator('#spin').click(force=True,timeout=5000)
        await page.wait_for_function("window.__venusEvents?.some(e=>e.type==='spin-result')",timeout=20000)
        await page.wait_for_timeout(1000)
        result=await page.evaluate("""() => ({
          events:window.__venusEvents,
          balance:document.querySelector('#balance')?.textContent,
          win:document.querySelector('#win')?.textContent,
          reelsSpinning:document.querySelector('#reels')?.classList.contains('is-spinning'),
          loadedImages:[...document.querySelectorAll('#reels img')].filter(i=>i.complete&&i.naturalWidth>0).length,
          totalImages:document.querySelectorAll('#reels img').length
        })""")
        await page.screenshot(path=str(OUT/'after-spin.png'),full_page=True)
        summary={'url':URL,'httpStatus':response.status,'config':config,'result':result,'pageErrors':errors,'failedRequests':failed}
        (OUT/'result.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
        await browser.close()
        print(json.dumps(summary,ensure_ascii=False,indent=2))
        if config['cols']!=5 or config['rows']!=4 or config['paylines']!=20: raise RuntimeError('unexpected grid/payline config')
        types=[e['type'] for e in result['events']]
        if 'spin-start' not in types or 'spin-result' not in types: raise RuntimeError('spin action did not produce full result events')
        if errors: raise RuntimeError('Browser reported page/console errors')

asyncio.run(main())
