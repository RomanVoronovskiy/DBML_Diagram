// Run with: node dbml-intellij-plugin/src/test/js/preview-routing.test.cjs
// Executes the actual preview functions without requiring an IDE/JCEF window.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/kotlin/io/github/dbmldiagram/plugin/preview/PreviewHtml.kt'), 'utf8');
const functions = source.slice(source.indexOf('function tableGroup('), source.indexOf('function diagramPoint('));
assert.ok(functions.includes('function updateRelation('));
function element(dataset = {}) {
  return { dataset, attributes: {}, setAttribute(k, v) { this.attributes[k] = String(v); },
    getAttribute(k) { return this.attributes[k]; } };
}
const users = element({ table: 'users', x: '48', y: '148', width: '360', height: '100' });
const orders = element({ table: 'orders', x: '600', y: '100', width: '360', height: '100' });
const rel = element({ relationId: 'test', fromTable: 'orders', toTable: 'users',
  fromSide: 'left', toSide: 'right', fromColumnOffset: '78', toColumnOffset: '52',
  controlX: '500', controlY: '189' });
const polylines = [element(), element(), element()];
const cardinalities = { from: element(), to: element() };
rel.querySelectorAll = selector => selector === 'polyline' ? polylines : [];
rel.querySelector = selector => cardinalities[selector.includes('"from"') ? 'from' : 'to'];
const snaps = ['from', 'to'].flatMap(end => ['top', 'right', 'bottom', 'left'].map(side => element({ end, side })));
const handles = Object.fromEntries(['from', 'to', 'control', 'x', 'y'].map(key => [key, element()]));
const controls = element({ controlsFor: 'test' });
controls.querySelectorAll = selector => selector === '.route-snap' ? snaps : [];
controls.querySelector = selector => handles[selector.includes('"from"') ? 'from' :
  selector.includes('"to"') ? 'to' : selector === '.route-control' ? 'control' : selector.endsWith('-x') ? 'x' : 'y'];
const svg = element();
svg.setAttribute('width', '1100'); svg.setAttribute('height', '400');
const context = { document: { querySelectorAll(selector) {
  if (selector === 'g[data-table]') return [users, orders];
  if (selector === '.route-controls') return [controls];
  return [];
} }, d: { querySelector: () => svg } };
vm.createContext(context);
vm.runInContext(functions, context);
const point = value => ({ x: value.x, y: value.y }); // Cross-realm objects have different prototypes.
function checkEndpoints(expectedFrom, expectedTo) {
  const points = context.relationPoints(rel);
  assert.deepEqual(point(points[0]), expectedFrom);
  assert.deepEqual(point(points.at(-1)), expectedTo);
  context.updateRelation(rel);
  assert.equal(handles.from.attributes.cy, String(expectedFrom.y));
  assert.equal(handles.to.attributes.cy, String(expectedTo.y));
  assert.equal(polylines[0].attributes.points, polylines[1].attributes.points);
  assert.equal(polylines[0].attributes.points, polylines[2].attributes.points);
}
checkEndpoints({ x: 600, y: 178 }, { x: 408, y: 200 });
assert.equal(snaps.find(dot => dot.dataset.end === 'from' && dot.dataset.side === 'left').attributes.cy, '178');
assert.equal(context.closestSide(context.tableBox('orders'), { x: 600, y: 178 }, 78), 'left');
// Moving a table must not return either endpoint to the table midpoint.
orders.dataset.x = '850'; orders.dataset.y = '900'; users.dataset.y = '300';
checkEndpoints({ x: 850, y: 978 }, { x: 408, y: 352 });
// Manual top/bottom choices stay on the border; returning to a side restores the actual row.
rel.dataset.fromSide = 'top'; rel.dataset.toSide = 'bottom';
checkEndpoints({ x: 1030, y: 900 }, { x: 228, y: 400 });
rel.dataset.fromSide = 'right'; rel.dataset.toSide = 'left';
checkEndpoints({ x: 1210, y: 978 }, { x: 48, y: 352 });
console.log('Preview routing passed: FK/PK rows, table movement, snap points, manual sides.');

// Reproduce the reported line crossing the users card, then insert an unrelated obstacle.
orders.dataset.x = '48'; orders.dataset.y = '412';
users.dataset.x = '264'; users.dataset.y = '174'; users.dataset.width = '390';
rel.dataset.fromSide = 'right'; rel.dataset.toSide = 'left';
rel.dataset.controlX = '430'; rel.dataset.controlY = '146';
let points = context.relationPoints(rel);
let boxes = context.routeBoxes();
// The anchor stubs necessarily enter the clearance padding; check middle segments against cards themselves.
function avoidsActualCards(route, cards) {
  const actual = cards.map(r => ({ left: r.left + r.padding, top: r.top + r.padding, right: r.right - r.padding, bottom: r.bottom - r.padding }));
  for (let i = 1; i < route.length; i++) assert.equal(context.clearSegment(route[i - 1], route[i], actual), true, 'Route crosses a card');
}
avoidsActualCards(points, boxes);
const obstacle = element({ table: 'third', x: '410', y: '300', width: '350', height: '80' });
const baseQuery = context.document.querySelectorAll;
context.document.querySelectorAll = selector => selector === 'g[data-table]' ? [users, orders, obstacle] : baseQuery(selector);
rel.dataset.controlX = '430'; rel.dataset.controlY = '146';
avoidsActualCards(context.relationPoints(rel), context.routeBoxes());
console.log('Obstacle routing passed: reported layout and unrelated table obstruction.');

// Even a gap narrower than the usual 24px stub must remain obstacle-free.
context.document.querySelectorAll = baseQuery;
orders.dataset.x = '48'; orders.dataset.y = '200';
users.dataset.x = '416'; users.dataset.y = '200';
rel.dataset.fromColumnOffset = '52'; rel.dataset.toColumnOffset = '52';
rel.dataset.controlX = '412'; rel.dataset.controlY = '252';
points = context.relationPoints(rel);
avoidsActualCards(points, context.routeBoxes());
assert.deepEqual(Array.from(points, point), [{ x: 408, y: 252 }, { x: 416, y: 252 }]);
console.log('Close-card routing passed: 8px gap without penetrating either card.');
