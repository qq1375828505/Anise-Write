#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""fix_docx_round3.py — PenAi 计划书 docx 保真后处理（文学出版风 literary-publishing）

输入：引擎原始转换产物（styles.xml 空、sectPr 空、无页脚、3712 处 var(--ff-body)
      字面量、0 pStyle、56 个 outlineLvl 标题、TOC 57 条双重编号、代码块换行折叠）。
输出：一站式 XML 级修复后重新打包的 docx。

修复清单：
  D1  3712 处 CSS 字体字面量 → Times New Roman/宋体（断言零残留）
  D2  重建 styles.xml：docDefaults + Normal + Heading1/2（outlineLvl 匹配直接格式）
      + TOC1/TOC2（静态目录样式，verify S-6 不误判）
  D3  3 个分节：封面（vAlign 垂直居中、无页码）/ 前置（摘要+目录，罗马页码）
      / 正文（"第 PAGE 页"、pgNumType decimal start=1）
  D4  自建 footer1.xml（罗马）/ footer2.xml（第 X 页）+ Content_Types + rels
  D5  逐段修复：封面 8 段 / 目录标题 / TOC 57 条去 numPr / 56 标题绑 pStyle+粗体+色
      字号 / 结论段左边线 callout / 正文 2em 首行缩进+1.5 倍行距 / 章首导语 muted
      列表行距 / 代码块重建（<w:br/> 恢复换行+等宽+底纹+细框）/ 尾注上线
  D6  表格：边框 CBCDD1→E5E1D8 细线、表头底纹 F5F2EA+粗体、单元格内距、
      摘要卡深靛蓝 3pt 左边线
  D7  fontTable 补 宋体/Consolas；core.xml 补标题
"""
import html as _html
import os
import re
import shutil
import subprocess
import sys
import zipfile

EX = "/tmp/docx_fix/extract"
SRC_DOCX = "/workspace/output/ceb31b98-c365-4eed-9c74-8ff9fe66870f/stage3/PenAi软件改造计划书.docx"
HTML = "/workspace/output/ceb31b98-c365-4eed-9c74-8ff9fe66870f/stage2/formatted-penai-software-renovation-plan.html"
OUT_DOCX = "/tmp/docx_fix/PenAi软件改造计划书.docx"

# ---------- 文学出版风调色板 ----------
C_TEXT = "1F1E1C"      # 墨黑
C_HEAD = "2B3A4A"      # 深墨蓝（章节标题）
C_PRIMARY = "3E5C76"   # 深靛蓝（小节标题/强调）
C_SECOND = "6E8697"    # 灰蓝（封面眉题）
C_MUTED = "6B6660"     # 暖灰（导语/尾注/页脚）
C_LINE = "E5E1D8"      # 细线
C_FILL = "F5F2EA"      # 暖纸底纹
C_ACCENT = "9E4A4A"    # 哑砖红（修订说明）
C_SOFT = "4A4640"      # 目录二级/代码
F_ASCII = "Times New Roman"
F_EA = "宋体"
F_MONO = "Consolas"

# ---------- schema 子元素序 ----------
PPR_ORDER = ["pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr",
             "widowControl", "numPr", "suppressLineNumbers", "pBdr", "shd", "tabs",
             "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct",
             "topLinePunct", "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd",
             "snapToGrid", "spacing", "ind", "contextualSpacing", "mirrorIndents",
             "suppressOverlap", "jc", "textDirection", "textAlignment",
             "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr",
             "pPrChange"]
RPR_ORDER = ["rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps",
             "strike", "dstrike", "outline", "shadow", "emboss", "imprint",
             "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing",
             "w", "kern", "position", "sz", "szCs", "highlight", "u", "effect",
             "bdr", "shd", "fitText", "vertAlign", "rtl", "cs", "em", "lang",
             "eastAsianLayout", "specVanish", "oMath"]

B = '<w:b w:val="1"/>'
NOB = '<w:b w:val="0"/>'


def _parse_elems(inner):
    """解析 pPr/rPr 直接子元素（支持 pBdr/numPr/rPr/sectPr 等含子元素的节点）。"""
    out = []
    for m in re.finditer(r"<w:(\w+)(?:\s[^>]*)?(?:/>|>.*?</w:\1>)", inner, re.S):
        out.append((m.group(1), m.group(0)))
    return out


def elems_set(inner, order, **kw):
    """按 updates 增删属性元素后按 schema 序重排。value=None 表示删除。"""
    d = {}
    for tag, xml in _parse_elems(inner):
        d.setdefault(tag, xml)
    for k, v in kw.items():
        if v is None:
            d.pop(k, None)
        else:
            d[k] = v
    parts = [d[t] for t in order if t in d]
    parts += [x for t, x in d.items() if t not in order]
    return "".join(parts)


def para_text(p):
    return "".join(re.findall(r"<w:t[^>]*>([^<]*)</w:t>", p))


def unesc(s):
    return (s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"')
             .replace("&apos;", "'").replace("&amp;", "&"))


def xesc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def get_ppr(p):
    m = re.search(r"<w:pPr>(.*?)</w:pPr>", p, re.S)
    return m


def set_ppr(p, **kw):
    """重写段落 pPr（保持段内 runs 不动）。"""
    m = get_ppr(p)
    if m:
        new_inner = elems_set(m.group(1), PPR_ORDER, **kw)
        return p[:m.start()] + "<w:pPr>" + new_inner + "</w:pPr>" + p[m.end():]
    # 无 pPr：在 <w:p ...> 后插入
    m2 = re.match(r"(<w:p\b[^>]*>)", p)
    return m2.group(1) + "<w:pPr>" + elems_set("", PPR_ORDER, **kw) + "</w:pPr>" + p[m2.end():]


def style_runs(p, **rpr_kw):
    """给段落所有 run 设置 rPr 属性（不动 pPr 内的段落标记 rPr）。"""
    def repl(m):
        inner = elems_set(m.group(2) or "", RPR_ORDER, **rpr_kw)
        return m.group(1) + "<w:rPr>" + inner + "</w:rPr>"
    return re.sub(r"(<w:r>)(?:<w:rPr>(.*?)</w:rPr>)?", repl, p, flags=re.S)


# ---------- D1：字体字面量 ----------
def fix_fonts(xml):
    for attr in ("ascii", "hAnsi", "cs"):
        xml = xml.replace('w:%s="var(--ff-body)"' % attr,
                          'w:%s="%s"' % (attr, F_ASCII))
    xml = xml.replace('w:eastAsia="var(--ff-body)"', 'w:eastAsia="%s"' % F_EA)
    for attr in ("ascii", "hAnsi", "cs"):
        xml = xml.replace('w:%s="var(--ff-mono)"' % attr,
                          'w:%s="%s"' % (attr, F_MONO))
    xml = xml.replace('w:eastAsia="var(--ff-mono)"', 'w:eastAsia="%s"' % F_EA)
    assert "var(--" not in xml, "D1 失败：仍有 CSS 变量字面量残留"
    return xml


# ---------- D2：styles.xml ----------
RF = ('<w:rFonts w:ascii="%s" w:hAnsi="%s" w:eastAsia="%s" w:cs="%s"/>'
      % (F_ASCII, F_ASCII, F_EA, F_ASCII))
RF_MONO = ('<w:rFonts w:ascii="%s" w:hAnsi="%s" w:eastAsia="%s" w:cs="%s"/>'
           % (F_MONO, F_MONO, F_EA, F_MONO))

STYLES_XML = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
    '<w:docDefaults><w:rPrDefault><w:rPr>' + RF +
    '<w:color w:val="%s"/><w:sz w:val="21"/><w:szCs w:val="21"/>'
    '<w:lang w:val="en-US" w:eastAsia="zh-CN" w:bidi="ar-SA"/>'
    '</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>'
    '<w:spacing w:after="0" w:line="340" w:lineRule="auto"/>'
    '</w:pPr></w:pPrDefault></w:docDefaults>'
    '<w:style w:type="paragraph" w:default="1" w:styleId="Normal">'
    '<w:name w:val="Normal"/><w:qFormat/>'
    '<w:pPr><w:spacing w:after="0" w:line="340" w:lineRule="auto"/></w:pPr>'
    '<w:rPr>' + RF + '<w:color w:val="%s"/><w:sz w:val="21"/><w:szCs w:val="21"/></w:rPr>'
    '</w:style>'
    '<w:style w:type="paragraph" w:styleId="Heading1">'
    '<w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/>'
    '<w:qFormat/><w:pPr><w:keepNext/><w:keepLines/>'
    '<w:pBdr><w:bottom w:val="single" w:sz="4" w:space="4" w:color="%s"/></w:pBdr>'
    '<w:spacing w:before="360" w:after="200" w:line="320" w:lineRule="auto"/>'
    '<w:outlineLvl w:val="1"/></w:pPr>'
    '<w:rPr>' + RF + B + '<w:bCs/><w:color w:val="%s"/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr>'
    '</w:style>'
    '<w:style w:type="paragraph" w:styleId="Heading2">'
    '<w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/>'
    '<w:qFormat/><w:pPr><w:keepNext/><w:keepLines/>'
    '<w:spacing w:before="280" w:after="140" w:line="320" w:lineRule="auto"/>'
    '<w:outlineLvl w:val="2"/></w:pPr>'
    '<w:rPr>' + RF + B + '<w:bCs/><w:color w:val="%s"/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr>'
    '</w:style>'
    '<w:style w:type="paragraph" w:styleId="TOC1">'
    '<w:name w:val="toc 1"/><w:basedOn w:val="Normal"/>'
    '<w:pPr><w:spacing w:before="100" w:after="0" w:line="280" w:lineRule="auto"/></w:pPr>'
    '</w:style>'
    '<w:style w:type="paragraph" w:styleId="TOC2">'
    '<w:name w:val="toc 2"/><w:basedOn w:val="Normal"/>'
    '<w:pPr><w:spacing w:after="0" w:line="280" w:lineRule="auto"/>'
    '<w:ind w:left="420"/></w:pPr>'
    '</w:style>'
    '</w:styles>'
) % (C_TEXT, C_TEXT, C_LINE, C_HEAD, C_PRIMARY)


# ---------- D5：逐段修复 ----------
COVER_SPEC = {
    "软件改造计划书 · SOFTWARE RENOVATION PLAN":
        dict(before=0, after=0, b=NOB, color=C_SECOND, sz=20),
    "PenAi 软件改造计划书":
        dict(before=360, after=200, b=B, color=C_HEAD, sz=52),
    "——基于 ZorvAI 基座的网文写作 AI Agent 改造方案":
        dict(before=0, after=0, b=NOB, color=C_MUTED, sz=24),
    "文档版本：V1.1":
        dict(before=900, after=0, b=NOB, color=C_MUTED, sz=21),
    "编制日期：2026 年 10 月 16 日":
        dict(before=0, after=0, b=NOB, color=C_MUTED, sz=21),
    "基座版本：ZorvAI v1.1.7（Kotlin 2.3 + Jetpack Compose，Apache-2.0）":
        dict(before=0, after=0, b=NOB, color=C_MUTED, sz=21),
    "目标仓库：qq1375828505/PenAi":
        dict(before=0, after=0, b=NOB, color=C_MUTED, sz=21),
    "修订说明：V1.1 纳入用户评审决策（2026-10-16）":
        dict(before=900, after=0, b=NOB, color=C_ACCENT, sz=18),
}

SP_AFTER120 = '<w:spacing w:before="0" w:after="120" w:line="340" w:lineRule="auto"/>'
IND_2EM = '<w:ind w:firstLine="420"/>'


def fix_cover(p, text):
    spec = COVER_SPEC.get(text)
    if not spec:
        return p
    p = set_ppr(p, spacing='<w:spacing w:before="%d" w:after="%d"/>'
               % (spec["before"], spec["after"]))
    p = style_runs(p, b=spec["b"], color='<w:color w:val="%s"/>' % spec["color"],
                   sz='<w:sz w:val="%d"/>' % spec["sz"],
                   szCs='<w:szCs w:val="%d"/>' % spec["sz"])
    return p


def fix_toc_title(p):
    p = set_ppr(p, spacing='<w:spacing w:before="240" w:after="320"/>')
    return style_runs(p, b=B, color='<w:color w:val="%s"/>' % C_HEAD,
                      sz='<w:sz w:val="32"/>', szCs='<w:szCs w:val="32"/>')


def fix_toc_entry(p):
    ilvl = re.search(r'<w:ilvl w:val="(\d)"/>', p)
    lvl = ilvl.group(1) if ilvl else "0"
    p = set_ppr(p, numPr=None,
                pStyle='<w:pStyle w:val="TOC%d"/>' % (int(lvl) + 1))
    if lvl == "0":
        p = style_runs(p, b=B, color='<w:color w:val="%s"/>' % C_HEAD,
                       sz='<w:sz w:val="22"/>', szCs='<w:szCs w:val="22"/>')
    else:
        p = style_runs(p, b=NOB, color='<w:color w:val="%s"/>' % C_SOFT,
                       sz='<w:sz w:val="21"/>', szCs='<w:szCs w:val="21"/>')
    return p


def fix_heading(p, lvl):
    p = set_ppr(p, spacing=None, rPr=None,
                pStyle='<w:pStyle w:val="Heading%d"/>' % (1 if lvl == "1" else 2))
    color = C_HEAD if lvl == "1" else C_PRIMARY
    sz = 32 if lvl == "1" else 26
    return style_runs(p, b=B, color='<w:color w:val="%s"/>' % color,
                      sz='<w:sz w:val="%d"/>' % sz,
                      szCs='<w:szCs w:val="%d"/>' % sz)


def fix_callout(p, accent=C_PRIMARY, fill=C_FILL):
    """callout 段：左侧色条 + 暖纸底纹 + 小字号 + 无首行缩进（镜像 CSS）。"""
    p = set_ppr(
        p,
        pBdr=('<w:pBdr><w:left w:val="single" w:sz="18" w:space="6" '
              'w:color="%s"/></w:pBdr>' % accent),
        shd='<w:shd w:val="clear" w:color="auto" w:fill="%s"/>' % fill,
        spacing='<w:spacing w:before="120" w:after="120" w:line="320" w:lineRule="auto"/>',
        ind='<w:ind w:left="240"/>')
    return style_runs(p, sz='<w:sz w:val="21"/>', szCs='<w:szCs w:val="21"/>')


def fix_endnote(p):
    """src-note 尾注：暖纸底纹 + 9pt 暖灰（镜像 CSS，无边框）。"""
    p = set_ppr(
        p, pBdr=None,
        shd='<w:shd w:val="clear" w:color="auto" w:fill="%s"/>' % C_FILL,
        spacing='<w:spacing w:before="240" w:after="0" w:line="300" w:lineRule="auto"/>')
    return style_runs(p, color='<w:color w:val="%s"/>' % C_MUTED,
                      sz='<w:sz w:val="18"/>', szCs='<w:szCs w:val="18"/>')


def fix_list(p):
    return set_ppr(p, spacing='<w:spacing w:after="60" w:line="320" w:lineRule="auto"/>')


def fix_prose(p, leadin=False):
    p = set_ppr(p, spacing=SP_AFTER120, ind=IND_2EM)
    if leadin:
        p = style_runs(p, color='<w:color w:val="%s"/>' % C_MUTED)
    return p


def apply_emphasis(xml, html_src):
    """按 HTML data-emphasis span 精确定位并拆分 run：短语 run 加粗+哑砖红。

    文本出现次数（19）多于 span 数（15）——"7 项"会误中"7 项缺陷"与未强调的
    正文重复出现，故不能按短语全局替换。做法：HTML 线性扫描得每个 span 的
    （前置上下文, 短语），再到 docx 的 run 序列中按上下文锚定拆分。
    """
    src = re.sub(r"<!--.*?-->", "", html_src, flags=re.S)
    src = re.sub(r"<(script|style)[^>]*>.*?</\1>", "", src, flags=re.S | re.I)
    spans, open_ph, ctx = [], None, []
    for m in re.finditer(r"<[^>]+>|[^<]+", src):
        tok = m.group(0)
        if tok.startswith("<"):
            if re.match(r'<span class="data-emphasis"[^>]*>', tok):
                open_ph = []
            elif tok == "</span>" and open_ph is not None:
                # 短语保留原始空格（docx 文本带空格）；上下文去空白后比较
                phrase = " ".join("".join(open_ph).split())
                spans.append(("".join(ctx[-12:]), phrase))
                ctx.extend("".join(open_ph).split())
                open_ph = None
        else:
            t = _html.unescape(tok)
            if open_ph is not None:
                open_ph.append(t)
            else:
                ctx.extend("".join(t.split()))
    assert open_ph is None and len(spans) == 15, \
        "HTML span 扫描异常: %d 个（未闭合=%s）" % (len(spans), open_ph is not None)

    # docx run 序列（单 w:t 形态）+ 每个 run 之前的去空白可见文本（跨 run 累积）
    runs = [(rm, unesc(rm.group(2)))
            for rm in re.finditer(r"<w:r><w:rPr>(.*?)</w:rPr><w:t[^>]*>([^<]*)</w:t></w:r>",
                                  xml, re.S)]
    priors, acc = [], []
    for _rm, text in runs:
        priors.append("".join(acc))
        acc.extend("".join(text.split()))
    # span -> run 锚定（同一 run 可含多个 span）
    plan = {}  # run_idx -> [(pos, phrase)]
    for prefix, phrase in spans:
        hit = None
        for idx, (rm, text) in enumerate(runs):
            start = 0
            while True:
                pos = text.find(phrase, start)
                if pos < 0:
                    break
                full_before = priors[idx] + "".join(text[:pos].split())
                if (full_before.endswith(prefix)
                        or (full_before and prefix.endswith(full_before))
                        or not prefix):
                    hit = (idx, pos)
                    break
                start = pos + 1
            if hit:
                break
        assert hit, "强调 span 无法定位: 上下文=%r 短语=%r" % (prefix, phrase)
        plan.setdefault(hit[0], []).append((hit[1], phrase))

    # 从后向前应用拆分（保持 earlier match 位置有效）
    for idx in sorted(plan, reverse=True):
        rm, text = runs[idx]
        cuts = sorted(plan[idx])
        segs, last = [], 0
        for pos, phrase in cuts:
            if pos > last:
                segs.append((False, text[last:pos]))
            segs.append((True, phrase))
            last = pos + len(phrase)
        if last < len(text):
            segs.append((False, text[last:]))
        rpr = rm.group(1)
        parts = []
        for is_em, seg in segs:
            if not seg:
                continue
            r = elems_set(rpr, RPR_ORDER, b=B,
                          color='<w:color w:val="%s"/>' % C_ACCENT) if is_em else rpr
            parts.append('<w:r><w:rPr>%s</w:rPr><w:t xml:space="preserve">%s</w:t></w:r>'
                         % (r, xesc(seg)))
        xml = xml[:rm.start()] + "".join(parts) + xml[rm.end():]
    return xml, sum(len(v) for v in plan.values())


def build_code_para(text):
    """代码块：等宽+暖纸底纹+细框；<w:br/> 必须是 run 级元素（w:t 内只放纯文本）。"""
    lines = text.split("\n")
    inner = "<w:br/>".join('<w:t xml:space="preserve">%s</w:t>' % xesc(l) for l in lines)
    return (
        '<w:p><w:pPr><w:pBdr>'
        '<w:top w:val="single" w:sz="4" w:space="6" w:color="%s"/>'
        '<w:left w:val="single" w:sz="4" w:space="6" w:color="%s"/>'
        '<w:bottom w:val="single" w:sz="4" w:space="6" w:color="%s"/>'
        '<w:right w:val="single" w:sz="4" w:space="6" w:color="%s"/>'
        '</w:pBdr><w:shd w:val="clear" w:color="auto" w:fill="%s"/>'
        '<w:spacing w:before="120" w:after="120" w:line="300" w:lineRule="auto"/>'
        '<w:ind w:left="160" w:right="160"/><w:jc w:val="left"/></w:pPr>'
        '<w:r><w:rPr>' % (C_LINE, C_LINE, C_LINE, C_LINE, C_FILL) + RF_MONO +
        '<w:color w:val="%s"/><w:sz w:val="16"/><w:szCs w:val="16"/></w:rPr>' % C_SOFT
        + inner + '</w:r></w:p>')


def main():
    # ---- 0. fresh extract（保证脚本可重入）----
    if os.path.isdir(EX):
        shutil.rmtree(EX)
    os.makedirs(EX)
    with zipfile.ZipFile(SRC_DOCX) as z:
        z.extractall(EX)

    doc_path = os.path.join(EX, "word", "document.xml")
    xml = open(doc_path, encoding="utf-8").read()
    stats = {}

    # ---- D1 字体 ----
    xml = fix_fonts(xml)

    # ---- D5 逐段 ----
    html_src = open(HTML, encoding="utf-8").read()
    pre_texts = []
    for pr in re.findall(r"<pre[^>]*>(.*?)</pre>", html_src, re.S):
        pre_texts.append(_html.unescape(re.sub(r"<[^>]+>", "", pr)))
    assert len(pre_texts) == 2, "HTML pre 数量异常: %d" % len(pre_texts)

    tbl_spans = [(m.start(), m.end()) for m in re.finditer(r"<w:tbl>.*?</w:tbl>", xml, re.S)]
    first_tbl = tbl_spans[0][0] if tbl_spans else len(xml)

    def in_table(s, e):
        return any(ts <= s and e <= te for ts, te in tbl_spans)

    def fix_cell_para(p):
        m = get_ppr(p)
        if not m:
            return p
        inner = elems_set(m.group(1), PPR_ORDER,
                          spacing='<w:spacing w:after="0" w:line="280" w:lineRule="auto"/>')
        return p[:m.start()] + "<w:pPr>" + inner + "</w:pPr>" + p[m.end():]

    state = {"after_h1": False}
    counters = dict(cover=0, toc_title=0, toc_entry=0, h1=0, h2=0, callout_info=0,
                    callout_warn=0, endnote=0, list=0, prose=0, leadin=0, code=0,
                    cell=0)
    out, pos = [], 0
    for m in re.finditer(r"<w:p\b[^>]*/>|<w:p\b[^>]*>.*?</w:p>", xml, re.S):
        out.append(xml[pos:m.start()])
        p = m.group(0)
        if in_table(m.start(), m.end()):
            state["after_h1"] = False
            counters["cell"] += 1
            out.append(fix_cell_para(p))
            pos = m.end()
            continue
        text = unesc(para_text(p))
        if not text.strip():
            out.append(p)
            pos = m.end()
            continue
        if m.start() < first_tbl:
            counters["cover"] += 1
            out.append(fix_cover(p, text))
        elif text == "目 录":
            state["after_h1"] = False
            counters["toc_title"] += 1
            out.append(fix_toc_title(p))
        elif '<w:numId w:val="1"/>' in p:
            state["after_h1"] = False
            counters["toc_entry"] += 1
            out.append(fix_toc_entry(p))
        elif "<w:outlineLvl" in p:
            lvl = re.search(r'<w:outlineLvl w:val="(\d)"/>', p).group(1)
            counters["h1" if lvl == "1" else "h2"] += 1
            state["after_h1"] = (lvl == "1")
            out.append(fix_heading(p, lvl))
        elif text.startswith("结论") or text.startswith("RAG 保留前提"):
            state["after_h1"] = False
            counters["callout_info"] += 1
            out.append(fix_callout(p, accent=C_PRIMARY, fill=C_FILL))
        elif text.startswith("⚠"):
            state["after_h1"] = False
            counters["callout_warn"] += 1
            out.append(fix_callout(p, accent=C_ACCENT, fill="F7F1E8"))
        elif "interface QuroTool {" in text or text.startswith("┌"):
            state["after_h1"] = False
            hit = [t for t in pre_texts if t.replace("\n", "") == text]
            assert hit, "代码块无法与 HTML pre 对齐: %s…" % text[:30]
            counters["code"] += 1
            out.append(build_code_para(hit[0]))
        elif text.startswith("本文档基于"):
            state["after_h1"] = False
            counters["endnote"] += 1
            out.append(fix_endnote(p))
        elif "<w:numPr>" in p:
            state["after_h1"] = False
            counters["list"] += 1
            out.append(fix_list(p))
        else:
            leadin = state["after_h1"]
            state["after_h1"] = False
            counters["prose"] += 1
            if leadin:
                counters["leadin"] += 1
            out.append(fix_prose(p, leadin=leadin))
        pos = m.end()
    out.append(xml[pos:])
    xml = "".join(out)
    stats["paras"] = counters
    assert counters["h1"] == 12 and counters["h2"] == 44, "标题数异常: %s" % counters
    assert counters["toc_entry"] == 57, "TOC 条目数异常: %d" % counters["toc_entry"]
    assert counters["code"] == 2, "代码块重建数异常: %d" % counters["code"]
    assert counters["cover"] == 8, "封面段数异常: %d" % counters["cover"]
    assert counters["callout_info"] == 2 and counters["callout_warn"] == 1, \
        "callout 数异常: %s" % counters

    # ---- data-emphasis 强调恢复（span 锚定 + run 拆分）----
    xml, n_emph = apply_emphasis(xml, html_src)
    stats["emphasis_runs"] = n_emph
    assert n_emph == 15, "强调 span 命中数异常: %d（期望 15）" % n_emph

    # ---- D6 表格 ----
    n_cellmar = xml.count("<w:tblCellMar/>")
    xml = xml.replace(
        "<w:tblCellMar/>",
        '<w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:left w:w="100" w:type="dxa"/>'
        '<w:bottom w:w="40" w:type="dxa"/><w:right w:w="100" w:type="dxa"/></w:tblCellMar>')
    old_tcb = ('<w:tcBorders><w:top w:val="single" w:sz="8"/>'
               '<w:left w:val="single" w:sz="8"/><w:bottom w:val="single" w:sz="8"/>'
               '<w:right w:val="single" w:sz="8"/></w:tcBorders>')
    new_tcb = ('<w:tcBorders><w:top w:val="single" w:sz="4" w:color="%s"/>'
               '<w:left w:val="single" w:sz="4" w:color="%s"/>'
               '<w:bottom w:val="single" w:sz="4" w:color="%s"/>'
               '<w:right w:val="single" w:sz="4" w:color="%s"/></w:tcBorders>'
               % (C_LINE, C_LINE, C_LINE, C_LINE))
    n_tcb = xml.count(old_tcb)
    xml = xml.replace(old_tcb, new_tcb)
    # 摘要卡（第一个表）左边线改深靛蓝粗线
    first_tbl_end = xml.find("</w:tbl>")
    head_tbl = xml[:first_tbl_end]
    head_tbl = re.sub(
        r'(<w:tblBorders><w:top w:val="single" w:color="CBCDD1" w:sz="6" w:space="0"/>'
        r'<w:left w:val="single" w:color=")CBCDD1(" w:sz="6" w:space="0"/>)',
        r'\g<1>%s\g<2>' % C_PRIMARY, head_tbl)
    head_tbl = head_tbl.replace('<w:left w:val="single" w:color="%s" w:sz="6" w:space="0"/>' % C_PRIMARY,
                                '<w:left w:val="single" w:color="%s" w:sz="24" w:space="0"/>' % C_PRIMARY)
    xml = head_tbl + xml[first_tbl_end:]
    # 其余表框线换色
    xml = xml.replace('w:color="CBCDD1"', 'w:color="%s"' % C_LINE)
    # 表头底纹
    n_shd = xml.count("<w:shd/>")
    xml = xml.replace("<w:shd/>",
                      '<w:shd w:val="clear" w:color="auto" w:fill="%s"/>' % C_FILL)

    # 表头单元格（tblHeader 行）：补 shd + 粗体 + 标题色
    n_hdr_cell = 0

    def fix_header_tr(mt):
        nonlocal n_hdr_cell
        tr = mt.group(0)
        if "tblHeader" not in tr:
            return tr

        def fix_tc(mc):
            nonlocal n_hdr_cell
            tc = mc.group(0)
            n_hdr_cell += 1
            if "<w:shd" not in tc:
                tc = tc.replace("<w:tcMar/>",
                                '<w:shd w:val="clear" w:color="auto" w:fill="%s"/><w:tcMar/>' % C_FILL, 1)
            tc = re.sub(r'(<w:rFonts[^>]*/>)(?:<w:b w:val="0"/>)?',
                        r'\g<1>' + B + '<w:color w:val="%s"/>' % C_HEAD, tc)
            return tc

        return re.sub(r"<w:tc\b[^>]*>.*?</w:tc>", fix_tc, tr, flags=re.S)

    xml = re.sub(r"<w:tr\b[^>]*>.*?</w:tr>", fix_header_tr, xml, flags=re.S)
    # 摘要卡标题段（执行摘要）加大字号（锚点=刚注入的 run 颜色，避开 pPr 标记 rPr）
    i_ea = xml.find(">执行摘要</w:t>")
    if i_ea > 0:
        seg_start = xml.rfind("<w:p ", 0, i_ea)
        seg_end = xml.find("</w:p>", i_ea) + 6
        seg = xml[seg_start:seg_end]
        assert '<w:color w:val="%s"/>' % C_HEAD in seg, "执行摘要段未找到注入色"
        seg2 = seg.replace('<w:color w:val="%s"/>' % C_HEAD,
                           '<w:color w:val="%s"/><w:sz w:val="24"/><w:szCs w:val="24"/>' % C_HEAD, 1)
        xml = xml[:seg_start] + seg2 + xml[seg_end:]

    stats["table"] = dict(tblCellMar=n_cellmar, tcBorders=n_tcb, shd=n_shd,
                          header_cells=n_hdr_cell,
                          tblHeader_rows=len(re.findall(r"<w:tblHeader", xml)))

    # ---- D3 分节 + D4 页脚 ----
    PG = '<w:pgSz w:w="11906" w:h="16838"/>'
    MARGIN = ('<w:pgMar w:top="1440" w:right="1800" w:bottom="1440" w:left="1800" '
              'w:header="720" w:footer="720" w:gutter="0"/>')
    cover_sect = ('<w:p><w:pPr><w:sectPr>' + PG + MARGIN +
                  '<w:vAlign w:val="center"/></w:sectPr></w:pPr></w:p>')
    fm_sect = ('<w:p><w:pPr><w:sectPr><w:footerReference w:type="default" r:id="rId5"/>'
               + PG + MARGIN + '<w:pgNumType w:fmt="upperRoman" w:start="1"/></w:sectPr></w:pPr></w:p>')
    body_sect = ('<w:sectPr><w:footerReference w:type="default" r:id="rId6"/>' + PG + MARGIN
                 + '<w:pgNumType w:fmt="decimal" w:start="1"/></w:sectPr>')

    i_tbl = xml.find("<w:tbl>")
    assert i_tbl > 0
    xml = xml[:i_tbl] + cover_sect + xml[i_tbl:]
    m_h = re.search(r"<w:outlineLvl", xml)
    h_start = xml.rfind("<w:p ", 0, m_h.start())
    xml = xml[:h_start] + fm_sect + xml[h_start:]
    assert "<w:sectPr/></w:body>" in xml
    xml = xml.replace("<w:sectPr/></w:body>", body_sect + "</w:body>")
    # 根元素补 xmlns:r（footerReference 需要）
    xml = xml.replace(
        '<w:document xmlns:w14=',
        '<w:document xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:w14=',
        1)
    assert 'xmlns:r=' in xml

    # ---- 写回 document.xml ----
    open(doc_path, "w", encoding="utf-8").write(xml)

    # ---- D2 写 styles.xml ----
    open(os.path.join(EX, "word", "styles.xml"), "w", encoding="utf-8").write(STYLES_XML)

    # ---- D7 fontTable / core.xml ----
    ft_path = os.path.join(EX, "word", "fontTable.xml")
    ft = open(ft_path, encoding="utf-8").read()
    add_fonts = ('<w:font w:name="宋体"><w:altName w:val="SimSun"/>'
                 '<w:panose1 w:val="02010600030101010101"/><w:charset w:val="86"/>'
                 '<w:family w:val="auto"/><w:pitch w:val="variable"/></w:font>'
                 '<w:font w:name="Consolas"><w:panose1 w:val="020B0503020204020204"/>'
                 '<w:charset w:val="00"/><w:family w:val="modern"/>'
                 '<w:pitch w:val="fixed"/></w:font>')
    if "宋体" not in ft:
        ft = ft.replace("</w:fonts>", add_fonts + "</w:fonts>")
        open(ft_path, "w", encoding="utf-8").write(ft)

    core_path = os.path.join(EX, "docProps", "core.xml")
    core = open(core_path, encoding="utf-8").read()
    if "<dc:title>" not in core:
        core = re.sub(r'(<cp:coreProperties[^>]*>)',
                      r'\1<dc:title>PenAi 软件改造计划书</dc:title>', core, count=1)
        open(core_path, "w", encoding="utf-8").write(core)

    # ---- D4 页脚部件 ----
    FRPR = ('<w:rPr>' + RF + '<w:color w:val="%s"/><w:sz w:val="18"/>'
            '<w:szCs w:val="18"/></w:rPr>' % C_MUTED)

    def page_field(placeholder):
        return ('<w:r>' + FRPR + '<w:fldChar w:fldCharType="begin"/></w:r>'
                '<w:r>' + FRPR + '<w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>'
                '<w:r>' + FRPR + '<w:fldChar w:fldCharType="separate"/></w:r>'
                '<w:r>' + FRPR + '<w:t>' + placeholder + '</w:t></w:r>'
                '<w:r>' + FRPR + '<w:fldChar w:fldCharType="end"/></w:r>')

    FTR_HDR = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
               'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">')
    footer1 = (FTR_HDR + '<w:p><w:pPr><w:spacing w:after="0"/><w:jc w:val="center"/></w:pPr>'
               '<w:r>' + FRPR + '<w:t xml:space="preserve">— </w:t></w:r>'
               + page_field("I") +
               '<w:r>' + FRPR + '<w:t xml:space="preserve"> —</w:t></w:r>'
               '</w:p></w:ftr>')
    footer2 = (FTR_HDR + '<w:p><w:pPr><w:spacing w:after="0"/><w:jc w:val="center"/></w:pPr>'
               '<w:r>' + FRPR + '<w:t xml:space="preserve">第 </w:t></w:r>'
               + page_field("1") +
               '<w:r>' + FRPR + '<w:t xml:space="preserve"> 页</w:t></w:r>'
               '</w:p></w:ftr>')
    open(os.path.join(EX, "word", "footer1.xml"), "w", encoding="utf-8").write(footer1)
    open(os.path.join(EX, "word", "footer2.xml"), "w", encoding="utf-8").write(footer2)

    ct_path = os.path.join(EX, "[Content_Types].xml")
    ct = open(ct_path, encoding="utf-8").read()
    if "footer1.xml" not in ct:
        ct = ct.replace("</Types>",
                        '<Override PartName="/word/footer1.xml" ContentType='
                        '"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>'
                        '<Override PartName="/word/footer2.xml" ContentType='
                        '"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>'
                        "</Types>")
        open(ct_path, "w", encoding="utf-8").write(ct)

    rels_path = os.path.join(EX, "word", "_rels", "document.xml.rels")
    rels = open(rels_path, encoding="utf-8").read()
    if "footer1.xml" not in rels:
        rels = rels.replace("</Relationships>",
                            '<Relationship Id="rId5" Type="http://schemas.openxmlformats.org/'
                            'officeDocument/2006/relationships/footer" Target="footer1.xml"/>'
                            '<Relationship Id="rId6" Type="http://schemas.openxmlformats.org/'
                            'officeDocument/2006/relationships/footer" Target="footer2.xml"/>'
                            "</Relationships>")
        open(rels_path, "w", encoding="utf-8").write(rels)

    # ---- 断言 ----
    head_paras = re.findall(r"<w:p\b[^>]*>(?:(?!</w:p>).)*?<w:outlineLvl(?:(?!</w:p>).)*?</w:p>",
                            xml, re.S)
    checks = {
        "var残留": xml.count("var(--"),
        "pStyle总数": len(re.findall(r"<w:pStyle ", xml)),
        "outlineLvl": xml.count("<w:outlineLvl"),
        "sectPr": len(re.findall(r"<w:sectPr", xml)),
        "footerReference": xml.count("footerReference"),
        "br换行": xml.count("<w:br/>"),
        "numId1残留": xml.count('<w:numId w:val="1"/>'),
        "shd底纹": xml.count('w:fill="%s"' % C_FILL),
        "细线色": xml.count(C_LINE),
        "加粗run": len(re.findall(r"<w:b w:val=\"1\"/>|<w:b/>", xml)),
        "标题未绑样式": sum(1 for hp in head_paras if "<w:pStyle" not in hp),
    }
    assert checks["var残留"] == 0, checks
    assert checks["pStyle总数"] == 56 + 57, checks
    assert checks["outlineLvl"] == 56, checks
    assert checks["sectPr"] == 3, checks
    assert checks["footerReference"] == 2, checks
    assert checks["br换行"] >= 24, checks
    assert checks["numId1残留"] == 0, checks
    assert checks["标题未绑样式"] == 0, checks
    stats["checks"] = checks

    # ---- 打包 ----
    if os.path.exists(OUT_DOCX):
        os.remove(OUT_DOCX)
    names = []
    for root, _dirs, files in os.walk(EX):
        for f in files:
            full = os.path.join(root, f)
            names.append(os.path.relpath(full, EX))
    names.sort(key=lambda n: (n != "[Content_Types].xml", n != "_rels/.rels", n))
    with zipfile.ZipFile(OUT_DOCX, "w", zipfile.ZIP_DEFLATED) as z:
        for n in names:
            z.write(os.path.join(EX, n), n.replace(os.sep, "/"))
    stats["zip_entries"] = len(names)
    stats["size_bytes"] = os.path.getsize(OUT_DOCX)

    print("OK " + __import__("json").dumps(stats, ensure_ascii=False))


if __name__ == "__main__":
    main()
