// Requires Playwright. First run Gradle test to create the actual PreviewHtml fixture.
// node dbml-intellij-plugin/src/test/js/preview-pointer.test.cjs [fixture.html]
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.DBML_BROWSER_CHANNEL ? { channel: process.env.DBML_BROWSER_CHANNEL } : {}) });
  try {
    const page = await browser.newPage({ viewport: { width: 1400, height: 1000 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const fixture = process.argv[2] || path.resolve(__dirname, '../../../build/reports/preview-fixture.html');
    await page.goto(pathToFileURL(fixture).href);
    const rel = '.relation-route';
    const controls = '.route-controls';
    const endpoint = end => `${controls} .route-endpoint[data-end="${end}"]`;
    const snap = (end, side) => `${controls} .route-snap[data-end="${end}"][data-side="${side}"]`;
    async function center(selector) {
      const box = await page.locator(selector).boundingBox();
      assert.ok(box, `Missing hit area: ${selector}`);
      return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
    }
    async function drag(selector, destination) {
      const start = await center(selector);
      await page.mouse.move(start.x, start.y);
      await page.mouse.down();
      await page.mouse.move(destination.x, destination.y, { steps: 12 });
      await page.mouse.up();
    }
    const linePoint = await page.locator('.relation-hit').evaluate(line => {
      const a = line.points.getItem(0), b = line.points.getItem(1);
      return new DOMPoint((a.x + b.x) / 2, (a.y + b.y) / 2).matrixTransform(line.getScreenCTM()).toJSON();
    });
    await page.mouse.click(linePoint.x, linePoint.y);
    assert.equal(await page.locator(endpoint('from')).isVisible(), true);
    assert.deepEqual(await page.evaluate(() => window.savedPayloads), [], 'A selection click must not save or reload the preview');
    const unchangedRoute = await page.locator(rel).getAttribute('data-control-x');
    await page.mouse.move(linePoint.x, linePoint.y);
    await page.mouse.down();
    await page.mouse.move(linePoint.x + 1, linePoint.y + 1);
    await page.mouse.up();
    assert.equal(await page.locator(rel).getAttribute('data-control-x'), unchangedRoute, 'Small mouse jitter must not move the control');
    assert.deepEqual(await page.evaluate(() => window.savedPayloads), [], 'Small mouse jitter must not trigger a preview reload');
    // A first click at the arrowhead must choose that endpoint, not the middle control.
    await page.evaluate(() => selectRelation(null));
    const arrowPoint = await page.locator('.relation-hit').evaluate(line => {
      const end = line.points.getItem(line.points.numberOfItems - 1);
      return new DOMPoint(end.x, end.y).matrixTransform(line.getScreenCTM()).toJSON();
    });
    await page.mouse.move(arrowPoint.x, arrowPoint.y);
    await page.mouse.down();
    assert.equal(await page.evaluate(() => routeDrag.role), 'to', 'Clicking an unselected arrowhead must grab its endpoint');
    const arrowDestination = await center(snap('to', 'top'));
    await page.mouse.move(arrowDestination.x, arrowDestination.y, { steps: 12 });
    await page.mouse.up();
    assert.equal(await page.locator(rel).getAttribute('data-to-side'), 'top');
    await drag(endpoint('from'), await center(snap('from', 'top')));
    assert.equal(await page.locator(rel).getAttribute('data-from-side'), 'top');
    await page.locator(snap('from', 'bottom')).click();
    assert.equal(await page.locator(rel).getAttribute('data-from-side'), 'bottom');
    await drag(endpoint('to'), await center(snap('to', 'top')));
    assert.equal(await page.locator(rel).getAttribute('data-to-side'), 'top');
    await page.locator(snap('to', 'bottom')).click();
    assert.equal(await page.locator(rel).getAttribute('data-to-side'), 'bottom');
    // At a different zoom, both drag and direct click must choose the same side.
    await page.evaluate(() => { zoom(.65); tx = 100; ty = 80; apply(); });
    await drag(endpoint('from'), await center(snap('from', 'right')));
    await page.locator(snap('to', 'left')).click();
    assert.equal(await page.locator(rel).getAttribute('data-from-side'), 'right');
    assert.equal(await page.locator(rel).getAttribute('data-to-side'), 'left');
    const payloads = await page.evaluate(() => window.savedPayloads);
    assert.ok(payloads.filter(p => p.startsWith('R\t')).length >= 6, 'Attachment changes must be sent for persistence');
    const lastRoute = payloads.filter(p => p.startsWith('R\t')).at(-1).split('\t');
    assert.equal(lastRoute.length, 9);
    assert.equal(lastRoute[2], 'right'); assert.equal(lastRoute[3], 'left');
    // Reproduce the reported layout: users above orders, old control point crossing users.
    await page.evaluate(() => { actual(); });
    const usersHeader = 'g[data-table="users"] .head';
    const start = await center(usersHeader);
    await drag(usersHeader, { x: start.x - 256, y: start.y + 14 });
    const desiredControl = await page.evaluate(() => new DOMPoint(430, 146).matrixTransform(document.querySelector('svg').getScreenCTM()).toJSON());
    // A table drag deselects routes. Select via the line's actual segment again.
    const movedPoint = await page.locator('.relation-hit').evaluate(line => {
      const a = line.points.getItem(0), b = line.points.getItem(1);
      return new DOMPoint((a.x + b.x) / 2, (a.y + b.y) / 2).matrixTransform(line.getScreenCTM()).toJSON();
    });
    await page.mouse.click(movedPoint.x, movedPoint.y);
    await drag('.route-control', desiredControl);
    const crossings = await page.evaluate(() => {
      const line = document.querySelector('.relation'), points = Array.from(line.points), cards = Array.from(document.querySelectorAll('g[data-table]')).map(g => ({ x: +g.dataset.x, y: +g.dataset.y, w: +g.dataset.width, h: +g.dataset.height }));
      const failures = [];
      for (let i = 1; i < points.length; i++) for (const r of cards) {
        const a = points[i - 1], b = points[i];
        const crosses = Math.abs(a.y - b.y) < .01 ? a.y > r.y + .01 && a.y < r.y + r.h - .01 && Math.max(a.x, b.x) > r.x + .01 && Math.min(a.x, b.x) < r.x + r.w - .01 : a.x > r.x + .01 && a.x < r.x + r.w - .01 && Math.max(a.y, b.y) > r.y + .01 && Math.min(a.y, b.y) < r.y + r.h - .01;
        if (crosses) failures.push({ a: { x: a.x, y: a.y }, b: { x: b.x, y: b.y }, r });
      }
      return failures;
    });
    assert.deepEqual(crossings, [], 'Live drag route must not pass through a table');
    // Moving the same card again must retain its accumulated translation.
    const beforeSecondDrag = await center(usersHeader);
    await drag(usersHeader, { x: beforeSecondDrag.x + 40, y: beforeSecondDrag.y + 30 });
    const afterSecondDrag = await center(usersHeader);
    assert.ok(Math.abs(afterSecondDrag.x - beforeSecondDrag.x - 40) < 1, 'Repeated drag must not reset the card translation');
    assert.ok(Math.abs(afterSecondDrag.y - beforeSecondDrag.y - 30) < 1, 'Repeated drag must retain the card vertical translation');
    const endpointAlignment = await page.locator(endpoint('to')).evaluate(dot => {
      const card = document.querySelector('g[data-table="users"]');
      const cardLeft = card.querySelector('.head').getBoundingClientRect().left;
      const end = new DOMPoint(+dot.getAttribute('cx'), +dot.getAttribute('cy')).matrixTransform(dot.getScreenCTM());
      return end.x - cardLeft;
    });
    assert.ok(Math.abs(endpointAlignment) < 1, 'Relationship endpoint must stay attached to the rendered card after a repeated drag');
    // This is a newly generated backend page, with no selected CSS classes in its SVG.
    // After persisting an edit, PreviewViewState must restore the selected handles.
    await page.goto(pathToFileURL(path.join(path.dirname(fixture), 'preview-selected-fixture.html')).href);
    assert.equal(await page.locator(endpoint('from')).isVisible(), true, 'Selection must survive a backend HTML refresh');
    assert.equal(await page.locator(endpoint('to')).isVisible(), true, 'Both endpoints must stay selectable after refresh');
    await page.locator(snap('to', 'top')).click();
    assert.equal(await page.locator(rel).getAttribute('data-to-side'), 'top');
    assert.deepEqual(errors, [], 'Browser must not report JavaScript errors');
    console.log('Pointer interaction passed: selection-only clicks, arrowhead picking, drag threshold, refresh selection, clickable anchors, zoom, persistence, obstacle rerouting.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
