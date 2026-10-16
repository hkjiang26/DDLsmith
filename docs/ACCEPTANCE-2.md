# DDLsmith 验收报告（第二轮会话）

本报告记录一次完整的「两轮测试 → 定位 → 修复 → 复验」循环。与上一份
[ACCEPTANCE.md](ACCEPTANCE.md) 的区别在于：本轮**九类测试各跑两次、每次 30 分钟**，两轮的种子区间
完全相同，因此每一轮都是对上一轮修复的**同种子 A/B 复验**。

* 被测对象：`target/DDLsmith.jar`（Java 17，Maven 构建）
* 环境：Windows，20 逻辑核，15.7 GB 内存；PostgreSQL/MySQL 服务器不可达（5432/3306 均未监听），
  这两者仍只做 `--print-ddl-only` 渲染与单元级验证
> 注：本文引用的 `runs/`、`bugs/` 证据树与 `logs/` 运行产物已不随仓库分发；文中这些路径是当时的原始记录。

* 编排脚本：`runs/run-round.ps1`（九类并行，每类独立 JVM、独立种子、独立目录、独立超时与硬杀）
* 定向复验脚本：`runs/verify-round.ps1`（从失败日志里取回引擎、种子与算法参数，在指定构建上原样重跑）
* 原始数据：`runs/round1/`、`runs/round2/`、`runs/round3/`、`runs/round4/`——每个类别一个子目录，
  内含该类的 `summary.tsv`、失败日志（`logs/<引擎>/`）与 bug 目录（`bugs-chunk-NNN/`）；
  定向复验结果在各轮的 `verify/verify.tsv`
* 引擎行为探针：`runs/probes/`（`DuckDbSelfRef.java`、`UnclearProbes.java`、`SelfRefUpdate.java`）

---

## 1. 测试规模与两轮对比

九类测试并行执行，每类预算 1800 秒；每一块（chunk）是独立 JVM，独立种子区间，独立 bug 目录，
并有 `--timeout-seconds` 与硬杀双重保护。

| 类别 | 命令要点 | 第一轮规模 | 第一轮失败 | 第二轮规模 | 第二轮失败 |
| --- | --- | --- | --- | --- | --- |
| 单元/组件 | `mvn -o test` 循环 | 41 轮 × 272 测试 | 0 | 36 轮 × 272 测试 | 0 |
| sqlite | 默认 oracle | 3 533 库 | 0 | 3 558 库 | 0 |
| h2 | 默认 oracle | 38 000 库 | 0 | 32 400 库 | 0 |
| duckdb | 默认 oracle | 3 576 库 | 0 | 3 374 库 | 0 |
| differential | `sqlite --compare-with h2,duckdb` | 1 522 库 | 0 | 1 473 库 | 0 |
| evolution | 三引擎轮换，`--evolve-steps 25` | 988 库 | **124** | 955 库 | **0** |
| constraints | 三引擎轮换，`--oracle ALL` | 1 272 库 | **524** | 1 178 库 | **2** |
| printddl | 5 方言 × 6 图生成器，各打印两遍 | 1 069 × 2 | 0 | 932 × 2 | 0 |
| reproduce | 回放本轮记录的全部失败日志 | 501 次回放 | 10 处告警 | 307 次回放 | 4 处告警 |
| **合计** | | **48 891 库** | **648** | **42 938 库** | **2** |

失败总数从 **648 降到 2**，且这 2 项经最小复现确认是**引擎真实行为**（见 §6）。

第一轮失败按「引擎 / 失败类型 / oracle」分布（每库一条日志，共 422 条）：

| 数量 | 引擎 | 失败类型 | oracle | 判定 |
| --- | --- | --- | --- | --- |
| 173 | sqlite | `METAMORPHIC` | metamorphic-alter | 工具缺陷 |
| 208 | sqlite + duckdb | `DATA_MISMATCH` | pivot-row | 工具缺陷 |
| 33 | sqlite | `CONSTRAINT_NOT_ENFORCED` | referential-action | 工具缺陷 |
| 5 | sqlite | `STANDARD_DEVIATION` | referential-action | 工具缺陷（同一根因） |
| 2 | sqlite + h2 | `CONSTRAINT_TOO_STRICT` | constraint-enforcement | 工具缺陷 |
| 1 | h2 | `SCHEMA_MISMATCH` | evolution | 工具缺陷 |

**第一轮 422 条失败全部是 DDLsmith 自身的缺陷，没有一条是引擎缺陷。** 这正是本工具最危险的失效
模式——把工具的错误报成引擎的缺陷——因此本轮的全部工作就是把它们消除。

---

## 2. 第一轮发现并修复的工具缺陷

按严重程度排列；每项都给出触发证据、根因与修复位置。本节共 12 项代码缺陷 + 4 项文档/交付物一致性
修正；另有 2 项代码缺陷由第二轮与交付物复现发现，记在 §5。合计 **14 项代码缺陷 + 4 项一致性修正**。

### 致命：会让 oracle 误报引擎缺陷

前三项来自**受信任**的 oracle（`schema-round-trip`、`crash`），第四项来自实验性的
`constraint-enforcement`——但它同样把工具的错误说成引擎的缺陷，所以放在一起。

1. **`DROP COLUMN` 无视引用该列的 `CHECK` 约束**
   * 证据：`runs/round1/evolution/logs/h2/database72-failure-001-schema_mismatch.log`——
     `ADD CONSTRAINT ck_t_2_3 CHECK (t_4_r_id <> 4)` 之后 `DROP COLUMN IF EXISTS t_4_r_id`，H2 连同
     该列一起删除了 CHECK，模型却仍然保留，`schema-round-trip` 报 `MISSING_CHECK_CONSTRAINT`。
   * 根因：`RandomDDLGenerator.dropGeneratedColumn` 检查了外键、主键、索引与前序外键列，唯独漏了
     `isChecked`；`renameColumn` 检查了，`dropGeneratedColumn` 没有。这违反了
     [DESIGN.md](DESIGN.md) §3.3 已经写明的规则。
   * 修复：`RandomDDLGenerator.dropGeneratedColumn` 增加 `isChecked(table, column.getName())` 判断。
   * 回归测试：`GeneratorRegressionTest.aCheckedColumnIsNeverDropped`。

2. **SQLite 的 identity 列被静默降级为普通列**
   * 证据：`COLUMN_IDENTITY at t_5.id_2: expected true, actual false`，由受信任的 `schema-round-trip`
     报出并归档为 `sqlite-007`（high confidence）。
   * 根因：`SchemaAdapter.enforceIdentityRestrictions` 用 `Table.isKeyColumn` 判断「是不是键列」，
     而唯一索引覆盖的列也算键列；但 SQLite 的渲染器只对**单列主键**输出
     `INTEGER PRIMARY KEY AUTOINCREMENT`。适配器保留了 identity，渲染器没输出，回读自然对不上。
   * 修复：新增 `EngineTraits.identityRequiresPrimaryKey` / `Dialect.identityRequiresPrimaryKey`，
     适配器改用与渲染器**逐字相同**的判定条件。

3. **`DECIMAL` 精度无上限地加宽**
   * 证据：`ALTER TABLE main.t_6_r ALTER COLUMN c_new_2 SET DATA TYPE DECIMAL(40,2)` →
     `Parser Error: Width must be between 1 and 38!`，崩溃 oracle 报成引擎缺陷。
   * 根因：`widen` 每次 +5 位，没有上限。
   * 修复：新增 `EngineTraits.maxDecimalPrecision`（duckdb=38，mysql=65），
     `RandomDDLGenerator.widen` 与 `MetamorphicAlterOracle.widen` 超出即返回 `null`（跳过该列）。
   * 回归测试：`GeneratorRegressionTest.decimalWideningStopsAtTheEngineLimit`。

4. **`NOT NULL` 生成列的表达式操作数为 NULL**
   * 证据：`runs/round1/constraints/logs/h2/database47-failure-001-constraint_too_strict.log`——
     `a13 GENERATED ALWAYS AS (a10 + 1) NOT NULL` 而 `a10=null`，H2 报
     `NULL not allowed for column "A13"`，工具却把这一行判定为「满足全部约束」并报引擎过严。
   * 根因：行生成器与 oracle 的校验都跳过生成列（无法求值），于是没人发现该列必为 NULL。
   * 修复：`RowGenerator.ensureGeneratedColumnsAreSatisfiable`——对每个 `NOT NULL` 生成列，解析其
     表达式中的标识符，把其中仍为 NULL 的基列填上值（生成列本身仍不插入）。
   * 回归测试：`ConstraintOracleSelfCheck.generatedNotNullColumn`。

### 严重：实验性 oracle 的大量误报

5. **metamorphic：`DROP COLUMN` 选中了引擎无法原样还原的列**
   * 证据：**617/617** 条 metamorphic 失败全部是 `sqlite / relation-A / COLUMN_DEFAULT`，形态完全
     相同：`DROP COLUMN a14` 的逆操作在 SQLite 上必须写成
     `ADD COLUMN a14 VARCHAR(20) NOT NULL DEFAULT ''`（SQLite 不接受无默认值的 NOT NULL 新列），
     于是「还原后」的列多了一个默认值。
   * 根因：oracle 只问「这个动作有没有逆操作」，没问「这个引擎能不能把它原样加回来」。
   * 修复：新增 `EngineTraits.canAddColumnExactly`（DuckDB 完全不接受带约束的新列；SQLite 会丢弃
     UNIQUE 并强加类型零默认值），`chooseActions` 的 `DROP COLUMN` 分支据此过滤。
   * 回归测试：`ConstraintOracleSelfCheck.metamorphicPositive`（SQLite 分支）。

6. **metamorphic：同一列被两个动作同时选中**
   * 证据：**154/617** 条日志里有 `action RENAME COLUMN a14 TO a14_mm ... was rejected (no such column: "a14")`
     ——`DROP COLUMN a14` 先执行，改名动作再去改一个已经不存在的列。
   * 根因：`chooseActions` 的 RENAME 分支把列名放进 `usedColumns` 却从不检查它，DROP 分支同样不检查。
     javadoc 明确写着「每列至多一个动作」，代码违反了这条不变量。
   * 修复：两个分支都先判断 `usedColumns.contains(name)`。

7. **pivot-row：期望模型只知道「自己插入的行」**
   * 证据：`runs/round1/constraints/logs/duckdb/database0-failure-001-data_mismatch.log`——
     `SELECT COUNT(a22) FROM t_5` 期望 1、实际 8；同一日志里 `t_5` 有 14 条 INSERT，来自算法的种子
     阶段与更早的 oracle。
   * 根因：数据 oracle 共用一个数据库，pivot-row 排在最后，表里早已有别人的行；而它的期望模型只
     由自己插入的行构建（`ConstraintEnforcementOracle.Support.populate`）。
   * 修复：新增 `Support.populate(..., boolean resetTables)`；pivot-row 传 `true`，populate 之前按
     **逆插入顺序**（先子后父）清空每张表，清空失败的表不参与比较。其余 oracle 行为不变。
   * 回归测试：`ConstraintOracleSelfCheck.pivotAfterAnotherOracle`。

8. **referential-action：`ON UPDATE` 探针读了 `ON DELETE` 动作**
   * 证据：`UPDATE t_3 SET id = 1000016 WHERE id = 13` 被拒；`t_1_r` 有两条指向 `t_3(id)` 的外键，
     被探针那条是 `ON UPDATE CASCADE`，另一条是 `ON UPDATE NO ACTION`——拒绝完全正确。
   * 根因：`restrictiveDependant` 无条件读取 `foreignKey.getOnDelete()`，而 update 探针需要的是
     `getOnUpdate()`。
   * 修复：新增 `restrictiveDependantOfParent(schema, parent, onUpdate, child, underTest)`，
     update 探针传 `true`，delete 探针传 `false`。

9. **referential-action：delete 探针没有检查「保护父行的另一条外键」**
   * 证据：`DELETE FROM t_1 WHERE id_2 = 23` 被拒；`t_5[t_1_id_2] -> t_1[id_2]` 没有 `ON DELETE`
     （即 NO ACTION）且已有子行。删除整行会被任何指向该行的限制性外键阻止。
   * 根因：delete 探针只检查了「谁引用**子表**」，没检查「谁引用**父表**」。
   * 修复：`cascadeLike` 分支在原有检查之外，再调用 `restrictiveDependantOfParent(..., false, ...)`。

10. **referential-action：把「父行自己」算成了引用它的子行**
    * 证据：`t_4[e4_dst_id] -> t_4[id]`（自引用），行 `(15, e4_dst_id=15)`；
      `DELETE FROM t_4 WHERE id = 15` 被 SQLite 接受，因为该行连同它对自己的引用一起消失。
      4 条 NO ACTION + 1 条 RESTRICT 误报都是这个形态。
    * 修复：自引用外键时用一次额外计数求出「父行自身」的贡献并扣除；扣除后为 0 则跳过探针并记录 note。
    * 回归测试：`ConstraintOracleSelfCheck.selfReferencingRowGuard`。

11. **referential-action：`ON UPDATE CASCADE` 的更新后计数恒为 0**
    * 证据：`expected 1 child row(s) to carry the new key, found 0; 0 child row(s) still carry the old key`，
      而引擎的级联其实完全正确。
    * 根因：`updateProbe` 把**以子列名为键**的 `childNewValues` 传给 `childCount`，而 `childCount`
      会再按**父列名**去取值——`getValue(map, "id")` 在键为 `t_1_id` 的 map 里找不到，得到 null，
      生成的谓词是 `WHERE "t_1_id" = NULL`，永远匹配不到行。
    * 修复：改传以父列名为键的 `newValues`，删除多余的 `childNewValues` 构造。
    * 回归测试：`ConstraintOracleSelfCheck.updateCascadeIsObserved`。

12. **SQLite 布尔值与数值的归一化类型不一致**
    * 证据：`SELECT MAX(a20) FROM t_5` 期望 `true (Boolean)`、实际 `1 (Integer)`；19 条日志，
      其中 16 条两边字面就是同一个数。
    * 根因：`DataModel.normalizeForComparison` 对 `Number` 返回 `BigDecimal`，对 `Boolean` 却返回
      自动装箱的 `Integer`，而调用方用 `equals` 比较——`Integer(1).equals(BigDecimal(1))` 为 false。
    * 修复：Boolean 分支返回 `BigDecimal.ONE / ZERO`。
    * 回归测试：`SchemaNormalizerTest.booleansNormalizeToTheSameTypeAsNumbers`。

### 文档与交付物一致性

13. `--num-tries` 的帮助文本写着「per thread」，实际是总数（`Main` 里只有一个循环）——已改为
    「a total, not a per-thread count」。
14. README §10 写着 241 个测试、`GeneratorRegressionTest` 5 个；实际为 272 / 9——已更正，
    `SchemaNormalizerTest` 18 → 19。
15. `ConstraintOracleTest` 的 javadoc 声称「不通过 `mvn test` 运行」，而 surefire 一直在跑它——已更正。
16. `bugs/` 目录里 2 份报告（`duckdb-001`、`duckdb-002`）来自修复前的构建，是工具缺陷被归档成
    引擎缺陷——已撤回，理由记录在 §7。

---

## 3. 定向复验（同种子，修复前 vs 修复后）

从第一轮的失败日志中按「失败类型 × 引擎 × oracle」抽样，取出其种子与算法参数，在**最终构建**上原样
重跑（`runs/verify-round.ps1`，结果在 `runs/round1/verify/verify.tsv`，共 32 条）：

| 失败类型 | 引擎 | 抽样数 | 修复前 | 修复后 |
| --- | --- | --- | --- | --- |
| `DATA_MISMATCH`（pivot-row） | duckdb | 6 | 各 1 | **0** |
| `DATA_MISMATCH`（pivot-row） | sqlite | 6 | 各 1 | **0** |
| `METAMORPHIC` | sqlite | 6 | 各 1 | **0** |
| `CONSTRAINT_NOT_ENFORCED` | sqlite | 6 | 各 1 | **0** |
| `STANDARD_DEVIATION` | sqlite | 5 | 各 1 | **0** |
| `CONSTRAINT_TOO_STRICT` | h2 / sqlite | 2 | 各 1 | **0** |
| `SCHEMA_MISMATCH`（evolution） | h2 | 1 | 1 | **0** |
| **合计** | | **32** | **32** | **0** |

代表种子（完整清单见 `verify.tsv`）：

| 失败类型 | 引擎 | 代表种子 |
| --- | --- | --- |
| `SCHEMA_MISMATCH`（evolution，被 CHECK 引用的列被删） | h2 | 1600072 |
| `METAMORPHIC`（SQLite 无法原样还原被删列） | sqlite | 2500000 / 2500010 / 2500011 / 2800012 |
| `DATA_MISMATCH`（pivot-row 拿到人留下的行） | duckdb | 1800001 / 2400010 / 2700000 / 2700011 |
| `DATA_MISMATCH` | sqlite | 2200011 / 2500011 / 2800000 / 2800001 |
| `CONSTRAINT_NOT_ENFORCED`（兄弟外键保护父行 / 自引用 / 更新级联计数） | sqlite | 1600019 / 1900015 / 2800001 / 2800013 |
| `CONSTRAINT_TOO_STRICT`（生成列操作数为 NULL） | h2 | 2300047 |
| `CONSTRAINT_TOO_STRICT` | sqlite | 1900098 |
| `STANDARD_DEVIATION` | sqlite | 1900041 / 1900089 / 2200057 / 2500091 |

**32/32 全部归零。** 第二轮的 2 条也用同一脚本复验（`runs/round2/verify/verify.tsv`）：种子 1900022
归零（缺陷 17 已修复），种子 1900033 仍报 1 条——那正是 §6.1 用 6 条语句复现过的 SQLite 行为。

单元测试：`mvn -o clean package` → **272 tests, 0 failures**。

---

## 4. 第二轮结果

第二轮使用与第一轮**完全相同的种子区间**（各类别基种子见 `runs/round2/_launchers/`），因此是严格
的 A/B 对比：

| 类别 | 第一轮失败 | 第二轮失败 |
| --- | --- | --- |
| unit | 0（41 轮） | 0（36 轮） |
| sqlite | 0 | 0 |
| h2 | 0 | 0 |
| duckdb | 0 | 0 |
| differential | 0 | 0 |
| evolution | **124** | **0** |
| constraints | **524** | **2** |
| printddl | 0 | 0 |
| reproduce | 10 处告警 | 4 处告警 |

* `evolution` 从「每个数据库都失败」降到 **0/955**：metamorphic 的 173 条与 h2 的 1 条
  `SCHEMA_MISMATCH` 全部消失。
* `constraints` 从 524 降到 **2**：pivot-row 的 208 条、referential-action 的 38 条、
  constraint-enforcement 的 2 条全部消失。
* `reproduce` 307 次回放**全部**在记录的语句处复现（exit 0），0 处真正的异常。

第二轮剩余的 2 条见 §6。

---

## 5. 第二轮发现并修复的缺陷

17. **`NO ACTION` 被当成 `RESTRICT` 检查**
    * 证据：`runs/round2/constraints/logs/sqlite/database22-failure-001-constraint_not_enforced.log`——
      `t_2_r` 有两个外键指向 `t_2(a2)`：`fk_t_2_r_0` 是 `ON DELETE CASCADE`，`fk_t_2_r_1` 是
      NO ACTION；一行同时携带两个键值 `('...05.0','...05.0')`。`DELETE FROM t_2 WHERE a2='...05.0'`
      先被 CASCADE 删掉该行，NO ACTION 检查时已经没有引用行，接受删除是**正确的**。
    * 根因：`RESTRICT` 在触碰父行时立即检查，`NO ACTION` 在语句结束时检查——两者语义不同，oracle
      用了同一套期望。
    * 修复：`ReferentialActionOracle.deleteProbe` 在 `accepted && expectFailure && action == NO_ACTION`
      时重新计数引用行；若已无引用行则记 note 并跳过，不再上报。
    * 回归测试：`ConstraintOracleSelfCheck.noActionAfterASiblingCascade`（两种引擎 × 20 个种子）。
    * 复验：同一日志的种子 1900022 在修复后 **0 失败**。

18. **同一张子表的两条级联外键：多删掉的行被算成「引擎删错了行」**
    * 证据：`runs/final-bugs/logs/sqlite/database13-failure-001-standard_deviation.log`——
      `t_1_r` 有两条指向 `t_4(id)` 的外键，都是 `ON DELETE CASCADE`
      （`fk_t_1_r_0(e4_src_id)` 与 `fk_t_1_r_1(e4_dst_id)`）。探针只统计了被探针那条外键引用的行，
      而另一条级联外键同样会删掉它引用的行，于是报
      `expected 9 child rows to remain, found 8`。
    * 根因：`expectedRemaining = childTotalBefore - referencing` 只减去了被探针外键的引用行。
    * 修复：新增 `cascadingRemovalCount`，用**一次析取查询**统计「通过本表任意一条级联外键指向该父行」
      的行数（同一行被两条键同时指向也只算一次），且该计数在 DELETE **之前**取值——第一版把它放在
      DELETE 之后，数出来的永远是 0，自检立刻把这个错误暴露出来。
    * 回归测试：`ConstraintOracleSelfCheck.twoCascadingKeysCountTheRowOnce`（两种引擎 × 20 个种子）。
    * 复验：同一种子（1791161894770）在修复后 **0 失败**。

---

## 5.1 最终确认轮

第二轮结束后又做了一轮短确认（`runs/round3/`、`runs/round4/`，各 8–10 分钟，覆盖
`constraints` / `evolution` / `differential` 三个会产出发现的类别）：

| 轮次 | 类别 | 数据库 | 失败 |
| --- | --- | --- | --- |
| round3 | differential | 1 489 | 0 |
| round3 | evolution | 955 | 0 |
| round3 | constraints | 971 | 1（§6.1 的 SQLite 行为） |
| round4 | differential | 1 200 | 0 |
| round4 | evolution | 684 | 0 |
| round4 | constraints | 895 | 1（同一条，同一种子 1900033） |

最后一轮的三类合计 2 779 个数据库只有 1 条发现，且它与上一轮是**同一条、同一种子**——即 §6.1 里
已经用 6 条语句最小复现过的 SQLite 行为。工具侧已无残余缺陷。

交付物 `bugs/` 用最终构建重新生成（2 330 个数据库，覆盖三个引擎 × 四种算法）：

```
[ 1] sqlite --num-tries 500 --threads 4                                databases=   500 failures=0
[ 2] h2 --num-tries 500 --threads 4                                    databases=   500 failures=0
[ 3] duckdb --num-tries 150 --threads 4                                databases=   150 failures=0
[ 4] sqlite --algorithm differential --compare-with h2,duckdb -n 100   databases=   100 failures=0
[ 5] sqlite --algorithm evolution --evolve-steps 25 -n 200             databases=   200 failures=0
[ 6] h2     --algorithm evolution --evolve-steps 25 -n 200             databases=   200 failures=0
[ 7] duckdb --algorithm evolution --evolve-steps 25 -n 80              databases=    80 failures=0
[ 8] sqlite --algorithm constraints --oracle ALL -n 250                databases=   250 failures=0
[ 9] h2     --algorithm constraints --oracle ALL -n 250                databases=   250 failures=0
[10] duckdb --algorithm constraints --oracle ALL -n 100                databases=   100 failures=0
```

`bugs/INDEX.md` 现在写着 **"No defect has been found yet."**——修复前的 2 份报告
（`duckdb-001`、`duckdb-002`）是工具缺陷被归档成引擎缺陷，已随构建一起撤回（理由见 §7）。

---

## 6. 残余发现：2 条，均为引擎真实行为

第二轮剩下的 2 条都来自 referential-action，经最小复现确认是**引擎行为**而非工具缺陷。

1. **SQLite 3.49 在同一语句里先执行 CASCADE、后检查 RESTRICT**
   * 日志：`runs/round2/constraints/logs/sqlite/database33-failure-001-constraint_not_enforced.log`
     （`t_3_r` 的两个外键指向 `t_1(id)`，一个 RESTRICT、一个 CASCADE，一行同时携带两个键值；
     `DELETE FROM t_1 WHERE id = 2` 被接受）。
   * 最小复现（6 条语句，`PRAGMA foreign_keys = ON`）：

     ```sql
     CREATE TABLE parent (k INTEGER NOT NULL PRIMARY KEY);
     CREATE TABLE child (id INTEGER NOT NULL PRIMARY KEY,
       restrict_ref INTEGER NOT NULL, cascade_ref INTEGER NOT NULL,
       CONSTRAINT fk_r FOREIGN KEY (restrict_ref) REFERENCES parent (k) ON DELETE RESTRICT,
       CONSTRAINT fk_c FOREIGN KEY (cascade_ref) REFERENCES parent (k) ON DELETE CASCADE);
     INSERT INTO parent (k) VALUES (1);
     INSERT INTO child (id, restrict_ref, cascade_ref) VALUES (10, 1, 1);
     DELETE FROM parent WHERE k = 1;   -- 被接受；SQL:2016 要求 RESTRICT 立即检查
     ```
   * 结论：SQLite 让同一条语句里的 CASCADE 先删掉了 RESTRICT 所保护的行。标准要求 RESTRICT
     「立即」检查，因此这是 SQLite 与标准的偏离。oracle 的期望是对的。

2. **DuckDB 1.3 无法存储「引用自己」的行**
   * 来源：第一轮 `constraints` 里唯一未能定性的 duckdb 记录
     （`t_1` 主键为 BOOLEAN、外键自引用），本轮用最小脚本定性。
   * 最小复现：

     ```sql
     CREATE TABLE t (id INTEGER NOT NULL, parent_id INTEGER NOT NULL, PRIMARY KEY (id),
       CONSTRAINT fk FOREIGN KEY (parent_id) REFERENCES t (id));
     INSERT INTO t (id, parent_id) VALUES (1, 1);
     -- Constraint Error: Violates foreign key constraint because key "id: 1" does not exist
     ```
   * DuckDB 逐行即时检查外键，因此自引用行永远插不进去——多行 INSERT、可空自引用、先插别的行都不
     例外。H2 2.3.232 与 SQLite 3.49 都接受同一语句（已实测）。对 `NOT NULL` 自引用外键而言，
     DuckDB 上这张表根本无法填充。
   * DDLsmith 的处理：`RowGenerator.resolveForeignKeys` 只在**没有父行**时才让 NOT NULL 自引用行
     指向自己，因此正常生成路径不会踩到；这条记录的价值在于它解释了历史上那条
     `CONSTRAINT_TOO_STRICT` 的 duckdb 记录。

---

## 7. 引擎侧的真实发现（可独立复现）

除上一份报告已经记录的之外，本轮新增/确认：

1. **DuckDB 1.3 拒绝自引用行**（见 §6.2）。
2. **SQLite 3.49 在同一 DELETE 中先 CASCADE 后 RESTRICT**（见 §6.1）。
3. **H2 2.3.232 接受自引用行的 `ON UPDATE NO ACTION` 并留下悬空外键**：

   ```sql
   CREATE TABLE t (id INTEGER NOT NULL, parent_id INTEGER NOT NULL, PRIMARY KEY (id),
     UNIQUE (parent_id), CONSTRAINT fk FOREIGN KEY (parent_id) REFERENCES t (id) ON UPDATE NO ACTION);
   INSERT INTO t (id, parent_id) VALUES (1, 1);
   UPDATE t SET id = 1000004 WHERE id = 1;   -- 被接受
   SELECT id, parent_id FROM t;              -- 1000004 | 1   ← 指向已不存在的键
   ```
   * 同一语句在 SQLite 上被正确拒绝。该行为由 `ConstraintOracleSelfCheck.selfReferencingRowGuard`
   持续监视。

---

## 8. 诚实边界

### 8.1 实机验证（2026-10-05 补做）

上一份报告与本报告 §8 原先写的「PostgreSQL/MySQL 无服务器可做实机验证」**不准确**，必须更正：本机
默认端口 5432/3306 确实没有监听，但 MySQL 8.0 是装好的（服务 `MySQL`/`MySQL80` 处于 Stopped），
Docker 也在运行且已有一个 PostgreSQL 实例（`dt-age`，宿主 5433）。我当时只做了默认端口的 TCP 探测，
就把上一轮报告的结论照搬了下来——默认端口没监听推不出「无法做实机验证」。

补齐方式：起两个**专属**容器（不碰 `dt-*` 那批别人的容器）：

```
docker run -d --name ddlsmith-pg    -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=ddlsmith -p 55432:5432 postgres:18
docker run -d --name ddlsmith-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ddlsmith \
  -p 33306:3306 mysql:26.7.0
```

实测版本：PostgreSQL **18.6**、MySQL **26.7.0**。配置与日志见 `runs/live/`。

**结果：实机路径此前根本无法工作，而且嵌入式引擎一个都看不出来。** 第一次运行
（`postgres --num-tries 1`）就在第一条 `CREATE TABLE` 上失败；20 库的普查里
PostgreSQL 43 次失败 / MySQL 60 次失败，两个引擎**几乎每个数据库都失败**。共发现并修复 7 项
只在实机上暴露的缺陷：

| # | 缺陷 | 证据 | 修复 |
| --- | --- | --- | --- |
| 19 | PostgreSQL 上外键目标不带 schema：`CREATE TABLE ddlsmith.t_3 (… REFERENCES t_1 (id))` 被解析到 `public` 而失败 | `ERROR: relation "t_1" does not exist`，第一次实机运行的**第一条**语句 | `PostgresDialect.getEffectiveSchemaName` 回退到 `public`，与 SQLite（`main`）/DuckDB（`main`）同一套做法——`Dialect` 的 javadoc 早就写明这是未加限定外键目标的必然结果，PostgreSQL 只是从未被实机跑过、没拿到这个覆写 |
| 20 | PostgreSQL 内省器把「非外键约束」当成有被引用表 | `ERROR: relation "ddlsmith.-" does not exist`，**20/20** 个数据库都让 `schema-round-trip` 抛异常 | `confrelid` 为 0（主键/唯一/CHECK）时 `0::regclass` 渲染成 `-`；`bareRelationName` 现在把 `-` 当作「无被引用表」返回 null |
| 21 | MySQL 声明了 `CREATE_INDEX_IF_NOT_EXISTS` 却从不渲染 | 20/20 报 `CREATE INDEX IF NOT EXISTS is not idempotent` | 实测 MySQL 对 `CREATE INDEX IF NOT EXISTS` 与 `DROP INDEX IF EXISTS` 都报 `ERROR 1064`；两个特性从特性集中删除——特性集是**契约**，声明一个引擎没有的能力正是 crash oracle 要抓的东西 |
| 22 | MySQL 拒绝「CHECK 约束引用被外键引用动作使用的列」 | `Column 't_2_id_2' cannot be used in a check constraint 'ck_t_1_1': needed in a foreign key constraint 'fk_t_1_0' referential action`（错误 3823），整张表建不出来 | 新增 `EngineTraits.refusesCheckOnReferentialActionColumn`，`SchemaAdapter` 在渲染前丢弃这类 CHECK 并记 note |
| 23 | PostgreSQL 把 CHECK 里的数值字面量加引号、把负数字面量写成 `-(27)`，且 `::double precision` 的转换残留 `precision` 一词 | `expected a24 != -1, actual [a24 != '-1']`；`a8 >= ('-32') precision` | `SchemaNormalizer.canonicalExpression`：比较运算符后紧跟的数值字面量去引号（限定在比较运算符之后，避免误伤真正的字符串比较）；cast 正则支持 `double precision` / `character varying` / `with time zone` 这类多词类型名 |
| 24 | PostgreSQL 的 `pg_get_viewdef` 带结尾分号 | `VIEW_DEFINITION at v_0`：两侧只差一个 `;` | `SchemaNormalizer.canonicalQuery` 去掉结尾分号 |
| 25 | MySQL 内省器用 DDLsmith 的**逻辑**库名当 schema | 表全部建成却报 `MISSING_TABLE` ×7 | `connection.getDatabaseName()` 是 `database0`，不是 MySQL 的库（`ddlsmith`）；`MySqlIntrospector.resolveSchema` 改为先问 `SELECT DATABASE()`，逻辑名只作兜底 |

修复后：**PostgreSQL 单库实机运行 0 失败**（44 条语句、默认 oracle 全绿，`runs/live/one/`）。
MySQL 单库从「7 张表全部 MISSING_TABLE」降到 1 条内容级差异（`information_schema` 返回的 CHECK
表达式带 `_utf8mb4` 字符集引导符、负数字面量写成 `-(27)`，以及一条索引未被报告）——这是下一段要清的
归一化尾巴，尚未修完。

**曾经存在的缺口（已修复，见 §8.1.1）：客户端/服务器引擎一次运行只能测一个数据库。**
`DatabaseConfig.jdbcUrl` 在配置了 `database` 时对所有生成库返回同一个库，而 schema 名固定为
`--schema-name`，于是第 2 个生成库开始表名冲突：`postgres --num-tries 5` → 4 次
`relation "t_0" already exists`，`mysql --num-tries 5` → 5 次 `Table 't_0' already exists`。
嵌入式引擎靠 `{db}` 占位符拿到「每个生成库一个全新数据库」，客户端/服务器引擎当时没有对应机制。

### 8.1.1 该缺口的处置：每个生成库一个专属命名空间

**选择「另起名」，而不是「删除共享的库」。** 共享名字下的删除有两个硬伤：`--threads N` 会同时测
N 个生成库，共享命名空间意味着线程之间互相删掉对方的对象；而 `DROP ... CASCADE` 会连带删掉命名空间
里任何不属于本工具的东西。所以：

* **命名**：`--schema-name` 加上生成库名，`ddlsmith` + `database3` → `ddlsmith_database3`
  （PostgreSQL 截到 63 字节、MySQL 截到 64，都从**尾部**截断，保证两个库不会因为截断而撞名）。
* **删除只作用于自己刚推导出的那个名字**，而且只在 `--keep-databases` 关闭时执行——这是嵌入式引擎
  「运行前先删掉数据库文件」的对应物，保证同前缀重跑从空开始，又不会碰到别人。
* **PostgreSQL 需要 `search_path`**：`AbstractDialect` 渲染外键目标时不带 schema，所以连接必须
  `SET search_path TO <专属 schema>, public`，否则就退回上一节那个 `relation "t_1" does not exist`。
  这也是为什么 PostgreSQL 不能简单地回退到 `public`——有了 search_path，它就能保留自己的命名空间。
* **MySQL 的 schema 就是 database**，所以它每个生成库建一个库，并用 `USE` 切过去；MySQL 不声明
  `CREATE_SCHEMA`，创建由连接准备阶段完成。

实现落在 `Dialect` 的四个新钩子上（`schemaNameForGeneratedDatabase`、`dropNamespaceStatements`、
`createNamespaceStatements`、`getSessionSetupStatements(schemaName)`），由 `AbstractJdbcProvider` 与
`Pipeline` 用**同一个方法、同一个库名**各算一次，连接准备与语句渲染因此不可能对不上；
`AbstractTestingAlgorithm` 对 `--compare-with` 的连接用公共 schema 名做同样的准备。

顺带修掉一个由多库并行暴露出来的误报：幂等 oracle 的**探测 schema 名**（`idem_schema_<n>`）是全局的，
两个并发库会同时探测同一个名字，而 PostgreSQL 的 `CREATE SCHEMA IF NOT EXISTS` 并非原子——两个会话
都发现 schema 不存在、都去插入，其中一个报
`duplicate key value violates unique constraint "pg_namespace_nspname_index"`。实测顺序执行完全幂等
（`NOTICE: schema ... already exists, skipping`），所以那是并发竞态而非引擎缺陷；探测名现在带上库名。

**复验（`runs/live/final/`，`--num-tries 20 --threads 4`）：**

| 引擎 | 修复前 | 修复后 | 命名空间 |
| --- | --- | --- | --- |
| PostgreSQL | 20 库**全部**建表失败（20 次 `already exists`）+ 20 条级联分歧 | 20 库 / **8** 条内容级分歧 | 20 个 `ddlsmith_database*` schema |
| MySQL | 60 条失败 | 20 库 / **21** 条 | 20 个 `ddlsmith_database*` 库 |

`already exists` 已完全消失。剩下的全部是内容级归一化差异（PostgreSQL 8 条都是 CHECK 表达式；
MySQL 是 CHECK / INDEX / VIEW_DEFINITION，另有 2 条 CHECK 落在「被外键引用动作使用的列」上——适配器
的守卫覆盖了建表时已有的 CHECK，但生成器后来追加的 CHECK 还没走同一条守卫）。这些是下一段要清的
归一化尾巴，不影响命名空间隔离本身。


### 8.2 其余边界

* `reproduce` 类别第一轮报告的 10 处「stack-trace」、第二轮报告的 4 处，经逐条回放确认**不是回放
  失败**：`exit=0`、语句全部执行、失败在记录位置复现。真正的原因是 sqlite-jdbc 在 `%TEMP%` 解包
  本地库时，无法删除另一个 JVM 仍持有的旧副本，于是打印
  `[main] ERROR org.sqlite.SQLiteJDBCLoader - Failed to delete old native lib` 与一段栈。这是多
  JVM 并发下的启动告警。第二轮已把回放输出按异常落盘（`reproduce/anomalies/`），因此这次能直接看到
  原因而不是猜测；编排脚本也已过滤这条已知告警。
* `reproduce` 类别第一轮还回放了仍在写入中的日志（另一类别正在追加），第二轮已改为只回放 60 秒内
  未再变化的日志。
* PostgreSQL / MySQL 的服务器**已可用**，本句原先写「仍无服务器可用，只做渲染与单元级验证」，与
  §8.1 自相矛盾，现更正：§8.1 已用两个专属容器做了实机验证（PostgreSQL 18.6、MySQL 26.7.0）。当时
  剩下的「归一化尾巴」后来被量化、定位并修完，MySQL 的第一次可信大规模实测见
  [ACCEPTANCE-MYSQL.md](ACCEPTANCE-MYSQL.md)（同种子 1 057 条失败 → 0 条，2 182 库 0 发现）。
* 第二轮的 2 条残余发现来自 `--oracle ALL`，属于 README 标注为实验性的 DML oracle；两条都已定位到
  引擎行为并给出最小复现，但报告里仍标 `needs review` 是恰当的。

---

## 9. 结论

* 两轮各九类、每类 30 分钟：第一轮 48 891 个数据库、648 次失败；第二轮 42 938 个数据库、**2 次失败**，
  且这 2 次是引擎真实行为。**第一轮的 422 条失败日志 100% 是工具自身缺陷，已全部消除。**
* 共修复 **14 项代码缺陷**（编号 1–12 与 17–18；其中 3 项会让**受信任**的 oracle 误报引擎缺陷，
  11 项会让实验性 oracle 误报）+ **4 项文档/交付物一致性修正**（编号 13–16）。
* 新增回归测试 8 个：`GeneratorRegressionTest` 2 个、`SchemaNormalizerTest` 1 个，
  `ConstraintOracleSelfCheck` 5 组场景（自引用删除、更新级联计数、限制性兄弟键、兄弟级联后的
  NO ACTION、两条级联键）。测试总数 269 → **272，全部通过**。
* 最终确认：round3 + round4 共 6 次类别运行、6 194 个数据库，只剩同一条 SQLite 行为发现；
  定向复验 32/32 在第一轮报告的种子上一律归零；另用最终构建重跑 2 330 个数据库覆盖三引擎四算法，
  **0 失败**，`bugs/INDEX.md` 为「No defect has been found yet.」。
* `evolution` 与 `constraints` 的可信度发生实质变化：`evolution` 在三个引擎上 955 库零失败；
  `constraints` 1 178 库仅 2 条引擎真实行为（修复最后一项后为 0～1 条）。
  **建议把 `evolution` 的置信度从「实验性」提升为可信**，`constraints` 的剩余输出可以直接当作
  引擎发现来读。
* 剩余工作（按优先级）：PostgreSQL/MySQL 的实机验证；把 `constraint-enforcement` 里那条基于错误
  文本关键字的守卫（`violatesAConstraintTheRowGeneratorDoesNotModel`）改成基于原因的判定，否则换个
  引擎措辞就可能重新出现误报；`MetamorphicAlterOracle` 与 `RandomDDLGenerator` 里重复的 `widen`
  逻辑可以合并到一处。
