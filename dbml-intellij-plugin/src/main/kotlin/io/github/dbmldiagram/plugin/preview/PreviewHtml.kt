package io.github.dbmldiagram.plugin.preview

internal data class PreviewViewState(val scale: Double, val x: Double, val y: Double, val selectedRelationId: String? = null,
                                     val grid: Boolean = true, val snap: Boolean = false)

internal object PreviewHtml {
    fun page(
        svg: String?,
        message: String?,
        dark: Boolean,
        positionCallback: String? = null,
        viewState: PreviewViewState? = null,
    ): String {
        val diagram = svg ?: "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 640 360\"><text x=\"40\" y=\"60\">Waiting for valid DBML…</text></svg>"
        val notice = message?.let { "<div id=\"notice\">${escape(it)}</div>" } ?: ""
        val colors = if (dark) "--bg:#2b2b2b;--card:#3c3f41;--header:#45494a;--text:#d7d7d7;--muted:#a8a8a8;--border:#696b6c;--edge:#96999b;--relation:#6ea3ff;--accent:#77a7e8" else "--bg:#f7f8fa;--card:#fff;--header:#eef1f5;--text:#24292f;--muted:#65717e;--border:#9aa0a6;--edge:#79838e;--relation:#356ae6;--accent:#3d65a5"
        val initialScale = viewState?.scale ?: 1.0
        val initialX = viewState?.x ?: 20.0
        val initialY = viewState?.y ?: 20.0
        val hasInitialView = viewState != null
        val selectedRelation = viewState?.selectedRelationId?.let { "'${escapeJs(it)}'" } ?: "null"
        val savePosition = positionCallback ?: ""
        return """<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;width:100%;height:100%;overflow:hidden;$colors;background:var(--bg);color:var(--text);font:12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
#notice{position:fixed;z-index:5;left:12px;top:10px;right:12px;padding:7px 10px;border:1px solid #b9923d;border-radius:4px;background:${if (dark) "#594b2b" else "#fff4ce"};white-space:pre-wrap;max-height:25%;overflow:auto}
#viewport{width:100%;height:100%;cursor:grab;transform-origin:0 0}#viewport.dragging{cursor:grabbing}#diagram{position:absolute;left:0;top:0;transform-origin:0 0}
#diagram svg{overflow:visible;--dbml-bg:var(--bg);--dbml-card:var(--card);--dbml-header:var(--header);--dbml-text:var(--text);--dbml-muted:var(--muted);--dbml-border:var(--border);--dbml-edge:var(--edge);--dbml-relation:var(--relation);--dbml-accent:var(--accent)}
#diagram .head,#diagram .title,#diagram .table-group-label,#diagram .table-group-handle{cursor:move}
#viewport{touch-action:none;background-image:radial-gradient(circle,${if (dark) "#55585c" else "#c8ced6"} 1px,transparent 1px);background-size:24px 24px}
#canvas-tools{position:fixed;left:12px;bottom:12px;z-index:6;display:flex;align-items:center;gap:6px;background:var(--card);border:1px solid var(--border);border-radius:6px;padding:6px 8px;user-select:none}
#canvas-tools button{background:var(--bg);color:var(--text);border:1px solid var(--border);border-radius:4px;padding:4px 8px;cursor:pointer}
#canvas-tools button[aria-pressed="true"]{border-color:var(--relation);color:var(--relation)}
#canvas-tools span{color:var(--muted);font-size:11px;pointer-events:none}#diagram{user-select:none}
#diagram .route-snap{pointer-events:all;cursor:pointer;opacity:.9}
#diagram .route-endpoint,#diagram .route-control,#diagram .route-axis,#diagram .relation-hit{touch-action:none}
</style></head><body>$notice<div id="viewport"><div id="diagram">$diagram</div></div><div id="canvas-tools"><button id="grid-toggle" type="button" onclick="toggleGrid()" title="Show or hide the world grid">Grid</button><button id="snap-toggle" type="button" onclick="toggleSnap()" title="Snap moved tables and groups to a 24-unit grid">Snap</button><span>Space + drag / middle mouse: pan</span></div><script>
let scale=$initialScale,tx=$initialX,ty=$initialY,tableDrag=null,panDrag=null,groupDrag=null,spaceDown=false;
let gridEnabled=${viewState?.grid ?: true},snapEnabled=${viewState?.snap ?: false},viewTimer=0;
const d=document.getElementById('diagram'),v=document.getElementById('viewport');
const svgRoot=d.querySelector('svg');
// Keep SVG user coordinates equal to world coordinates. Export keeps its own tight viewBox.
svgRoot.setAttribute('viewBox','0 0 '+(svgRoot.getAttribute('width')||640)+' '+(svgRoot.getAttribute('height')||360));
function camera(){return{scale:scale,x:tx,y:ty,grid:gridEnabled,snap:snapEnabled}}
function viewSuffix(){return scale+'\t'+tx+'\t'+ty+'\t'+(gridEnabled?1:0)+'\t'+(snapEnabled?1:0)}
function saveView(){clearTimeout(viewTimer);send('V\t'+viewSuffix())}
function apply(){
 d.style.transform='translate('+tx+'px,'+ty+'px) scale('+scale+')';
 const stride=Math.pow(2,Math.max(0,Math.ceil(Math.log2(12/(24*scale))))),gap=24*scale*stride;
 // The radial dot is at the tile center: offset half a cell so world (0,0) is a dot.
 v.style.backgroundSize=gap+'px '+gap+'px';v.style.backgroundPosition=(tx-gap/2)+'px '+(ty-gap/2)+'px';v.style.backgroundImage=gridEnabled?'':'none';
 document.getElementById('grid-toggle').setAttribute('aria-pressed',String(gridEnabled));
 document.getElementById('snap-toggle').setAttribute('aria-pressed',String(snapEnabled));
}
function toggleGrid(){gridEnabled=!gridEnabled;apply();saveView()}
function toggleSnap(){snapEnabled=!snapEnabled;apply();saveView()}
function contentBounds(){
 let left=Infinity,top=Infinity,right=-Infinity,bottom=-Infinity;
 function include(x,y){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y)}
 document.querySelectorAll('g[data-table]').forEach(g=>{const x=+g.dataset.x,y=+g.dataset.y;include(x,y);include(x+(+g.dataset.width),y+(+g.dataset.height))});
 document.querySelectorAll('.table-group-frame').forEach(r=>{const x=+r.getAttribute('x'),y=+r.getAttribute('y');include(x,y);include(x+(+r.getAttribute('width')),y+(+r.getAttribute('height')))});
 document.querySelectorAll('.relation').forEach(r=>Array.from(r.points).forEach(p=>include(p.x,p.y)));
 return left===Infinity?{x:0,y:0,w:640,h:360}:{x:left-48,y:top-48,w:right-left+96,h:bottom-top+96};
}
function zoom(f,cx=innerWidth/2,cy=innerHeight/2){const p={x:(cx-tx)/scale,y:(cy-ty)/scale};scale=Math.max(.01,Math.min(4,scale*f));tx=cx-p.x*scale;ty=cy-p.y*scale;apply();saveView()}
function actual(){const b=contentBounds();scale=1;tx=innerWidth/2-(b.x+b.w/2);ty=innerHeight/2-(b.y+b.h/2);apply();saveView()}
function fit(publish=true){const b=contentBounds();scale=Math.max(.01,Math.min((innerWidth-40)/b.w,(innerHeight-40)/b.h,1.5));tx=innerWidth/2-(b.x+b.w/2)*scale;ty=innerHeight/2-(b.y+b.h/2)*scale;apply();if(publish)saveView()}
function send(payload){$savePosition}
window.saveTablePosition=function(table,x,y){clearTimeout(viewTimer);send('T\t'+encodeURIComponent(table)+'\t'+x+'\t'+y+'\t'+viewSuffix())};
window.saveRelationRoute=function(rel){clearTimeout(viewTimer);send('R\t'+encodeURIComponent(rel.dataset.relationId)+'\t'+rel.dataset.fromSide+'\t'+rel.dataset.toSide+'\t'+rel.dataset.controlX+'\t'+rel.dataset.controlY+'\t'+viewSuffix())};
function tableGroup(id){return Array.from(document.querySelectorAll('g[data-table]')).find(g=>g.dataset.table===id)}
let routingSnapshot=null;
function tableBox(id){const g=tableGroup(id);return g?{x:parseFloat(g.dataset.x),y:parseFloat(g.dataset.y),w:parseFloat(g.dataset.width),h:parseFloat(g.dataset.height)}:null}
// Keep this geometry in sync with TableGroupGeometry (24px padding, 40px title band).
function updateTableGroups(){
 document.querySelectorAll('g[data-table-group]').forEach(g=>{
  const boxes=JSON.parse(g.dataset.tables).map(tableBox).filter(Boolean);if(!boxes.length)return;
  const x=Math.min(...boxes.map(b=>b.x))-24,y=Math.min(...boxes.map(b=>b.y))-40;
  const w=Math.max(+g.dataset.minWidth,Math.max(...boxes.map(b=>b.x+b.w))-x+24),h=Math.max(...boxes.map(b=>b.y+b.h))-y+24;
  const rect=g.querySelector('.table-group-frame');rect.setAttribute('x',x);rect.setAttribute('y',y);rect.setAttribute('width',w);rect.setAttribute('height',h);
  const handle=g.querySelector('.table-group-handle');if(handle){handle.setAttribute('x',x+1);handle.setAttribute('y',y+1);handle.setAttribute('width',w-2)}
  const dot=g.querySelector('.table-group-dot');dot.setAttribute('cx',x+18);dot.setAttribute('cy',y+20);
  const label=g.querySelector('.table-group-label');label.setAttribute('x',x+32);label.setAttribute('y',y+25);
  const count=g.querySelector('.table-group-count');count.setAttribute('x',x+w-16);count.setAttribute('y',y+25);
 });
}
function sidePoint(b,s,rowOffset){const y=b.y+(Number.isFinite(rowOffset)?rowOffset:b.h/2);if(s==='top')return{x:b.x+b.w/2,y:b.y};if(s==='right')return{x:b.x+b.w,y:y};if(s==='bottom')return{x:b.x+b.w/2,y:b.y+b.h};return{x:b.x,y:y}}
function columnOffset(rel,end){return parseFloat(end==='from'?rel.dataset.fromColumnOffset:rel.dataset.toColumnOffset)}
function endpointPoint(rel,end,side){return sidePoint(tableBox(end==='from'?rel.dataset.fromTable:rel.dataset.toTable),side,columnOffset(rel,end))}
function exitPoint(p,s){if(s==='top')return{x:p.x,y:p.y-24};if(s==='right')return{x:p.x+24,y:p.y};if(s==='bottom')return{x:p.x,y:p.y+24};return{x:p.x-24,y:p.y}}
function controlsFor(id){return Array.from(document.querySelectorAll('.route-controls')).find(g=>g.dataset.controlsFor===id)}
function routeLeg(p,s,c){if(s==='right'){const x=Math.max(p.x,c.x);return[p,{x:x,y:p.y},{x:x,y:c.y},c]}if(s==='left'){const x=Math.min(p.x,c.x);return[p,{x:x,y:p.y},{x:x,y:c.y},c]}if(s==='bottom'){const y=Math.max(p.y,c.y);return[p,{x:p.x,y:y},{x:c.x,y:y},c]}const y=Math.min(p.y,c.y);return[p,{x:p.x,y:y},{x:c.x,y:y},c]}
function routingCards(){if(routingSnapshot)return routingSnapshot.cards;return Array.from(document.querySelectorAll('g[data-table]')).map(g=>({x:+g.dataset.x,y:+g.dataset.y,w:+g.dataset.width,h:+g.dataset.height}))}
function routeBoxes(){if(routingSnapshot&&routingSnapshot.boxes)return routingSnapshot.boxes;const cards=routingCards();let padding=12;for(let i=0;i<cards.length;i++)for(let j=i+1;j<cards.length;j++){const a=cards[i],b=cards[j],gap=Math.max(Math.max(a.x-b.x-b.w,b.x-a.x-a.w),Math.max(a.y-b.y-b.h,b.y-a.y-a.h));if(gap>.01)padding=Math.min(padding,Math.max(.1,gap/3))}return cards.map(g=>({left:g.x-padding,top:g.y-padding,right:g.x+g.w+padding,bottom:g.y+g.h+padding,padding:padding}))}
function routeExit(p,side){let distance=24;for(const n of routingCards()){let gap=null;if(side==='right'&&p.y>=n.y&&p.y<=n.y+n.h&&n.x>p.x+.01)gap=n.x-p.x;else if(side==='left'&&p.y>=n.y&&p.y<=n.y+n.h&&n.x+n.w<p.x-.01)gap=p.x-n.x-n.w;else if(side==='bottom'&&p.x>=n.x&&p.x<=n.x+n.w&&n.y>p.y+.01)gap=n.y-p.y;else if(side==='top'&&p.x>=n.x&&p.x<=n.x+n.w&&n.y+n.h<p.y-.01)gap=p.y-n.y-n.h;if(gap!==null)distance=Math.min(distance,gap/2)}if(side==='top')return{x:p.x,y:p.y-distance};if(side==='right')return{x:p.x+distance,y:p.y};if(side==='bottom')return{x:p.x,y:p.y+distance};return{x:p.x-distance,y:p.y}}
function insideBox(p,r){return p.x>r.left+.01&&p.x<r.right-.01&&p.y>r.top+.01&&p.y<r.bottom-.01}
function clearSegment(a,b,boxes){return!boxes.some(r=>Math.abs(a.y-b.y)<.01?a.y>r.top+.01&&a.y<r.bottom-.01&&Math.max(a.x,b.x)>r.left+.01&&Math.min(a.x,b.x)<r.right-.01:Math.abs(a.x-b.x)<.01?a.x>r.left+.01&&a.x<r.right-.01&&Math.max(a.y,b.y)>r.top+.01&&Math.min(a.y,b.y)<r.bottom-.01:true)}
function simplifyRoute(points){const result=[];for(const p of points){let b=result[result.length-1];if(b&&b.x===p.x&&b.y===p.y)continue;while(result.length>=2){const a=result[result.length-2];b=result[result.length-1];const straight=(Math.abs(a.x-b.x)<.01&&Math.abs(b.x-p.x)<.01&&(b.y-a.y)*(p.y-b.y)>=0)||(Math.abs(a.y-b.y)<.01&&Math.abs(b.y-p.y)<.01&&(b.x-a.x)*(p.x-b.x)>=0);if(!straight)break;result.pop()}result.push(p)}return result}
function freeControl(p,boxes){if(!boxes.some(r=>insideBox(p,r)))return p;const candidates=boxes.flatMap(r=>[{x:r.left,y:p.y},{x:r.right,y:p.y},{x:p.x,y:r.top},{x:p.x,y:r.bottom}]).filter(q=>!boxes.some(r=>insideBox(q,r)));return candidates.reduce((best,q)=>!best||Math.abs(q.x-p.x)+Math.abs(q.y-p.y)<Math.abs(best.x-p.x)+Math.abs(best.y-p.y)?q:best,null)||p}
function gridRoute(start,end,boxes){
 if(start.x===end.x&&start.y===end.y)return[start];
 if(boxes.some(r=>insideBox(start,r)||insideBox(end,r)))return null;
 const xs=Array.from(new Set([start.x,end.x].concat(boxes.flatMap(r=>[r.left,r.right])))).sort((a,b)=>a-b),ys=Array.from(new Set([start.y,end.y].concat(boxes.flatMap(r=>[r.top,r.bottom])))).sort((a,b)=>a-b),w=xs.length;
 if(w*ys.length>100000)return null;
 const point=cell=>({x:xs[cell%w],y:ys[Math.floor(cell/w)]}),source=ys.indexOf(start.y)*w+xs.indexOf(start.x),target=ys.indexOf(end.y)*w+xs.indexOf(end.x),dist=new Float64Array(w*ys.length*3).fill(Infinity),previous=new Int32Array(dist.length).fill(-1),heap=[];
 const less=(a,b)=>a.estimate<b.estimate||(a.estimate===b.estimate&&a.state<b.state);
 function push(item){heap.push(item);let i=heap.length-1;while(i>0){const parent=(i-1)>>1;if(!less(item,heap[parent]))break;heap[i]=heap[parent];i=parent}heap[i]=item}
 function pop(){const first=heap[0],last=heap.pop();if(heap.length){let i=0;while(i*2+1<heap.length){let child=i*2+1;if(child+1<heap.length&&less(heap[child+1],heap[child]))child++;if(!less(heap[child],last))break;heap[i]=heap[child];i=child}heap[i]=last}return first}
 dist[source*3]=0;push({state:source*3,cost:0,estimate:Math.abs(start.x-end.x)+Math.abs(start.y-end.y)});
 while(heap.length){const item=pop();if(item.cost>dist[item.state]+.01)continue;const cell=Math.floor(item.state/3),direction=item.state%3;if(cell===target){const path=[];let state=item.state;while(state>=0){path.push(point(Math.floor(state/3)));state=previous[state]}return simplifyRoute(path.reverse())}
 const p=point(cell),neighbors=[];if(cell%w>0)neighbors.push([cell-1,1]);if(cell%w<w-1)neighbors.push([cell+1,1]);if(Math.floor(cell/w)>0)neighbors.push([cell-w,2]);if(Math.floor(cell/w)<ys.length-1)neighbors.push([cell+w,2]);
 for(const [nextCell,nextDirection] of neighbors){const q=point(nextCell);if(!clearSegment(p,q,boxes))continue;const cost=item.cost+Math.abs(q.x-p.x)+Math.abs(q.y-p.y)+(direction!==0&&direction!==nextDirection?18:0),next=nextCell*3+nextDirection;if(cost+.01>=dist[next])continue;dist[next]=cost;previous[next]=item.state;push({state:next,cost:cost,estimate:cost+Math.abs(q.x-end.x)+Math.abs(q.y-end.y)})}}
 return null;
}
function relationPoints(rel){
 const a=endpointPoint(rel,'from',rel.dataset.fromSide),b=endpointPoint(rel,'to',rel.dataset.toSide),ae=routeExit(a,rel.dataset.fromSide),be=routeExit(b,rel.dataset.toSide),boxes=routeBoxes(),c=freeControl({x:+rel.dataset.controlX,y:+rel.dataset.controlY},boxes),preferred=simplifyRoute([ae].concat(routeLeg(ae,rel.dataset.fromSide,c),routeLeg(be,rel.dataset.toSide,c).reverse(),[be]));
 let middle=preferred,actualControl=c;
 if(!preferred.slice(1).every((p,i)=>clearSegment(preferred[i],p,boxes))){const first=gridRoute(ae,c,boxes),second=gridRoute(c,be,boxes);if(first&&second)middle=simplifyRoute(first.concat(second.slice(1)));else{middle=gridRoute(ae,be,boxes)||preferred;actualControl=middle[Math.floor(middle.length/2)]}}
 rel.dataset.controlX=actualControl.x;rel.dataset.controlY=actualControl.y;
 return simplifyRoute([a].concat(middle,[b]));
}
function setCardinality(text,p,side){if(!text)return;if(side==='left'){text.setAttribute('x',p.x-8);text.setAttribute('y',p.y-6);text.setAttribute('text-anchor','end')}else if(side==='right'){text.setAttribute('x',p.x+8);text.setAttribute('y',p.y-6);text.setAttribute('text-anchor','start')}else if(side==='top'){text.setAttribute('x',p.x+8);text.setAttribute('y',p.y-7);text.setAttribute('text-anchor','start')}else{text.setAttribute('x',p.x+8);text.setAttribute('y',p.y+15);text.setAttribute('text-anchor','start')}}
function updateRelation(rel){const pts=relationPoints(rel),value=pts.map(p=>p.x+','+p.y).join(' ');rel.querySelectorAll('polyline').forEach(p=>p.setAttribute('points',value));setCardinality(rel.querySelector('.cardinality[data-end="from"]'),pts[0],rel.dataset.fromSide);setCardinality(rel.querySelector('.cardinality[data-end="to"]'),pts[pts.length-1],rel.dataset.toSide);const controls=controlsFor(rel.dataset.relationId);if(!controls)return;controls.querySelectorAll('.route-snap').forEach(dot=>{const p=endpointPoint(rel,dot.dataset.end,dot.dataset.side);dot.setAttribute('cx',p.x);dot.setAttribute('cy',p.y)});const from=controls.querySelector('.route-endpoint[data-end="from"]'),to=controls.querySelector('.route-endpoint[data-end="to"]'),control=controls.querySelector('.route-control'),axisX=controls.querySelector('.route-axis-x'),axisY=controls.querySelector('.route-axis-y'),cx=parseFloat(rel.dataset.controlX),cy=parseFloat(rel.dataset.controlY);from.setAttribute('cx',pts[0].x);from.setAttribute('cy',pts[0].y);to.setAttribute('cx',pts[pts.length-1].x);to.setAttribute('cy',pts[pts.length-1].y);control.setAttribute('cx',cx);control.setAttribute('cy',cy);axisX.setAttribute('x1',cx-13);axisX.setAttribute('x2',cx+13);axisX.setAttribute('y1',cy);axisX.setAttribute('y2',cy);axisY.setAttribute('x1',cx);axisY.setAttribute('x2',cx);axisY.setAttribute('y1',cy-13);axisY.setAttribute('y2',cy+13)}
function selectRelation(rel){document.querySelectorAll('.relation-route,.route-controls').forEach(g=>g.classList.remove('selected'));if(!rel)return;rel.classList.add('selected');const controls=controlsFor(rel.dataset.relationId);if(controls)controls.classList.add('selected')}
function closestSide(box,p,rowOffset){const sides=['top','right','bottom','left'];return sides.reduce((best,s)=>{const a=sidePoint(box,s,rowOffset),dist=(a.x-p.x)*(a.x-p.x)+(a.y-p.y)*(a.y-p.y);return!best||dist<best.dist?{side:s,dist:dist}:best},null).side}
function diagramPoint(e){return{x:(e.clientX-tx)/scale,y:(e.clientY-ty)/scale}}
let routeDrag=null,routeFrame=0;
function flushRoutes(){
 if(routeFrame){cancelAnimationFrame(routeFrame);routeFrame=0}
 routingSnapshot={cards:routingCards(),boxes:null};
 try{routingSnapshot.boxes=routeBoxes();document.querySelectorAll('.relation-route').forEach(updateRelation)}finally{routingSnapshot=null}
}
function queueRoutes(){if(!routeFrame)routeFrame=requestAnimationFrame(()=>{routeFrame=0;flushRoutes()})}
function endpointRole(rel,e){const p=diagramPoint(e),from=endpointPoint(rel,'from',rel.dataset.fromSide),to=endpointPoint(rel,'to',rel.dataset.toSide),fromDistance=Math.hypot(p.x-from.x,p.y-from.y)*scale,toDistance=Math.hypot(p.x-to.x,p.y-to.y)*scale;return Math.min(fromDistance,toDistance)<=14?(fromDistance<toDistance?'from':'to'):'control'}
function capturePointer(target,id){try{target.setPointerCapture(id)}catch(ignore){}}
function releasePointer(target,id){try{if(target.hasPointerCapture(id))target.releasePointerCapture(id)}catch(ignore){}}
function snapCoordinate(value){return snapEnabled?Math.round(value/24)*24:value}
function setTablePosition(g,x,y){
 if(g.dataset.renderX===undefined){g.dataset.renderX=g.dataset.x;g.dataset.renderY=g.dataset.y}
 g.dataset.x=x;g.dataset.y=y;g.setAttribute('transform','translate('+(x-(+g.dataset.renderX))+' '+(y-(+g.dataset.renderY))+')');
}
v.addEventListener('pointerdown',e=>{
 if(tableDrag||groupDrag||routeDrag||panDrag)return;
 const target=e.target,controls=target.closest&&target.closest('.route-controls'),relHit=target.closest&&target.closest('.relation-route'),group=target.closest&&target.closest('g[data-table]'),frame=target.closest&&target.closest('g[data-table-group]');
 if(e.button===1||(e.button===0&&spaceDown)){e.preventDefault();panDrag={pointerId:e.pointerId,startX:e.clientX,startY:e.clientY,x:tx,y:ty};v.classList.add('dragging');capturePointer(v,e.pointerId);return}
 if(e.button!==0)return;
 if(controls&&target.classList.contains('route-snap')){const rel=Array.from(document.querySelectorAll('.relation-route')).find(g=>g.dataset.relationId===controls.dataset.controlsFor);if(!rel)return;selectRelation(rel);e.preventDefault();if(target.dataset.end==='from')rel.dataset.fromSide=target.dataset.side;else rel.dataset.toSide=target.dataset.side;updateRelation(rel);window.saveRelationRoute(rel);return}
 if(controls&&(target.classList.contains('route-control')||target.classList.contains('route-endpoint')||target.classList.contains('route-axis'))){const rel=Array.from(document.querySelectorAll('.relation-route')).find(g=>g.dataset.relationId===controls.dataset.controlsFor);if(!rel)return;selectRelation(rel);e.preventDefault();routeDrag={rel:rel,target:target,role:target.dataset.role||target.dataset.end,pointerId:e.pointerId,startClientX:e.clientX,startClientY:e.clientY,startX:+rel.dataset.controlX,startY:+rel.dataset.controlY};capturePointer(target,e.pointerId);return}
 if(relHit){selectRelation(relHit);e.preventDefault();routeDrag={rel:relHit,target:target,role:endpointRole(relHit,e),pointerId:e.pointerId,startClientX:e.clientX,startClientY:e.clientY,startX:+relHit.dataset.controlX,startY:+relHit.dataset.controlY};capturePointer(target,e.pointerId);return}
 if(group&&(target.classList.contains('head')||target.classList.contains('title'))){selectRelation(null);e.preventDefault();tableDrag={group:group,pointerId:e.pointerId,startClientX:e.clientX,startClientY:e.clientY,startX:+group.dataset.x,startY:+group.dataset.y};capturePointer(group,e.pointerId);return}
 if(frame){
  const members=JSON.parse(frame.dataset.tables).map(tableGroup).filter(Boolean),ids=new Set(members.map(g=>g.dataset.table));if(!members.length)return;
  const routes=Array.from(document.querySelectorAll('.relation-route')).filter(r=>ids.has(r.dataset.fromTable)&&ids.has(r.dataset.toTable)).map(r=>({rel:r,x:+r.dataset.controlX,y:+r.dataset.controlY}));
  groupDrag={frame:frame,pointerId:e.pointerId,startClientX:e.clientX,startClientY:e.clientY,members:members.map(g=>({group:g,x:+g.dataset.x,y:+g.dataset.y})),routes:routes};
  selectRelation(null);e.preventDefault();capturePointer(frame,e.pointerId);return;
 }
 if(!group){selectRelation(null);e.preventDefault();panDrag={pointerId:e.pointerId,startX:e.clientX,startY:e.clientY,x:tx,y:ty};v.classList.add('dragging');capturePointer(v,e.pointerId)}
});
window.addEventListener('pointermove',e=>{
 if(panDrag&&panDrag.pointerId===e.pointerId){e.preventDefault();tx=panDrag.x+e.clientX-panDrag.startX;ty=panDrag.y+e.clientY-panDrag.startY;apply();return}
 if(routeDrag&&routeDrag.pointerId===e.pointerId){
  e.preventDefault();if(!routeDrag.moved&&Math.hypot(e.clientX-routeDrag.startClientX,e.clientY-routeDrag.startClientY)<4)return;routeDrag.moved=true;
  if(routeDrag.role==='control'||routeDrag.role==='x'||routeDrag.role==='y'){if(routeDrag.role!=='y')routeDrag.rel.dataset.controlX=routeDrag.startX+(e.clientX-routeDrag.startClientX)/scale;if(routeDrag.role!=='x')routeDrag.rel.dataset.controlY=routeDrag.startY+(e.clientY-routeDrag.startClientY)/scale}
  else{const p=diagramPoint(e),box=tableBox(routeDrag.role==='from'?routeDrag.rel.dataset.fromTable:routeDrag.rel.dataset.toTable),side=closestSide(box,p,columnOffset(routeDrag.rel,routeDrag.role));if(routeDrag.role==='from')routeDrag.rel.dataset.fromSide=side;else routeDrag.rel.dataset.toSide=side}
  updateRelation(routeDrag.rel);return;
 }
 const current=tableDrag||groupDrag;if(!current||current.pointerId!==e.pointerId)return;
 e.preventDefault();if(!current.moved&&Math.hypot(e.clientX-current.startClientX,e.clientY-current.startClientY)<4)return;current.moved=true;
 let dx=(e.clientX-current.startClientX)/scale,dy=(e.clientY-current.startClientY)/scale;
 if(tableDrag)setTablePosition(current.group,snapCoordinate(current.startX+dx),snapCoordinate(current.startY+dy));
 else{
  const anchor=current.members[0];dx=snapCoordinate(anchor.x+dx)-anchor.x;dy=snapCoordinate(anchor.y+dy)-anchor.y;
  current.members.forEach(m=>setTablePosition(m.group,m.x+dx,m.y+dy));
  current.routes.forEach(r=>{r.rel.dataset.controlX=r.x+dx;r.rel.dataset.controlY=r.y+dy});
 }
 updateTableGroups();queueRoutes();
});
function finishPointerDrag(e){
 if(panDrag&&panDrag.pointerId===e.pointerId){releasePointer(v,e.pointerId);panDrag=null;v.classList.remove('dragging');saveView();return}
 if(routeDrag&&routeDrag.pointerId===e.pointerId){const current=routeDrag;routeDrag=null;releasePointer(current.target,e.pointerId);if(current.moved)window.saveRelationRoute(current.rel);return}
 if(groupDrag&&groupDrag.pointerId===e.pointerId){
  const current=groupDrag;groupDrag=null;releasePointer(current.frame,e.pointerId);if(!current.moved)return;flushRoutes();clearTimeout(viewTimer);
  const tables=current.members.map(m=>({id:m.group.dataset.table,x:+m.group.dataset.x,y:+m.group.dataset.y}));
  const routes=current.routes.map(r=>({id:r.rel.dataset.relationId,fromSide:r.rel.dataset.fromSide,toSide:r.rel.dataset.toSide,x:+r.rel.dataset.controlX,y:+r.rel.dataset.controlY}));
  send('B\t'+encodeURIComponent(JSON.stringify({tables:tables,routes:routes,view:camera()})));return;
 }
 if(!tableDrag||tableDrag.pointerId!==e.pointerId)return;const current=tableDrag;tableDrag=null;releasePointer(current.group,e.pointerId);
 if(current.moved){flushRoutes();window.saveTablePosition(current.group.dataset.table,current.group.dataset.x,current.group.dataset.y)}
}
window.addEventListener('pointerup',finishPointerDrag);window.addEventListener('pointercancel',finishPointerDrag);
window.addEventListener('keydown',e=>{if(e.code==='Space'&&!e.ctrlKey&&!e.metaKey&&!e.altKey){e.preventDefault();spaceDown=true;v.style.cursor='grab'}});
window.addEventListener('keyup',e=>{if(e.code==='Space'){spaceDown=false;v.style.cursor=''}});
window.addEventListener('blur',()=>{spaceDown=false;v.style.cursor='';const current=panDrag||routeDrag||tableDrag||groupDrag;if(current)finishPointerDrag({pointerId:current.pointerId})});
v.addEventListener('wheel',e=>{
 e.preventDefault();if(tableDrag||groupDrag||routeDrag||panDrag)return;
 if(e.ctrlKey||e.metaKey)zoom(Math.exp(-e.deltaY*.002),e.clientX,e.clientY);
 else{const unit=e.deltaMode===1?16:e.deltaMode===2?innerHeight:1;tx-=(e.shiftKey&&!e.deltaX?e.deltaY:e.deltaX)*unit;ty-=(e.shiftKey&&!e.deltaX?0:e.deltaY)*unit;apply();clearTimeout(viewTimer);viewTimer=setTimeout(saveView,150)}
},{passive:false});
let viewportWidth=innerWidth,viewportHeight=innerHeight;
addEventListener('resize',()=>{tx+=(innerWidth-viewportWidth)/2;ty+=(innerHeight-viewportHeight)/2;viewportWidth=innerWidth;viewportHeight=innerHeight;apply();saveView()});
if($hasInitialView)apply();else fit(false);
const initialSelectedRelation=$selectedRelation;if(initialSelectedRelation)selectRelation(Array.from(document.querySelectorAll('.relation-route')).find(rel=>rel.dataset.relationId===initialSelectedRelation));
</script></body></html>"""
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun escapeJs(value: String) = value.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n").replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
}
