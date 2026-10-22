// Run Gradle test first; uses only the public synthetic TableGroup showcase.
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.DBML_BROWSER_CHANNEL ? { channel: process.env.DBML_BROWSER_CHANNEL } : {}) });
  const directory = path.resolve(__dirname, '../../../build/reports/table-groups');
  try {
    const page = await browser.newPage({ viewport: { width: 1600, height: 1100 }, deviceScaleFactor: 1.5 });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    async function checkFrames() {
      return page.evaluate(() => {
        const failures = [];
        document.querySelectorAll('g[data-table-group]').forEach(g => {
          const frame = g.querySelector('rect'), x = +frame.getAttribute('x'), y = +frame.getAttribute('y'),
            w = +frame.getAttribute('width'), h = +frame.getAttribute('height');
          for (const id of JSON.parse(g.dataset.tables)) {
            const box = tableBox(id);
            if (!(box.x >= x + 24 && box.y >= y + 40 && box.x + box.w <= x + w - 24 && box.y + box.h <= y + h - 24)) failures.push(id);
          }
          const label = g.querySelector('.table-group-label').getBBox(), count = g.querySelector('.table-group-count').getBBox();
          if (label.x + label.width > count.x - 8) failures.push('label/count overlap: ' + g.dataset.tableGroup);
          const svg = document.querySelector('svg');
          if (x < 0 || y < 0 || x + w > +svg.getAttribute('width') || y + h > +svg.getAttribute('height')) failures.push('canvas clips ' + g.dataset.tableGroup);
        });
        return failures;
      });
    }
    const bounds = name => page.locator(`g[data-table-group="${name}"] rect`).evaluate(e => ['x', 'y', 'width', 'height'].map(a => +e.getAttribute(a)));
    async function dragTable(name, dx, dy) {
      const header = page.locator(`g[data-table="${name}"] .head`);
      const box = await header.boundingBox();
      const scale = await page.evaluate(() => window.getComputedStyle(document.querySelector('#diagram')).transform);
      const factor = Number(scale.match(/matrix\(([^,]+)/)[1]);
      const start = { x: box.x + 40, y: box.y + box.height / 2 };
      await page.mouse.move(start.x, start.y);
      await page.mouse.down();
      await page.mouse.move(start.x + dx * factor, start.y + dy * factor, { steps: 12 });
      // A frame must follow during the drag, not only after the backend renders again.
      assert.deepEqual(await checkFrames(), []);
      await page.mouse.up();
    }
    for (const theme of ['light', 'dark']) {
      await page.goto(pathToFileURL(path.join(directory, `preview-${theme}.html`)).href);
      assert.equal(await page.locator('g[data-table-group]').count(), 3);
      assert.equal(await page.locator('g[data-table]').count(), 6);
      assert.equal(await page.locator('.relation-route.invalid').count(), 0);
      assert.deepEqual(await checkFrames(), []);
      assert.deepEqual(await page.locator('.table-group-frame').evaluateAll(es => es.map(e => e.getAttribute('stroke'))), ['#3b82f6', '#10b981', '#a78bfa']);
      await page.screenshot({ path: path.join(directory, `table-groups-${theme}.png`) });
    }
    const salesBefore = await bounds('Sales'), catalogBefore = await bounds('Catalog'), identityBefore = await bounds('Identity');
    await dragTable('customers', 0, 500);
    const expanded = await bounds('Identity');
    assert.ok(expanded[3] > identityBefore[3], 'Frame must expand around a moved member');
    assert.deepEqual(await bounds('Sales'), salesBefore, 'Unrelated group must not move');
    assert.deepEqual(await bounds('Catalog'), catalogBefore);
    await dragTable('customers', 0, -500);
    const restored = await bounds('Identity');
    assert.ok(restored.every((n, i) => Math.abs(n - identityBefore[i]) < .01), 'Frame must shrink back on a second drag');
    assert.equal((await page.evaluate(() => window.savedPayloads.filter(p => p.startsWith('T\t')))).length, 2);
    await page.evaluate(() => { zoom(.6); apply(); });
    await dragTable('products', 80, 60);
    assert.deepEqual(await checkFrames(), []);
    assert.ok((await page.evaluate(() => window.savedPayloads.at(-1))).startsWith('T\tproducts\t'));
    await page.goto(pathToFileURL(path.join(directory, 'preview-restored.html')).href);
    assert.equal(await page.locator('g[data-table="customers"]').getAttribute('data-x'), '1500.0');
    assert.deepEqual(await checkFrames(), [], 'Backend-restored frames must use the same bounds');
    assert.deepEqual(errors, []);
    console.log('TableGroup frames: themes, PNG-ready showcase, live expansion/shrink, repeated/zoomed dragging and restored positions passed.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
