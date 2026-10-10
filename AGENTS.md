# AGENTS.md — 给 AI 助手的工作约定

> 适用范围：**单个仓库 `medbox`**，代码与文档同仓。接口文档在 `medbox-spec/` 下，**不通过 git subtree 同步**（没有 `spec-repo` 远端）。
> **本文件放在仓库根目录**（AI 工具会自动加载），是**唯一权威副本**；`medbox-spec/` 下不放同名文件，也**不使用软链**（Windows 下 git 对软链支持不可靠）。
> **AI 助手在动手写代码之前，必须先读完本文件，再读 `medbox-spec/00_README_文档索引.md`。**

---

## 0. 项目一句话

家用智能药品箱：设备（药箱主控 + 传感器 + 摄像头）经 **MQTT(EMQX)** 上报，Java 后端落库并判定，**WebSocket** 推给 uni-app 小程序，**HTTP REST** 供小程序读写，AI 问答走大模型（OCR 与 Embedding 本地化）。当前全部为**局域网**通信。

---

## 0.1 仓库目录约定（单仓多目录）

| 目录 | 端 | 技术栈 | 任务清单 | 本期 |
|------|----|--------|----------|------|
| `medbox-server/` | Java 后端 | **Spring Boot 4 + Java 21 + Gradle（Kotlin DSL）** | `medbox-spec/07` | ✅ 实现 |
| `medbox-miniapp/` | uni-app 小程序 | Vue3 + Vite | `medbox-spec/08` | ✅ 实现 |
| `medbox-admin/` | Web 前端 / 管理后台 | 未定 | 无（待设计） | ⏸ **本期占位，不要在这里写代码** |
| `medbox-spec/` | 本文档集（唯一权威副本） | — | — | ✅ |

**给 AI 的三条硬要求**：

1. **先确认自己在哪个端**：接到需求先判断落在 `medbox-server/` 还是 `medbox-miniapp/`，**不要跨端改动**（一个分支只动一个端；确实需要跨端时必须先向用户说明并尽量拆成两个分支）。
2. **相对路径是相对端目录的**：文档里写 `src/main/resources/application.yml` 指 `medbox-server/src/main/resources/application.yml`；写 `src/config/index.js` 指 `medbox-miniapp/src/config/index.js`。**不要在仓库根建 `src/`**。
3. **`medbox-admin/` 本期不实现**：没有 09 清单，也没有管理员接口（见 01 第 3 章）。用户若要求做 Web 前端，先提醒"文档未设计、需先补接口与清单"。

---

## 1. 铁律（AI 发现违反时，必须先提醒用户再继续）

1. **绝不在主分支开发**：一切改动都在 `feat/xxx` 特性分支上做。分支名取自 `medbox-spec/07`（`feat/backend-xxx`）或 `medbox-spec/08`（`feat/mp-xxx`）。
2. **每次开工前先拉最新**：`git switch main && git pull`，代码与文档一起更新；再看 `medbox-spec/CHANGELOG.md` 判断有没有影响自己这一端。
3. **完成特性分支、合并前必须更新文档，且与代码同 PR 提交**：勾选 07/08 待办、补 `CHANGELOG.md`、修正与实现不一致的接口/字段。
4. **远程有更新时用 rebase，不要 merge**：切回主分支 fetch/pull，再回特性分支 `git rebase main`。
5. **修 Bug / 重构不单独开分支记账**，但仍要走特性分支，随所在模块一起提交。
6. **一个分支只动一个端**（见 0.1）。

---

## 2. 开始开发前（每次会话都要做）

```bash
# ① 确认自己在特性分支上（不是 main）
git branch --show-current

# ② 拉最新（代码 + 文档同仓，一次 pull 全都有）
git switch main && git pull && git switch -

# ③ 看文档这次有没有变
git log --oneline -5 -- medbox-spec/
```

然后读文档：

- 后端任务 → `medbox-spec/07_特性分支清单_后端.md` 找到自己那条待办，按其"参考"列读 `medbox-spec/02`（接口）、`medbox-spec/06`（表结构）、`medbox-spec/03`（MQTT）；
- 小程序任务 → `medbox-spec/08_特性分支清单_小程序.md`，读 `medbox-spec/02`、`medbox-spec/04`；
- 不确定口径 → 查 `medbox-spec/01`（目录结构、时区、权限、过期、鉴权等全局口径）。

**提醒义务**：如果用户直接在主分支上让我改代码，或本次会话还没 pull 过，我必须先说一句：

> ⚠️ 当前在 `main` 分支 / 尚未同步最新代码与文档，建议先 `git switch -c feat/xxx` 并 `git switch main && git pull`，需要我帮你做吗？

---

## 3. 开发中

- 严格按文档实现：**字段名、错误码、枚举值、Topic、Payload、时区口径**以文档为准；
- 发现文档有歧义或与实际不符：**不要擅自偏离文档**，把问题记下来，在第 4 步"更新文档"时一并修正并说明；
- 新增接口 / 字段 / 表 → 同步改 `medbox-spec/02` / `06` / `03`，不要只在代码里加；
- 不确定时先问用户，不要猜。

### 3.1 三条实现约定（2026-10-10 新增，所有功能分支适用）

1. **功能分支必须带集成测试，并输出测试报告**
   - 地基分支（骨架 / 统一响应 / 建表 / 认证）有单元测试即可；**从第一个功能分支起**，凡新增接口或判定逻辑，必须补集成测试（后端用 MockMvc 打真实接口链路，小程序侧至少覆盖请求层与编排层）；
   - 交付时**把测试报告贴给用户**（`./gradlew test` / npm test 的结果摘要：用例数、通过数、失败项），不要只说"已实现"；
   - 目的：把"人工手动跑一遍"从瓶颈里摘出来。用户的验证动作降级为**看报告**，不再逐接口手点。
2. **判定型分支先出规则表，再写代码**
   - "判定型" = 含业务规则与优先级的分支（漏服 / 错服 / 提醒触发 / 计划时间线展开 / 库存扣减 / 权限校验顺序等）；
   - 动手前先输出一张规则表给用户确认：**输入 → 判定条件与优先级 → 输出 → 边界用例**（如"容忍时长：计划级 > 老人级 > 系统 15 分钟"）；
   - 用户确认后才写代码，规则表一并沉淀进 `medbox-spec/02` 或 `06`；
   - 目的：这类逻辑写错了不会报错、只会悄悄算错，返工成本远高于先对齐口径。
3. **合并同类分支，不要为每个待办单开一个分支**
   - 能在**同一个分支内写完并一起测试**的待办合并成一个分支（如"药品 CRUD + 库存录入"、"记录列表 + 详情 + 补录"、"抓拍上传 + 读取"）；
   - 合并后的分支名见 07 / 08 清单（如 `feat/device-core`、`feat/mp-record`），PR 描述写明"对应 07/08 的 `feat/xxx`"，清单里**合并前的各项都勾选**；
   - 目的：省掉重复的分支 / PR / 文档改动开销，也让相关代码在同一次测试里被覆盖。

---

## 4. 完成一个特性分支时（合并前必做）

```bash
# ① 先拉最新（避免改在旧版本上、也避免与他人更新冲突）
git switch main && git pull && git switch -

# ② 更新 medbox-spec/ 下的文档（与代码放在同一次提交）
#    - 后端：medbox-spec/07 对应项改为 - [x]
#    - 小程序：medbox-spec/08 对应项改为 - [x]
#    - 在 medbox-spec/CHANGELOG.md 顶部追加一条（用日期标识，不再编号，条目力求简洁）

# ③ 一起提交
git add medbox-server/ medbox-spec/      # 或 medbox-miniapp/ medbox-spec/
git commit -m "feat(<模块>): <说明>；docs(spec): 勾选 07/08 的 feat/xxx、补 CHANGELOG"
```

**提醒义务**：如果用户说"这个分支做完了 / 帮我合并"，而 `medbox-spec/` 还没有文档改动，我必须先提醒：

> ⚠️ 合并前请先更新 `medbox-spec/` 文档（勾选 07/08 待办 + 补 CHANGELOG）并与代码一起提交，否则文档会落后于代码。需要我来做吗？

---

## 5. 远程仓库有更新时（同步主线）

```bash
git stash -u                 # 有未提交改动先收起来
git switch main
git pull                     # 或 git fetch && git merge --ff-only
git switch <特性分支>
git rebase main              # 用 rebase 保持线性历史
# 冲突：解决后 git add <file> && git rebase --continue
git push --force-with-lease  # 分支已推送过时用，不要用 --force
git stash pop                # 恢复未提交改动
```

**提醒义务**：用户说"拉取一下最新代码 / 远程更新了"时，提醒按上面流程走（**先回主分支 fetch，再回特性分支 rebase**），不要直接在特性分支上 `git pull origin main`（会产生合并提交）。

---

## 6. 常用命令速查

| 场景 | 命令 |
|------|------|
| 新建特性分支（后端） | `git switch -c feat/backend-xxx main` |
| 新建特性分支（小程序） | `git switch -c feat/mp-xxx main` |
| 开工同步（代码 + 文档） | `git switch main && git pull && git switch -` |
| 跑后端测试（出报告） | `cd medbox-server && ./gradlew test` |
| 看文档改了什么 | `git log --oneline -5 -- medbox-spec/` |
| 文档改动提交 | 与代码同一个 PR 即可，可单独 commit：`git add medbox-spec/ ...` |
| 同步主线 | `git switch main && git pull && git switch - && git rebase main` |

> **已废弃**：`git subtree add/pull/push --prefix=spec spec-repo ...` —— 单仓后不再有文档仓库与 `spec-repo` 远端，见到这类命令请忽略（详见 `medbox-spec/00_README_文档索引.md` 的《文档就在本仓库》）。

---

## 7. 自检清单（每次回答前过一遍）

- [ ] 我这次改的是**特性分支**，不是 `main`？
- [ ] 我清楚自己改的是**哪一个端**（`medbox-server/` / `medbox-miniapp/`），没有跨端乱改？
- [ ] 本次会话**pull 过最新代码与文档**了吗？
- [ ] 实现与 `medbox-spec/` 里的字段 / 错误码 / 枚举**一致**吗？
- [ ] 改了接口或表结构 → **`medbox-spec/02` / `06` / `03` 同步改了**吗？
- [ ] 改了文档 → **`CHANGELOG.md` 顶部加了简洁条目**吗？（**已不再维护版本号**，用日期定位；历史条目里的 V15 / V16 是旧编号，原样保留、不要回去改）
- [ ] 是功能分支 → **写了集成测试并把测试报告给了用户**吗？是判定型分支 → **先出规则表再写代码**了吗？
- [ ] 能合并的待办合并成一个分支了吗？没有为单接口 CRUD 单开分支？
- [ ] 功能已完成 → **勾选了 07/08 待办 + 补了 CHANGELOG**，且与代码同 PR？
- [ ] 用户要合并 → 提醒过"先更新文档再合并"吗？
- [ ] 用户要拉远程更新 → 提醒过"回主分支 fetch + 特性分支 rebase"吗？
