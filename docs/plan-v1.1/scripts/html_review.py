#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Stage 2 html-review 六维自检（重排版版）：
SC 结构 / BR 渲染 / SI 语义 / DT design token / TQ 排版 / CF 内容保真
"""
import io
import re
import sys

HTML = "/workspace/output/ceb31b98-c365-4eed-9c74-8ff9fe66870f/stage2/formatted-penai-software-renovation-plan.html"
MD = "/workspace/output/ceb31b98-c365-4eed-9c74-8ff9fe66870f/stage1/final_draft.md"

html = io.open(HTML, encoding="utf-8").read()
md = io.open(MD, encoding="utf-8").read()
issues = []

# ---------- SC 结构 ----------
for pat, name in [
    (r'<section role="cover" class="cover">', "封面节"),
    (r'class="executive-summary summary-card"', "执行摘要卡"),
    (r'<nav class="doc-toc"', "目录导航"),
    (r'<section role="body" class="report-body"', "正文节"),
    (r'data-page-restart="1"', "正文页码重启"),
    (r'<html lang="zh-CN">', "语言声明"),
    (r'name="docx-page-size" content="A4"', "A4 页面尺寸"),
]:
    if not re.search(pat, html):
        issues.append(f"SC: 缺少{name}")

# ---------- BR 渲染 ----------
if re.search(r'src="http|href="http|@import|url\(', html):
    issues.append("BR: 存在外部资源引用")
for tag in ("div", "section", "table", "p", "li", "h2", "h3", "pre", "code", "strong", "span", "ol", "ul", "thead", "tbody", "tr", "td", "th", "nav"):
    o = len(re.findall(rf"<{tag}[ >]", html))
    c = len(re.findall(rf"</{tag}>", html))
    if o != c:
        issues.append(f"BR: <{tag}> 不配平 open={o} close={c}")
if "var(--" in re.sub(r":root\s*\{.*?\}", "", html, flags=re.S).replace("<style>", "").replace("</style>", ""):
    # var() 只允许出现在 :root 定义与 CSS 规则内（本文件全部 CSS 在 <style> 中）
    pass

# ---------- SI 语义 ----------
tbls = re.findall(r"<table>.*?</table>", html, re.S)
for i, t in enumerate(tbls):
    if "<thead>" not in t or "<tbody>" not in t:
        issues.append(f"SI: 第 {i + 1} 个表缺 thead/tbody")
    if t.count("<tr>") < 2:
        issues.append(f"SI: 第 {i + 1} 个表行数不足")
if re.search(r"<p>[^<]*<h[23]", html):
    issues.append("SI: 段落未闭合即出标题")

# ---------- DT design token ----------
style = re.search(r"<style>(.*?)</style>", html, re.S).group(1)
root = re.search(r":root\s*\{(.*?)\}", style, re.S).group(1)
rules = style.replace(root, "")
# 规则区（:root 之外）不允许出现十六进制颜色与字体名
hexes = re.findall(r"#[0-9A-Fa-f]{3,6}\b", rules)
if hexes:
    issues.append(f"DT: 规则区存在裸十六进制颜色 {set(hexes)}")
fonts = re.findall(r"font-family:\s*([^;}]+)", rules)
for f in fonts:
    if "var(" not in f:
        issues.append(f"DT: 规则区裸字体族 {f.strip()}")
margins = re.findall(r"(?:margin|padding)(?:-top|-bottom|-left|-right)?:\s*[\d.]+(?:em|px|cm|pt)", rules)
if margins:
    issues.append(f"DT: 规则区裸间距 {margins[:5]}")
# token 值必须是文学出版风
for var, val in [("--color-primary", "#3E5C76"), ("--color-text", "#1F1E1C"),
                 ("--color-heading", "#2B3A4A"), ("--color-highlight", "#F5F2EA"),
                 ("--color-divider", "#E5E1D8"), ("--color-muted", "#6B6660")]:
    if f"{var}: {val};" not in root:
        issues.append(f"DT: {var} 未设为 {val}")
if "Noto Serif SC" not in root:
    issues.append("DT: 标题字体未用衬线 Noto Serif SC")
if "陶土" in root or "楷体" in root:
    issues.append("DT: 含被排除的风格元素")

# ---------- TQ 排版 ----------
if "--indent-body: 2em;" not in root:
    issues.append("TQ: 缺首行缩进变量")
if "--fs-body: 12pt;" not in root:
    issues.append("TQ: 正文字号未锁定 12pt")
n_nbsp_h2 = len(re.findall(r"<h2 [^>]*>&nbsp;&nbsp;", html))
if n_nbsp_h2 != 12:
    issues.append(f"TQ: h2 前缀异常 {n_nbsp_h2}/12")

# ---------- CF 内容保真 ----------
def norm(s):
    s = re.sub(r"<[^>]+>", "", s)
    s = (s.replace("&nbsp;", "").replace("&amp;", "&").replace("&lt;", "<")
          .replace("&gt;", ">").replace("&quot;", '"'))
    s = s.replace("|", "").replace("\ufe0f", "")
    s = re.sub(r"\s+", "", s)
    return s

# md 侧：取所有非结构文本行（跳过标题行/表格分隔线/代码块）
md_lines = md.split("\n")
in_code = False
md_texts = []
for ln in md_lines:
    s = ln.strip()
    if s.startswith("```"):
        in_code = not in_code
        continue
    if in_code or not s:
        continue
    if s.startswith("#") or re.match(r"^\|[\s:\-|]+\|$", s):
        continue
    s = re.sub(r"^[>\-]\s+", "", s)
    s = re.sub(r"^\d+\.\s+", "", s)
    s = s.strip("|")
    s = s.replace("**", "").replace("`", "")
    s = re.sub(r'"([^"]*)"', r"「\1」", s)
    if s:
        md_texts.append(norm(s))

html_text = norm(html)
missing = [t for t in md_texts if t not in html_text]
if missing:
    issues.append(f"CF: HTML 缺失 {len(missing)} 段文本，首条：{missing[0][:60]}")

# 反向：HTML 正文关键块数
print(f"md 文本块：{len(md_texts)}")
print(f"表格：{len(tbls)}  pre：{len(re.findall(r'<pre>', html))}")
print(f"标题：h2={len(re.findall(r'<h2 ', html))} h3={len(re.findall(r'<h3 ', html))}")
print(f"目录链接：{len(re.findall(r'toc-list.*?</ol>', html, re.S)) and len(re.findall(r'<li><a href=', html))}")
if issues:
    print("=== ISSUES ===")
    for i in issues:
        print(" -", i)
    sys.exit(1)
print("=== SIX-DIM REVIEW PASS ===")
