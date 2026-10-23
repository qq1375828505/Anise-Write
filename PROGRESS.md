# 📌 项目进度 · PROGRESS

> **AI 接手指引：先读本文件，不要全仓扫描。**
> 本文件是唯一进度真相源；技术细节再看 [PROJECT_RECORD.md](./PROJECT_RECORD.md)，
> 当日过程看 [docs/progress/HANDOFF_2026-10-23.md](./docs/progress/HANDOFF_2026-10-23.md)。

---

## ⚡ 现在唯一要做的事

**等 CI 确认 `9a74d0b`（Phase 9 裁剪，149 文件 / -33692 行）构建结果。**
绿 → 开发阶段完结，只剩验证/发布两项（见下）；
红 → 带 `GITHUB_TOKEN` 走 API 下载日志定位修复。

---

## ✅ 已完成（全部 10 个开发阶段）

| 阶段 | 内容 | 证据 |
|------|------|------|
| Phase 0 | 环境与基线（改名 / 签名配置 / 干净构建） | ✅ 基线通过 |
| Phase 1 | 端到端最小闭环（建作品→写章→保存→重进恢复） | ✅ |
| Phase 2 | AI 闭环（novel_write / get / build_context + 云端续写） | ✅ |
| Phase 3 | 工具域全量（44 工具 + 8 类资料 + 关系图数据层） | ✅ |
| Phase 4 | 技能与流派（novel-writing + tomato-genre + 25 流派） | ✅ |
| Phase 5 | 子 Agent（6 子代理 + 五级模糊匹配 + 3 轮收敛） | ✅ |
| Phase 6 | 基座缺陷修复（#15-#21） | ✅ 9dbd62a |
| Phase 7 | 关系图 v2（物理渲染 / 点选高亮 / 图例折叠） | ✅ 9dbd62a |
| Phase 8 | 一致性审查 + 精修 + 资料自动分析 | ✅ bac0d3c |
| Phase 9 | **裁剪与包体优化**（集群/MCP/bot/ACI/CMS/插件/KaleidoBox/小程序/GenUI + R8 规则） | ✅ `9a74d0b` CI 待验 |
| P1-4 | 记忆双轨（十节快照 + 五表图谱 + RRF 检索） | ✅ f113092 CI 绿 |

**已取消（不是未完成）**：⑧ 富文本编辑器 `:novel-editor` —— 2026-10-23 用户决策取消，
不做 WebView 富文本；正文维持 AI 全章写作 + 用户指错后的定向修订。
新建的 `novel-editor-api` 空模块文件已删除，无代码进入仓库。

---

## ⏳ 未完成（按优先级，全部依赖 APK / 签名，不是开发任务）

| # | 事项 | 依赖 | 说明 |
|---|------|------|------|
| 1 | 真机验收 | 装 CI 产物 APK | 按 [docs/progress/ACCEPTANCE_CHECKLIST.md](./docs/progress/ACCEPTANCE_CHECKLIST.md) 6 组 ~40 条逐项勾；含 Phase 8「卸载重装不丢稿」 |
| 2 | Phase 7b 正式打包 | ① 一把签名密钥（本地暂无）② 4 个 GitHub Secret：`ZORV_KEYSTORE_BASE64` / `ZORV_KEY_ALIAS` / `ZORV_STORE_PASS` / `ZORV_KEY_PASS` ③ 推 `v*` tag | release.yml 首步预检 Secret，缺一 fail fast；构建 30-50 分钟 |
| 3 | 签名发布 | 依赖 #2 | 产物自动挂 GitHub Release |

---

## 🚫 已取消

- **⑧ 富文本编辑器**（2026-10-23 用户决策）。规划书相关章节保留作历史，以本文件为准。

---

## 🔧 AI 工作约定（避免重复踩坑）

1. 本地**不能编译**，CI 是唯一编译器（每轮 25-30 分钟）；
2. 改文件一律 python 精确替换脚本 + `count==1` 断言，或单次单文件编辑；
3. Kotlin 注释避免 `/` 紧跟 `*`（嵌套块注释杀手）；
4. 推送前先 `curl api.github.com` 探网（github.com 时断时续，api 稳定）；
5. 提交规范 `[Phase N] 模块: 描述`；`feature/penai` 与 `main` 始终同步推。

*最后更新：2026-10-23 · Phase 9 裁剪提交 `9a74d0b` 已推双分支，CI 进行中*
