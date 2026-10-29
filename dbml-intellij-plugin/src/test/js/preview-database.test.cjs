const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({headless:true,...(process.env.DBML_BROWSER_CHANNEL ? {channel:process.env.DBML_BROWSER_CHANNEL} : {})});
  try {
    const page = await browser.newPage({viewport:{width:1400,height:800}});
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    for(const theme of ['dark','light']) {
      const dir=path.resolve(__dirname,'../../../build/reports/database-comparison');
      await page.goto(pathToFileURL(path.join(dir,`preview-${theme}.html`)).href);
      assert.equal(await page.locator('g[data-table="users"] [data-column="email"]').evaluate(e=>getComputedStyle(e).fill),'rgb(59, 130, 246)');
      assert.equal(await page.locator('g[data-table="users"] [data-column="name"]').evaluate(e=>getComputedStyle(e).fill),'rgb(232, 163, 50)');
      assert.equal(await page.locator('g[data-table="orders"]').getAttribute('data-db-drift'),'missing');
      assert.ok((await page.locator('g[data-table="users"] > title').textContent()).includes('Column is missing'));
      await page.evaluate(()=>fit(false));
      await page.screenshot({path:path.join(dir,`comparison-${theme}.png`)});
      const box=await page.locator('g[data-table="users"] .head').boundingBox();
      await page.mouse.move(box.x+30,box.y+15);await page.mouse.down();
      await page.mouse.move(box.x+130,box.y+95,{steps:12});await page.mouse.up();
      assert.equal(await page.locator('g[data-table="users"] [data-column="email"]').getAttribute('data-db-drift'),'missing');
      const anchored=await page.evaluate(()=>Array.from(document.querySelectorAll('.relation-route')).every(r=>{
        const pts=r.querySelector('.relation').points,a=endpointPoint(r,'from',r.dataset.fromSide),b=endpointPoint(r,'to',r.dataset.toSide);
        return Math.abs(pts.getItem(0).x-a.x)<.01 && Math.abs(pts.getItem(pts.numberOfItems-1).y-b.y)<.01;
      }));
      assert.ok(anchored);
    }
    assert.deepEqual(errors,[]);
    console.log('Database overlay passed: missing/changed colors, escaped tooltips, both themes, table dragging and FK anchoring.');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
