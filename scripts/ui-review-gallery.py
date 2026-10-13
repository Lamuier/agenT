#!/usr/bin/env python3
"""Create an offline gallery for JVM screenshots; only sample fixtures are rendered."""
from pathlib import Path
import json

root = Path(__file__).resolve().parents[1] / 'app/build/reports/ui-review'
files = sorted(root.glob('*.png'))
items = [{'file': p.name, 'profile': p.name.split('-', 1)[0], 'scene': p.stem.split('-', 1)[1]} for p in files]
page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>agenT 界面验收</title><style>
body{margin:0;background:#f3f5f8;color:#19212b;font:15px system-ui,sans-serif}header{padding:28px;position:sticky;top:0;background:#f3f5f8ed;backdrop-filter:blur(10px);z-index:2}h1{margin:0 0 8px}p{margin:8px 0;color:#596679}select{font:inherit;padding:10px;border-radius:12px;border:1px solid #ccd4dd;margin:8px 8px 0 0}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:24px;padding:20px 28px}figure{margin:0;padding:14px;background:white;border-radius:20px;box-shadow:0 3px 16px #2333460d}img{width:100%;height:auto;border-radius:12px}figcaption{padding:8px 0 14px;font-weight:600}a{color:inherit;text-decoration:none}.count{font-variant-numeric:tabular-nums}
</style><header><h1>agenT 界面验收</h1><p>真实 Compose 的 JVM 渲染，使用示例数据。覆盖中英文、浅深色、320 / 360 / 393 / 600 / 800 / 840 / 1000 / 1280 / 1600 dp、书本 / 桌面 / Flip 悬停、横屏和 200% 字号；不代表系统小组件或 OEM 宿主渲染。</p>
<select id="profile"><option value="">所有窗口与主题</option></select><select id="scene"><option value="">所有页面</option></select><p class="count" id="count"></p></header><main id="gallery"></main>
<script>const items=ITEMS;const p=document.querySelector('#profile'),s=document.querySelector('#scene');
for(const [select,key] of [[p,'profile'],[s,'scene']]){for(const value of [...new Set(items.map(x=>x[key]))]){const o=document.createElement('option');o.value=value;o.textContent=value;select.append(o)}select.onchange=render}
function render(){const visible=items.filter(x=>(!p.value||x.profile===p.value)&&(!s.value||x.scene===s.value));document.querySelector('#count').textContent=`${visible.length} / ${items.length} 个场景`;const g=document.querySelector('#gallery');g.replaceChildren();for(const x of visible){const f=document.createElement('figure'),c=document.createElement('figcaption'),a=document.createElement('a'),im=document.createElement('img');c.textContent=x.profile+' · '+x.scene;a.href=x.file;im.src=x.file;im.alt=c.textContent;im.loading='lazy';a.append(im);f.append(c,a);g.append(f)}}render();</script></html>'''
root.mkdir(parents=True, exist_ok=True)
(root/'index.html').write_text(page.replace('ITEMS', json.dumps(items)), encoding='utf-8')
print(f'{len(files)} scenes: {root / "index.html"}')
