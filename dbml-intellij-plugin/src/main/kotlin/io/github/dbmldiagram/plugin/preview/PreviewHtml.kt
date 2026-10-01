package io.github.dbmldiagram.plugin.preview

internal data class PreviewViewState(val scale: Double, val x: Double, val y: Double)

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
        val savePosition = positionCallback ?: ""
        return """<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;width:100%;height:100%;overflow:hidden;$colors;background:var(--bg);color:var(--text);font:12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
#notice{position:fixed;z-index:5;left:12px;top:10px;right:12px;padding:7px 10px;border:1px solid #b9923d;border-radius:4px;background:${if (dark) "#594b2b" else "#fff4ce"};white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#viewport{width:100%;height:100%;cursor:grab;transform-origin:0 0}#viewport.dragging{cursor:grabbing}#diagram{position:absolute;left:0;top:0;transform-origin:0 0}
#diagram svg{--dbml-bg:var(--bg);--dbml-card:var(--card);--dbml-header:var(--header);--dbml-text:var(--text);--dbml-muted:var(--muted);--dbml-border:var(--border);--dbml-edge:var(--edge);--dbml-relation:var(--relation);--dbml-accent:var(--accent)}
#diagram .head,#diagram .title{cursor:move}
</style></head><body>$notice<div id="viewport"><div id="diagram">$diagram</div></div><script>
let scale=$initialScale,tx=$initialX,ty=$initialY,drag=false,lx=0,ly=0,tableDrag=null; const d=document.getElementById('diagram'),v=document.getElementById('viewport');
function apply(){d.style.transform='translate('+tx+'px,'+ty+'px) scale('+scale+')'}
function zoom(f){scale=Math.max(.1,Math.min(4,scale*f));apply()}
function actual(){scale=1;tx=20;ty=20;apply()}
function fit(){const s=d.querySelector('svg');if(!s)return;const w=parseFloat(s.getAttribute('width')||640),h=parseFloat(s.getAttribute('height')||360);scale=Math.min((innerWidth-40)/w,(innerHeight-40)/h,1.5);tx=(innerWidth-w*scale)/2;ty=(innerHeight-h*scale)/2;apply()}
function send(payload){$savePosition}
window.saveTablePosition=function(table,x,y){send('T\t'+encodeURIComponent(table)+'\t'+x+'\t'+y+'\t'+scale+'\t'+tx+'\t'+ty)};
window.saveRelationRoute=function(rel){send('R\t'+encodeURIComponent(rel.dataset.relationId)+'\t'+rel.dataset.fromSide+'\t'+rel.dataset.toSide+'\t'+rel.dataset.controlX+'\t'+rel.dataset.controlY+'\t'+scale+'\t'+tx+'\t'+ty)};
function tableGroup(id){return Array.from(document.querySelectorAll('g[data-table]')).find(g=>g.dataset.table===id)}
function tableBox(id){const g=tableGroup(id);return g?{x:parseFloat(g.dataset.x),y:parseFloat(g.dataset.y),w:parseFloat(g.dataset.width),h:parseFloat(g.dataset.height)}:null}
function sidePoint(b,s){if(s==='top')return{x:b.x+b.w/2,y:b.y};if(s==='right')return{x:b.x+b.w,y:b.y+b.h/2};if(s==='bottom')return{x:b.x+b.w/2,y:b.y+b.h};return{x:b.x,y:b.y+b.h/2}}
function exitPoint(p,s){if(s==='top')return{x:p.x,y:p.y-24};if(s==='right')return{x:p.x+24,y:p.y};if(s==='bottom')return{x:p.x,y:p.y+24};return{x:p.x-24,y:p.y}}
function controlsFor(id){return Array.from(document.querySelectorAll('.route-controls')).find(g=>g.dataset.controlsFor===id)}
function ensureCanvas(maxX,maxY){const svg=d.querySelector('svg'),w=Math.max(parseFloat(svg.getAttribute('width')||640),maxX+48),h=Math.max(parseFloat(svg.getAttribute('height')||360),maxY+48);svg.setAttribute('width',w);svg.setAttribute('height',h);svg.setAttribute('viewBox','0 0 '+w+' '+h)}
function routeLeg(p,s,c){if(s==='right'){const x=Math.max(p.x,c.x);return[p,{x:x,y:p.y},{x:x,y:c.y},c]}if(s==='left'){const x=Math.min(p.x,c.x);return[p,{x:x,y:p.y},{x:x,y:c.y},c]}if(s==='bottom'){const y=Math.max(p.y,c.y);return[p,{x:p.x,y:y},{x:c.x,y:y},c]}const y=Math.min(p.y,c.y);return[p,{x:p.x,y:y},{x:c.x,y:y},c]}
function relationPoints(rel){const a=sidePoint(tableBox(rel.dataset.fromTable),rel.dataset.fromSide),b=sidePoint(tableBox(rel.dataset.toTable),rel.dataset.toSide),ae=exitPoint(a,rel.dataset.fromSide),be=exitPoint(b,rel.dataset.toSide),c={x:parseFloat(rel.dataset.controlX),y:parseFloat(rel.dataset.controlY)},left=routeLeg(ae,rel.dataset.fromSide,c),right=routeLeg(be,rel.dataset.toSide,c).reverse();return[a].concat(left,right,[b]).filter((p,i,all)=>i===0||p.x!==all[i-1].x||p.y!==all[i-1].y)}
function setCardinality(text,p,side){if(!text)return;if(side==='left'){text.setAttribute('x',p.x-8);text.setAttribute('y',p.y-6);text.setAttribute('text-anchor','end')}else if(side==='right'){text.setAttribute('x',p.x+8);text.setAttribute('y',p.y-6);text.setAttribute('text-anchor','start')}else if(side==='top'){text.setAttribute('x',p.x+8);text.setAttribute('y',p.y-7);text.setAttribute('text-anchor','start')}else{text.setAttribute('x',p.x+8);text.setAttribute('y',p.y+15);text.setAttribute('text-anchor','start')}}
function updateRelation(rel){const pts=relationPoints(rel),value=pts.map(p=>p.x+','+p.y).join(' ');ensureCanvas(Math.max.apply(null,pts.map(p=>p.x)),Math.max.apply(null,pts.map(p=>p.y)));rel.querySelectorAll('polyline').forEach(p=>p.setAttribute('points',value));setCardinality(rel.querySelector('.cardinality[data-end="from"]'),pts[0],rel.dataset.fromSide);setCardinality(rel.querySelector('.cardinality[data-end="to"]'),pts[pts.length-1],rel.dataset.toSide);const controls=controlsFor(rel.dataset.relationId);if(!controls)return;const fromBox=tableBox(rel.dataset.fromTable),toBox=tableBox(rel.dataset.toTable);controls.querySelectorAll('.route-snap').forEach(dot=>{const p=sidePoint(dot.dataset.end==='from'?fromBox:toBox,dot.dataset.side);dot.setAttribute('cx',p.x);dot.setAttribute('cy',p.y)});const from=controls.querySelector('.route-endpoint[data-end="from"]'),to=controls.querySelector('.route-endpoint[data-end="to"]'),control=controls.querySelector('.route-control');from.setAttribute('cx',pts[0].x);from.setAttribute('cy',pts[0].y);to.setAttribute('cx',pts[pts.length-1].x);to.setAttribute('cy',pts[pts.length-1].y);control.setAttribute('cx',rel.dataset.controlX);control.setAttribute('cy',rel.dataset.controlY)}
function selectRelation(rel){document.querySelectorAll('.relation-route,.route-controls').forEach(g=>g.classList.remove('selected'));if(!rel)return;rel.classList.add('selected');const controls=controlsFor(rel.dataset.relationId);if(controls)controls.classList.add('selected')}
function closestSide(box,p){const sides=['top','right','bottom','left'];return sides.reduce((best,s)=>{const a=sidePoint(box,s),dist=(a.x-p.x)*(a.x-p.x)+(a.y-p.y)*(a.y-p.y);return!best||dist<best.dist?{side:s,dist:dist}:best},null).side}
function diagramPoint(e){return{x:(e.clientX-tx)/scale,y:(e.clientY-ty)/scale}}
let routeDrag=null;
v.addEventListener('pointerdown',e=>{const target=e.target,controls=target.closest&&target.closest('.route-controls'),relHit=target.classList&&target.classList.contains('relation-hit')?target.closest('.relation-route'):null,group=target.closest&&target.closest('g[data-table]');if(controls&&(target.classList.contains('route-control')||target.classList.contains('route-endpoint'))){const rel=Array.from(document.querySelectorAll('.relation-route')).find(g=>g.dataset.relationId===controls.dataset.controlsFor);selectRelation(rel);e.preventDefault();e.stopPropagation();routeDrag={rel:rel,target:target,role:target.classList.contains('route-control')?'control':target.dataset.end,pointerId:e.pointerId,startClientX:e.clientX,startClientY:e.clientY,startX:parseFloat(rel.dataset.controlX),startY:parseFloat(rel.dataset.controlY)};target.setPointerCapture(e.pointerId);return}if(relHit){selectRelation(relHit);e.preventDefault();e.stopPropagation();routeDrag={rel:relHit,target:target,role:'control',pointerId:e.pointerId,startClientX:e.clientX,startClientY:e.clientY,startX:parseFloat(relHit.dataset.controlX),startY:parseFloat(relHit.dataset.controlY)};target.setPointerCapture(e.pointerId);return}if(group&&(target.classList.contains('head')||target.classList.contains('title'))){selectRelation(null);e.preventDefault();e.stopPropagation();const startX=parseFloat(group.dataset.x),startY=parseFloat(group.dataset.y);tableDrag={group:group,pointerId:e.pointerId,startClientX:e.clientX,startClientY:e.clientY,startX:startX,startY:startY,x:startX,y:startY};group.setPointerCapture(e.pointerId);return}if(!group)selectRelation(null)});
v.addEventListener('pointermove',e=>{if(routeDrag&&routeDrag.pointerId===e.pointerId){e.preventDefault();if(routeDrag.role==='control'){routeDrag.rel.dataset.controlX=Math.max(20,routeDrag.startX+(e.clientX-routeDrag.startClientX)/scale);routeDrag.rel.dataset.controlY=Math.max(20,routeDrag.startY+(e.clientY-routeDrag.startClientY)/scale)}else{const p=diagramPoint(e),box=tableBox(routeDrag.role==='from'?routeDrag.rel.dataset.fromTable:routeDrag.rel.dataset.toTable),side=closestSide(box,p);if(routeDrag.role==='from')routeDrag.rel.dataset.fromSide=side;else routeDrag.rel.dataset.toSide=side}updateRelation(routeDrag.rel);return}if(!tableDrag||tableDrag.pointerId!==e.pointerId)return;e.preventDefault();const x=Math.max(48,tableDrag.startX+(e.clientX-tableDrag.startClientX)/scale),y=Math.max(48,tableDrag.startY+(e.clientY-tableDrag.startClientY)/scale);tableDrag.x=x;tableDrag.y=y;tableDrag.group.dataset.x=x;tableDrag.group.dataset.y=y;tableDrag.group.setAttribute('transform','translate('+(x-tableDrag.startX)+' '+(y-tableDrag.startY)+')');ensureCanvas(x+parseFloat(tableDrag.group.dataset.width),y+parseFloat(tableDrag.group.dataset.height));document.querySelectorAll('.relation-route').forEach(rel=>{if(rel.dataset.fromTable===tableDrag.group.dataset.table||rel.dataset.toTable===tableDrag.group.dataset.table)updateRelation(rel)})});
function finishPointerDrag(e){if(routeDrag&&routeDrag.pointerId===e.pointerId){const current=routeDrag;routeDrag=null;if(current.target.hasPointerCapture(e.pointerId))current.target.releasePointerCapture(e.pointerId);window.saveRelationRoute(current.rel);return}if(!tableDrag||tableDrag.pointerId!==e.pointerId)return;const moved=Math.abs(tableDrag.x-tableDrag.startX)>.1||Math.abs(tableDrag.y-tableDrag.startY)>.1,current=tableDrag;tableDrag=null;if(current.group.hasPointerCapture(e.pointerId))current.group.releasePointerCapture(e.pointerId);if(moved)window.saveTablePosition(current.group.dataset.table,current.x.toFixed(2),current.y.toFixed(2))}
v.addEventListener('pointerup',finishPointerDrag);v.addEventListener('pointercancel',finishPointerDrag);
v.addEventListener('mousedown',e=>{if(e.target.closest&&(e.target.closest('g[data-table]')||e.target.closest('.relation-route')||e.target.closest('.route-controls')))return;drag=true;lx=e.clientX;ly=e.clientY;v.classList.add('dragging')});addEventListener('mouseup',()=>{drag=false;v.classList.remove('dragging')});addEventListener('mousemove',e=>{if(drag){tx+=e.clientX-lx;ty+=e.clientY-ly;lx=e.clientX;ly=e.clientY;apply()}});
v.addEventListener('wheel',e=>{if(e.ctrlKey||e.metaKey){e.preventDefault();zoom(e.deltaY<0?1.12:.89)}},{passive:false});addEventListener('resize',fit);
if($hasInitialView)apply();else fit();
</script></body></html>"""
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
