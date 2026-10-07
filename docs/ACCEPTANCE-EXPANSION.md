# 分层采样验收（`--databases-per-graph`）

本报告记录把 DDLSmith 从"一库一图"改成"一图多库"两级采样后的验证结果。

| | |
| --- | --- |
| 变更 | 新增 `--databases-per-graph K`（默认 **8**）、`--reroll-instance`、`--input-slot k` |
| 核心机制 | 一个图（schema + 实例）由组的种子生成；组内每个库用 `slotSeed = graphSeed * 1000000 + slot` 重掷 **ER 解读** 与 **映射决策** |
| 不变量 | `K == 1` 时不发生任何重新播种，随机流与历史版本完全一致（改动前记录的种子需显式传 1） |
| 验证环境 | JDK 17，Windows，SQLite/H2/DuckDB 嵌入式引擎；`mvn -o test` 全量 312 项 |

---

## 1. 判据

1. **K=1 位级不回退**：同种子同参数，新旧构建输出逐字节相同。
2. **组内共享图**：同一组的 K 个库生成**完全相同**的图（schema + 实例）。
3. **组内不共享解读**：同组的 ER 图与关系模式必须不同（否则该选项没有意义）。
4. **可复现**：同 `(种子, K, slot)` 两次运行结果一致；`--input-slot` 能单独重放组内某一个库。
5. **真实数据库可建**：共享图产出的每个模式在真实引擎上都能建表并干净回环（round trip）。
6. **同预算下有收益**：与世界模式在相同数据库预算下对照，覆盖面不减、有明确的机制性收益。

---

## 2. K=1 位级兼容（判据 1）

用旧构建（`target/DDLSmith.jar`，改动前）与新类（`target/classes`，改动后）各跑一次
`--print-ddl-only`，比较整份输出：

| 种子 | 旧构建 | 新构建 | 结果 |
| --- | --- | --- | --- |
| 7 | 10128 B | 10128 B | 逐字节相同 |
| 42 | 7958 B | 7958 B | 逐字节相同 |
| 123 | 7813 B | 7813 B | 逐字节相同 |
| 999 | 7351 B | 7351 B | 逐字节相同 |

实现上这是构造保证的：`Pipeline.generate` 只在 `K > 1` 时调用 `Randomly.setSeed(slotSeed(...))`，
`K == 1` 时随机流与改动前完全一致（默认值在本轮定为 8，所以要复现改动前的种子需显式传
`--databases-per-graph 1`）。已有 `GeneratorRegressionTest`、`MultiThreadedReproducibilityTest`、
`CliBehaviourTest` 全部保持绿色。

---

## 3. 组内共享与差异（判据 2、3）

`EndToEndCheck` 新增扩张段（2 个图 → 8 个库，SQLite 实机建表 + 回环）：

```
### expansion (8 databases from 2 graphs on sqlite)
  graphs=2 (expected 2), ER diagrams=8, schemas=8, statements=104, clean round trips=8/8
```

- **图**：8 个库来自恰好 2 个图（每组 4 个），逐字节相同的图签名。
- **解读**：8 个库得到 8 个不同的 ER 图统计行、8 个不同的模式统计行。
- **实机**：104 条语句全部执行成功，8/8 干净回环（共享图没有产出引擎表达不了的模式）。

一个具体的单图多读样本（`h2 --print-ddl-only --random-seed 7 --databases-per-graph 4`）：

| slot | 图 | ER 图（节选） | 模式 |
| --- | --- | --- | --- |
| 0 | nodes=40 edges=44 | relationships=7，1:1=5，n-ary=1，categories=1 | 10 表 / 59 列 / 9 FK / 14 CHECK |
| 1 | 同一个图 | relationships=9，1:1=7，isa=1/4，composite=1 | 7 表 / 51 列 / 4 FK / 3 CHECK |
| 2 | 同一个图 | relationships=8，M:N=1，n-ary=1，compositeKeys=3 | 9 表 / 54 列 / 10 FK / 4 CHECK |
| 3 | 同一个图 | relationships=6，n-ary=2，composite=1，derived=1 | 10 表 / 59 列 / 10 FK / 10 CHECK |

`ExpansionTest`（8 项，全部通过）把上述性质固化成断言：组内图签名全等、组内 ER/模式去重数
≥ K−2、`--reroll-instance` 下 schema 全等而实例不全等、两种模式下可复现、`slotSeed` 在
(图, 槽) 网格上无碰撞。

---

## 4. 复现（判据 4）

组内某个库可以被单独重放。`--random-seed 7 --databases-per-graph 4` 的第 3 槽（ER：
`entities=8 attributes=29 relationships=6(1:1=2,1:N=1,M:N=1,n-ary=2)…`，模式：
`tables=10 columns=59 pk=10(composite=2) fk=10(composite=0,self=2) unique=10 check=10 index=22 views=2`）
与 `--random-seed 7 --databases-per-graph 4 --input-slot 3` 的输出逐字段相同。

失败标识也随之扩展：

* 失败日志头：`seed : 77` 之外增加 `-- slot : 3 of 4`；
* 失败报告 `toShortString()`：`(seed=77, slot=3/4, db=database3)`；
* 缺陷报告 `| **First seen** | seed=77, slot=3/4, database=… |`；
* 缺陷报告的复现命令：`--databases-per-graph 4 --input-slot 3`；
* DDL dump 头：`-- graph seed: 1` / `-- slot : 0 of 2`（实测 `dmp0..dmp3` 分成两组，各 2 个槽）。

`ExpansionTest.failuresCarryTheSlotOfTheDatabaseThatFoundThem` 走真实 `BugRepository.recordAll`
路径断言报告内容，并验证报告被下一轮重新读取时不丢字段。

---

## 5. 同预算对照战役（判据 6）

SQLite，`--algorithm constraints --insert-rows 3 --random-seed 1000`，各 500 个数据库：

| | 世界模式（K=1） | 分层采样（K=25） |
| --- | --- | --- |
| 数据库 | 500 | 500 |
| **生成的图** | **500** | **20** |
| 不同的 ER 图 | 500 (100%) | 395 (79%) |
| 不同的关系模式 | 500 (100%) | **500 (100%)** |
| 成功执行的语句 | 24k | 24k |
| 用时 | 167.5 s | 168.1 s |

读法：

* **图的数量降到 1/25，ER 图的种类保留 79%，模式的种类 100% 保留。** 也就是说同样的多样性
  不再需要同样多的图，而"1 个图 = 25 个库"这一结构本身（受控比较：同一张图的不同解读）
  是世界模式给不了的。
* 79% 这个数字正是解读轴**饱和**的体现：每组 25 个库，后几个库与同组前面的某个库读到同一个
  结构（`R2` 的测量：单图的结构性解读在数百次重掷内饱和）。要拉高去重率就调小 K，要更多
  组就调大 `--num-tries`。
* **模式 500/500 不同**：即使 ER 图重复，映射决策（1:1 三策略、ISA 三策略、键策略、索引/CHECK
  随机化等）仍在继续分化，这与映射轴"最深"的测量一致。
* **用时相同**：生成只占每库成本约 1%（进程内 4 ms vs 实机 337 ms/库），所以这个选项改变的是
  **输入分布**，不是吞吐量。

### 5.1 K 取多少？（同预算 1024 库的扫描）

固定 1024 个数据库的预算，只改 K（[`KProbe`](er_features/probes/KProbe.java)，base seed 900000，H2）：

| K | 生成的图 | 不同 ER 图 | 每库买到的新 ER 图 | 不同 overlay | 不同模式 | ER 重复率 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 1024 | 1024 | 1.000 | 1004 | 1024 | 0.0% |
| 2 | 512 | 1011 | 0.987 | 946 | 1024 | 1.3% |
| 4 | 256 | 966 | 0.943 | 906 | 1024 | 5.7% |
| **8** | **128** | **911** | **0.890** | **862** | **1024** | **11.0%** |
| 16 | 64 | 810 | 0.791 | 766 | 1024 | 20.9% |
| 32 | 32 | 733 | 0.716 | 696 | 1024 | 28.4% |
| 64 | 16 | 633 | 0.618 | 619 | 1024 | 38.2% |
| 128 | 8 | 443 | 0.433 | 437 | 1024 | 56.7% |

同一扫描加 `--reroll-instance`（组内实例也重掷，资格维度不再固定）：

| K | 2 | 4 | 8 | 16 | 32 | 64 | 128 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ER 重复率 | 0.7% | 1.9% | **4.2%** | 8.6% | 12.6% | 17.5% | 30.2% |

**结论：K = 8（已定为默认值）**（保守取 4，深度审计取 16–32，且大 K 建议同时开 `--reroll-instance`）。

* 曲线在 K≈8 之后变陡（11% → 21% → 28%），即超过 8 之后边际收益衰减明显加快；
* **不同模式数在任何 K 下都是 1024/1024**：映射轴的多样性不受 K 影响，K 的代价只落在"图 /
  骨架多样性"这一项；
* K 直接决定一次战役能看多少**骨架**：500 库时 K=1→500 图、K=4→125 图、K=8→63 图、K=25→20 图。
  由于 ER 图的"种类"主要由骨架决定（不同骨架的解读空间 Jaccard 仅 0.03–0.08），若目标是覆盖
  更多种类的 ER 图而非单图深度，K 不宜超过 8。

**默认值 = 8**（2026-10-07 决定；此前实现阶段曾以 1 出厂）。

这个值不是从 K=1 保守起步，而是取曲线转折点：K≤8 时每库仍能买到 ≥0.89 个新 ER 图，K 再往上
（16/32）就掉到 0.79/0.72 且重复率翻倍。**代价的边界要说清**：

```
同一 seed 7：
K=1（改动前的口径）              : 10128 B，ER: relationships=7(1:1=5,…) derived=1 compositeKeys=0
K=8 slot 0（默认口径，图完全相同）:          ER: relationships=7(1:1=5,…) derived=0 compositeKeys=3
```

图一样、**解读不同** —— 所以**改动前记录的种子必须显式加 `--databases-per-graph 1` 才能复现**
（缺陷报告里内嵌的旧命令、旧验收文档钉住的种子都属于这一类）。两点缓解：

* `--reproduce <log>` 不受影响：它回放日志里的语句，不重新生成输入；
* 从现在起，缺陷报告的命令行会带上 `--databases-per-graph K --input-slot k`，恒等于当时那个库。

---

## 6. 诚实边界

* **失败日志的 slot 行没有用真实引擎失败端到端验证过**：本轮没能构造出一个确定性的引擎失败
  （SQLite/H2/DuckDB 在默认与若干 `--ddl-features` 组合下都 0 失败，这正是工具当前的状态）。
  该链路由 `ExpansionTest` 覆盖：真实 `OracleFailure` → 真实 `BugRepository.recordAll` →
  读回报告文件断言 `slot=3/4` 与 `--input-slot 3`，并用 `toLogString()` 断言失败日志头部。
* **`--databases-per-graph` 的上限是 999999**（槽以 `seed * 1000000 + slot` 折进种子），超过会被
  参数校验拒绝。
* **最后一个不完整的组**：`--num-tries` 不是 K 的整数倍时，最后一组只生成余数个库（文档已写明）。
* **两条轴都补不了生成器的资格空洞**：多级 ISA、弱实体链、度 ≥ 4 的关系等（`ER-GAPS.md` A 系列）
  在当前图生成器下**永远不可达**，与采样策略无关。
* **未做的两件事**（另立计划）：把 mapper 的硬币显式化以支持系统性枚举覆盖矩阵（Phase 2）；
  补齐生成器资格空洞（Phase 3）。

---

## 7. 复现命令

```shell
# 组内共享一个图的 4 个解读（H2，只打印 DDL）
java -jar target/DDLSmith.jar h2 --print-ddl-only --random-seed 7 --databases-per-graph 4

# 重放其中第 3 槽
java -jar target/DDLSmith.jar h2 --print-ddl-only --random-seed 7 --databases-per-graph 4 --input-slot 3

# 实机跑两组（SQLite），摘要里看 "generated graphs" 与 "distinct ER diagrams"
java -jar target/DDLSmith.jar sqlite --algorithm constraints --num-tries 8 --databases-per-graph 4 \
    --random-seed 1 --insert-rows 3 --keep-databases --database-prefix exp

# 同预算对照战役（各 500 库）：世界模式要显式写 1，分层采样写 25
java -jar target/DDLSmith.jar sqlite --algorithm constraints --num-tries 500 --databases-per-graph 1 \
    --random-seed 1000 --insert-rows 3 --bug-dir off
java -jar target/DDLSmith.jar sqlite --algorithm constraints --num-tries 500 --databases-per-graph 25 \
    --random-seed 1000 --insert-rows 3 --bug-dir off

# 复现改动前（默认 K=1 时代）记录的种子
java -jar target/DDLSmith.jar h2 --print-ddl-only --random-seed 7 --databases-per-graph 1

# 端到端检查（含扩张段）
java -cp "target/classes;target/test-classes;<deps>" org.ddlsmith.EndToEndCheck 20240101 2
```
