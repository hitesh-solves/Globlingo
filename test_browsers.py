import asyncio, json
from playwright.async_api import async_playwright

URL = "http://127.0.0.1:5501/index.html#/shorts"

async def probe(browser, name):
    pg = await browser.new_page()
    await pg.set_viewport_size({"width": 1920, "height": 1080})
    await pg.goto(URL, wait_until="domcontentloaded")
    await pg.wait_for_timeout(5000)
    r = await pg.evaluate("""() => {
      const rc = el => { const b = el && el.getBoundingClientRect(); return b && {x:+b.x.toFixed(1), y:+b.y.toFixed(1), w:+b.width.toFixed(1), h:+b.height.toFixed(1)}; };
      const cs = el => { const c = el && getComputedStyle(el); return c && {w:c.width,h:c.height,disp:c.display,pos:c.position,overflow:c.overflowY,scrollSnap:c.scrollSnapType}; };
      return {
        dpr: devicePixelRatio,
        inner: [innerWidth, innerHeight],
        feedRect: rc(document.querySelector('.shorts-feed')),
        feedCS: cs(document.querySelector('.shorts-feed')),
        tallRect: rc(document.querySelector('.short-tall')),
        frameRect: rc(document.querySelector('.short-tall__frame')),
        frameCS: cs(document.querySelector('.short-tall__frame')),
        subsRect: rc(document.querySelector('.short-tall__subs')),
        subsCS: cs(document.querySelector('.short-tall__subs')),
        cssHref: (document.querySelector('link[rel=stylesheet]')||{}).href,
        scrollW: document.documentElement.scrollWidth,
      };
    }""")
    print(f"=== {name} ===")
    print(json.dumps(r, indent=1))
    await pg.close()

async def main():
    async with async_playwright() as p:
        for channel, name in [("chrome","CHROME"), ("msedge","EDGE")]:
            try:
                b = await p.chromium.launch(channel=channel)
                await probe(b, name)
                await b.close()
            except Exception as e:
                print(name, "FAILED:", str(e)[:200])

asyncio.run(main())
