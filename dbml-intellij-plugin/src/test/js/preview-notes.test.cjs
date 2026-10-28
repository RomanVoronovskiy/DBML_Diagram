const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.DBML_BROWSER_CHANNEL ? { channel: process.env.DBML_BROWSER_CHANNEL } : {}) });
  try {
    const page = await browser.newPage({ viewport: { width: 1500, height: 1100 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    for (const theme of ['dark', 'light']) {
      await page.goto(pathToFileURL(path.resolve(__dirname, `../../../build/reports/notes/preview-${theme}.html`)).href);
      await page.evaluate(() => fit(false));
      await page.screenshot({path:path.resolve(__dirname, `../../../build/reports/notes/notes-${theme}.png`)});
      const positions = () => page.locator('g[data-table]').evaluateAll(gs => gs.map(g => [g.dataset.table,g.dataset.x,g.dataset.y]));
      const tables = await positions();
      const selector = 'g[data-note="note:reminder"]';
      const before = await page.locator(selector).evaluate(g => ({ x:+g.dataset.x,y:+g.dataset.y }));
      const box = await page.locator(selector + ' .note-title').boundingBox();
      await page.mouse.move(box.x+20,box.y+5);
      await page.mouse.down();
      await page.mouse.move(box.x-160,box.y-80,{steps:12});
      await page.mouse.up();
      const moved = await page.locator(selector).evaluate(g => ({ x:+g.dataset.x,y:+g.dataset.y }));
      assert.ok(moved.x < before.x && moved.y < before.y);
      assert.deepEqual(await positions(),tables,'Moving a note must not move a table');
      assert.ok(await page.evaluate(() => window.savedPayloads.some(p => p.startsWith('N\tnote%3Areminder\t'))));
      await page.evaluate(() => fit(false));
      const view = await page.evaluate(() => ({tx,ty}));
      await page.keyboard.down('Space');
      const b = await page.locator(selector + ' .note-title').boundingBox();
      await page.mouse.move(b.x+10,b.y+5); await page.mouse.down();
      await page.mouse.move(b.x+70,b.y+45,{steps:5}); await page.mouse.up();
      await page.keyboard.up('Space');
      assert.deepEqual(await page.locator(selector).evaluate(g => ({x:+g.dataset.x,y:+g.dataset.y})),moved);
      assert.ok((await page.evaluate(() => tx))-view.tx > 50);
      await page.evaluate(() => fit(false));
      // User's top-to-right screenshot: no collinear out-and-back tail survives endpoint movement.
      const failure = await page.evaluate(() => {
        const r=document.querySelector('.relation-route');
        r.dataset.fromSide='top';r.dataset.toSide='right';r.dataset.controlX=270;r.dataset.controlY=300;updateRelation(r);
        const ps=Array.from(r.querySelector('.relation').points);
        return ps.slice(2).some((c,i)=>{const a=ps[i],b=ps[i+1];return(Math.abs(a.x-b.x)<.01&&Math.abs(b.x-c.x)<.01)||(Math.abs(a.y-b.y)<.01&&Math.abs(b.y-c.y)<.01)});
      });
      assert.equal(failure,false,'No redundant tail after reattaching to the top edge');
    }
    assert.deepEqual(errors,[]);
    console.log('Notes passed: both themes, independent negative-coordinate drag, persistence payload, Space-pan, Fit, tail regression.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
