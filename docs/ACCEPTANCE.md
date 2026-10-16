# DDLsmith 验收报告

> **当前有效的验收报告是 [ACCEPTANCE-2.md](ACCEPTANCE-2.md)（第二轮会话）。**
> 本文记录的是第一轮会话，其中的缺陷已在第二轮全部修复并复验；本文保留下来是为了让每一项修复都有
> 一份「它当时是怎么被发现的」的原始记录。两者合起来才是工具的完整验收历史：
> 本文的 27 项修复 → [ACCEPTANCE-2.md](ACCEPTANCE-2.md) 的 18 项修复 → 九类测试两轮零工具缺陷。
>
> 本文的结论中已被后续会话改变的部分，在 [ACCEPTANCE-2.md](ACCEPTANCE-2.md) §4/§5/§9 中有明确对照。

本报告记录一次完整的“测试 → 定位 → 修复 → 复验”循环：先阅读文档，再按测试类别各跑约一小时，
根据结果修复工具自身（而非被测引擎）的缺陷，最后用同一批种子做 A/B 复验。

* 被测对象：`target/DDLsmith.jar`（Java 17，Maven 构建）
* 环境：Windows，20 逻辑核，PostgreSQL/MySQL 服务器不可达（5432/3306 均未监听），因此这两者只做了
  `--print-ddl-only` 渲染与单元级验证
* 所有测试运行目录、原始汇总与失败日志都在 `runs/` 下（`runs/<类别>/summary.tsv` 等）

> 注：本文引用的 `runs/`、`bugs/` 证据树与 `logs/` 运行产物已不随仓库分发；文中这些路径是当时的原始记录。

---

## 1. 测试类别与规模

九类测试并行执行，每类预算 3600 秒；每一块（chunk）是独立 JVM，独立种子区间，独立 bug 目录，
并有超时与硬杀保护，因此一个卡死的块不会吞掉整段预算。

| 类别 | 命令要点 | 规模（数据库/次数） | 失败 | harness 崩溃 |
| --- | --- | --- | --- | --- |
| 单元/组件 | `mvn -o test` 循环 90 轮 | 每轮 265 个测试 | 10 轮受干扰（见 §5） | 0 |
| sqlite | 默认 oracle，`--threads 2` | 5 837 | 0 | 0 |
| h2 | 默认 oracle | 83 500 | 20 | 0 |
| duckdb | 默认 oracle | 7 087 | 0 | 0 |
| differential | `sqlite --compare-with h2,duckdb` | 2 800（每个建 3 份 schema） | 13 | 0 |
| evolution | 三引擎轮换，`--evolve-steps 25` | 7 600 | 7 569 | 0 |
| constraints | 三引擎轮换，`--oracle ALL` | 2 421 | 3 679 | 0 |
| printddl | 5 方言 × 6 图生成器，`--print-ddl-only` 各跑两遍 | 2 141 × 2 | 0 | 0 |
| reproduce | 回放所有失败日志 | 1 496 次回放 | 11 处异常 | 0 |

补充结论：

* `printddl` 2 141 轮全部 exit 0、全部含 `CREATE TABLE`、**同种子两次输出逐字节一致**（确定性 100%）。
* `reproduce` 1 496 次回放中 1 340 次在记录的语句处复现（exit 0），156 次为“纯目录类”失败（无语句被
  拒绝，全部语句执行成功，exit 1，符合预期）。
* sqlite/duckdb 在 5 837 / 7 087 个数据库上零失败；h2 的 20 次失败全部收敛到**同一个** DDLsmith 缺陷
  （见 §2 第 15 项），已修复。

---

## 2. 发现并修复的工具缺陷

按严重程度排列。每项都给出了触发证据与修复位置。

### 致命：运行不结束

1. **多线程运行永不退出**（`--threads > 1` 时随机发生）
   * 证据：H2 20 库 / 2 线程的进程在全部数据库跑完后仍存活 10 分钟以上；`jstack` 显示主线程停在
     `ThreadPoolExecutor.awaitTermination`，worker 线程空转等待任务。
   * 根因：`Main.executeMain` 让 worker 通过 `threadsShutdown++` 判断“是否全部完成”，`volatile int`
     自增存在丢失更新，计数永远到不了总数，`shutdown()` 从未调用。
   * 修复：任务提交完毕后由主线程调用 `execService.shutdown()`；计数改为 `AtomicInteger`，只用于进度显示。
   * 回归测试：`RunTerminationTest`（旧代码下会超时失败）。

2. **常量缓存跨线程共享 → 生成器崩溃 + 同种子不可复现**
   * 证据：`h2 --num-tries 32 --threads 8` 时 2 个数据库抛
     `IndexOutOfBoundsException: Index 2 out of bounds for length 0`（`Randomly.fromList` ← 常量缓存），
     计为 HARNESS_CRASH；同一 `--random-seed` 在 1 线程与 6 线程下 6/6 个数据库的 DDL 完全不同。
   * 根因：`CACHED_LONGS/STRINGS/DOUBLES/BYTES` 是静态 `ArrayList`，而每个 worker 开工时都会
     `setSeed()` → `clearCache()`，于是一个线程清空缓存时另一个线程正在读取。
   * 修复：四个缓存改为 `ThreadLocal`；`clearCache()` 只清当前线程。
   * 验证：修复后同样对比 6/6 完全一致；回归测试 `MultiThreadedReproducibilityTest`。

### 严重：会误报“引擎缺陷”（最有害的一类）

3. **崩溃 oracle 把“生成的行”被拒当成引擎缺陷**：`engine rejected seed-insert`（高置信度）实际是行生成
   器不满足自己 schema 的 UNIQUE/FK 约束。修复：崩溃 oracle 不再上报非 probe 的 INSERT/UPDATE/DELETE
   （`seed-insert` 等），由数据 oracle 以“表无法填充”记录。
4. **约束 oracle 把“引擎过严”误报**：每块最高 79 次 `CONSTRAINT_TOO_STRICT`，实际是行生成器重复了唯一值或
   指向不存在的父行。修复：引擎因生成器未建模的约束（UNIQUE/PRIMARY KEY/FOREIGN KEY）拒绝时只记 note。
5. **DuckDB 上 100% 的 evolution 库被误报**：`Dependency Error: Cannot alter entry ... because there are
   entries that depend on it`。探针确认：DuckDB 1.3 对“有索引”或“被外键引用”的表拒绝除 `ADD COLUMN` 之外的
   全部 ALTER。修复：`EngineTraits.alterRequiresNoDependents` + 生成器只选无依赖的表。
6. **H2 上 14+29+3 次误报**：
   * `ALTER TABLE ... ADD CONSTRAINT uq_t_3_0` 与初始 schema 的约束**重名**（`Constraint "UQ_T_3_0" already exists`）；
   * `CREATE INDEX / ADD UNIQUE` 作用在 H2 无法索引的 `CHARACTER LARGE OBJECT` 列上；
   * `TRUNCATE TABLE` 作用在有依赖（索引/被外键引用）的表上（29 次）。
   修复：约束名避开已用名；唯一约束与索引只取 `dialect.isIndexable(type)` 的列；TRUNCATE 跳过有依赖的表。
7. **SQLite 上 rename/drop column 全部失败**：生成器先 `DROP TABLE t_new_4`，而 `ev_v_t_new_4_5` 视图仍
   引用它，SQLite 在任何 DDL 前会重新校验全部视图，于是后续语句报
   `error in view ...: no such table`。修复：跟踪“被视图引用的表”，不删这类表，也不重命名/删除其列。
8. **H2 生成列链**：`a13 BIGINT GENERATED ALWAYS AS (a2 + 1)`，而 `a2` 本身是生成列 → H2 报
   `Column "A2" not found` 并整表创建失败（83 500 库中 10 次，全部同一根因）。探针确认 H2 允许基于普通列、
   拒绝基于生成列。修复：`SchemaRepair` 把这类派生列改为普通存储列（并置为可空以便插入）。
9. **DuckDB 外键类型链**：`t_6.id_2` 被改写为 INTEGER，而引用它的 `t_1.t_6_id_2` 仍是 BIGINT →
   `incompatible types between column "id_2" ("INTEGER") and column "t_6_id_2" ("BIGINT")`。根因：类型对齐只跑
   一趟，链式引用未传播。修复：对齐迭代到不动点。
10. **DuckDB 布尔默认值比较**：目录返回 `CAST('f' AS BOOLEAN)`，模型是 `false` → 每个布尔默认值都被报成
    “默认值丢失”。修复：`SchemaNormalizer` 归一化该写法。

### 中等：语句/模型不一致与可诊断性

11. **evolution 语句不限定 schema**：`CREATE VIEW ev_v_... AS SELECT ... FROM t_3`、`DROP TABLE IF EXISTS
    t_new_1`、`TRUNCATE TABLE t_1`、序列语句都未加命名空间，导致 H2 上视图缺失 47 次、表未删 14 次、
    TRUNCATE 被拒。修复：`Dialect.qualifyName`，并在 `DropTable/TruncateTable/DropSequence/CreateSequence`
    与各方言覆写中统一走 `renderTableReference`。
12. **DuckDB 的 ADD COLUMN 约束**：`ADD COLUMN ... NOT NULL/CHECK` 被 DuckDB 拒绝
    （`Adding columns with constraints not yet supported`），模型却认为列已存在。修复：
    `EngineTraits.allowsConstraintsOnAddedColumn`（duckdb=false）。
13. **模型不回滚被拒绝的语句**：任何引擎拒绝都会在下一句比较中变成“schema 分歧”，既造假又掩盖真正的拒绝。
    修复：`RandomDDLGenerator.discardLastStatement()` + `RelationalSchema.restoreFrom`，evolution 算法在语句
    失败时回滚模型。
14. **SQLite identity 主键类型**：生成器建表用 BIGINT + identity，SQLite 存成 INTEGER（`INTEGER PRIMARY KEY
    AUTOINCREMENT`）→ 96 次 `COLUMN_TYPE` 分歧。修复：生成器建表/加列时应用 `Dialect.effectiveType`。
15. **`--log-each-select` 完全无效**：文档写“默认开、复现需要”，实际 live 日志从未被写入（写入路径没有
    任何算法调用）。修复：把 `StateLogger` 接到 `StatementRunner`，选项真正生效，默认改为关（失败日志本来
    就完整记录语句，复现不依赖它）。
16. **`--quiet` 不抑制进度行**：文档说抑制“banner 与进度输出”，实际只抑制 banner。修复：`--quiet` 同时
    关掉周期性进度行（最终汇总保留，因为它是结果而不是噪声）。
17. **bug 目录跨运行互相覆盖**：每次运行编号从 001 重新开始并重写文件，上一次运行的报告会被同名新报告
    覆盖，`INDEX.md` 也被重建成只有本次运行的内容。修复：启动时读取已有报告，沿用原 id，就地更新
    出现次数/种子/数据库/标题（保留正文与人工批注），新缺陷取下一个空闲编号。
18. **报告里的复现命令无法复现**：`--ddl-features` 等“塑形”选项没被写进命令，于是“强制 DuckDB 支持
    CASCADE”的发现给出的命令生成的是另一套 schema。修复：命令包含 `--ddl-features/--disable-ddl-features/
    --graph-generator/--oracle/--evolve-steps/--num-ddl/--insert-rows/--max-constraint-probes/--schema-name`。
19. **harness 崩溃不可诊断**：只打印栈，没有 seed、没有已执行语句。修复：崩溃写入
    `logs/<engine>/<db>-crash-NNN.log`（含 seed 与已执行语句）。
20. **MySQL 的 `--host/--port/--database` 被忽略**（只有 postgres 生效）。修复：所有客户端/服务器引擎统一
    应用命令行连接参数，嵌入式引擎不受影响。
21. **死选项**：`--max-generated-databases` 解析后从未使用。已删除。

### 文档一致性

22. README：测试数（241 → 实际 265）、`--rows-per-table`（实际是 `--insert-rows`）、`--num-tries` 语义
    （是总数，不是每线程）、`--quiet`/`--log-each-select` 描述、补齐未记录选项、bug 目录的“跨运行累积”
    语义、`logs/` 每库一文件的说法（仅 `--log-each-select` 打开时成立）。

---

## 3. 修复验证（A/B，同一批种子）

| 运行 | 引擎 | 修复前失败 | 修复后失败 |
| --- | --- | --- | --- |
| evolution chunk 0 | sqlite | 100 / 100 库 | 39 |
| evolution chunk 3 | sqlite | 100 / 100 库 | 46 |
| evolution chunk 6 | sqlite | 100 / 100 库 | 40 |
| evolution chunk 1 | h2 | 100 / 100 库 | 80 |
| evolution chunk 4 | h2 | 99 / 100 库 | 78 |
| evolution chunk 2 | duckdb | 100 / 100 库 | 100（当时仍未修依赖规则） |
| constraints chunk 0 | sqlite | 176 | 84 |
| constraints chunk 3 | sqlite | 191 | 90 |
| constraints chunk 1 | h2 | 93 | 0 |
| constraints chunk 4 | h2 | 89 | 0 |
| constraints chunk 2 | duckdb | 176 | 38 |

定向复现（单个种子，最能说明问题）：

| 复现 | 修复前 | 修复后 |
| --- | --- | --- |
| `sqlite --algorithm differential --compare-with h2,duckdb -s 4200003` | 4 个失败（外键类型链） | **0** |
| `h2 -s 9200054`（生成列链） | 2 个失败 | **0** |
| `duckdb --algorithm evolution -s 5200000` | 100/100 库失败 | 见 §4 最终复验 |

最终复验（在全部修复后的构建上，同种子同参数）见 §4。

单元测试：`mvn -o test` → **265 tests, 0 failures**（新增 5 个回归测试类/用例：
`RunTerminationTest`、`MultiThreadedReproducibilityTest`、`GeneratorRegressionTest`、`SchemaRepairTest`、
`CliBehaviourTest`，以及 `BugRepositoryTest`/`SchemaOracleTest`/`SchemaNormalizerTest` 的新用例）。

---

## 4. 最终复验（最终构建）

同一批种子、同一参数，在全部修复后的构建上重跑（`runs/verify-evolution`、`runs/final-evolution`、
`runs/verify-constraints` 的 `summary.tsv`）。

**evolution（每块 100 个数据库，25 步 ALTER）**

| chunk | 引擎 | 种子 | 修复前失败 | 修复后失败 | 修复后构成 |
| --- | --- | --- | --- | --- | --- |
| 0 | sqlite | 5000000 | 100 | **26** | 全部 METAMORPHIC（实验 oracle） |
| 1 | h2 | 5100000 | 100 | **3** | 1 SCHEMA_MISMATCH + 2 UNEXPECTED_ERROR |
| 2 | duckdb | 5200000 | 100 | **17 → 见 final-evolution** | 全部 UNEXPECTED_ERROR（依赖规则） |
| 3 | sqlite | 5300000 | 100 | **35** | 全部 METAMORPHIC |
| 4 | h2 | 5400000 | 99 | **3** | 1 SCHEMA_MISMATCH + 2 UNEXPECTED_ERROR |
| 5 | duckdb | 5500000 | 100 | **11** | 全部 UNEXPECTED_ERROR |
| 6 | sqlite | 5600000 | 100 | **30** | 全部 METAMORPHIC |
| 7 | h2 | 5700000 | 100 | **1** | UNEXPECTED_ERROR |

合计：800 个数据库的失败数从 **799** 降到 **126**（其中 91 个来自实验性的 metamorphic-alter oracle，
报告标注 `needs review`）；可信 oracle（crash / schema-round-trip）的发现从约 800 降到约 35，
再经过最后两项 DuckDB 规则修复后进一步下降（见 `final-evolution`）。

**constraints（`--oracle ALL`，每块 100 个数据库）**

| chunk | 引擎 | 修复前失败 | 修复后失败 |
| --- | --- | --- | --- |
| 0 | sqlite | 176 | 84 |
| 1 | h2 | 93 | **0** |
| 2 | duckdb | 176 | 38 |
| 3 | sqlite | 191 | 90 |
| 4 | h2 | 89 | **0** |
| 5 | duckdb | 183 | 49 |
| 6 | sqlite | — | 74 |
| 7 | h2 | — | 1 |
| 8 | duckdb | — | 35 |
| 9 | sqlite | — | 64 |
| 10 | h2 | — | **0** |

h2 从“每库约一次失败”降到 0；剩余失败集中在实验性 oracle（`DATA_MISMATCH` = pivot-row、
`METAMORPHIC` = metamorphic-alter、`CONSTRAINT_NOT_ENFORCED`），均为 `needs review`。

**定向复现（单种子，最终构建，各 100 个数据库）**

| 复现 | 修复前 | 最终 |
| --- | --- | --- |
| `sqlite --algorithm differential --compare-with h2,duckdb -s 4200003` | 4 个失败 | **0** |
| `h2 -s 9200054`（生成列链） | 2 个失败 | **0** |
| `duckdb --algorithm evolution -s 5200000` | 100 | **0** |
| `duckdb --algorithm evolution -s 5500000` | 100 | **0** |
| `h2 --algorithm evolution -s 5100000` | 100 | **1** |
| `h2 --algorithm evolution -s 5400000` | 99 | **0** |
| `h2 --algorithm evolution -s 5700000` | 100 | **0** |
| `h2 --algorithm evolution -s 6900000` | 100 | **0** |
| `sqlite --algorithm evolution -s 5000000` | 100 | **23**（全部 METAMORPHIC，实验 oracle） |
| `sqlite --algorithm evolution -s 5300000` | 100 | **37**（全部 METAMORPHIC） |

结论：evolution 在 duckdb 上从“每库必失败”降到 0，在 h2 上从 100 降到 0～1，在 sqlite 上剩下的
全部来自实验性的 `metamorphic-alter` oracle（报告标注 `needs review`），**可信 oracle（crash /
schema-round-trip）在 sqlite 上已无发现**。唯一残留的 h2 失败是 1 个 `DROP COLUMN` 分歧（同样标注
`needs review`）。

单元测试：`mvn -o test` → **269 tests, 0 failures**。

### 4.1 最后一轮修复（在 §3 复验之后追加）

复验暴露的三条更细的引擎规则，均已修复并复验：

* **DuckDB 拒绝删除“位于外键列之前”的列**（`Cannot drop this column: an index depends on a column
  after it!`）：探针确认 `FOREIGN KEY (t_3_a6)` 存在时，删除它前面的列失败、删除它后面的列成功。
* **DuckDB 拒绝修改被 `CHECK` 约束引用的列的类型**（`Cannot change the type of a column that has a
  CHECK constraint specified`）。
* **H2 不允许 `DROP NOT NULL` 作用于 identity 列**（`Column "ID_2" must not be nullable`），也不允许直接
  删除与唯一约束/主键重复的索引（`Index ... belongs to constraint UQ_T_2_0`）。
* **重命名不应转移“归属权”**：生成器把重命名后的新名字登记为“自己创建的列”，于是随后删除了一个来自
  create script 的列——这正是 DuckDB 那条“外键列之前的列不能删除”规则的触发路径。

---

## 5. 测试方法上的说明（诚实的边界）

* 单元测试类别的一小时里有 10 轮 `BUILD FAILURE`：这是**我自己**在循环运行期间修改源码、以及并发执行
  `mvn package` 造成的（编译期错误、`target/test-classes` 被覆盖），不是测试不稳定。停止改源码后连续
  60+ 轮全部 265/265 通过。
* 三个类别（evolution/constraints）第一次启动时因为我的运行脚本把计划参数按空格切分而整段失败，已修正
  并重新计时；`reproduce` 的 11 处“异常”出现在旧构建上，用新构建逐个回放这些日志不再出现异常。
* 五方言中的 PostgreSQL/MySQL 无服务器可用，只做了渲染与单元级验证；这一点在 README 中已如实标注。

---

## 6. 引擎侧的真实发现（可独立复现）

1. **DuckDB 1.3 接受 `ALTER TABLE ... ALTER COLUMN <键列> DROP NOT NULL` 但不生效**：
   ```sql
   CREATE TABLE t (a INTEGER NOT NULL, b INTEGER, PRIMARY KEY (a));
   ALTER TABLE t ALTER COLUMN a DROP NOT NULL;          -- 成功
   SELECT is_nullable FROM information_schema.columns WHERE table_name='t' AND column_name='a';  -- NO
   ```
   非键列同样语句会被正确应用。
2. **DuckDB 1.3 拒绝在既有表上加带约束的列**：`ADD COLUMN c INTEGER NOT NULL`、`... CHECK (...)`、`... UNIQUE`
   全部 `Parser Error: Adding columns with constraints not yet supported`；`ADD COLUMN c INTEGER DEFAULT 3`
   可以。
3. **DuckDB 1.3 对“有索引或被外键引用”的表拒绝除 `ADD COLUMN` 外的全部 ALTER**（见 §2 第 5 项探针输出）。
4. **H2 2.3 拒绝“基于生成列的生成列”**：`a1 GENERATED ALWAYS AS (a0+1)` + `a2 GENERATED ALWAYS AS (a1+1)`
   → `Column "A1" not found`（整表创建失败）；基于普通列可以。
5. **H2 2.3 不能为 `CHARACTER LARGE OBJECT` 列建索引**，`CREATE INDEX` / `ADD UNIQUE` 报
   `Feature not supported: Index on column ...`；也不能 `TRUNCATE` 有依赖的表。
6. **SQLite 在任何 DDL 前重新校验所有视图**：一个因表被删除而失效的视图会让之后每条 DDL 报
   `error in view ...: no such table`。

---

## 7. 结论

* 工具的核心路径（graph → ER → 关系模式 → DDL → 目录回读 → 比较）在 sqlite/duckdb 上各数千库零失败，
  在 h2 上 83 500 库仅剩一个已修复的生成器缺陷，差分测试与渲染确定性均无异常。
* 本次共修复 **27 项代码缺陷 + 文档一致性修正**，其中 2 项致命（运行不结束、跨线程缓存导致的崩溃与不可
  复现）、8 项会**误把工具自身的问题报成引擎缺陷**。
* `evolution` 算法的可信度发生了实质变化：修复前它在 duckdb/h2/sqlite 上几乎对每个数据库都产生一条
  “引擎缺陷”，修复后 duckdb 为 0/100，h2 为 0～1/100，sqlite 的剩余发现全部来自标注 `needs review`
  的实验 oracle。建议再清一轮 sqlite 的 METAMORPHIC 期望模型后，把 evolution 的置信度提升为可信。
* `constraints`/`--oracle ALL` 仍以实验 oracle 的期望模型为主（`DATA_MISMATCH`/`METAMORPHIC`/
  `CONSTRAINT_NOT_ENFORCED`），但最有害的两类误报（把生成的行当成引擎缺陷、把行生成器不满足的约束当成
  “引擎过严”）已消除；h2 上从每库约 0.9 次失败降到 0。
* 剩余工作（按优先级）：sqlite 的 `metamorphic-alter` 期望模型、`pivot-row` 的 `DATA_MISMATCH`、
  PostgreSQL/MySQL 的实机验证。

---

## 8. 后续会话的处置（2026-10-05 追加）

本文列出的剩余工作，前两项已在 [ACCEPTANCE-2.md](ACCEPTANCE-2.md) 中完成：

| 本文的剩余工作 | 处置 |
| --- | --- |
| sqlite 的 `metamorphic-alter` 期望模型 | 已修复：`DROP COLUMN` 不再选中引擎无法原样还原的列（`EngineTraits.canAddColumnExactly`），同一列也不再被两个动作同时选中。第二轮 955 库 **0** 失败 |
| `pivot-row` 的 `DATA_MISMATCH` | 已修复：该 oracle 现在先清空它要查询的表（`Support.populate(..., resetTables)`），不再拿自己插入的行去比对更早的 oracle 留下的行。第二轮 1 178 库 **2** 条失败，且都是引擎行为 |
| PostgreSQL/MySQL 的实机验证 | **已完成**（2026-10-05 更正）：两个专属容器起来了（MySQL 26.7.0 宿主 33306、PostgreSQL 18.6 宿主 15432），MySQL 的第一次可信大规模实测见 [ACCEPTANCE-MYSQL.md](ACCEPTANCE-MYSQL.md)。本行原先写「仍未完成——本机 5432/3306 依旧没有服务」，那句话描述的是当时的端口，而不是后来发生的事：实机验证用的是**另起的容器端口**，仓库根 `config.json` 的 3306 至今没有监听，所以走战役配置 `runs/mysql-campaign/config.json` |

同时本文中「evolution 与 DML oracle 只能读、不能信」的结论也已更新：`evolution` 在同一批种子上的
第二轮 955 库与确认轮 955 库**均为 0 失败**（第一轮同种子区间的 988 库有 124 次失败，全部是工具缺陷，
已修复），因此已从实验性提升为可信；`--oracle ALL` 的剩余输出也已收敛到可用最小复现确认的引擎行为。
两轮合计的失败数从 **648（48 891 库）降到 2（42 938 库）**。
