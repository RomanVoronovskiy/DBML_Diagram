package io.github.dbmldiagram.plugin.preview

internal object PreviewHtml {
    fun page(svg: String?, message: String?, dark: Boolean): String {
        val diagram = svg ?: "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 640 360\"><text x=\"40\" y=\"60\">Waiting for valid DBML…</text></svg>"
        val notice = message?.let { "<div id=\"notice\">${escape(it)}</div>" } ?: ""
        val colors = if (dark) "--bg:#2b2b2b;--card:#3c3f41;--header:#45494a;--text:#d7d7d7;--muted:#a8a8a8;--border:#696b6c;--edge:#96999b;--accent:#77a7e8" else "--bg:#f7f8fa;--card:#fff;--header:#eef1f5;--text:#24292f;--muted:#65717e;--border:#9aa0a6;--edge:#79838e;--accent:#3d65a5"
        return """<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;width:100%;height:100%;overflow:hidden;$colors;background:var(--bg);color:var(--text);font:12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
#notice{position:fixed;z-index:5;left:12px;top:10px;right:12px;padding:7px 10px;border:1px solid #b9923d;border-radius:4px;background:${if (dark) "#594b2b" else "#fff4ce"};white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#viewport{width:100%;height:100%;cursor:grab;transform-origin:0 0}#viewport.dragging{cursor:grabbing}#diagram{position:absolute;left:0;top:0;transform-origin:0 0}
#diagram svg{--dbml-card:var(--card);--dbml-header:var(--header);--dbml-text:var(--text);--dbml-muted:var(--muted);--dbml-border:var(--border);--dbml-edge:var(--edge);--dbml-accent:var(--accent)}
</style></head><body>$notice<div id="viewport"><div id="diagram">$diagram</div></div><script>
let scale=1,tx=20,ty=20,drag=false,lx=0,ly=0; const d=document.getElementById('diagram'),v=document.getElementById('viewport');
function apply(){d.style.transform='translate('+tx+'px,'+ty+'px) scale('+scale+')'}
function zoom(f){scale=Math.max(.1,Math.min(4,scale*f));apply()}
function actual(){scale=1;tx=20;ty=20;apply()}
function fit(){const s=d.querySelector('svg');if(!s)return;const w=parseFloat(s.getAttribute('width')||640),h=parseFloat(s.getAttribute('height')||360);scale=Math.min((innerWidth-40)/w,(innerHeight-40)/h,1.5);tx=(innerWidth-w*scale)/2;ty=(innerHeight-h*scale)/2;apply()}
v.addEventListener('mousedown',e=>{drag=true;lx=e.clientX;ly=e.clientY;v.classList.add('dragging')});addEventListener('mouseup',()=>{drag=false;v.classList.remove('dragging')});addEventListener('mousemove',e=>{if(drag){tx+=e.clientX-lx;ty+=e.clientY-ly;lx=e.clientX;ly=e.clientY;apply()}});
v.addEventListener('wheel',e=>{if(e.ctrlKey||e.metaKey){e.preventDefault();zoom(e.deltaY<0?1.12:.89)}},{passive:false});addEventListener('resize',fit);fit();
</script></body></html>"""
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
