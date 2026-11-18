import asyncio, json, time
from pathlib import Path
from playwright.async_api import async_playwright

OUT = Path('venus-audit')
OUT.mkdir(exist_ok=True)
URLS = {
    '6x5': 'https://florenciaamor36-ship-it.github.io/halloween-spins/hearts-of-venus-preview.html',
    '5x4': 'https://florenciaamor36-ship-it.github.io/halloween-spins/hearts-of-venus-5x4-preview.html',
}

async def main():
    report = {'startedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'pages': []}
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True, args=['--no-sandbox'])
        for variant, url in URLS.items():
            for device, viewport in [('desktop', {'width': 1365, 'height': 900}), ('mobile', {'width': 390, 'height': 844})]:
                page = await browser.new_page(viewport=viewport, device_scale_factor=1, is_mobile=(device == 'mobile'), has_touch=(device == 'mobile'))
                row = {'variant': variant, 'device': device, 'url': url, 'consoleErrors': [], 'pageErrors': [], 'failedRequests': [], 'badResponses': [], 'checks': {}, 'interactions': []}
                page.on('console', lambda msg, r=row: r['consoleErrors'].append(msg.text[:500]) if msg.type == 'error' else None)
                page.on('pageerror', lambda exc, r=row: r['pageErrors'].append(str(exc)[:700]))
                page.on('requestfailed', lambda req, r=row: r['failedRequests'].append({'url': req.url[:400], 'failure': req.failure}))
                page.on('response', lambda res, r=row: r['badResponses'].append({'status': res.status, 'url': res.url[:400]}) if res.status >= 400 else None)
                try:
                    resp = await page.goto(url, wait_until='networkidle', timeout=90000)
                    row['httpStatus'] = resp.status if resp else None
                    await page.wait_for_timeout(9000)
                    row['title'] = await page.title()
                    row['finalUrl'] = page.url
                    row['loading'] = await page.locator('#loadingScreen').evaluate('(e)=>({display:getComputedStyle(e).display,hidden:e.classList.contains("hidden"),text:e.innerText})')
                    row['viewport'] = await page.evaluate('() => ({innerWidth,innerHeight,dpr:devicePixelRatio,scrollWidth:document.documentElement.scrollWidth,scrollHeight:document.documentElement.scrollHeight,bodyWidth:document.body.scrollWidth,bodyHeight:document.body.scrollHeight})')
                    row['initialScreenshot'] = f'{variant}-{device}-initial.png'
                    await page.screenshot(path=str(OUT / row['initialScreenshot']), full_page=True)
                    for id_ in ['game', 'reels', 'spin', 'minus', 'plus', 'lineUp', 'lineDown', 'autoSpin', 'menu', 'paytableModal']:
                        loc = page.locator('#' + id_)
                        row['checks'][id_] = {'count': await loc.count()}
                        if await loc.count():
                            row['checks'][id_].update(await loc.evaluate('(e)=>({rect:(()=>{let r=e.getBoundingClientRect();return{x:r.x,y:r.y,w:r.width,h:r.height}})(),display:getComputedStyle(e).display,visible:!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length),text:(e.innerText||"").trim().slice(0,100)})'))
                    for btn in ['menu', 'closePaytable', 'plus', 'minus', 'lineUp', 'lineDown', 'spin']:
                        loc = page.locator('#' + btn)
                        if await loc.count() and await loc.is_visible():
                            try:
                                before = await page.locator('#balance').inner_text() if await page.locator('#balance').count() else None
                                await loc.click(timeout=4000)
                                await page.wait_for_timeout(1400)
                                after = await page.locator('#balance').inner_text() if await page.locator('#balance').count() else None
                                row['interactions'].append({'button': btn, 'ok': True, 'balanceBefore': before, 'balanceAfter': after, 'paytableVisible': await page.locator('#paytableModal').is_visible() if await page.locator('#paytableModal').count() else None})
                            except Exception as e:
                                row['interactions'].append({'button': btn, 'ok': False, 'error': str(e)[:400]})
                    await page.wait_for_timeout(2500)
                    row['postScreenshot'] = f'{variant}-{device}-after-controls.png'
                    await page.screenshot(path=str(OUT / row['postScreenshot']), full_page=True)
                    row['canvas'] = await page.locator('canvas').evaluate_all('(es)=>es.map(e=>({width:e.width,height:e.height,rect:(()=>{let r=e.getBoundingClientRect();return{x:r.x,y:r.y,w:r.width,h:r.height}})()}))')
                    row['failedImageSources'] = await page.locator('img').evaluate_all('(es)=>es.filter(e=>!e.complete||e.naturalWidth===0).map(e=>({src:e.currentSrc||e.src,alt:e.alt}))')
                except Exception as e:
                    row['fatal'] = str(e)[:1000]
                report['pages'].append(row)
                await page.close()
        await browser.close()
    report['summary'] = {'pages': len(report['pages']), 'pageErrors': sum(len(x.get('pageErrors', [])) for x in report['pages']), 'consoleErrors': sum(len(x.get('consoleErrors', [])) for x in report['pages']), 'failedRequests': sum(len(x.get('failedRequests', [])) for x in report['pages']), 'badResponses': sum(len(x.get('badResponses', [])) for x in report['pages'])}
    (OUT / 'audit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report['summary'], ensure_ascii=False))
    for x in report['pages']:
        print(json.dumps({k: x.get(k) for k in ['variant', 'device', 'httpStatus', 'title', 'loading', 'viewport', 'pageErrors', 'consoleErrors', 'failedRequests', 'badResponses', 'interactions', 'failedImageSources', 'fatal']}, ensure_ascii=False))

asyncio.run(main())
