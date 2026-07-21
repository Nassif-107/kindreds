"""Assemble the standalone explorer: a book of lore, not a starfield."""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = open(os.path.join(HERE, 'kindreds-data.json'), encoding='utf-8').read()
OUT = 'C:/dev/minecraft_mods/new-mod/docs/tree-redesign/kindreds-explorer.html'

TEMPLATE = r"""<title>Kindreds of Middle-earth — The Book of Kindreds</title>
<style>
/* ============ tokens: aged vellum, oak-gall ink, gold leaf =============== */
:root{
  color-scheme: light dark;
  --accent:#4A7A96; --accent2:#8A6B3A;

  --page:#E9DFC6;            /* vellum */
  --page-2:#E2D6B8;          /* deeper vellum, panels */
  --page-3:#F2EAD6;          /* lit vellum, cards */
  --ink:#241C12;             /* oak-gall ink */
  --ink-2:#5A4A35;           /* faded ink */
  --ink-3:#8B7A5F;           /* very faded */
  --rule:#C3B38C;            /* ruled lines */
  --rule-soft:#D6C8A6;
  --gold:#9C7A2E;            /* leaf */
  --oxblood:#7C2F26;
  --moss:#5E6B3A;
  --grain:.5;

  --serif:"Iowan Old Style","Palatino Linotype",Palatino,"Book Antiqua",Georgia,serif;
  --sans:"Segoe UI Variable Text","Segoe UI",system-ui,-apple-system,sans-serif;
  --mono:ui-monospace,"Cascadia Mono",Consolas,"SF Mono",monospace;
  --shadow:0 20px 44px -30px #241C1288;
  --r:3px;
}
@media (prefers-color-scheme: dark){
  :root{
    --page:#17130C; --page-2:#221C13; --page-3:#2B2418;
    --ink:#EFE5CC; --ink-2:#BEAE8D; --ink-3:#93866A;
    --rule:#4C4029; --rule-soft:#362E1F;
    --gold:#C79B3F; --oxblood:#B4574A; --moss:#8FA357; --grain:.34;
    --shadow:0 22px 50px -28px #000B;
  }
}
:root[data-theme="light"]{
  --page:#E9DFC6; --page-2:#E2D6B8; --page-3:#F2EAD6;
  --ink:#241C12; --ink-2:#5A4A35; --ink-3:#8B7A5F;
  --rule:#C3B38C; --rule-soft:#D6C8A6;
  --gold:#9C7A2E; --oxblood:#7C2F26; --moss:#5E6B3A; --grain:.5;
  --shadow:0 20px 44px -30px #241C1288;
}
:root[data-theme="dark"]{
  --page:#17130C; --page-2:#221C13; --page-3:#2B2418;
  --ink:#EFE5CC; --ink-2:#BEAE8D; --ink-3:#93866A;
  --rule:#4C4029; --rule-soft:#362E1F;
  --gold:#C79B3F; --oxblood:#B4574A; --moss:#8FA357; --grain:.34;
  --shadow:0 22px 50px -28px #000B;
}

*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{
  margin:0;background:var(--page);color:var(--ink);font-family:var(--sans);font-size:15px;
  line-height:1.6;-webkit-font-smoothing:antialiased;transition:background .45s,color .3s;
}
h1,h2,h3,h4{font-family:var(--serif);font-weight:600;margin:0;text-wrap:balance;letter-spacing:.005em}
button{font:inherit;color:inherit;background:none;border:0;cursor:pointer}
:focus-visible{outline:2px solid var(--gold);outline-offset:3px}
.tnum{font-variant-numeric:tabular-nums}
.runes{font-family:var(--serif);letter-spacing:.5em;color:var(--gold);opacity:.75;font-size:12px}

/* parchment grain + vignette, drawn not downloaded */
/* Parchment grain as a seamless 140px tile rather than one full-screen filtered rect:
   an <svg> is a replaced element, so inset:0 with auto width left it at its intrinsic
   300x150 parked in the top-left corner - and filtering a whole 4K viewport is costly.
   stitchTiles makes the tile repeat without seams. */
body::before{content:"";position:fixed;inset:0;z-index:0;pointer-events:none;
  opacity:var(--grain);mix-blend-mode:multiply;
  background-image:url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='140' height='140'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/><feColorMatrix type='saturate' values='0'/></filter><rect width='140' height='140' filter='url(%23n)' opacity='0.5'/></svg>")}
@media (prefers-color-scheme: dark){body::before{mix-blend-mode:screen}}
:root[data-theme="dark"] body::before{mix-blend-mode:screen}
:root[data-theme="light"] body::before{mix-blend-mode:multiply}

.wrap{position:relative;z-index:1;max-width:1480px;margin:0 auto;padding:0 22px 70px}

/* ============ masthead ================================================== */
.masthead{padding:38px 0 14px;text-align:center;position:relative}
.masthead .rule-orn{display:flex;align-items:center;gap:14px;justify-content:center;color:var(--rule)}
.masthead .rule-orn::before,.masthead .rule-orn::after{content:"";height:1px;flex:1;max-width:220px;
  background:linear-gradient(90deg,transparent,var(--rule),transparent)}
.masthead h1{font-size:clamp(30px,4.6vw,52px);line-height:1.02;margin:10px 0 6px;letter-spacing:.012em}
.masthead .sub{font-family:var(--serif);font-style:italic;color:var(--ink-2);font-size:clamp(14px,1.7vw,18px)}
.masthead .strip{display:flex;gap:6px 24px;justify-content:center;flex-wrap:wrap;margin-top:14px;
  font-family:var(--mono);font-size:11.5px;letter-spacing:.1em;text-transform:uppercase;color:var(--ink-3)}
.masthead .strip b{color:var(--gold);font-weight:600}

/* ============ sticky kindred rail ======================================= */
.sticky{position:sticky;top:0;z-index:30;margin:0 -22px;padding:0 22px;
  background:color-mix(in srgb,var(--page) 92%,transparent);backdrop-filter:blur(10px) saturate(1.1);
  border-bottom:1px solid var(--rule);box-shadow:0 10px 24px -22px #0006}
.kinrow{display:flex;gap:6px;overflow-x:auto;padding:9px 0;scrollbar-width:thin}
.kin{flex:0 0 auto;display:flex;align-items:center;gap:8px;padding:7px 14px;border:1px solid var(--rule);
  border-radius:2px;background:var(--page-3);color:var(--ink-2);white-space:nowrap;
  font-family:var(--serif);font-size:14.5px;transition:.18s}
.kin .sig{width:20px;height:20px;flex:0 0 auto;display:grid;place-items:center;border-radius:50%;
  border:1.5px solid var(--kc);color:var(--kc);font-size:11px;font-family:var(--serif);font-weight:700}
.kin:hover{color:var(--ink);border-color:var(--kc);transform:translateY(-1px)}
.kin[aria-pressed="true"]{background:color-mix(in srgb,var(--kc) 16%,var(--page-3));
  border-color:var(--kc);color:var(--ink);font-weight:600}
.kin[aria-pressed="true"] .sig{background:var(--kc);color:var(--page-3)}
.viewrow{display:flex;gap:14px;align-items:center;justify-content:space-between;flex-wrap:wrap;
  padding:0 0 9px}
.tabs{display:flex;gap:0;border:1px solid var(--rule);border-radius:2px;overflow:hidden;background:var(--page-3)}
.tab{padding:7px 17px;color:var(--ink-2);font-family:var(--serif);font-size:14px;border-right:1px solid var(--rule)}
.tab:last-child{border-right:0}
.tab:hover{background:color-mix(in srgb,var(--gold) 10%,transparent);color:var(--ink)}
.tab[aria-selected="true"]{background:var(--ink);color:var(--page);font-weight:600}
.mini{display:flex;gap:6px}
.chip{padding:5px 11px;border:1px solid var(--rule);border-radius:2px;color:var(--ink-2);
  font-family:var(--mono);font-size:11.5px;letter-spacing:.05em;background:var(--page-3);transition:.15s}
.chip:hover{border-color:var(--gold);color:var(--ink)}
.chip[aria-pressed="true"]{background:var(--ink);border-color:var(--ink);color:var(--page)}

/* ============ illuminated race header =================================== */
.illum{display:grid;grid-template-columns:auto 1fr auto;gap:20px;align-items:start;
  padding:26px 0 18px;border-bottom:1px solid var(--rule-soft);margin-bottom:16px}
.dropcap{width:74px;height:74px;display:grid;place-items:center;border:2px solid var(--kc);
  border-radius:2px;background:color-mix(in srgb,var(--kc) 12%,var(--page-3));position:relative}
.dropcap span{font-family:var(--serif);font-size:42px;line-height:1;color:var(--kc);font-weight:700}
.dropcap::after{content:"";position:absolute;inset:4px;border:1px solid color-mix(in srgb,var(--kc) 40%,transparent)}
.illum h2{font-size:clamp(23px,3vw,33px);color:var(--ink)}
.illum .epithet{font-family:var(--serif);font-style:italic;color:var(--kc);font-size:15px;margin:2px 0 8px}
.illum p{margin:0;color:var(--ink-2);max-width:70ch}
.ledger{display:grid;grid-template-columns:repeat(4,auto);gap:4px 22px;text-align:right;
  font-family:var(--mono);font-size:10.5px;letter-spacing:.09em;text-transform:uppercase;color:var(--ink-3)}
.ledger b{display:block;font-family:var(--serif);font-size:22px;color:var(--ink);letter-spacing:0}

/* ============ dossier =================================================== */
.dossier{margin:0 0 16px}
.dosssub{font:600 10px/1.4 var(--sans);letter-spacing:.09em;text-transform:uppercase;color:var(--gold);margin:11px 0 3px;padding-bottom:2px;border-bottom:1px solid var(--rule-soft)}
.dosscol .dosssub:first-of-type{margin-top:0}
.dosstoggle{font-family:var(--mono);font-size:11px;letter-spacing:.12em;text-transform:uppercase;
  color:var(--ink-2);border:1px solid var(--rule);border-radius:2px;background:var(--page-3);
  padding:6px 13px;transition:.15s}
.dosstoggle:hover{border-color:var(--kc);color:var(--ink)}
.dossbody{display:none;grid-template-columns:repeat(auto-fit,minmax(228px,1fr));gap:14px;
  margin-top:12px;padding:16px 18px;background:var(--page-2);border:1px solid var(--rule);border-radius:var(--r)}
.dossier.open .dossbody{display:grid}
.dosscol{min-width:0}
.dosshead{font-family:var(--mono);font-size:10.5px;letter-spacing:.14em;text-transform:uppercase;
  color:var(--ink-3);margin-bottom:7px;padding-bottom:5px;border-bottom:1px solid var(--rule-soft)}
.dosstext{margin:0;color:var(--ink-2);font-size:13.5px;line-height:1.62}
.dosslist{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:5px}
.dosslist li{font-size:12.8px;color:var(--ink-2);padding-left:14px;position:relative;line-height:1.45}
.dosslist li::before{content:"·";position:absolute;left:4px;color:var(--ink-3)}
.dosslist li.good::before{content:"\2726";color:var(--moss);font-size:9px;top:1px}
.dosslist li.bad::before{content:"\2716";color:var(--oxblood);font-size:9px;top:1px}
.dosslist li.sig{font-family:var(--serif);font-size:14px;color:var(--ink)}
.dosslist li.sig::before{content:"\2756";color:var(--gold);font-size:10px}
.branches{grid-column:1/-1;margin-top:4px}
.branchgrid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:10px;margin-top:9px}
.branchcard{text-align:left;background:var(--page-3);border:1px solid var(--rule);border-radius:var(--r);
  border-top:2px solid var(--kc);padding:11px 13px;transition:.18s;display:flex;flex-direction:column;gap:6px}
.branchcard:hover{transform:translateY(-2px);box-shadow:var(--shadow);border-color:var(--kc)}
.branchcard .bname{font-family:var(--serif);font-size:16px;color:var(--ink)}
.branchcard .bmeta{font-family:var(--mono);font-size:10px;letter-spacing:.06em;color:var(--ink-3)}
.branchcard .bactive{font-family:var(--serif);font-style:italic;font-size:12.5px;color:var(--kc)}

/* ============ filters + legend ========================================== */
.filters{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-bottom:12px}
.fgroup{display:flex;gap:4px;flex-wrap:wrap;align-items:center}
.fgroup .lbl{font-family:var(--mono);font-size:10.5px;letter-spacing:.12em;text-transform:uppercase;
  color:var(--ink-3);margin-right:2px}
.fchip{padding:4px 10px;border:1px solid var(--rule);border-radius:2px;background:var(--page-3);
  color:var(--ink-2);font-size:12.5px;transition:.15s;display:flex;align-items:center;gap:6px}
.fchip:hover{border-color:var(--kc);color:var(--ink)}
.fchip[aria-pressed="true"]{background:color-mix(in srgb,var(--kc) 18%,var(--page-3));
  border-color:var(--kc);color:var(--ink);font-weight:600}
.fchip .ct{font-family:var(--mono);font-size:10.5px;color:var(--ink-3)}
.glyph{width:13px;height:13px;flex:0 0 auto}
input[type=search]{flex:1 1 220px;min-width:0;padding:7px 12px;border:1px solid var(--rule);
  border-radius:2px;background:var(--page-3);color:var(--ink);font:inherit;font-size:13.5px}
input[type=search]:focus{outline:none;border-color:var(--gold)}

/* ============ the chart ================================================= */
.stage{display:grid;grid-template-columns:minmax(0,1fr) 320px;gap:16px}
.chart{position:relative;background:var(--page-2);border:1px solid var(--rule);border-radius:var(--r);
  overflow:hidden;box-shadow:var(--shadow)}
.chart::before{content:"";position:absolute;inset:7px;border:1px solid var(--rule-soft);
  pointer-events:none;z-index:2;border-radius:2px}
svg.map{display:block;width:100%;height:min(78vh,820px);cursor:grab;touch-action:none}
svg.map.drag{cursor:grabbing}
.lane-band{fill:color-mix(in srgb,var(--kc) 9%,transparent);stroke:var(--rule-soft);stroke-width:1}
.lane-rule{stroke:var(--rule-soft);stroke-width:1}
.lane-label{font-family:var(--serif);font-size:19px;fill:var(--ink);letter-spacing:.06em}
.tier-label{font-family:var(--mono);font-size:13px;fill:var(--ink-3);letter-spacing:.12em}
.edge{stroke:color-mix(in srgb,var(--kc) 30%,var(--rule));stroke-width:2;fill:none}
.edge.hot{stroke:var(--kc);stroke-width:2.2}
.edge.mute{opacity:.2}
.node{cursor:pointer}
.node .shape{fill:color-mix(in srgb,var(--kc) 12%,var(--page-3));
  stroke:color-mix(in srgb,var(--kc) 62%,var(--ink-3));stroke-width:2.2;
  transition:fill .18s,stroke .18s}
.node:hover .shape{fill:color-mix(in srgb,var(--kc) 34%,var(--page-3));stroke:var(--kc)}
.node.sel .shape{fill:var(--kc);stroke:var(--ink)}
.node.sealed .shape{stroke:var(--gold);stroke-width:2.2}
.node .ring{fill:none;stroke:var(--gold);opacity:.7;stroke-width:1.6}
.node .nm{font-family:var(--sans);font-size:12px;letter-spacing:-.1px;fill:var(--ink-2);pointer-events:none;
  opacity:0;transition:opacity .15s}
/* all 79 labels drawn at once collide into mush, so they appear on demand: on hover,
   on the selected node and its path, on sealed capstones, and everywhere once you
   have zoomed in far enough to have room for them */
.node:hover .nm,.node.sel .nm,.node.kin-path .nm,.node.sealed .nm{opacity:1;fill:var(--ink);font-weight:600}
/* a hovered or selected label must win over its neighbours rather than tangle with them */
.node:hover,.node.sel{isolation:isolate}
.node:hover .nm,.node.sel .nm{stroke-width:4.5}
svg.map.close .node .nm{opacity:1}
.node .nm{stroke:var(--page-2);stroke-width:3;paint-order:stroke fill}
.node:hover .shape{filter:drop-shadow(0 0 6px color-mix(in srgb,var(--kc) 60%,transparent))}
.node.sel .shape{stroke-width:2.6;filter:drop-shadow(0 0 9px color-mix(in srgb,var(--kc) 70%,transparent))}
.node.sel .pulse{fill:none;stroke:var(--kc);opacity:.6;animation:beat 1.9s ease-out infinite}
@keyframes beat{0%{r:14px;opacity:.6}70%{r:27px;opacity:0}100%{r:27px;opacity:0}}
.node.kin-path .shape{stroke:var(--kc);stroke-width:2.1}
.node.mute{opacity:.14}
.node.mute .nm{display:none}
.tip{position:absolute;z-index:6;pointer-events:none;max-width:252px;padding:8px 11px;
  background:var(--page-3);border:1px solid var(--rule);border-left:3px solid var(--kc);
  border-radius:2px;box-shadow:var(--shadow);opacity:0;transform:translateY(3px);transition:.12s}
.tip.on{opacity:1;transform:none}
.tip b{display:block;font-family:var(--serif);font-size:14.5px;line-height:1.25}
.tip .meta{font-family:var(--mono);font-size:10px;letter-spacing:.08em;text-transform:uppercase;
  color:var(--ink-3);margin:2px 0 4px}
.tip .e{display:block;font-size:12px;color:var(--ink-2);line-height:1.42}
.pop{opacity:0;animation:pop .4s cubic-bezier(.2,.8,.3,1) forwards}
@keyframes pop{from{opacity:0;transform:scale(.7)}to{opacity:1;transform:scale(1)}}
@keyframes ink{to{stroke-dashoffset:0}}
.inked{stroke-dasharray:var(--len);stroke-dashoffset:var(--len);animation:ink .8s ease forwards}
.tools{position:absolute;right:14px;bottom:14px;display:flex;gap:5px;z-index:3}
.tools button{width:32px;height:32px;border:1px solid var(--rule);border-radius:2px;background:var(--page-3);
  color:var(--ink-2);font-size:15px;line-height:1}
.tools button:hover{color:var(--ink);border-color:var(--gold)}
.mapnote{position:absolute;left:16px;bottom:14px;z-index:3;font-family:var(--mono);font-size:10.5px;
  color:var(--ink-3);letter-spacing:.06em;pointer-events:none}

/* ============ inspector ================================================= */
.folio{background:var(--page-3);border:1px solid var(--rule);border-radius:var(--r);padding:18px 18px 20px;
  align-self:start;position:sticky;top:104px;max-height:min(78vh,820px);overflow:auto;box-shadow:var(--shadow)}
.folio .eyebrow{font-family:var(--mono);font-size:10.5px;letter-spacing:.14em;text-transform:uppercase;
  color:var(--ink-3)}
.folio h3{font-size:22px;margin:5px 0 3px}
.folio .flavor{font-family:var(--serif);font-style:italic;color:var(--ink-2);margin:0 0 14px;
  padding-left:11px;border-left:2px solid var(--kc)}
.facts{display:flex;flex-wrap:wrap;gap:5px;margin-bottom:6px}
.fact{font-family:var(--mono);font-size:11px;padding:3px 8px;border:1px solid var(--rule);border-radius:2px;
  color:var(--ink-2)}
.fact.gold{border-color:var(--gold);color:var(--gold)}
.fact.blood{border-color:var(--oxblood);color:var(--oxblood)}
.sec{font-family:var(--mono);font-size:10.5px;letter-spacing:.14em;text-transform:uppercase;color:var(--ink-3);
  margin:17px 0 8px;padding-top:13px;border-top:1px solid var(--rule-soft)}
.eff{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:8px}
.eff li{display:flex;gap:9px;align-items:flex-start;font-size:13.5px;color:var(--ink)}
.eff svg{flex:0 0 auto;margin-top:3px}
.eff .lnk{cursor:pointer;text-decoration:underline;text-decoration-color:var(--rule);
  text-underline-offset:3px}
.eff .lnk:hover{text-decoration-color:var(--kc)}

/* ============ codex ===================================================== */
.scroll{overflow-x:auto;border:1px solid var(--rule);border-radius:var(--r);background:var(--page-3)}
table{border-collapse:collapse;width:100%;min-width:720px}
th,td{text-align:left;padding:9px 14px;border-bottom:1px solid var(--rule-soft);vertical-align:top}
th{font-family:var(--mono);font-size:10.5px;letter-spacing:.1em;text-transform:uppercase;color:var(--ink-3);
  position:sticky;top:0;background:var(--page-2);z-index:1;border-bottom:1px solid var(--rule)}
tbody tr{transition:background .12s;cursor:pointer}
tbody tr:hover{background:color-mix(in srgb,var(--kc) 10%,transparent)}
td .nm{font-family:var(--serif);font-size:15px;font-weight:600}
td .fl{color:var(--ink-3);font-family:var(--serif);font-style:italic;font-size:12.5px}
td .ef{color:var(--ink-2);font-size:12.5px;display:block}

/* ============ compare =================================================== */
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(258px,1fr));gap:14px}
.card{background:var(--page-3);border:1px solid var(--rule);border-top:3px solid var(--kc);
  border-radius:var(--r);padding:16px 17px;transition:.2s;cursor:pointer}
.card:hover{transform:translateY(-3px);box-shadow:var(--shadow)}
.card h3{font-size:19px}
.card .ep{font-family:var(--serif);font-style:italic;color:var(--kc);font-size:13px;margin-bottom:8px}
.card p{color:var(--ink-2);font-size:13px;margin:0 0 12px}
.metric{display:flex;align-items:center;gap:9px;margin:6px 0;font-size:12.5px}
.metric .lab{width:70px;flex:0 0 auto;color:var(--ink-3);font-family:var(--mono);font-size:10px;
  letter-spacing:.07em;text-transform:uppercase}
.metric .track{flex:1;height:5px;background:var(--rule-soft);border-radius:2px;overflow:hidden}
.metric .fill{height:100%;background:var(--kc);width:0;transition:width .85s cubic-bezier(.2,.8,.3,1)}
.metric .val{width:34px;text-align:right;font-family:var(--mono);font-size:11.5px}

/* ============ rules ===================================================== */
.prose{max-width:72ch}
.prose h2{font-size:27px;margin:32px 0 4px}
.prose .kicker{font-family:var(--mono);font-size:10.5px;letter-spacing:.16em;text-transform:uppercase;
  color:var(--gold);margin-bottom:6px}
.prose p{color:var(--ink-2)}
.grid2{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:11px;margin:16px 0}
.tile{background:var(--page-3);border:1px solid var(--rule);border-radius:var(--r);padding:13px 15px}
.tile .k{font-family:var(--mono);font-size:10.5px;letter-spacing:.12em;text-transform:uppercase;color:var(--ink-3)}
.tile .v{font-family:var(--serif);font-size:25px;color:var(--ink);line-height:1.2;margin-top:2px}
.tile .d{font-size:12.5px;color:var(--ink-2);margin-top:5px}
.deedlist{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:11px;margin-top:12px}
.deed{background:var(--page-3);border:1px solid var(--rule);border-left:3px solid var(--gold);
  border-radius:var(--r);padding:12px 14px}
.deed b{display:block;font-family:var(--serif);font-size:16px;margin-bottom:3px}
.deed span{font-size:12.5px;color:var(--ink-2)}
.warnbox{border:1px solid color-mix(in srgb,var(--oxblood) 45%,var(--rule));
  background:color-mix(in srgb,var(--oxblood) 9%,var(--page-3));border-left:3px solid var(--oxblood);
  border-radius:var(--r);padding:15px 17px;margin:16px 0}
.warnbox b{color:var(--oxblood)}

footer{margin-top:44px;padding-top:16px;border-top:1px solid var(--rule);color:var(--ink-3);
  font-size:12.5px;display:flex;gap:16px;flex-wrap:wrap;justify-content:space-between}

@media (max-width:1040px){
  .stage{grid-template-columns:1fr}
  .folio{position:static;max-height:none}
  svg.map{height:56vh}
  .illum{grid-template-columns:auto 1fr;gap:14px}
  .ledger{grid-column:1/-1;text-align:left;grid-template-columns:repeat(4,1fr)}
}
@media (max-width:560px){
  .wrap{padding:0 14px 46px}
  .dropcap{width:56px;height:56px}.dropcap span{font-size:32px}
  .tabs{width:100%}.tab{flex:1;text-align:center;padding:8px 4px;font-size:13px}
  .ledger{grid-template-columns:repeat(2,1fr);gap:8px}
}
@media (prefers-reduced-motion:reduce){
  *{animation-duration:.001ms !important;transition-duration:.001ms !important}
}
</style>

<div class="wrap">
  <header class="masthead">
    <div class="rule-orn"><span class="runes">ᚦᚱᛁ</span></div>
    <h1 id="bookTitle">The Book of Kindreds</h1>
    <p class="sub" id="sub"></p>
    <div class="strip" id="strip"></div>
  </header>

  <div class="sticky">
    <nav class="kinrow" id="kinrow" aria-label="Choose a kindred"></nav>
    <div class="viewrow">
      <div class="tabs" role="tablist" id="tabs"></div>
      <div class="mini">
        <button class="chip" id="langBtn" aria-pressed="false">EN · РУ</button>
        <button class="chip" id="themeBtn" title="Light / dark">☾</button>
      </div>
    </div>
  </div>

  <main id="view"></main>

  <footer><span id="footL"></span><span id="footR"></span></footer>
</div>

<script>
const DATA = @@DATA@@;
const DOSSIER = @@DOSSIER@@;
const BRANCHES = @@BRANCHES@@;
const S = {race:'elf', view:'map', lang:'en', node:null, q:'', disc:'all', kind:'all'};
const $ = s => document.querySelector(s);
const el = (t,c,txt)=>{const e=document.createElement(t); if(c)e.className=c; if(txt!=null)e.textContent=txt; return e;};
const NS='http://www.w3.org/2000/svg';
const svgEl=(t,attrs)=>{const e=document.createElementNS(NS,t); for(const k in (attrs||{})) e.setAttribute(k,attrs[k]); return e;};
const reduced = matchMedia('(prefers-reduced-motion:reduce)').matches;
const ROMAN=['','I','II','III','IV','V','VI','VII','VIII'];

/* epithets: what the book calls each people */
const EPITHET={
  elf:['The Firstborn','Перворождённые'], dwarf:['Durin’s Folk','Народ Дурина'],
  human:['The Second-born','Пришедшие следом'], hobbit:['The Little Folk','Малый народ'],
  uruk:['The Fighting Uruk-hai','Бойцы Урук-хай'], orc:['Servants of the Eye','Слуги Ока'],
  snaga:['The Driven','Погоняемые'], goblin:['Folk of Goblin-town','Народ Гоблин-тауна']
};
const T={
 en:{title:'The Book of Kindreds',themeHint:'Light / dark',
   map:'The Chart',codex:'Codex',compare:'Kindreds',rules:'Lore of the Law',
   sub:'Eight peoples, six hundred and three skills, drawn from the mod itself',
   nodes:'skills',points:'points',effects:'effects',actives:'abilities',lanes:'branches',
   tier:'Tier',pts:'pts',needs:'Reached through',excl:'One of a choice',sealed:'Sealed by a deed',
   what:'What it does',pick:'Choose a skill',pickBody:'Every skill in this book does something the game actually reads. Pick one from the chart.',
   search:'Search skills, flavour, effects…',all:'All',branch:'Branch',kind:'Kind',
   kStat:'Stats',kPerk:'Perks',kAct:'Abilities',kCtx:'Conditional',kSeal:'Sealed',
   name:'Skill',lane:'Branch',showing:'showing',
   capT:'The Point Cap',renownT:'Great Deeds',bargainT:'The Bargain',ranksT:'Ranks',diffT:'The Four Roads',
   inLore:'In the lore',inPlay:'In play',bornWith:'Born with',strengths:'Strengths',weaknesses:'Weaknesses',
   gAlways:'Always',gCtx:'Wakes with place and hour',gBurden:'Burdens',
   signature:'Signature abilities',branchesT:'What each branch opens',
   opens:'opens',skills:'skills',sealedN:'sealed',more:'Read the dossier',less:'Close the dossier'},
 ru:{title:'Книга народов',themeHint:'Светлая / тёмная',
   map:'Хартия',codex:'Кодекс',compare:'Народы',rules:'Свод правил',
   sub:'Восемь народов, шестьсот три умения — прямо из файлов мода',
   nodes:'умений',points:'очков',effects:'эффектов',actives:'умений',lanes:'ветвей',
   tier:'Ступень',pts:'оч.',needs:'Открывается через',excl:'Один из выбора',sealed:'Запечатано подвигом',
   what:'Что даёт',pick:'Выберите умение',pickBody:'Каждое умение здесь делает то, что игра действительно читает. Выберите узел на хартии.',
   search:'Поиск по названию, описанию, эффектам…',all:'Все',branch:'Ветвь',kind:'Тип',
   kStat:'Характеристики',kPerk:'Перки',kAct:'Умения',kCtx:'Условные',kSeal:'Запечатанные',
   name:'Умение',lane:'Ветвь',showing:'показано',
   capT:'Предел очков',renownT:'Великие деяния',bargainT:'Сделка',ranksT:'Ранги',diffT:'Четыре дороги',
   inLore:'В предании',inPlay:'В игре',bornWith:'Дано от рождения',strengths:'Сильные стороны',
   gAlways:'Всегда',gCtx:'Пробуждается местом и часом',gBurden:'Бремя',
   weaknesses:'Слабые стороны',signature:'Знаковые умения',branchesT:'Что открывает каждая ветвь',
   opens:'открывает',skills:'умений',sealedN:'запечатано',more:'Открыть досье',less:'Закрыть досье'}
};
const t=k=>T[S.lang][k];
const race=()=>DATA.races[S.race];
const nName=n=>(S.lang==='ru'&&n.nr)?n.nr:n.n;
const nFlav=n=>(S.lang==='ru'&&n.fr)?n.fr:n.f;
const nEff =n=>(S.lang==='ru'&&n.er&&n.er.length)?n.er:n.e;
const discName=d=>DATA.disc[S.lang][d]||d;

/* a node's kind decides its shape - the chart's own legend */
function kindOf(n){
  if(n.k.includes('active')) return 'act';
  if(n.k.includes('contextual_boon')) return 'ctx';
  if(n.k.includes('perk')) return 'perk';
  return 'stat';
}
function glyphPath(kind,r){
  if(kind==='act')  return 'M0,'+(-r)+' L'+(r*.36)+','+(-r*.36)+' L'+r+',0 L'+(r*.36)+','+(r*.36)+
                            ' L0,'+r+' L'+(-r*.36)+','+(r*.36)+' L'+(-r)+',0 L'+(-r*.36)+','+(-r*.36)+' Z';
  if(kind==='perk') return 'M0,'+(-r)+' L'+r+',0 L0,'+r+' L'+(-r)+',0 Z';
  if(kind==='ctx'){ const a=r*.87; return 'M'+(-a)+','+(-r/2)+' L0,'+(-r)+' L'+a+','+(-r/2)+
                            ' L'+a+','+(r/2)+' L0,'+r+' L'+(-a)+','+(r/2)+' Z'; }
  return null; // stat = circle
}
function legendGlyph(kind){
  const s=svgEl('svg',{class:'glyph',viewBox:'-8 -8 16 16'});
  const p=glyphPath(kind,6.4);
  const sh = p? svgEl('path',{d:p}) : svgEl('circle',{r:6});
  sh.setAttribute('fill','none'); sh.setAttribute('stroke','currentColor'); sh.setAttribute('stroke-width','1.6');
  s.append(sh); return s;
}

/* ---------------- chrome ---------------- */
function paint(){
  const r=race();
  document.documentElement.style.setProperty('--kc', r.accent);
  document.documentElement.style.setProperty('--accent', r.accent);
}
function buildKin(){
  const box=$('#kinrow'); box.innerHTML='';
  DATA.order.forEach(id=>{
    const r=DATA.races[id];
    const b=el('button','kin'); b.style.setProperty('--kc', r.accent);
    b.setAttribute('aria-pressed', String(id===S.race));
    const sig=el('span','sig',(S.lang==='ru'?r.nameRu:r.name).slice(0,1));
    b.append(sig, el('span',null,S.lang==='ru'?r.nameRu:r.name));
    b.onclick=()=>{S.race=id;S.node=null;S.q='';S.disc='all';S.kind='all';paint();buildKin();render();};
    box.append(b);
  });
}
function buildTabs(){
  const box=$('#tabs'); box.innerHTML='';
  [['map',t('map')],['codex',t('codex')],['compare',t('compare')],['rules',t('rules')]].forEach(([id,l])=>{
    const b=el('button','tab',l); b.setAttribute('role','tab'); b.setAttribute('aria-selected',String(id===S.view));
    b.onclick=()=>{S.view=id;buildTabs();render();}; box.append(b);
  });
}
function buildStrip(){
  const tot=DATA.order.reduce((a,id)=>{const r=DATA.races[id];a.n+=r.nodes.length;a.e+=r.effects;return a;},{n:0,e:0});
  $('#bookTitle').textContent=t('title');
  $('#themeBtn').title=t('themeHint');
  $('#sub').textContent=t('sub');
  $('#strip').innerHTML='<span><b>8</b> '+(S.lang==='ru'?'народов':'kindreds')+'</span>'+
    '<span><b>'+tot.n+'</b> '+t('nodes')+'</span><span><b>'+tot.e+'</b> '+t('effects')+'</span>'+
    '<span><b>12</b> '+t('lanes')+'</span>';
  $('#footL').textContent=S.lang==='ru'?'Kindreds of Middle-earth · мод Fabric для Minecraft 1.21.8'
    :'Kindreds of Middle-earth · a Fabric mod for Minecraft 1.21.8';
  $('#footR').textContent=S.lang==='ru'?'Каждое число прочитано из файлов мода'
    :'Every number here is read out of the mod’s own files';
}

/* ---------------- illuminated header ---------------- */
function illum(root){
  const r=race(), ru=S.lang==='ru';
  const box=el('div','illum');
  const cap=el('div','dropcap'); cap.append(el('span',null,(ru?r.nameRu:r.name).slice(0,1)));
  const mid=el('div');
  mid.append(el('h2',null,ru?r.nameRu:r.name));
  mid.append(el('div','epithet',EPITHET[S.race][ru?1:0]));
  mid.append(el('p',null,ru?r.blurbRu:r.blurb));
  const led=el('div','ledger');
  [[r.nodes.length,t('nodes')],[r.points,t('points')],[r.actives,t('actives')],[r.disciplines.length,t('lanes')]]
    .forEach(([v,l])=>{const d=el('div'); d.append(el('b','tnum',String(v))); d.append(document.createTextNode(l)); led.append(d);});
  box.append(cap,mid,led); root.append(box);
  dossier(root);
}

/* ---------------- the dossier: lore, play, and what the branches open ------- */
function dossier(root){
  const r=race(), L=S.lang, d=DOSSIER[S.race], br=BRANCHES[S.race]||[];
  if(!d) return;
  const wrap=el('section','dossier');

  const toggle=el('button','dosstoggle');
  const setLabel=()=>toggle.textContent=(wrap.classList.contains('open')?t('less'):t('more'));
  toggle.onclick=()=>{ wrap.classList.toggle('open'); setLabel();
    toggle.setAttribute('aria-expanded',String(wrap.classList.contains('open'))); };
  toggle.setAttribute('aria-expanded','false'); setLabel();

  const body=el('div','dossbody');

  const col=(head,build)=>{const c=el('div','dosscol'); c.append(el('div','dosshead',head)); build(c); return c;};
  const para=(txt)=>{const p=el('p','dosstext',txt); return p;};

  body.append(col(t('inLore'), c=>c.append(para(d.lore[L]))));
  body.append(col(t('inPlay'), c=>c.append(para(d.play[L]))));
  body.append(col(t('bornWith'), c=>{
    // Three groups, as the traits page in the game shows them: what is always true of the body,
    // what only wakes in some place or hour, and what is carried as a burden. An empty group is
    // dropped rather than shown as a bare heading - most kindreds have no innate burden at all.
    [['always','gAlways'],['ctx','gCtx'],['burden','gBurden']].forEach(g=>{
      const lines=(d.born[L]||{})[g[0]]||[];
      if(!lines.length) return;
      c.append(el('div','dosssub',t(g[1])));
      const ul=el('ul','dosslist');
      lines.forEach(line=>{
        const bad = g[0]==='burden' || line.charAt(0)==='✖';
        const li=el('li', bad?'bad':(line.charAt(0)==='✦'?'good':''));
        li.textContent=line.replace(/^[✦✖]\s*/,'');
        ul.append(li);
      });
      c.append(ul);
    });
  }));

  const pros=col(t('strengths'), c=>{
    const ul=el('ul','dosslist');
    d.strong[L].forEach(x=>{const li=el('li','good'); li.textContent=x; ul.append(li);});
    c.append(ul);
  });
  const cons=col(t('weaknesses'), c=>{
    const ul=el('ul','dosslist');
    d.weak[L].forEach(x=>{const li=el('li','bad'); li.textContent=x; ul.append(li);});
    c.append(ul);
  });
  body.append(pros,cons);
  body.append(col(t('signature'), c=>{
    const ul=el('ul','dosslist');
    d.sig[L].forEach(x=>{const li=el('li','sig'); li.textContent=x; ul.append(li);});
    c.append(ul);
  }));

  const branchBox=el('div','branches');
  branchBox.append(el('div','dosshead',t('branchesT')));
  const grid=el('div','branchgrid');
  br.forEach(b=>{
    const card=el('button','branchcard');
    card.append(el('div','bname',b.name[L]));
    card.append(el('div','bmeta', b.nodes+' '+t('skills')+' · '+b.points+' '+t('pts')+
      (b.sealed?' · '+b.sealed+' '+t('sealedN'):'')));
    const acts=(b.actives&&b.actives[L])||[];
    if(acts.length){
      const a=el('div','bactive'); a.textContent=acts.join(' · '); card.append(a);
    }
    const ul=el('ul','dosslist');
    b.gives[L].forEach(g=>{const li=el('li'); li.textContent=g; ul.append(li);});
    b.ctx[L].forEach(cx=>{const li=el('li','good'); li.textContent=cx; ul.append(li);});
    card.append(ul);
    card.onclick=()=>{ S.disc=b.id; S.view='map'; buildTabs(); render(); };
    grid.append(card);
  });
  branchBox.append(grid);
  body.append(branchBox);

  wrap.append(toggle, body); root.append(wrap);
}

/* ---------------- filters ---------------- */
function filters(root, counts, onChange){
  const r=race();
  const bar=el('div','filters');
  const search=el('input'); search.type='search'; search.placeholder=t('search'); search.value=S.q;
  search.oninput=e=>{S.q=e.target.value; onChange();};
  bar.append(search);

  const g1=el('div','fgroup'); g1.append(el('span','lbl',t('branch')));
  const mk=(label,active,fn,count,glyph)=>{
    const b=el('button','fchip'); b.setAttribute('aria-pressed',String(active));
    if(glyph) b.append(glyph);
    b.append(el('span',null,label));
    if(count!=null) b.append(el('span','ct',String(count)));
    b.onclick=fn; return b;
  };
  g1.append(mk(t('all'), S.disc==='all', ()=>{S.disc='all';render();}, r.nodes.length));
  r.disciplines.forEach(d=>{
    const c=r.nodes.filter(n=>n.d===d).length;
    g1.append(mk(discName(d), S.disc===d, ()=>{S.disc=d;render();}, c));
  });
  bar.append(g1);

  const g2=el('div','fgroup'); g2.append(el('span','lbl',t('kind')));
  g2.append(mk(t('all'), S.kind==='all', ()=>{S.kind='all';render();}, null));
  [['stat',t('kStat')],['perk',t('kPerk')],['act',t('kAct')],['ctx',t('kCtx')]].forEach(([k,l])=>{
    const c=r.nodes.filter(n=>kindOf(n)===k).length;
    g2.append(mk(l, S.kind===k, ()=>{S.kind=k;render();}, c, legendGlyph(k)));
  });
  const sc=r.nodes.filter(n=>n.deed).length;
  g2.append(mk(t('kSeal'), S.kind==='sealed', ()=>{S.kind='sealed';render();}, sc));
  bar.append(g2);
  root.append(bar);
}
function matches(n){
  if(S.disc!=='all' && n.d!==S.disc) return false;
  if(S.kind==='sealed'){ if(!n.deed) return false; }
  else if(S.kind!=='all' && kindOf(n)!==S.kind) return false;
  const q=S.q.trim().toLowerCase();
  if(q && !(nName(n).toLowerCase().includes(q) || (nFlav(n)||'').toLowerCase().includes(q)
        || nEff(n).join(' ').toLowerCase().includes(q))) return false;
  return true;
}

/* ---------------- the chart ---------------- */
/* A branch must be wide enough for its fullest row - four nodes at SPREAD apart plus
   a node's own width - or neighbouring branches interleave and the bands stop meaning
   anything. 3x68 + 2x14 = 232 inside a 250 gap leaves 18px of clearance. TIER_H is then
   set so the chart's aspect lands near its box's, which is what ends the letterboxing. */
const LANE_GAP=250, TIER_H=125, SPREAD=68;
function layout(r){
  const lanes=r.disciplines, pos={}, bucket={};
  r.nodes.forEach(n=>{(bucket[n.d+'|'+n.t] ||= []).push(n);});
  Object.values(bucket).forEach(l=>l.sort((a,b)=>a.i.localeCompare(b.i)));
  r.nodes.forEach(n=>{
    const li=lanes.indexOf(n.d), list=bucket[n.d+'|'+n.t], k=list.indexOf(n);
    pos[n.i]={x: li*LANE_GAP + (k-(list.length-1)/2)*SPREAD, y: n.t*TIER_H};
  });
  return {pos,lanes};
}
function renderMap(root){
  const r=race(), {pos,lanes}=layout(r);
  illum(root);
  filters(root, null, ()=>applyFilter(svg));

  const stage=el('div','stage');
  const chart=el('div','chart');
  const xs=Object.values(pos).map(p=>p.x), ys=Object.values(pos).map(p=>p.y);
  const minX=Math.min(...xs)-165, maxX=Math.max(...xs)+120;
  const minY=Math.min(...ys)-96, maxY=Math.max(...ys)+80;
  const svg=svgEl('svg',{class:'map',viewBox:[minX,minY,maxX-minX,maxY-minY].join(' ')});
  const g=svgEl('g'); svg.append(g);

  const tiers=[...new Set(r.nodes.map(n=>n.t))].sort((a,b)=>a-b);
  tiers.forEach(ti=>{
    g.append(svgEl('line',{class:'lane-rule',x1:minX+150,y1:ti*TIER_H,x2:maxX-20,y2:ti*TIER_H,opacity:.5}));
    const lab=svgEl('text',{class:'tier-label',x:minX+18,y:ti*TIER_H+4});
    lab.textContent=(S.lang==='ru'?'СТУПЕНЬ ':'TIER ')+ROMAN[ti+1]; g.append(lab);
  });
  lanes.forEach((d,i)=>{
    const lx=i*LANE_GAP;
    g.append(svgEl('rect',{class:'lane-band',x:lx-LANE_GAP*0.44,y:minY+34,
      width:LANE_GAP*0.88,height:maxY-minY-34,rx:4}));
    const lab=svgEl('text',{class:'lane-label',x:lx,y:minY+22,'text-anchor':'middle'});
    lab.textContent=discName(d); g.append(lab);
  });

  const byId=Object.fromEntries(r.nodes.map(n=>[n.i,n]));
  let ei=0;
  r.nodes.forEach(n=>n.p.forEach(pr=>{
    if(!byId[pr]) return;
    const a=pos[pr], b=pos[n.i], mid=(a.y+b.y)/2;
    const path=svgEl('path',{class:'edge'+(reduced?'':' inked'),
      d:'M'+a.x+','+a.y+' C'+a.x+','+mid+' '+b.x+','+mid+' '+b.x+','+b.y});
    path.dataset.from=pr; path.dataset.to=n.i;
    path.style.setProperty('--len', Math.hypot(b.x-a.x,b.y-a.y)+90);
    path.style.animationDelay=(ei++%36)*10+'ms';
    g.append(path);
  }));

  r.nodes.forEach((n,idx)=>{
    const p=pos[n.i], kind=kindOf(n), rad=n.deed?18:14;
    /* outer <g> holds the position; inner <g> is animated, so the keyframe's
       transform can never overwrite the translate (that bug ate the first draft) */
    const outer=svgEl('g',{transform:'translate('+p.x+','+p.y+')'});
    const inner=svgEl('g',{class:'node'+(n.deed?' sealed':'')+(reduced?'':' pop'),
      tabindex:'0',role:'button','aria-label':nName(n)});
    inner.style.animationDelay=(n.t*45+(idx%6)*14)+'ms';
    inner.dataset.id=n.i;
    if(n.deed) inner.append(svgEl('circle',{class:'ring',r:rad+5,'stroke-width':1}));
    const path=glyphPath(kind,rad);
    const shape = path ? svgEl('path',{class:'shape',d:path}) : svgEl('circle',{class:'shape',r:rad});
    inner.append(shape);
    const label=svgEl('text',{class:'nm','text-anchor':'middle',y:rad+15});
    labelLines(nName(n)).forEach((line,li)=>{
      const ts=svgEl('tspan',{x:0,dy:li?12:0}); ts.textContent=line; label.append(ts);
    });
    inner.append(label);
    inner.onclick=()=>select(n.i,svg);
    inner.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();select(n.i,svg);}};
    inner.onpointerenter=e=>{ outer.parentNode.appendChild(outer); showTip(n,e); };
    inner.onpointermove=e=>moveTip(e);
    inner.onpointerleave=hideTip;
    inner.onfocus=()=>{const b=inner.getBoundingClientRect();
      showTip(n,{clientX:b.left+b.width/2,clientY:b.top});};
    inner.onblur=hideTip;
    outer.append(inner); g.append(outer);
  });

  chart.append(svg);
  const tip=el('div','tip'); tip.id='tip'; chart.append(tip);
  const note=el('div','mapnote',S.lang==='ru'?'колесо — масштаб · тяните — сдвиг':'scroll to zoom · drag to pan');
  const tools=el('div','tools');
  [['+',()=>zoom(1.25)],['−',()=>zoom(.8)],['⟲',()=>reset()]].forEach(([s,fn])=>{
    const b=el('button',null,s); b.onclick=fn; tools.append(b);
  });
  chart.append(note,tools);

  const folio=el('aside','folio'); folio.id='folio';
  stage.append(chart,folio); root.append(stage);
  drawFolio();
  applyFilter(svg);
  if(S.node) select(S.node,svg);

  /* The chart is wider than it is tall; its box is nearly square. Left alone, SVG's
     default "meet" scaling shrank everything to ~40% and floated it in dead space.
     Grow the SHORT side of the viewBox to the box's own aspect, so the content fills
     one axis exactly and sits centred in the other. */
  let vb={x:minX,y:minY,w:maxX-minX,h:maxY-minY};
  function fit(){
    const b=svg.getBoundingClientRect(); if(!b.width||!b.height) return;
    const boxAR=b.width/b.height, cw=maxX-minX, ch=maxY-minY;
    let w=cw,h=ch;
    if(cw/ch>boxAR) h=cw/boxAR; else w=ch*boxAR;
    vb={x:minX-(w-cw)/2, y:minY-(h-ch)/2, w, h};
  }
  fit();
  const base={...vb};
  const apply=()=>{
    svg.setAttribute('viewBox',[vb.x,vb.y,vb.w,vb.h].join(' '));
    svg.classList.toggle('close', vb.w < base.w*0.62);  // zoomed in far enough to name everything
  };
  function zoom(f,cx,cy){
    const nw=Math.min(base.w*2.4,Math.max(base.w*0.16,vb.w/f)), nh=nw*(vb.h/vb.w);
    vb.x+=(vb.w-nw)*(cx==null?.5:cx); vb.y+=(vb.h-nh)*(cy==null?.5:cy); vb.w=nw; vb.h=nh; apply();
  }
  function reset(){vb={...base};apply();}
  addEventListener('resize',()=>{ fit(); Object.assign(base,vb); apply(); });
  svg.addEventListener('wheel',e=>{e.preventDefault();
    const b=svg.getBoundingClientRect();
    zoom(e.deltaY<0?1.13:1/1.13,(e.clientX-b.left)/b.width,(e.clientY-b.top)/b.height);},{passive:false});
  let drag=null;
  svg.addEventListener('pointerdown',e=>{drag={x:e.clientX,y:e.clientY,vx:vb.x,vy:vb.y};
    svg.classList.add('drag'); svg.setPointerCapture(e.pointerId);});
  svg.addEventListener('pointermove',e=>{ if(!drag)return;
    const b=svg.getBoundingClientRect();
    vb.x=drag.vx-(e.clientX-drag.x)*(vb.w/b.width); vb.y=drag.vy-(e.clientY-drag.y)*(vb.h/b.height); apply();});
  const stop=()=>{drag=null;svg.classList.remove('drag');};
  svg.addEventListener('pointerup',stop); svg.addEventListener('pointercancel',stop);
}
/* A column is SPREAD units wide; a name set at 12px overruns it after roughly a dozen
   characters, and once every label is shown they collide. Wrap to two short lines and
   clip the rest - the full name is always a hover (or the folio) away. */
const LABEL_CHARS = 12, LABEL_LINES = 2;
function labelLines(text){
  const words = String(text).split(/\s+/);
  const lines = []; let cur = '', overflow = false;
  for (const w of words){
    const cand = cur ? cur + ' ' + w : w;
    if (cand.length <= LABEL_CHARS){ cur = cand; continue; }
    if (cur) lines.push(cur);
    cur = w;
    if (lines.length === LABEL_LINES){ overflow = true; break; }
  }
  if (!overflow && cur && lines.length < LABEL_LINES) lines.push(cur);
  else if (cur && lines.length < LABEL_LINES) lines.push(cur);
  const out = lines.slice(0, LABEL_LINES).map(l =>
    l.length > LABEL_CHARS ? l.slice(0, LABEL_CHARS - 1) + '\u2026' : l);
  if ((overflow || lines.length > LABEL_LINES) && out.length){
    // the ellipsis has to fit INSIDE the budget, not be bolted on after the check
    const last = out[out.length - 1].replace(/\u2026$/, '');
    out[out.length - 1] = last.slice(0, LABEL_CHARS - 1) + '\u2026';
  }
  return out;
}

function showTip(n,e){
  const tip=document.getElementById('tip'); if(!tip) return;
  tip.innerHTML='';
  tip.append(el('b',null,nName(n)));
  tip.append(el('div','meta',discName(n.d)+' · '+t('tier')+' '+ROMAN[n.t+1]+' · '+n.c+' '+t('pts')));
  const eff=nEff(n);
  eff.slice(0,3).forEach(l=>tip.append(el('span','e',l)));
  if(eff.length>3) tip.append(el('span','e','+'+(eff.length-3)+' '+(S.lang==='ru'?'ещё':'more')));
  tip.classList.add('on'); moveTip(e);
}
function moveTip(e){
  const tip=document.getElementById('tip'); if(!tip||!tip.classList.contains('on')) return;
  const box=tip.parentElement.getBoundingClientRect();
  const x=Math.min(e.clientX-box.left+14, box.width-tip.offsetWidth-10);
  const y=Math.min(e.clientY-box.top+14, box.height-tip.offsetHeight-10);
  tip.style.left=Math.max(8,x)+'px'; tip.style.top=Math.max(8,y)+'px';
}
function hideTip(){const t=document.getElementById('tip'); if(t) t.classList.remove('on');}

function applyFilter(svg){
  if(!svg) return;
  const r=race(); const ok=new Set(r.nodes.filter(matches).map(n=>n.i));
  svg.querySelectorAll('.node').forEach(g=>g.classList.toggle('mute', !ok.has(g.dataset.id)));
  svg.querySelectorAll('.edge').forEach(e=>e.classList.toggle('mute', !(ok.has(e.dataset.to)&&ok.has(e.dataset.from))));
}
/* every node you must already own to reach this one - the trail the chart lights */
function ancestry(id){
  const r=race(), byId=Object.fromEntries(r.nodes.map(n=>[n.i,n]));
  const seen=new Set(), stack=[id];
  while(stack.length){
    const n=byId[stack.pop()]; if(!n) continue;
    n.p.forEach(x=>{ if(!seen.has(x)){ seen.add(x); stack.push(x); } });
  }
  return seen;
}
function select(id,svg){
  S.node=id;
  const trail = id ? ancestry(id) : new Set();
  if(svg){
    svg.querySelectorAll('.node').forEach(g=>{
      g.classList.toggle('sel', g.dataset.id===id);
      g.classList.toggle('kin-path', trail.has(g.dataset.id));
    });
    svg.querySelectorAll('.edge').forEach(e=>{
      const from=e.dataset.from, to=e.dataset.to;
      e.classList.toggle('hot', (to===id||trail.has(to)) && (from===id||trail.has(from)));
    });
    svg.querySelectorAll('.pulse').forEach(c=>c.remove());
    const sel=svg.querySelector('.node.sel');
    if(sel && !reduced) sel.insertBefore(svgEl('circle',{class:'pulse',r:14,'stroke-width':1.4}), sel.firstChild);
  }
  drawFolio();
  if(innerWidth<=1040){
    const f=document.getElementById('folio');
    if(f) f.scrollIntoView({behavior:reduced?'auto':'smooth',block:'nearest'});
  }
}
function drawFolio(){
  const box=document.getElementById('folio'); if(!box) return;
  const r=race(), n=r.nodes.find(x=>x.i===S.node);
  box.innerHTML='';
  if(!n){
    box.append(el('div','eyebrow',S.lang==='ru'?'Разбор':'Inspector'));
    box.append(el('h3',null,t('pick')));
    box.append(el('p','flavor',t('pickBody')));
    box.append(el('div','sec',t('kind')));
    const ul=el('ul','eff');
    [['stat',t('kStat')],['perk',t('kPerk')],['act',t('kAct')],['ctx',t('kCtx')]].forEach(([k,l])=>{
      const li=el('li'); li.append(legendGlyph(k), el('span',null,l)); ul.append(li);
    });
    box.append(ul);
    return;
  }
  box.append(el('div','eyebrow',discName(n.d)+' · '+t('tier')+' '+ROMAN[n.t+1]));
  box.append(el('h3',null,nName(n)));
  if(nFlav(n)) box.append(el('p','flavor',nFlav(n)));
  const f=el('div','facts');
  f.append(el('span','fact',n.c+' '+t('pts')));
  if(n.deed) f.append(el('span','fact gold',t('sealed')));
  if(n.x) f.append(el('span','fact blood',t('excl')));
  box.append(f);
  box.append(el('div','sec',t('what')));
  const ul=el('ul','eff');
  nEff(n).forEach(line=>{
    const li=el('li');
    const k = /^(Active|Умение)/.test(line) ? 'act'
            : /^(under|in|at|под|во|при|на)\b/i.test(line) ? 'ctx'
            : /[+\-]\d/.test(line) ? 'stat' : 'perk';
    li.append(legendGlyph(k), el('span',null,line)); ul.append(li);
  });
  box.append(ul);
  if(n.p.length){
    box.append(el('div','sec',t('needs')));
    const list=el('ul','eff');
    n.p.forEach(p=>{
      const pn=r.nodes.find(x=>x.i===p);
      const li=el('li'); const s=el('span','lnk',pn?nName(pn):p);
      s.onclick=()=>{S.node=p; render();};
      li.append(legendGlyph(pn?kindOf(pn):'stat'), s); list.append(li);
    });
    box.append(list);
  }
}

/* ---------------- codex ---------------- */
function renderCodex(root){
  illum(root);
  filters(root,null,()=>fill());
  const scroll=el('div','scroll'), table=el('table');
  table.innerHTML='<thead><tr><th>'+t('name')+'</th><th>'+t('lane')+'</th><th>'+t('tier')+
    '</th><th>'+t('pts')+'</th><th>'+t('what')+'</th></tr></thead>';
  const tb=el('tbody'); table.append(tb); scroll.append(table); root.append(scroll);
  function fill(){
    const r=race(); tb.innerHTML='';
    const rows=r.nodes.filter(matches).sort((a,b)=>a.d.localeCompare(b.d)||a.t-b.t||a.n.localeCompare(b.n));
    rows.forEach(n=>{
      const tr=el('tr');
      const c1=el('td'); c1.append(el('div','nm',nName(n)));
      if(nFlav(n)) c1.append(el('div','fl',nFlav(n)));
      tr.append(c1, el('td',null,discName(n.d)));
      const c3=el('td','tnum',ROMAN[n.t+1]); tr.append(c3);
      const c4=el('td','tnum',String(n.c)); tr.append(c4);
      const c5=el('td'); nEff(n).forEach(l=>c5.append(el('span','ef',l))); tr.append(c5);
      tr.onclick=()=>{S.node=n.i;S.view='map';buildTabs();render();};
      tb.append(tr);
    });
  }
  fill();
}

/* ---------------- kindreds ---------------- */
function renderCompare(root){
  const ru=S.lang==='ru';
  const max={n:0,p:0,e:0,a:0};
  DATA.order.forEach(id=>{const r=DATA.races[id];
    max.n=Math.max(max.n,r.nodes.length);max.p=Math.max(max.p,r.points);
    max.e=Math.max(max.e,r.effects);max.a=Math.max(max.a,r.actives);});
  const cards=el('div','cards');
  DATA.order.forEach(id=>{
    const r=DATA.races[id], c=el('div','card'); c.style.setProperty('--kc',r.accent);
    c.append(el('h3',null,ru?r.nameRu:r.name));
    c.append(el('div','ep',EPITHET[id][ru?1:0]));
    c.append(el('p',null,ru?r.blurbRu:r.blurb));
    [[t('nodes'),r.nodes.length,max.n],[t('points'),r.points,max.p],
     [t('effects'),r.effects,max.e],[t('actives'),r.actives,max.a]].forEach(([lab,v,mx])=>{
      const m=el('div','metric'); m.append(el('span','lab',lab));
      const tr=el('div','track'), fl=el('div','fill'); tr.append(fl); m.append(tr);
      m.append(el('span','val tnum',String(v))); c.append(m);
      requestAnimationFrame(()=>{fl.style.width=Math.round(v/mx*100)+'%';});
    });
    c.onclick=()=>{S.race=id;S.view='map';S.node=null;paint();buildKin();buildTabs();render();
      scrollTo({top:0,behavior:'smooth'});};
    cards.append(c);
  });
  root.append(cards);
}

/* ---------------- rules ---------------- */
function renderRules(root){
  const r=race(), ru=S.lang==='ru', p=el('div','prose');
  const sec=(kick,head)=>{p.append(el('div','kicker',kick)); p.append(el('h2',null,head));};

  sec(ru?'Правило первое':'The first law', t('capT'));
  p.append(el('p',null,ru
    ?'Нельзя освоить всё древо. Предел — доля вашего собственного древа, поэтому он одинаково честен к гному с четырьмя ветвями и к эльфу с пятью. По умолчанию — 75%: три-четыре ветви, но никогда все.'
    :'You cannot master your whole tree. The cap is a share of your own tree, so it binds a four-branch Dwarf and a five-branch Elf alike. The default is three-quarters — three or four branches, never all of them.'));
  const tiles=el('div','grid2');
  DATA.order.forEach(id=>{const rr=DATA.races[id], tl=el('div','tile');
    tl.append(el('div','k',ru?rr.nameRu:rr.name));
    tl.append(el('div','v tnum',Math.round(rr.points*0.75)+' / '+rr.points));
    tl.append(el('div','d',ru?'очков на «Дороге»':'points on The Road')); tiles.append(tl);});
  p.append(tiles);

  sec(ru?'Выбор владельца сервера':'Chosen by the server', t('diffT'));
  const dg=el('div','grid2');
  DATA.difficulty.forEach(d=>{const tl=el('div','tile');
    tl.append(el('div','k',ru?d.ru:d.en));
    tl.append(el('div','v tnum',d.cap===100?(ru?'без предела':'no cap'):d.cap+'%'));
    tl.append(el('div','d',(ru?d.noteRu:d.noteEn)+' · xp ×'+d.xp+' · '+(ru?d.deathRu:d.death)));
    dg.append(tl);});
  p.append(dg);

  sec(ru?'Как раздвинуть предел':'How the ceiling moves', t('renownT'));
  p.append(el('p',null,ru
    ?'Предел раздвигают дела, а не часы. У каждого народа свои четыре Великих деяния, каждое даёт +5% вашего древа. Деяния чужого народа не в счёт: слава принадлежит тому, кем вы её добыли.'
    :'The ceiling moves for deeds, not for hours. Every kindred has its own four Great Deeds, each worth a twentieth of your tree. Another kindred’s deeds do not count — renown belongs to the people you earned it as.'));
  const dl=el('div','deedlist');
  r.deeds.forEach(d=>{const c=el('div','deed');
    c.append(el('b',null,(ru&&d.tr)?d.tr:d.t));
    c.append(el('span',null,(ru&&d.dr)?d.dr:d.d)); dl.append(c);});
  p.append(dl);

  sec(ru?'Иная дорога':'The other road', t('bargainT'));
  const wb=el('div','warnbox');
  wb.innerHTML=ru
    ?'<b>+10% древа — и два сердца, навсегда.</b><br>Сделку предлагают лишь тому, кто уже упёрся в собственный предел. Её не отменит ни сброс древа, ни очищение, ни смерть, и сервер объявляет о ней всем: слух расходится.'
    :'<b>A tenth of your tree, and two hearts of your life — both forever.</b><br>The Bargain is offered only to someone already standing at their own ceiling. No respec, no cleansing and no death undoes it, and the server tells everyone: word spreads.';
  p.append(wb);

  sec(ru?'Глубина ветви':'Depth of a branch', t('ranksT'));
  p.append(el('p',null,ru
    ?'Некоторые перки повторяются вдоль ветви — и каждый повтор поднимает ранг. Скрытность гоблина: ранг 1 прячет лишь неподвижного, ранг 3 позволяет красться, ранг 5 и выше оставляет невидимым ещё миг после того, как вы поднялись.'
    :'Some perks repeat down a branch, and every repeat raises its rank. Goblin camouflage: rank one hides you only while you hold still, rank three lets you creep, rank five and above keeps you unseen for a moment after you rise.'));
  root.append(p);
}

/* ---------------- render ---------------- */
function render(){
  buildStrip();
  const root=$('#view'); root.innerHTML='';
  if(S.view==='map') renderMap(root);
  else if(S.view==='codex') renderCodex(root);
  else if(S.view==='compare') renderCompare(root);
  else { illum(root); renderRules(root); }
}
$('#langBtn').onclick=e=>{
  S.lang=S.lang==='en'?'ru':'en';
  e.currentTarget.setAttribute('aria-pressed',String(S.lang==='ru'));
  e.currentTarget.textContent=S.lang==='en'?'EN · РУ':'РУ · EN';
  buildKin();buildTabs();render();
};
$('#themeBtn').onclick=e=>{
  const cur=document.documentElement.getAttribute('data-theme');
  const dark=cur?cur==='dark':matchMedia('(prefers-color-scheme: dark)').matches;
  document.documentElement.setAttribute('data-theme',dark?'light':'dark');
  e.currentTarget.textContent=dark?'☾':'☀';
};
paint();buildKin();buildTabs();render();
</script>
"""

DOSS = open(os.path.join(HERE, 'kindreds-dossier.json'), encoding='utf-8').read()
BRAN = open(os.path.join(HERE, 'kindreds-branches.json'), encoding='utf-8').read()
html = (TEMPLATE.replace('@@DATA@@', DATA)
                .replace('@@DOSSIER@@', DOSS)
                .replace('@@BRANCHES@@', BRAN))
os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, 'w', encoding='utf-8').write(html)
print('wrote %s  (%.0f KB)' % (OUT, os.path.getsize(OUT) / 1024))
