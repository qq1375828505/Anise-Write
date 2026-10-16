#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Stage 2 重新排版：以文学出版风 design token 从 final_draft.md 重新生成 HTML。

约定（与上一版 formatted HTML 完全一致，仅换 token 值与内容）：
- h2 章节标题带 &nbsp;&nbsp; 前缀，id=section-N（N 从 2 起，执行摘要占 section-1）
- h3 小节 id=section-N-M
- 段落内 `code` → <code>；表格/列表内 code 同样转 <code>
- "" 引号 → 「」；**bold** → <strong>
- blockquote：⚠ 开头 → callout-warning；"本文档基于"开头 → src-note；其余 → callout-info
- ``` 代码块 → <pre><code>；表格 → thead/tbody
- 关键数字包 <span class="data-emphasis">；7.1 表 P0 单元格与风险表"高"单元格加 priority-p0
- 章首导语（LEAD_INS）由排版层补写，与上一版一致
"""
import io
import re
import sys

MD = "/workspace/output/ceb31b98-c365-4eed-9c74-8ff9fe66870f/stage1/final_draft.md"
OUT = "/workspace/output/ceb31b98-c365-4eed-9c74-8ff9fe66870f/stage2/formatted-penai-software-renovation-plan.html"

LEAD_INS = {
    1: "本章说明项目的提出背景、产品定位与核心目标、改造对象与范围，并约定全文使用的术语体系。",
    2: "本章汇总对 ZorvAI 基座的代码级调研结论，覆盖工程概况、Agent 内核、工具系统、技能与提示词体系、数据与记忆层、推理层与许可证，作为全部改造决策的事实基础。",
    3: "本章清点 novelIDE 的领域资产，按 P0/P1/P2 分级给出移植结论与映射方法，并明确不宜移植部分。",
    4: "本章剖析 AIGC-NetNov 同题改造实践的完整样本，提炼已验证有效的架构决策与必须规避的失败模式。",
    5: "本章给出 PenAi 的改造原则、目标架构、功能矩阵与基座裁剪策略，构成后续详细设计的顶层约束。",
    6: "本章逐项展开网文工具域、技能包、人格与提示词、数据层、记忆与上下文、子 Agent 体系、界面、关系图与推理策略的详细设计。",
    8: "本章将改造工作划分为十个阶段，明确各阶段目标、出口验收标准与禁止事项，并约定环境准备与工程协作规范。",
    12: "本附录提供基座关键文件、领域资产来源与基座缺陷 Issue 的三类检索索引，供实施期快速定位。",
}

EMPHASIS = [
    "7 项", "248 个工具注册", "203 个", "154 个",
    "约 95% 业务逻辑与 UI 框架无关", "20 篇", "4000-8000 字符", "5-7 人周",
]


def esc(t):
    return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def inline(text, code_tag=True):
    """行内格式：code/bold/emphasis/引号。code_tag=False 时 code 转纯文本。"""
    # 1. 保护 code span
    codes = []

    def _stash(m):
        codes.append(m.group(1))
        return f"\x00{len(codes) - 1}\x00"

    text = re.sub(r"`([^`]+)`", _stash, text)
    # 2. HTML 转义
    text = esc(text)
    # 3. 加粗
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    # 4. 数据强调
    for s in EMPHASIS:
        if s in text:
            text = text.replace(s, f'<span class="data-emphasis">{s}</span>')
    # 5. 直引号 → 「」
    text = re.sub(r'"([^"]*)"', r"「\1」", text)
    # 6. 还原 code
    def _restore(m):
        c = esc(codes[int(m.group(1))])
        return f"<code>{c}</code>" if code_tag else c

    return re.sub(r"\x00(\d+)\x00", _restore, text)


def cell(text):
    """表格单元格：code 也转 <code>，P0/高 加 priority-p0"""
    t = text.strip()
    cls = ""
    if t in ("P0", "高"):
        cls = ' class="priority-p0"'
    return f"<td{cls}>{inline(t)}</td>"


def parse_blocks(md):
    lines = md.split("\n")
    blocks = []
    i = 0
    while i < len(lines):
        line = lines[i]
        s = line.strip()
        if not s:
            i += 1
            continue
        if s.startswith("```"):
            j = i + 1
            buf = []
            while j < len(lines) and not lines[j].strip().startswith("```"):
                buf.append(lines[j])
                j += 1
            blocks.append(("code", "\n".join(buf)))
            i = j + 1
            continue
        if s.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                raw = lines[i].strip()
                if not re.match(r"^\|[\s:\-|]+\|$", raw):
                    cells = [c.strip() for c in raw.strip("|").split("|")]
                    rows.append(cells)
                i += 1
            blocks.append(("table", rows))
            continue
        if s.startswith("> "):
            buf = []
            while i < len(lines) and lines[i].strip().startswith(">"):
                buf.append(re.sub(r"^>\s?", "", lines[i].strip()))
                i += 1
            blocks.append(("quote", "\n".join(buf)))
            continue
        m = re.match(r"^###\s+(\d+\.\d+)\s+(.*)$", s)
        if m:
            blocks.append(("h3", m.group(1), m.group(2).strip()))
            i += 1
            continue
        m = re.match(r"^##\s+(\d+)\.\s*(.*)$", s)
        if m:
            blocks.append(("h2", int(m.group(1)), m.group(2).strip()))
            i += 1
            continue
        if s.startswith("## "):
            # 文档副标题（## ——基于 ...），由封面层处理
            blocks.append(("subtitle", s[3:].strip()))
            i += 1
            continue
        if s.startswith("# "):
            blocks.append(("title", s[2:].strip()))
            i += 1
            continue
        if re.match(r"^-\s+", s):
            items = []
            while i < len(lines) and re.match(r"^-\s+", lines[i].strip()):
                items.append(re.sub(r"^-\s+", "", lines[i].strip()))
                i += 1
            blocks.append(("ul", items))
            continue
        if re.match(r"^\d+\.\s+", s):
            items = []
            while i < len(lines) and re.match(r"^\d+\.\s+", lines[i].strip()):
                items.append(re.sub(r"^\d+\.\s+", "", lines[i].strip()))
                i += 1
            blocks.append(("ol", items))
            continue
        # 普通段落（连续行合并）
        buf = [s]
        i += 1
        while i < len(lines):
            nxt = lines[i].strip()
            if (not nxt or nxt.startswith(("#", "|", "> ", "```", "- "))
                    or re.match(r"^\d+\.\s+", nxt)):
                break
            buf.append(nxt)
            i += 1
        blocks.append(("p", "".join(buf)))
    return blocks


def render_table(rows):
    out = ["<table>", "<thead>", "<tr>"]
    for c in rows[0]:
        out.append(f"<th>{inline(c)}</th>")
    out.append("</tr>")
    out.append("</thead>")
    out.append("<tbody>")
    for r in rows[1:]:
        out.append("<tr>" + "".join(cell(c) for c in r) + "</tr>")
    out.append("</tbody>")
    out.append("</table>")
    return "\n".join(out)


CSS = """<style>
/* 页面规则：正文页脚居中页码（仅当前页） */
@page { @bottom-center { content: "第 " counter(page) " 页"; } }
/* 封面命名页：无页脚 */
@page cover { @bottom-center { content: none; } }
section[role="cover"] { page: cover; }

:root {
  /* Typography design tokens（literary-publishing 主题注入） */
  --typography-fontFamily-heading: "Noto Serif SC", "Source Han Serif SC", "Songti SC", "SimSun", serif;
  --typography-fontFamily-bodyLatin: "Times New Roman", Georgia, serif;
  --typography-fontFamily-code: Consolas, "Source Code Pro", monospace;
  --typography-lineHeight-body: 1.6;
  --typography-lineHeight-compact: 1.35;
  --typography-fontWeight-heading: 600;
  --typography-fontWeight-body: 400;
  --typography-fontWeight-bold: 700;
  /* Color design tokens */
  --color-primary: #3E5C76;
  --color-secondary: #6E8697;
  --color-accent: #3E5C76;
  --color-text: #1F1E1C;
  --color-muted: #6B6660;
  --color-heading: #2B3A4A;
  --color-divider: #E5E1D8;
  --color-bg: #FFFFFF;
  --color-highlight: #F5F2EA;
  --color-emphasis: #9E4A4A;
  --color-warning-bg: #F7F1E8;
  --color-note-bg: #F5F2EA;
  /* Spacing design tokens */
  --spacing-paragraph: 0.75em;
  --spacing-section: 2.2em;
  --spacing-block: 1em;
  --margin-page: 2.5cm;
  /* 本地映射层（版式硬规范：正文宋体小四、首行缩进 2em） */
  --ff-heading: var(--typography-fontFamily-heading);
  --ff-body: "SimSun", "宋体", serif;
  --ff-mono: var(--typography-fontFamily-code);
  --fs-body: 12pt;
  --fs-h2: 16pt;
  --fs-h3: 14pt;
  --fs-table: 10.5pt;
  --fs-small: 10.5pt;
  --fs-tiny: 9pt;
  --fs-cover-title: 26pt;
  --fs-cover-subtitle: 15pt;
  --fs-cover-meta: 11pt;
  --lh-body: var(--typography-lineHeight-body);
  --lh-compact: var(--typography-lineHeight-compact);
  --fw-bold: var(--typography-fontWeight-bold);
  --indent-body: 2em;
  --page-content-width: 15.6cm;
  /* Spacing 本地变量（消除裸间距） */
  --spacing-2xs: 0.1em;
  --spacing-xs: 0.25em;
  --spacing-s: 0.45em;
  --spacing-sm: 0.3em;
  --spacing-li: 0.35em;
  --spacing-m: 0.6em;
  --spacing-ml: 0.8em;
  --spacing-l: 1.2em;
  --spacing-xl: 1.5em;
  --spacing-2xl: 2em;
  --spacing-3xl: 3.2em;
  --spacing-card-x: 1.4em;
  --spacing-callout-x: 0.9em;
  --spacing-list-indent: 2em;
  --spacing-toc-indent: 1.5em;
  --spacing-cover-top: 5.5cm;
  --spacing-summary-top: 1.5cm;
  --spacing-toc-top: 1.5cm;
  --border-heading-width: 4px;
}

* { box-sizing: border-box; margin: 0; padding: 0; }
body {
  font-family: var(--ff-body);
  font-size: var(--fs-body);
  line-height: var(--lh-body);
  color: var(--color-text);
  background: var(--color-bg);
  max-width: var(--page-content-width);
  margin-left: auto;
  margin-right: auto;
}

/* ===== 封面 ===== */
section[role="cover"].cover { padding-top: var(--spacing-cover-top); }
.cover-category {
  font-family: var(--ff-heading);
  font-size: var(--fs-small);
  font-weight: var(--fw-bold);
  color: var(--color-secondary);
  letter-spacing: 0.25em;
  text-align: center;
  margin-bottom: var(--spacing-l);
}
.cover-title {
  font-family: var(--ff-heading);
  font-size: var(--fs-cover-title);
  font-weight: var(--fw-bold);
  color: var(--color-primary);
  text-align: center;
  letter-spacing: 0.06em;
  margin-bottom: var(--spacing-m);
}
.cover-subtitle {
  font-family: var(--ff-heading);
  font-size: var(--fs-cover-subtitle);
  font-weight: var(--typography-fontWeight-heading);
  color: var(--color-heading);
  text-align: center;
  margin-bottom: var(--spacing-3xl);
}
.cover-meta { text-align: center; margin-top: var(--spacing-2xl); }
.cover-meta-item {
  font-size: var(--fs-cover-meta);
  color: var(--color-muted);
  text-align: center;
  margin-bottom: var(--spacing-s);
}

/* ===== 执行摘要（前置部分，独立成节） ===== */
.summary-card {
  width: 100%;
  border-collapse: collapse;
  margin-top: var(--spacing-summary-top);
}
.summary-card > tbody > tr > td {
  background: var(--color-note-bg);
  border-left: var(--border-heading-width) solid var(--color-primary);
  padding: var(--spacing-l) var(--spacing-card-x);
}
.summary-title {
  font-family: var(--ff-heading);
  font-size: var(--fs-h2);
  font-weight: var(--fw-bold);
  color: var(--color-heading);
  text-indent: 0;
  margin-bottom: var(--spacing-ml);
}
.summary-card p { text-indent: var(--indent-body); margin-bottom: var(--spacing-paragraph); }
.summary-metric-line {
  text-indent: 0;
  font-size: var(--fs-small);
  color: var(--color-muted);
  margin-bottom: var(--spacing-paragraph);
}

/* ===== 目录 ===== */
.toc-section { padding-top: var(--spacing-toc-top); }
nav.doc-toc { padding: 0; }
.toc-title {
  font-family: var(--ff-heading);
  font-size: var(--fs-h2);
  font-weight: var(--fw-bold);
  color: var(--color-heading);
  text-align: center;
  text-indent: 0;
  margin-bottom: var(--spacing-block);
}
.toc-list, .toc-list ol, .toc-list ul {
  list-style: none;
  list-style-type: none;
  padding-left: var(--spacing-toc-indent);
}
.toc-list > li {
  font-family: var(--ff-heading);
  font-weight: var(--typography-fontWeight-heading);
  margin-bottom: var(--spacing-s);
  text-indent: 0;
}
.toc-list ol li {
  font-family: var(--ff-body);
  font-weight: var(--typography-fontWeight-body);
  font-size: var(--fs-table);
  margin-bottom: var(--spacing-xs);
}
.toc-list a { color: var(--color-text); text-decoration: none; }

/* ===== 正文 ===== */
.report-body h2 {
  font-family: var(--ff-heading);
  font-size: var(--fs-h2);
  font-weight: var(--fw-bold);
  color: var(--color-heading);
  border-left: var(--border-heading-width) solid var(--color-primary);
  margin-top: var(--spacing-section);
  margin-bottom: var(--spacing-ml);
  text-indent: 0;
  line-height: var(--lh-compact);
}
.report-body h3 {
  font-family: var(--ff-heading);
  font-size: var(--fs-h3);
  font-weight: var(--fw-bold);
  color: var(--color-primary);
  margin-top: var(--spacing-block);
  margin-bottom: var(--spacing-m);
  text-indent: 0;
  line-height: var(--lh-compact);
}
.report-body p {
  font-family: var(--ff-body);
  font-size: var(--fs-body);
  color: var(--color-text);
  text-indent: var(--indent-body);
  margin-bottom: var(--spacing-paragraph);
  text-align: justify;
}
.report-body ul, .report-body ol { margin-bottom: var(--spacing-paragraph); padding-left: var(--spacing-list-indent); }
.report-body li { font-size: var(--fs-body); margin-bottom: var(--spacing-li); text-indent: 0; }
.report-body li p { text-indent: 0; margin-bottom: var(--spacing-sm); }
.report-body strong { font-weight: var(--fw-bold); }
.report-body code {
  font-family: var(--ff-mono);
  font-size: var(--fs-small);
  background: var(--color-highlight);
  padding: var(--spacing-2xs) var(--spacing-sm);
}
.report-body pre {
  font-family: var(--ff-mono);
  font-size: var(--fs-small);
  line-height: var(--lh-compact);
  background: var(--color-highlight);
  border: 1px solid var(--color-divider);
  padding: var(--spacing-ml) var(--spacing-block);
  white-space: pre-wrap;
  word-break: break-all;
  text-indent: 0;
  margin-bottom: var(--spacing-paragraph);
}
.report-body pre code { background: none; padding: 0; font-size: var(--fs-small); }

/* ===== 表格 ===== */
.report-body table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: var(--spacing-paragraph);
}
.report-body th {
  font-family: var(--ff-heading);
  font-size: var(--fs-table);
  font-weight: var(--fw-bold);
  color: var(--color-heading);
  background: var(--color-highlight);
  border: 1px solid var(--color-divider);
  padding: var(--spacing-s) var(--spacing-m);
  text-align: left;
  text-indent: 0;
}
.report-body td {
  font-family: var(--ff-body);
  font-size: var(--fs-table);
  color: var(--color-text);
  border: 1px solid var(--color-divider);
  padding: var(--spacing-s) var(--spacing-m);
  text-indent: 0;
  vertical-align: top;
}
.report-body td p { text-indent: 0; font-size: var(--fs-table); margin-bottom: var(--spacing-sm); }

/* ===== 强调组件 ===== */
.data-emphasis { color: var(--color-emphasis); font-weight: var(--fw-bold); }
.callout-warning {
  border-left: 3px solid var(--color-emphasis);
  background: var(--color-warning-bg);
  padding: var(--spacing-m) var(--spacing-callout-x);
  text-indent: 0;
  font-size: var(--fs-small);
  margin-bottom: var(--spacing-paragraph);
}
.callout-info {
  border-left: 3px solid var(--color-primary);
  background: var(--color-note-bg);
  padding: var(--spacing-m) var(--spacing-callout-x);
  text-indent: 0;
  font-size: var(--fs-small);
  margin-bottom: var(--spacing-paragraph);
}
.src-note {
  font-size: var(--fs-tiny);
  color: var(--color-muted);
  background: var(--color-note-bg);
  padding: var(--spacing-m) var(--spacing-callout-x);
  text-indent: 0;
  margin-top: var(--spacing-section);
}
.priority-p0 { color: var(--color-emphasis); font-weight: var(--fw-bold); }
</style>
"""


def main():
    md = io.open(MD, encoding="utf-8").read()
    blocks = parse_blocks(md)

    # ---- 提取封面信息 ----
    doc_title = "PenAi 软件改造计划书"
    doc_subtitle = ""
    for b in blocks:
        if b[0] == "title":
            doc_title = b[1]
        elif b[0] == "subtitle" and not doc_subtitle:
            doc_subtitle = b[1]

    # ---- 正文渲染 + 目录收集 ----
    body = []
    toc = []  # (level, id, text)
    chap_no = 0
    for b in blocks:
        kind = b[0]
        if kind in ("title", "subtitle"):
            continue
        if kind == "h2":
            chap_no = b[1]
            sid = f"section-{chap_no + 1}"
            toc.append((1, sid, f"{chap_no}. {b[2]}"))
            body.append(f'<h2 id="{sid}">&nbsp;&nbsp;{chap_no}. {esc(b[2])}</h2>')
            lead = LEAD_INS.get(chap_no)
            if lead:
                body.append(f"<p>{inline(lead)}</p>")
        elif kind == "h3":
            sid = f"section-{chap_no + 1}-{b[1].split('.')[1]}"
            toc.append((2, sid, f"{b[1]} {b[2]}"))
            body.append(f'<h3 id="{sid}">{b[1]} {esc(b[2])}</h3>')
        elif kind == "p":
            if b[1].startswith("**结论"):
                body.append(f'<p class="callout-info">&nbsp;&nbsp;{inline(b[1])}</p>')
            else:
                body.append(f"<p>{inline(b[1])}</p>")
        elif kind == "ul":
            body.append("<ul>")
            for it in b[1]:
                body.append(f"<li>{inline(it)}</li>")
            body.append("</ul>")
        elif kind == "ol":
            body.append("<ol>")
            for it in b[1]:
                body.append(f"<li>{inline(it)}</li>")
            body.append("</ol>")
        elif kind == "code":
            body.append(f"<pre><code>{esc(b[1])}</code></pre>")
        elif kind == "table":
            body.append(render_table(b[1]))
        elif kind == "quote":
            q = b[1].strip()
            if q.startswith("本文档基于"):
                body.append(f'<p class="src-note">&nbsp;&nbsp;{inline(q)}</p>')
            elif q.startswith("⚠"):
                q = q.replace("⚠️", "⚠")
                body.append(f'<p class="callout-warning">&nbsp;&nbsp;{inline(q)}</p>')
            else:
                body.append(f'<p class="callout-info">&nbsp;&nbsp;{inline(q)}</p>')

    # ---- 目录 HTML ----
    toc_html = ['<ol class="toc-list">']
    toc_html.append('      <li><a href="#section-1">执行摘要</a></li>')
    cur = None
    for lvl, sid, text in toc:
        if lvl == 1:
            if cur is not None:
                toc_html.append("        </ol>")
                toc_html.append("      </li>")
            toc_html.append(f'      <li><a href="#{sid}">{esc(text)}</a>')
            toc_html.append("        <ol>")
            cur = sid
        else:
            toc_html.append(f'          <li><a href="#{sid}">{esc(text)}</a></li>')
    if cur is not None:
        toc_html.append("        </ol>")
        toc_html.append("      </li>")
    toc_html.append("    </ol>")

    # ---- 执行摘要 ----
    summary = """  <table class="executive-summary summary-card">
    <thead>
      <tr><th id="section-1" class="summary-title">&nbsp;&nbsp;执行摘要</th></tr>
    </thead>
    <tbody>
      <tr>
        <td>
          <p class="summary-metric-line"><strong>新增工具域</strong>：30 个 novel_* 工具 ｜ <strong>基座缺陷修复</strong>：7 项（3 项 P0） ｜ <strong>移植资产</strong>：50 个工具定义 + 25 个流派预设 + 6 个子 Agent ｜ <strong>实施阶段</strong>：Phase 0–9 共 10 个</p>
          <p>本项目以开源安卓端侧 AI Agent <strong>ZorvAI</strong>（v1.1.7，Apache-2.0）为基座，通过领域化改造构建专注于小说创作的 AI Agent 软件 <strong>PenAi</strong>（目标仓库 qq1375828505/PenAi）。改造遵循「基座最小侵入、端到端闭环先行、资产平移而非重造」三大原则：Agent 内核（ReAct 主循环、长程编排、交付闸门、记忆四型、知识库 RAG）零改动复用，全部工作量集中于网文领域层。</p>
          <p>领域层建设以两个既有资产来源为输入：novelIDE 的 50 个工具定义与三段式中文描述、25 个番茄流派预设、6 个子 Agent 提示词与双轨记忆设计；AIGC-NetNov 的实体数据模型、章节状态机与上下文管理设计；两者均经代码级核查，确认约 95% 业务逻辑与 UI 框架无关，具备整体平移条件。同时，针对使用者向 ZorvAI 官方仓库反馈的 <span class="data-emphasis">7 项缺陷</span>（Issues #15–#21），本文档第 7 章逐条给出代码级核实结论、根因分析与修复方案，随 PenAi 分支同步修复并回馈上游。</p>
          <p>经用户评审决策，本版计划书冻结以下关键设计：<strong>多角色集群整体硬裁剪</strong>（子 Agent 降级为 callable 技能，经 novel_delegate_subagent 统一调度，零写权限、写操作一律回流主 AI，以职责边界矩阵消除冲突）；<strong>提示词资产以双技能套件承载</strong>（novel-writing 写作规约 + tomato-genre 二十五流派预设，用户可查看、修改、开关、导出）；<strong>富文本编辑器以独立可插拔模块保留</strong>（桥接契约冻结 + 端到端验收 + 降级纯文本不阻塞）；<strong>新增人物关系图系统</strong>（三表数据 + Compose Canvas 物理渲染，5-7 人周）；<strong>不做导入导出</strong>（数据依托基座本地工作区）；界面采用现代文学出版风（暖白纸底、墨黑正文、衬线标题、深靛蓝强调、细线大留白）。</p>
          <p>实施按 Phase 0–9 十个阶段推进，第一里程碑为端到端最小闭环（入口→工作台→建作品→AI 写一章→保存），全面规避 AIGC-NetNov「最后一公里失守」的失败模式。预期产出：可安装发布的 PenAi 应用、完整网文领域层代码、25 个流派预设与全套中文 prompt 资产、基座修复 Patch 及配套单测与端到端测试套件。</p>
        </td>
      </tr>
    </tbody>
  </table>"""

    html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="docx-page-size" content="A4">
<title>{esc(doc_title)}</title>
{CSS}
</head>
<body>

<!-- 封面 -->
<section role="cover" class="cover">
  <p class="cover-category" style="text-align: center;">软件改造计划书 · SOFTWARE RENOVATION PLAN</p>
  <p class="cover-title" style="text-align: center;">{esc(doc_title)}</p>
  <p class="cover-subtitle" style="text-align: center;">{esc(doc_subtitle)}</p>
  <div class="cover-meta" style="text-align: center;">
    <p class="cover-meta-item" style="text-align: center;">文档版本：V1.1</p>
    <p class="cover-meta-item" style="text-align: center;">编制日期：2026 年 10 月 16 日</p>
    <p class="cover-meta-item" style="text-align: center;">基座版本：ZorvAI v1.1.7（Kotlin 2.3 + Jetpack Compose，Apache-2.0）</p>
    <p class="cover-meta-item" style="text-align: center;">目标仓库：qq1375828505/PenAi</p>
    <p class="cover-meta-item" style="text-align: center;">修订说明：V1.1 纳入用户评审决策（2026-10-16）</p>
  </div>
</section>

<!-- 执行摘要（前置部分，独立成节） -->
<section role="cover">
{summary}
</section>

<!-- 目录 -->
<section role="cover" class="toc-section">
  <nav class="doc-toc" aria-label="文档目录">
    <p class="toc-title">目 录</p>
    {chr(10).join(toc_html)}
  </nav>
</section>

<!-- 正文 -->
<section role="body" class="report-body" data-page-restart="1">

{chr(10).join(body)}

</section>

</body>
</html>
"""
    with io.open(OUT, "w", encoding="utf-8") as f:
        f.write(html)

    # ---- 自检 ----
    n_h2 = len(re.findall(r"<h2 ", html))
    n_h3 = len(re.findall(r"<h3 ", html))
    n_tbl = len(re.findall(r"<table>", html))
    n_pre = len(re.findall(r"<pre>", html))
    n_callout = len(re.findall(r'class="callout-', html))
    print(f"OK -> {OUT}")
    print(f"h2={n_h2} h3={n_h3} table={n_tbl} pre={n_pre} callout/srcnote={n_callout}")
    print(f"toc_entries={len(toc)}")
    # 平衡检查
    for tag in ("table", "thead", "tbody", "tr", "td", "th", "ul", "ol", "li", "p", "h2", "h3", "pre", "code", "strong", "span"):
        o = len(re.findall(rf"<{tag}[ >]", html))
        c = len(re.findall(rf"</{tag}>", html))
        if o != c:
            print(f"!! UNBALANCED <{tag}>: open={o} close={c}")
    bad = re.findall(r"var\(--(?!ff-body|ff-mono|ff-heading|color-|spacing-|fs-|lh-|fw-|indent-|page-content|border-heading|typography-|margin-page)", html)
    if bad:
        print("!! suspicious var:", set(bad))
    print("self-check done")


if __name__ == "__main__":
    main()
