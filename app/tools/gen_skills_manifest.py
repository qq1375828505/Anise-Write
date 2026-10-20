#!/usr/bin/env python3
"""Phase 4a 校验 + manifest 生成脚本（一次性）：
1. 从 novelIDE Dart 源提取 25 个流派 preset（id/name/category/description/systemPrompt/tags）
2. 校验 penai/<slug>/SKILL.md：frontmatter 完整、原文 systemPrompt 逐行逐字保真、无占位符
3. 生成 manifest.json（含真实 size 与 SkillSigner HMAC-SHA256 签名）
"""
import hashlib
import hmac
import json
import re
import sys

SALT = "zorv-ai-builtin-skill-sign-v1"  # 与基座 SkillSigner.SALT 一致
DART = "/workspace/penai/repos/novelIDE/lib/data/presets/tomato_presets_data.dart"
ROOT = "/workspace/penai/worktrees/phase4a/app/src/main/assets/skills/penai"


def parse_dart():
    src = open(DART, encoding="utf-8").read()
    # 按 TomatoPreset( 切块
    blocks = src.split("TomatoPreset(")[1:]
    presets = []
    for b in blocks:
        pid = re.search(r"id:\s*'([^']+)'", b)
        name = re.search(r"name:\s*'([^']+)'", b)
        cat = re.search(r"category:\s*'([^']+)'", b)
        desc = re.search(r"description:\s*'([^']+)'", b)
        tags = re.search(r"tags:\s*\[([^\]]+)\]", b)
        # systemPrompt: 拼接字符串 → 提取从 'systemPrompt:' 到 'tags:' 之间的所有字符串字面量并反转义 \n
        seg = b.split("systemPrompt:")[1].split("tags:")[0]
        strs = re.findall(r"'((?:[^'\\]|\\.)*)'", seg)
        sp = "".join(strs).replace("\\n", "\n").replace("\\'", "'")
        presets.append({
            "id": pid.group(1),
            "name": name.group(1),
            "category": cat.group(1),
            "description": desc.group(1),
            "systemPrompt": sp,
            "tags": [t.strip().strip("'") for t in tags.group(1).split(",")],
        })
    return presets


def main():
    presets = parse_dart()
    print(f"Dart 源解析出 {len(presets)} 个流派")
    assert len(presets) == 25, "Dart 源流派数 != 25"

    errors = []
    skills = []
    for p in presets:
        path = f"{ROOT}/{p['id']}/SKILL.md"
        try:
            md = open(path, encoding="utf-8").read()
        except FileNotFoundError:
            errors.append(f"[缺失] {p['id']} -> {path}")
            continue

        # --- frontmatter 校验（单行字段，parseSkillMd 兼容） ---
        fm = md.split("---")[1]
        name_m = re.search(r"^name:\s*(.+)$", fm, re.M)
        desc_m = re.search(r"^description:\s*(.+)$", fm, re.M)
        trig_m = re.search(r"^trigger:\s*(.+)$", fm, re.M)
        if not name_m:
            errors.append(f"[frontmatter] {p['id']} 缺 name")
        elif name_m.group(1).strip() != p["name"]:
            errors.append(f"[frontmatter] {p['id']} name 不匹配: {name_m.group(1)} != {p['name']}")
        if not desc_m:
            errors.append(f"[frontmatter] {p['id']} 缺 description（必须单行）")
        if not trig_m:
            errors.append(f"[frontmatter] {p['id']} 缺 trigger")
        for t in p["tags"]:
            if t not in (trig_m.group(1) if trig_m else ""):
                errors.append(f"[frontmatter] {p['id']} trigger 缺标签 {t}")

        # --- 原文 systemPrompt 逐行逐字保真校验 ---
        sp_lines = p["systemPrompt"].split("\n")
        body = md.split("---", 2)[2]
        for i, line in enumerate(sp_lines, 1):
            if f"> {line}" not in body:
                errors.append(f"[保真] {p['id']} 第 {i} 行原文缺失或不符: {line[:60]!r}")
        # 逆向：定位「番茄原文规约」小节后的第一个 blockquote 块，须与原文逐行相等（无夹带无漏行）
        sec = body.split("番茄原文规约")[1]
        buf = []
        for l in sec.splitlines():
            if l.startswith("> "):
                buf.append(l[2:])
            elif buf:
                break
        if buf != sp_lines:
            errors.append(f"[保真] {p['id']} 原文区块并非逐字平移（可能夹带或漏行）")

        # --- 占位符/兜底文案扫描 ---
        for bad in ["待补充", "TODO", "placeholder", "占位", "待完善", "TBD"]:
            if bad in md:
                errors.append(f"[占位] {p['id']} 含禁用字样 {bad!r}")
        # --- 结构必需章节 ---
        for sec in ["流派定位", "番茄原文规约", "写作技法清单", "节奏控制参数", "语言规范", "禁用套路", "质检清单"]:
            if sec not in md:
                errors.append(f"[结构] {p['id']} 缺章节 {sec}")

        # --- manifest 登记 ---
        skill_id = "penai_" + hashlib.sha256(p["id"].encode()).hexdigest()[:12]
        signature = hmac.new(SALT.encode(), f"{skill_id}|{name_m.group(1).strip()}|{md}".encode(), hashlib.sha256).hexdigest()
        skills.append({
            "id": skill_id,
            "name": p["name"],
            "description": desc_m.group(1).strip(),
            "file": f"{p['id']}/SKILL.md",
            "size": len(md.encode("utf-8")),
            "genre_id": p["id"],
            "category": p["category"],
            "suite": "tomato-genre",
            "signature": signature,
        })

    if errors:
        print("\n".join(errors))
        print(f"\n共 {len(errors)} 个问题")
        sys.exit(1)

    # 生成 manifest.json（对齐基座字段 + genre_id/category 补充）
    manifest = {
        "version": 1,
        "suite": "tomato-genre",
        "source": "novelIDE/lib/data/presets/tomato_presets_data.dart (25 TomatoPreset)",
        "signatureAlgorithm": "HMAC-SHA256(id|name|fileContent, SkillSigner.SALT)",
        "seedPath": "skills/penai/<genre_id>/SKILL.md",
        "skills": skills,
    }
    out = f"{ROOT}/manifest.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print(f"OK: 25/25 流派校验通过")
    print(f"manifest 写入 {out}（{len(skills)} 项）")
    for s in skills:
        print(f"  {s['genre_id']:<18} {s['id']}  {s['size']:>5}B  sig={s['signature'][:12]}...")


if __name__ == "__main__":
    main()
