#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PenAi 软件改造计划书.docx — Round 2 保真修复（XML 级）
修复转换引擎遗留的三类缺陷 + 版式保真缺口：
  D1 字体字面量 var(--ff-body) 未解析（750 处）
  D2 正文 run 无字号/颜色（继承 Word 默认 10/11pt）
  D3 目录条目被加自动编号 numId=1（与文本序号重复）
  D4 正文缺首行缩进 2em（HTML text-indent 硬规范）
  D5 封面/目录标题/表格表头/代码块/注释 的字号字体颜色未落地
"""
import re
import sys

BASE = '/tmp/docx_fix/extract'
DOC = f'{BASE}/word/document.xml'
STY = f'{BASE}/word/styles.xml'
NUM = f'{BASE}/word/numbering.xml'

BODY_F = ('Times New Roman', 'Times New Roman', '宋体', 'Times New Roman')
HEAD_F = ('Microsoft YaHei', 'Microsoft YaHei', '微软雅黑', 'Microsoft YaHei')
MONO_F = ('Consolas', 'Consolas', '宋体', 'Consolas')

C_TEXT = '212121'      # --color-text
C_HEADING = '1A237E'   # --color-heading
C_PRIMARY = '1565C0'   # --color-primary
C_SECONDARY = '0288D1' # --color-secondary
C_MUTED = '616161'     # --color-muted
FILL_HL = 'F5F7FA'     # --color-highlight / --color-note-bg

report = {}

# ============ 1. 全局字体字面量替换 ============
xml = open(DOC, encoding='utf-8').read()
n_font = xml.count('var(--ff-body)')
xml = (xml.replace('w:ascii="var(--ff-body)"', 'w:ascii="Times New Roman"')
          .replace('w:hAnsi="var(--ff-body)"', 'w:hAnsi="Times New Roman"')
          .replace('w:eastAsia="var(--ff-body)"', 'w:eastAsia="宋体"')
          .replace('w:cs="var(--ff-body)"', 'w:cs="Times New Roman"'))
assert 'var(--' not in xml, 'D1 未清除：仍有 CSS 变量字面量'
report['D1_font_literals_fixed'] = n_font

# ============ 2. 结构跨度计算 ============
tbl_spans = [(m.start(), m.end()) for m in re.finditer(r'<w:tbl>.*?</w:tbl>', xml, re.S)]
tr_spans = [(m.start(), m.end()) for m in re.finditer(r'<w:tr\b.*?</w:tr>', xml, re.S)]
tc_spans = [(m.start(), m.end()) for m in re.finditer(r'<w:tc>.*?</w:tc>', xml, re.S)]

def in_spans(pos, spans):
    return any(s <= pos < e for s, e in spans)

def enclosing(pos, spans):
    for s, e in spans:
        if s <= pos < e:
            return (s, e)
    return None

def text_of(p):
    return ''.join(re.findall(r'<w:t[^>]*>([^<]*)</w:t>', p))

# 摘要卡表 = 含"执行摘要"表头的表
summary_tbl = None
for s, e in tbl_spans:
    if '执行摘要' in xml[s:e]:
        summary_tbl = (s, e)
        break

# ============ 3. run 级 rPr 重建 ============
def rfonts_xml(f):
    return (f'<w:rFonts w:hint="eastAsia" w:ascii="{f[0]}" w:hAnsi="{f[1]}" '
            f'w:eastAsia="{f[2]}" w:cs="{f[3]}"/>')

def set_rpr(run, fonts=None, bold=None, color=None, sz=None, spacing=None):
    """按 OOXML rPr 子元素序（rFonts,b,bCs,color,spacing,sz,szCs）重建。
    fonts/bold/color/sz/spacing 为 None 时保留原值（若原本存在）。"""
    m = re.search(r'<w:rPr>(.*?)</w:rPr>', run, re.S)
    old = m.group(1) if m else ''
    # 保留/覆写 rFonts
    rf = re.search(r'<w:rFonts\b[^>]*/>', old)
    if fonts is not None:
        rf_xml = rfonts_xml(fonts)
    else:
        rf_xml = rf.group(0) if rf else ''
    # 保留/覆写 b
    bm = re.search(r'<w:b w:val="([01])"/>', old)
    if bold is True:
        b_xml = '<w:b w:val="1"/><w:bCs w:val="1"/>'
    elif bold is False:
        b_xml = '<w:b w:val="0"/><w:bCs w:val="0"/>'
    elif bm:
        b_xml = f'<w:b w:val="{bm.group(1)}"/><w:bCs w:val="{bm.group(1)}"/>'
    else:
        b_xml = ''
    # 保留/覆写 color
    cm = re.search(r'<w:color w:val="([0-9A-Fa-f]{6})"/>', old)
    c_xml = f'<w:color w:val="{color}"/>' if color else (cm.group(0) if cm else '')
    # 保留/覆写 spacing（字距）
    sm = re.search(r'<w:spacing w:val="(\d+)"/>', old)
    s_xml = f'<w:spacing w:val="{spacing}"/>' if spacing is not None else (sm.group(0) if sm else '')
    # sz
    sz_xml = f'<w:sz w:val="{sz}"/><w:szCs w:val="{sz}"/>' if sz else ''
    new_rpr = '<w:rPr>' + rf_xml + b_xml + c_xml + s_xml + sz_xml + '</w:rPr>'
    if m:
        return run[:m.start()] + new_rpr + run[m.end():]
    rm = re.match(r'(<w:r\b[^>]*>)', run)
    return run[:rm.end()] + new_rpr + run[rm.end():]

def fix_runs(para, **kw):
    """对段落内所有 run 应用 set_rpr"""
    def _r(m):
        return set_rpr(m.group(0), **kw)
    return re.sub(r'<w:r\b[^>]*>.*?</w:r>', _r, para, flags=re.S)

# ============ 4. 段落级 pPr 重建 ============
PPR_ORDER = ['pStyle', 'keepNext', 'keepLines', 'pageBreakBefore', 'framePr', 'widowControl',
             'numPr', 'suppressLineNumbers', 'pBdr', 'shd', 'tabs', 'suppressAutoHyphens',
             'kinsoku', 'wordWrap', 'overflowPunct', 'topLinePunct', 'autoSpaceDE', 'autoSpaceDN',
             'bidi', 'adjustRightInd', 'snapToGrid', 'spacing', 'ind', 'contextualSpacing',
             'mirrorIndents', 'suppressOverlap', 'jc', 'textDirection', 'textAlignment',
             'textboxTightWrap', 'outlineLvl', 'divId', 'cnfStyle', 'rPr', 'sectPr', 'pPrChange']
SPACING_ATTR_ORDER = ['before', 'after', 'line', 'lineRule']
IND_ATTR_ORDER = ['left', 'leftChars', 'right', 'rightChars', 'hanging', 'hangingChars',
                  'firstLine', 'firstLineChars']

def _merge_attrs(cur_xml, tag, updates):
    """解析现有 <w:tag a=".."/> 属性字典，合并 updates，按规范序输出"""
    attrs = {}
    if cur_xml:
        for k, v in re.findall(r'w:(\w+)="([^"]*)"', cur_xml):
            attrs[k] = v
    attrs.update(updates)
    order = SPACING_ATTR_ORDER if tag == 'spacing' else IND_ATTR_ORDER
    parts = [f'w:{k}="{attrs[k]}"' for k in order if k in attrs]
    return f'<w:{tag} ' + ' '.join(parts) + '/>'

def set_ppr(para, *, numpr='keep', spacing=None, ind=None, jc=None, pbdr=None, shd=None):
    """重建 pPr：numpr='keep'|'drop'|<xml>；spacing/ind 为属性 dict（合并）；pbdr/shd 为完整 xml"""
    m = re.search(r'<w:pPr>(.*?)</w:pPr>', para, re.S)
    inner = m.group(1) if m else ''
    def grab(tag):
        mm = re.search(rf'<w:{tag}\b[^>]*/>', inner)
        if mm:
            return mm.group(0)
        mm = re.search(rf'<w:{tag}\b[^>]*>.*?</w:{tag}>', inner, re.S)
        return mm.group(0) if mm else None
    parts = {t: grab(t) for t in PPR_ORDER if t not in ('spacing', 'ind')}
    # spacing / ind 合并
    sp = _merge_attrs(grab('spacing'), 'spacing', spacing or {})
    ind_x = _merge_attrs(grab('ind'), 'ind', ind or {})
    # numPr
    if numpr == 'drop':
        parts['numPr'] = None
    elif numpr != 'keep':
        parts['numPr'] = numpr
    # jc
    if jc:
        parts['jc'] = f'<w:jc w:val="{jc}"/>'
    # pBdr / shd
    if pbdr is not None:
        parts['pBdr'] = pbdr
    if shd is not None:
        parts['shd'] = shd
    # 若 spacing/ind 无属性则丢弃空元素
    if not re.search(r'w:\w+=', sp):
        sp = None
    if not re.search(r'w:\w+=', ind_x):
        ind_x = None
    out = []
    for t in PPR_ORDER:
        if t == 'spacing':
            if sp:
                out.append(sp)
        elif t == 'ind':
            if ind_x:
                out.append(ind_x)
        elif parts.get(t):
            out.append(parts[t])
    new_ppr = '<w:pPr>' + ''.join(out) + '</w:pPr>'
    if m:
        return para[:m.start()] + new_ppr + para[m.end():]
    rm = re.match(r'(<w:p\b[^>]*>)', para)
    return para[:rm.end()] + new_ppr + para[rm.end():]

# ============ 5. 逐段分类与修复 ============
stats = {'cover_cat': 0, 'cover_title': 0, 'cover_sub': 0, 'cover_meta': 0,
         'toc_title': 0, 'toc_l1': 0, 'toc_l2': 0, 'srcnote': 0, 'metric': 0,
         'pre': 0, 'th': 0, 'summary_title': 0, 'summary_td': 0, 'td': 0,
         'list': 0, 'body': 0, 'heading_skip': 0, 'sectpr_skip': 0}

edits = []  # (start, end, new_xml)
first_meta_seen = False

for m in re.finditer(r'<w:p\b[^>]*>.*?</w:p>', xml, re.S):
    p = m.group(0)
    pos = m.start()
    text = text_of(p).strip()

    # 结构空段（含 sectPr）不动
    if '<w:sectPr' in p:
        stats['sectpr_skip'] += 1
        continue

    pstyle_m = re.search(r'<w:pStyle w:val="(cnpxtk|i9n9ix)"/>', p)
    if pstyle_m:
        stats['heading_skip'] += 1
        continue  # 标题：样式已提供字体/字号/颜色/加粗

    in_tbl = in_spans(pos, tbl_spans)
    is_summary_tbl = summary_tbl and summary_tbl[0] <= pos < summary_tbl[1]
    tr = enclosing(pos, tr_spans)
    is_header_row = bool(tr and '<w:tblHeader' in xml[tr[0]:tr[1]])

    new = p

    # ---- 封面 ----
    if text == '软件改造计划书 · SOFTWARE RENOVATION PLAN':
        new = fix_runs(p, fonts=HEAD_F, bold=True, color=C_SECONDARY, sz=21, spacing=52)
        new = set_ppr(new, spacing={'before': 3118, 'after': 200}, jc='center')
        stats['cover_cat'] += 1
    elif text == 'PenAi 软件改造计划书':
        new = fix_runs(p, fonts=HEAD_F, bold=True, color=C_PRIMARY, sz=52, spacing=31)
        new = set_ppr(new, spacing={'after': 300}, jc='center')
        stats['cover_title'] += 1
    elif text.startswith('—— 基于'):
        new = fix_runs(p, fonts=HEAD_F, bold=True, color=C_HEADING, sz=30)
        new = set_ppr(new, spacing={'after': 900}, jc='center')
        stats['cover_sub'] += 1
    elif text.startswith(('文档版本：', '编制日期：', '基座版本：', '目标仓库：')):
        sp = {'after': 120}
        if not first_meta_seen:
            sp['before'] = 600
            first_meta_seen = True
        new = fix_runs(p, fonts=BODY_F, bold=False, color=C_MUTED, sz=22)
        new = set_ppr(new, spacing=sp, jc='center')
        stats['cover_meta'] += 1

    # ---- 目录标题 ----
    elif text == '目 录':
        new = fix_runs(p, fonts=HEAD_F, bold=True, color=C_HEADING, sz=32)
        new = set_ppr(new, spacing={'after': 240}, jc='center')
        stats['toc_title'] += 1

    # ---- 目录条目（numId=1，去自动编号）----
    elif '<w:numId w:val="1"/>' in p:
        is_l2 = bool(re.match(r'^\d+\.\d+', text))
        new = set_ppr(p, numpr='drop',
                      ind=({'left': 315} if is_l2 else {}),
                      spacing={'after': 50 if is_l2 else 100})
        if is_l2:
            new = fix_runs(new, fonts=BODY_F, bold=False, color=C_TEXT, sz=21)
            stats['toc_l2'] += 1
        else:
            new = fix_runs(new, fonts=HEAD_F, bold=True, color=C_TEXT, sz=24)
            stats['toc_l1'] += 1

    # ---- 尾注（src-note：9pt  muted）----
    elif text.startswith('本文档基于'):
        new = fix_runs(p, fonts=BODY_F, bold=False, color=C_MUTED, sz=18)
        new = set_ppr(new, spacing={'before': 360, 'after': 0},
                      ind={}, jc='both',
                      shd=f'<w:shd w:val="clear" w:color="auto" w:fill="{FILL_HL}"/>')
        stats['srcnote'] += 1

    # ---- 摘要指标行（10.5pt muted，无缩进）----
    elif '｜' in text:
        new = fix_runs(p, fonts=BODY_F, color=C_MUTED, sz=21)
        new = set_ppr(new, ind={}, spacing={'before': 80, 'after': 80})
        stats['metric'] += 1

    # ---- 代码块 pre（等宽 10.5pt，浅灰底+细框）----
    elif text.startswith(('interface QuroTool {', '┌', 'val total = (card.total')):
        new = fix_runs(p, fonts=MONO_F, color=C_TEXT, sz=21)
        new = set_ppr(new, spacing={'before': 120, 'after': 180, 'line': 324, 'lineRule': 'auto'},
                      ind={'left': 120, 'right': 120},
                      pbdr=('<w:pBdr><w:top w:val="single" w:sz="4" w:space="4" w:color="E0E0E0"/>'
                            '<w:left w:val="single" w:sz="4" w:space="4" w:color="E0E0E0"/>'
                            '<w:bottom w:val="single" w:sz="4" w:space="4" w:color="E0E0E0"/>'
                            '<w:right w:val="single" w:sz="4" w:space="4" w:color="E0E0E0"/></w:pBdr>'),
                      shd=f'<w:shd w:val="clear" w:color="auto" w:fill="{FILL_HL}"/>')
        stats['pre'] += 1

    # ---- 表格内段落 ----
    elif in_tbl:
        if text == '执行摘要':
            # 摘要卡标题：16pt 加粗 标题字体 深蓝
            new = fix_runs(p, fonts=HEAD_F, bold=True, color=C_HEADING, sz=32)
            new = set_ppr(new, ind={}, spacing={'after': 120})
            stats['summary_title'] += 1
        elif is_header_row:
            new = fix_runs(p, fonts=HEAD_F, bold=True, color=C_HEADING, sz=21)
            new = set_ppr(new, ind={}, spacing={'after': 40})
            stats['th'] += 1
        elif is_summary_tbl:
            # 摘要卡正文：10.5pt，首行缩进 2em
            new = fix_runs(p, fonts=BODY_F, bold=False, color=C_TEXT, sz=21)
            new = set_ppr(new, ind={'firstLine': 420}, spacing={'after': 120})
            stats['summary_td'] += 1
        else:
            new = fix_runs(p, fonts=BODY_F, bold=False, color=C_TEXT, sz=21)
            new = set_ppr(new, ind={}, spacing={'after': 40})
            stats['td'] += 1

    # ---- 列表项（保留自动编号）----
    elif '<w:numPr>' in p:
        new = fix_runs(p, fonts=BODY_F, color=C_TEXT, sz=24)
        new = set_ppr(new, spacing={'after': 80, 'line': 384, 'lineRule': 'auto'})
        stats['list'] += 1

    # ---- 正文段：12pt 宋体 2em 缩进 两端对齐 1.6 倍行距 ----
    else:
        new = fix_runs(p, fonts=BODY_F, color=C_TEXT, sz=24)
        new = set_ppr(new, ind={'firstLine': 480}, spacing={'after': 180, 'line': 384, 'lineRule': 'auto'},
                      jc='both')
        stats['body'] += 1

    if new != p:
        edits.append((m.start(), m.end(), new))

# 从后往前 splicing
for s, e, new in reversed(edits):
    xml = xml[:s] + new + xml[e:]

report['paragraph_edits'] = len(edits)
report['stats'] = stats

# ============ 6. 表头单元格 shading（th 行加 F5F7FA 底纹）============
def _fill_shd(tc, fill):
    """把空 <w:shd/> 替换为带 fill 的 shd；无 shd 则按 tcPr 次序插入"""
    filled = f'<w:shd w:val="clear" w:color="auto" w:fill="{fill}"/>'
    if '<w:shd/>' in tc:
        return tc.replace('<w:shd/>', filled, 1)
    if re.search(r'<w:shd [^>]*/>', tc):
        return tc  # 已有带属性 shd
    if '<w:tcMar/>' in tc:
        return tc.replace('<w:tcMar/>', filled + '<w:tcMar/>', 1)
    mm = re.search(r'<w:tcW\b[^>]*/>', tc)
    if mm:
        return tc[:mm.end()] + filled + tc[mm.end():]
    return tc

def shade_header_cells(xml_str):
    # 对每个含 tblHeader 的 tr 内所有 tc 的 tcPr 注入 shd（摘要卡除外）
    def _tr(m):
        tr = m.group(0)
        if '<w:tblHeader' not in tr:
            return tr
        if '执行摘要' in tr:
            return tr  # 摘要卡标题行不加底纹（HTML 无背景）
        return re.sub(r'<w:tc>.*?</w:tc>', lambda mt: _fill_shd(mt.group(0), FILL_HL),
                      tr, flags=re.S)
    return re.sub(r'<w:tr\b[^>]*>.*?</w:tr>', _tr, xml_str, flags=re.S)

xml = shade_header_cells(xml)

# 摘要卡 td：浅蓝底纹 + 左侧主色粗边（注意：须在段级 splicing 之后重新定位表格）
def style_summary_td(xml_str):
    sm = None
    for m in re.finditer(r'<w:tbl>.*?</w:tbl>', xml_str, re.S):
        if '执行摘要' in m.group(0):
            sm = (m.start(), m.end())
            break
    if not sm:
        return xml_str
    s, e = sm
    seg = xml[s:e]
    def _tc(mt):
        tc = mt.group(0)
        if '执行摘要' in tc:
            return tc
        border = ('<w:tcBorders><w:left w:val="single" w:sz="24" w:space="0" w:color="1565C0"/>'
                  '</w:tcBorders>')
        filled = f'<w:shd w:val="clear" w:color="auto" w:fill="{FILL_HL}"/>'
        if '<w:shd/>' in tc:
            tc = tc.replace('<w:shd/>', border + filled, 1)
        elif re.search(r'<w:shd [^>]*/>', tc):
            pass  # 已有底纹
        elif '<w:tcMar/>' in tc:
            tc = tc.replace('<w:tcMar/>', border + filled + '<w:tcMar/>', 1)
        else:
            mm = re.search(r'<w:tcW\b[^>]*/>', tc)
            if mm:
                tc = tc[:mm.end()] + border + filled + tc[mm.end():]
        return tc
    seg = re.sub(r'<w:tc>.*?</w:tc>', _tc, seg, flags=re.S)
    return xml[:s] + seg + xml[e:]

xml = style_summary_td(xml)

# ============ 7. 封面节去页码（移除 sect0 的 footerReference）============
first_sect = re.search(r'<w:sectPr.*?</w:sectPr>', xml, re.S)
if first_sect:
    seg = first_sect.group(0)
    new_seg = re.sub(r'<w:footerReference[^>]*/>', '', seg)
    xml = xml[:first_sect.start()] + new_seg + xml[first_sect.end():]
    report['cover_footer_removed'] = True

open(DOC, 'w', encoding='utf-8').write(xml)

# ============ 8. styles.xml：Normal/Heading2/Heading3 增强 ============
sty = open(STY, encoding='utf-8').read()
# Normal：宋体/TNR + 12pt + 正文色
sty = sty.replace(
    '<w:style w:type="paragraph" w:styleId="9z0wct" w:default="1"><w:name w:val="Normal"/><w:pPr><w:widowControl w:val="0"/><w:jc w:val="left"/></w:pPr></w:style>',
    '<w:style w:type="paragraph" w:styleId="9z0wct" w:default="1"><w:name w:val="Normal"/><w:pPr><w:widowControl w:val="0"/><w:jc w:val="left"/></w:pPr><w:rPr><w:rFonts w:hint="eastAsia" w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="宋体" w:cs="Times New Roman"/><w:color w:val="212121"/><w:sz w:val="24"/><w:szCs w:val="24"/></w:rPr></w:style>')
# heading 2：微软雅黑 + 深蓝 + 左侧主色竖线（4px≈3pt）
sty = sty.replace(
    '<w:pPr><w:keepNext w:val="1"/><w:keepLines w:val="1"/><w:spacing w:before="0" w:after="0" w:line="408" w:lineRule="auto"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b w:val="1"/><w:bCs w:val="1"/><w:color w:val="1A1A1A"/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr>',
    '<w:pPr><w:keepNext w:val="1"/><w:keepLines w:val="1"/><w:pBdr><w:left w:val="single" w:sz="24" w:space="4" w:color="1565C0"/></w:pBdr><w:spacing w:before="0" w:after="0" w:line="408" w:lineRule="auto"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:rFonts w:hint="eastAsia" w:ascii="Microsoft YaHei" w:hAnsi="Microsoft YaHei" w:eastAsia="微软雅黑" w:cs="Microsoft YaHei"/><w:b w:val="1"/><w:bCs w:val="1"/><w:color w:val="1A237E"/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr>')
# heading 3：微软雅黑 + 主色蓝
sty = sty.replace(
    '<w:pPr><w:keepNext w:val="1"/><w:keepLines w:val="1"/><w:spacing w:before="0" w:after="0" w:line="408" w:lineRule="auto"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b w:val="1"/><w:bCs w:val="1"/><w:color w:val="1A1A1A"/><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr>',
    '<w:pPr><w:keepNext w:val="1"/><w:keepLines w:val="1"/><w:spacing w:before="0" w:after="0" w:line="408" w:lineRule="auto"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:rFonts w:hint="eastAsia" w:ascii="Microsoft YaHei" w:hAnsi="Microsoft YaHei" w:eastAsia="微软雅黑" w:cs="Microsoft YaHei"/><w:b w:val="1"/><w:bCs w:val="1"/><w:color w:val="1565C0"/><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr>')
assert 'w:eastAsia="宋体"' in sty and 'w:eastAsia="微软雅黑"' in sty
open(STY, 'w', encoding='utf-8').write(sty)

# ============ 9. numbering.xml：目录编号格式置空 ============
num = open(NUM, encoding='utf-8').read()
num = num.replace(
    '<w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="420" w:hanging="420"/></w:pPr></w:lvl>',
    '<w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="none"/><w:lvlText w:val=""/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="420" w:hanging="420"/></w:pPr></w:lvl>')
open(NUM, 'w', encoding='utf-8').write(num)

print('=== ROUND2 FIX REPORT ===')
for k, v in report.items():
    print(f'{k}: {v}')
