// The order page's own stylesheet, inlined into every response (interface design, section 5).
// It repeats the token values of app/tokens.css and must stay under 8 KB.
export const ORDER_PAGE_CSS = `
:root{--ink:#1c1917;--ink-muted:#57534e;--surface:#ffffff;--surface-page:#f5f2ee;--border:#d6d0c8;--accent:#176b3a;--accent-strong:#125530;--danger:#b42318;--placed-ink:#7c4a03;--placed-ground:#fef3c7;--confirmed-ink:#1e3a8a;--confirmed-ground:#dbeafe;--delivered-ink:#14532d;--delivered-ground:#dcfce7;--cancelled-ink:#57534e;--cancelled-ground:#e7e5e4}
*{box-sizing:border-box}
html{font:16px/24px system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans",sans-serif;color:var(--ink);background:var(--surface-page)}
body{margin:0 auto;max-width:480px;padding:16px}
h1{font-size:20px;line-height:28px;font-weight:600;margin:0}
h2{font-size:16px;line-height:24px;font-weight:600;margin:24px 0 8px}
.title{font-size:24px;line-height:32px}
p{margin:0}
.small{font-size:14px;line-height:20px;color:var(--ink-muted)}
.num{font-variant-numeric:tabular-nums}
a{color:var(--accent)}
:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.banner,.card{background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:12px 16px;margin:16px 0}
.banner strong{display:block}
.errors{color:var(--danger);margin:16px 0}
.errors a{color:var(--danger)}
.rows{background:var(--surface);border:1px solid var(--border);border-radius:10px;margin:16px 0;padding:0 16px}
.row{display:flex;align-items:center;gap:12px;min-height:64px;padding:8px 0;border-bottom:1px solid var(--border);cursor:pointer}
.row:last-child{border-bottom:0}
.row span{flex:1}
.row b{font-weight:600;display:block}
input[type=number]{width:64px;height:44px;font:inherit;text-align:right;padding:0 8px;border:1px solid var(--border);border-radius:6px;background:var(--surface);color:var(--ink)}
.bad input,input.bad{border-color:var(--danger)}
.bad .small{color:var(--danger)}
label.block{display:block;font-size:13px;line-height:16px;font-weight:600;text-transform:uppercase;letter-spacing:.04em;margin:16px 0 4px}
textarea{width:100%;min-height:88px;font:inherit;padding:8px 12px;border:1px solid var(--border);border-radius:6px;background:var(--surface);color:var(--ink)}
.bar{margin:16px 0;background:var(--surface-page);padding:8px 0}
.js .bar{position:sticky;bottom:0}
.total{font-weight:600;margin-bottom:8px}
.primary{display:block;width:100%;min-height:44px;font:inherit;font-weight:600;color:#fff;background:var(--accent);border:0;border-radius:6px;padding:0 16px;cursor:pointer}
.primary:hover,.primary:active{background:var(--accent-strong)}
.text{min-height:44px;font:inherit;font-weight:600;background:none;border:0;padding:0 8px;cursor:pointer;color:var(--accent)}
.danger{color:var(--danger)}
ul.recent{list-style:none;margin:0;padding:0}
ul.recent li{display:flex;flex-wrap:wrap;align-items:center;gap:8px;min-height:44px;padding:8px 0;border-bottom:1px solid var(--border)}
ul.recent li a{flex:1 1 100%}
ul.recent form{margin-left:auto}
.badge{display:inline-block;font-size:13px;line-height:16px;font-weight:600;text-transform:uppercase;letter-spacing:.04em;padding:4px 8px;border-radius:6px}
.placed{color:var(--placed-ink);background:var(--placed-ground)}
.confirmed{color:var(--confirmed-ink);background:var(--confirmed-ground)}
.delivered{color:var(--delivered-ink);background:var(--delivered-ground)}
.cancelled{color:var(--cancelled-ink);background:var(--cancelled-ground)}
table{width:100%;border-collapse:collapse;margin:12px 0}
th{font-size:13px;line-height:16px;font-weight:600;text-transform:uppercase;letter-spacing:.04em;text-align:left;padding:8px 4px;border-bottom:1px solid var(--border)}
td{padding:8px 4px;border-bottom:1px solid var(--border);vertical-align:top}
.r{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
tfoot td{font-weight:600;border-bottom:0}
blockquote{margin:8px 0;padding:8px 12px;border-left:3px solid var(--border)}
.center{text-align:center;margin-top:96px}
.center p{margin-top:8px}
.back{display:inline-flex;align-items:center;min-height:44px}
`.trim();
