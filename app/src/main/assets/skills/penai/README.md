# PenAi 流派技能包套件 `tomato-genre`

> Phase 4a 交付物。25 个番茄流派写作规约技能包，源数据：novelIDE
> `lib/data/presets/tomato_presets_data.dart`（25 个 `TomatoPreset`，`systemPrompt` 逐字平移）。
> 文件格式对齐基座 `assets/skills/zorv/`（单文件 Markdown + YAML frontmatter + `manifest.json` 登记），
> 可直接复用基座 `QuroSkillStore.parseSkillMd` / `SkillSigner` / `seedBuiltinZorvSkills` 装卸链路。

## 1. 套件定位

- 套件名：`tomato-genre`（规划书 §6.2 双技能套件之一，另一个 `novel-writing` 属 Phase 4b 领域）。
- 定位：**流派规约资产库**。每个流派是一项独立技能，用户选用后其正文（`prompt`）作为会话级写作约束注入系统提示词。
- 默认态：全部关闭（`enabled=false`），按作品显式选用；不挤占本地模型提示词预算（规划书 Phase 4 出口验收约束）。
- 用户可改性：流派文本随 assets 播种后进入 SharedPreferences，用户可查看、修改、开关、导出，不锁定（规划书 §6.2"载体结论"）。

## 2. 二十五流派索引

| # | 流派名 | slug（=genre_id） | 分类 | 一句话定位 |
| --- | --- | --- | --- | --- |
| 1 | 都市赘婿·隐忍爆发 | `urban_zhuixu` | urban | 被羞辱的主角隐忍蓄势，身份揭露时集中打脸，压得越实炸得越响 |
| 2 | 都市神医·一针定乾坤 | `urban_shenyi` | urban | 西医判死刑、中医一针回春，救人与打脸共享同一场高潮 |
| 3 | 都市战神·龙帅归来 | `urban_zhanshen` | urban | 战神回归护妻女，一层层身份揭露，一招秒灭一切挑衅 |
| 4 | 玄幻签到·开局无敌 | `fantasy_qiandao` | fantasy | 每天签到领逆天奖励，恒高大境界的花式碾压，不虐主 |
| 5 | 玄幻系统·任务狂魔 | `fantasy_renwu` | fantasy | 有趣的反差任务+眼红奖励，主角用骚操作完成，四拍爽点链 |
| 6 | 玄幻无敌·横推诸天 | `fantasy_wudi` | fantasy | 开局满级无成长，看点是花式吊打天才老祖神明的姿势库 |
| 7 | 穿越种田·发家致富 | `chuanyue_zhongtian` | 穿越 | 现代知识降维古代，3 章温馨日常接 1 章打脸，张弛有度 |
| 8 | 穿越年代·改革开放 | `chuanyue_niandai` | 穿越 | 踩改革开放鼓点倒腾经商，每章一个商机+一次打脸，年代感拉满 |
| 9 | 悬疑灵异·捉鬼天师 | `xuanyi_lingyi` | 悬疑 | 开篇即诡异，恐怖→调查→更恐怖→揭秘，氛围与逻辑双轮驱动 |
| 10 | 盗墓探险·寻龙点穴 | `xuanyi_daomu` | 悬疑 | 风水定位古墓，险境五拍链+每章一件有来头有威力的宝物 |
| 11 | 规则怪谈·发疯破局流 | `guize_guaitan` | 悬疑 | 3 明规则+2 藏规则（1 陷阱），乐子人主角"我就是规则本身" |
| 12 | 县城振兴·全民分红流 | `xiancheng_zhenxing` | urban | 绑定振兴系统全民分红，每章一个基建项目，全村全县的集体暴富 |
| 13 | 修仙职场·KPI考核流 | `xiuxian_zc` | fantasy | 宗门=互联网公司，摸鱼甩锅涨修为，卷王与 KPI 表格的荒诞讽刺 |
| 14 | 无CP大女主·搞钱流 | `female_gaojian` | female | 开局手撕渣男贱女，主线只有搞钱复仇，男主工具人零暧昧 |
| 15 | 情绪发疯·怼人变强流 | `qingxu_fafeng` | urban | 被规训者的代理复仇，发疯语录变全网热梗，道德绑架无效 |
| 16 | 长生家族·千年底蕴流 | `changsheng_jiazu` | urban | "老祖活了五千年"，每章揭一层家族实力，敌人从看不起到跪舔 |
| 17 | 天命反派·背景编辑流 | `tianming_fanpai` | fantasy | 穿越成反派编辑跟脚背景，靠设定层降维让天命之子怀疑人生 |
| 18 | 恶毒女配·全家反派洗白流 | `edu_nvpei` | female | 带全家反派一起逆袭，每章改写一个剧情节点，家人变团宠 |
| 19 | 都市高武·守夜人流 | `dushi_shouye` | urban | 国家特殊部门+民族大义，"大夏境内，神明禁行"式热血碾压 |
| 20 | 年代重生·整顿亲情流 | `niandai_qinqing` | 穿越 | 重生 70-90 年代手撕极品亲戚+弥补前世遗憾，亲情正义强制执行 |
| 21 | 职业系统·超能力流 | `zhiye_xt` | urban | 法医外卖员快递员绑定系统，职业日常行为全链路超凡化 |
| 22 | 女性悬疑·刑侦流 | `nvxing_xingzhen` | 悬疑 | 女性法医/刑警/心理学家破解完美犯罪，专业碾压+性别偏见打脸 |
| 23 | 摸鱼摆烂·变强流 | `moyu_bailan` | urban | 越摸鱼越强，准点下班胜过卷王加班，老板从愤怒到求饶 |
| 24 | 直播短视频·逆袭流 | `zhibo_nixi` | urban | 能力直播从无人问津到全网顶流，弹幕嘲讽到跪舔的反转引擎 |
| 25 | 洪荒神话·编辑流 | `honghuang_bianji` | fantasy | 修改洪荒历史与神话设定，每次改写三清懵了、天地异象播报 |

## 3. 文件结构

```
assets/skills/penai/
├── README.md                  ← 本文件
├── manifest.json              ← 25 项技能登记（播种清单）
└── <slug>/SKILL.md            ← 每个流派一个目录、一个文件
```

### SKILL.md 格式（对齐基座 `skills/zorv/*.md`）

```markdown
---
name: <流派名>                  ← 与 novelIDE TomatoPreset.name 逐字一致
description: <单行>             ← 技能列表展示语（触发场景）
trigger: <逗号分隔触发词>        ← 含全部 tags + slug，供 matchTriggerSkills 命中
---

# <流派名>（引言块：套件/分类/流派 ID/核心卖点）
## 1. 流派定位
## 2. 番茄原文规约（全文平移）  ← novelIDE systemPrompt 逐字 blockquote
## 3. 写作技法清单
## 4. 节奏控制参数               ← 爽点密度/大高潮频率/章末钩子（含原文依据）
## 5. 语言规范
## 6. 禁用套路（避坑清单）
## 7. 人物设定惯例
## 8. 质检清单                   ← 交付前逐项核对的 checklist
```

格式要点（Phase 2 播种实现请注意）：

1. frontmatter 三个字段均为**单行**（`QuroSkillStore.parseSkillMd` 用单行正则提取，多行 YAML 会解析为空）。
2. 正文（frontmatter 之后的全部内容）即 `QuroSkill.prompt`，注入时作为系统提示词段落使用。
3. `manifest.json` 的 `file` 字段为 `<slug>/SKILL.md`（基座为平铺文件名，此处按流派分目录，播种时拼接路径即可）。

### manifest.json 字段

| 字段 | 说明 |
| --- | --- |
| `id` | `penai_` + `sha256(slug)[:12]`，确定性生成，不与基座 `zorv_` 前缀冲突 |
| `name` / `description` | 与 SKILL.md frontmatter 一致 |
| `file` | `skills/penai/` 内的相对路径 |
| `size` | 文件字节数（UTF-8） |
| `genre_id` | novelIDE 原始预设 id，**等于目录 slug**，供 `novel_list_genres` / `novel_apply_genre` 工具引用 |
| `category` | novelIDE 分类（urban/fantasy/穿越/悬疑/female） |
| `suite` | `tomato-genre` |
| `signature` | `HMAC-SHA256("id\|name\|fileContent", "zorv-ai-builtin-skill-sign-v1")` 的 hex，与基座 `SkillSigner` 算法/盐值一致，可直接算出 `verified` 态 |

## 4. 技能包加载机制（Phase 2 集成指引）

推荐在 Phase 2 参照基座 `seedBuiltinZorvSkills`（`QuroSkill.kt:356`）新增一次性播种 `seedPenAiGenreSkills(context)`：

1. **读 manifest**：`assets.open("skills/penai/manifest.json")`，逐项取 `id` / `file` / `suite` / `signature`。
2. **读文件**：`assets.open("skills/penai/$file")`（注意此处比基座多一级 `<slug>/` 目录）。
3. **解析**：`QuroSkillStore.parseSkillMd(md)` —— frontmatter 提取 `name`/`description`/`trigger`，正文作 `prompt`。
4. **签名校验**：`SkillSigner.verify(id, parsed.name, md, signature)`；`verified`/`unsigned` 皆放行，`failed` 记日志不放行（防用户篡改后二次播种覆盖）。
5. **登记**：`suite="tomato-genre"`，`enabled=(suite 不启用，默认 false)`，`callable=false`，`alwaysOn=false`；`updatedAt=System.currentTimeMillis()`。
6. **守卫 key**：新开一次性 key（如 `builtin_penai_genre_v1`），不要复用基座 `builtin_zorv_v1`（基座存量设备已占位，见规划书 §6.2"增量播种需新开一次性守卫 key"）。

**注入链路（用户显式动作）**：用户在作品/人设界面选流派 → 对应技能 `enabled=true` → 会话组装系统提示词时拼入该技能 `prompt` → AI 输出遵守流派规约。链路不写死人格，全库仍仅一张默认人格（规划书 §6.3）。

**与工具域的关系**：`novel_list_genres` / `novel_apply_genre`（Phase 2 工具）应以本目录 `slug` 作为 `genre_id` 与 `novel_apply_genre` 的目标技能解析键；工具描述中的流派名以本 README §2 索引表为准。

## 5. 保真度与校验

- `systemPrompt` 原文逐字平移，无增删改；校验脚本见 `app/tools/gen_skills_manifest.py`（一次性脚本，可重复执行）：
  Dart 源 25 preset 逐一比对 SKILL.md 原文区块、frontmatter `name`/`trigger`（含全部 tags）、结构章节完整性、占位符扫描，通过后重算 `manifest.json`。
- 结构化章节（定位/技法/参数/语言/禁用/人物/质检）均从 Dart 源 `description`/`systemPrompt`/`tags`/`category` 提炼显式化；节奏控制参数表中的"依据"列标注了原文出处或推导关系。
- 参考克隆（`repos/`）为只读源，上游更新如需同步，先改 novelIDE 源，再重跑校验脚本重新生成本套件。
