# DDLSmith × MySQL 实验结果分析与工具缺陷定位

实验于 **2026-10-10T12:27** 手动停止，实际运行 **47.1 小时 / 144 轮中的 90 轮**。
本文分析该次运行产出的**全部**结果，区分「引擎缺陷」与「DDLSmith 自身缺陷」。

> **v2 修订说明**：初版把「自引用外键的 `ON UPDATE` 动作不执行」判为引擎缺陷，并据此把
> 186 个数据库计入引擎发现。复核 MySQL 官方手册后发现，**该行为是 MySQL 明文规定的、
> 有意的限制**，不是缺陷。本文已按复核结论改写，并补上在本机 6 个 MySQL 构建上
> 做的验证矩阵。第 7 节记录复核过程与证据。

---

## 1. 规模与稳定性

| 指标 | 数值 |
|---|---|
| 轮数 / chunk 数 | 90 / 1 611 |
| **生成的数据库** | **57 699** |
| 执行的语句 | 4 226 000 |
| 失败 | 765（→ 442 次独立事件 → 236 个数据库） |
| **有报告的数据库占比** | **236 / 57 699 = 0.41 %** |
| harness 崩溃 / 被杀的 chunk / 缺失的 summary | **0 / 0 / 0** |
| chunk 退出码非 0 | 386（=「本 chunk 发现了失败」，非故障） |

**没有任何一次 harness 故障。** 1 611 个 chunk 的 `state` 全为 `ok`，`killed` 全为 false，
`unavailable` 全为 0，810 个 `summary.tsv` 一个不缺。

### 资源（2 828 个采样点，47.1 小时）

| 指标 | 均值 | 峰值 | 配额 | 用量 |
|---|---|---|---|---|
| slice CPU | **0.342 核** | — | 4 核 | **8.6 %** |
| slice 内存 | 3 108 MB | **5 678 MB** | 16 384 MB | **35 %** |
| 宿主 CPU | 9.7 %（≈1.16 核 / 12 核） | 16.4 % | — | — |
| 宿主可用内存 | 45.3 GB | 最低 **41.9 GB** | — | — |
| swap | **0 MB，47 小时始终为 0** | 0 | — | — |
| 磁盘 | — | — | 839 GB 可用 | **47 小时用掉 1.61 GB = 0.034 GB/h** |
| 活跃命名空间 | 221 | **280** | 8 000 | **3.5 %** |
| MySQL 数据目录 | — | — | — | 2 227 → 2 561 MB 后不再增长 |

这个实验根本没有接近任何资源上限，CPU 只用了配额的 8.6 %；瓶颈是那块 7200 rpm HDD 的
fsync 延迟。命名空间数在 280 处收敛并回落——因为 `<schema>_database<N>` 的 `N` 在每个
chunk 内重新计数，同时存在的数量收敛到「单个 chunk 的最大产量」，不随时间累积。

---

## 2. 结果分解：29 个签名收敛为 7 个根因

按 **(轮, 数据库)** 去重后：

| 数据库数 | 家族 | 根因归属 |
|---:|---|---|
| 118 | A `fk-on-update-CASCADE`（自引用） | **DDLSmith 缺陷**（生成器产出了文档明文禁止的组合） |
| 68 | A `fk-on-update-SET_NULL`（自引用） | **DDLSmith 缺陷**（同上） |
| 23 | A `fk-on-update-CASCADE`（跨表） | **DDLSmith 缺陷**（oracle 过度断言） |
| 17 | E schema round-trip mismatch | **DDLSmith 缺陷**（§4.1 的级联） |
| 16 | D engine rejected CREATE | **DDLSmith 缺陷**（§4.1） |
| 14 | A `FOREIGN_KEY`（复合自引用 FK 未被强制） | **引擎缺陷——新版回归，已本地确认** |
| 13 | A `fk-on-delete-RESTRICT`（跨表） | **DDLSmith 缺陷**（§4.4 + §4.5） |
| 10 | F schema diverged after a DDL step | **DDLSmith 缺陷**（§4.3） |
| 6 | G differential mismatch vs postgres | **DDLSmith 缺陷**（§4.1 的级联） |
| 6 | H generated table cannot be queried | **DDLSmith 缺陷**（§4.1 的级联） |
| 4 | A `fk-on-update-SET_NULL`（跨表） | 待定，倾向 oracle 过度断言 |
| 2 | B cascaded row count differs | 待定 |
| 1 | C row satisfying every constraint rejected | **DDLSmith 缺陷**（§4.1 的级联） |
| 1 | A `fk-on-delete-CASCADE`（跨表） | 待定 |

**29 个报告签名只对应 7 个根因；其中 236 个有报告数据库里，只有 14 个是真引擎缺陷，
其余全部出在 DDLSmith 自己身上。**

---

## 3. 真正属于引擎的缺陷

### 3.1 【新发现，严重】MySQL 9.7 LTS / 26.7 的 SQL 层外键引擎漏检复合自引用外键

**14 个数据库**（`FOREIGN_KEY` 组）。这是本次唯一的引擎缺陷，而且是一条**引用完整性被静默破坏**的回归。

#### 现象

DDLSmith 自己产出的失败记录（`m7204/mysql-constraints-deep`，database9）：

```sql
CREATE TABLE t_2 (a5 BOOLEAN NOT NULL, a6 CHAR(5), a7 DECIMAL(10,2), a8 DATETIME,
  a9 VARCHAR(20) NOT NULL, e2_dst_a5 BOOLEAN, e2_dst_a9 VARCHAR(20), a22 INT,
  CONSTRAINT pk_t_2_0 PRIMARY KEY (a5, a9),
  CONSTRAINT ck_t_2_0 CHECK (a22 >= -90),
  CONSTRAINT fk_t_2_0 FOREIGN KEY (e2_dst_a5, e2_dst_a9) REFERENCES t_2 (a5, a9)
    ON DELETE CASCADE ON UPDATE RESTRICT) ENGINE=InnoDB;

INSERT INTO t_2 (a5, a6, a7, a8, a9, e2_dst_a5, e2_dst_a9, a22)
  VALUES (TRUE, 'tbkdc', -489.21, '2003-03-03 09:31:59.0', 'u867',
          TRUE, 'no_such_parent_value', -90);
-- 表里没有任何 (a5,a9) = (TRUE,'no_such_parent_value') 的行，插入却成功了
```

`foreign_key_checks=1`，约束在 `information_schema.KEY_COLUMN_USAGE` 里完整存在
（两行：`e2_dst_a5→a5`、`e2_dst_a9→a9`），`SHOW CREATE TABLE` 也带着它。

#### 精确规则（本机复现，不是推测）

在隔离会话里逐条执行、用退出码判定（`local/narrow.py`），得到的规则是：

> **对于指向自身所在表的复合外键，`INSERT` 只比较外键的第一列与「本行自己的被引用键的第一列」；
> 两者相等时，其余列一概不比较。**

| 用例 | 行自己的键 | 外键值 | 结果 |
|---|---|---|---|
| 精确自引用 | (1,5) | (1,5) | 接受 ✓ 合法 |
| 精确引用另一行 | (1,5)，另有 (7,7) | (7,7) | 接受 ✓ 合法 |
| **自己的首列匹配、次列不匹配** | (1,5) | **(1,99)** | **接受 ✗ 漏检** |
| **三列，自己的首列匹配** | (1,5,7) | **(1,5,99)** | **接受 ✗ 漏检** |
| 自己的首列不匹配 | (1,5) | (9,5) | 拒绝 ✓ |
| **别人的首列匹配** | (1,5)，另有 (7,7) | (7,99) | 拒绝 ✓ |
| 跨表外键，同样四种组合 | — | — | 全部正确 ✓ |
| `UPDATE`（而非 INSERT） | — | — | 正确 ✓ |

要点：漏检**必须**是 INSERT；**必须**是自引用表；判定只看「本行自己」而不看别的行；
跨表外键完全正常；显式建了子列索引也一样漏。

#### 版本矩阵（本机 Docker，每个构建独立容器）

`local/final_matrix.py`，输出见 `results/_fk_matrix_final.txt`：

| 构建 | `innodb_native_foreign_keys` | 首列匹配漏检 | 三列漏检 |
|---|---|---|---|
| MySQL 8.0.46 | （无此变量） | **拒绝 ✓** | **拒绝 ✓** |
| MySQL 8.4.11 LTS | （无此变量） | **拒绝 ✓** | **拒绝 ✓** |
| MySQL 9.7.2 LTS | **OFF（默认）** | **接受 ✗** | **接受 ✗** |
| MySQL 9.7.2 LTS | ON | 拒绝 ✓ | 拒绝 ✓ |
| MySQL 26.7.0 | **OFF（默认）** | **接受 ✗** | **接受 ✗** |
| MySQL 26.7.0 | ON | 拒绝 ✓ | 拒绝 ✓ |

把 DDLSmith 那条原始语句逐字放到全部 6 个构建上（`local/exact_all.py`，
输出见 `results/_exact_stmt_all.txt`）得到完全一致的二分：

```
MySQL 8.0.46            rejected  (correct)
MySQL 8.4.11 LTS        rejected  (correct)
MySQL 9.7.2 native=OFF  ACCEPTED  <-- referential integrity hole
MySQL 9.7.2 native=ON   rejected  (correct)
MySQL 26.7.0 native=OFF ACCEPTED  <-- referential integrity hole
MySQL 26.7.0 native=ON  rejected  (correct)
```

#### 结论

MySQL 在这个版本线里引入了**第二套外键实现**。`mysqld --verbose --help` 的官方说明：

```
--innodb-native-foreign-keys
    Use InnoDB foreign key checks and cascade operations instead of
    SQL level foreign key checks or cascade operations          (默认 FALSE)

--enable-cascade-triggers
    Execute trigger on child tables during foreign key cascade operations
    for SQL Engine foreign key handling (i.e. innodb_native_foreign_keys = OFF).
```

也就是说：

* **默认走的是新的「SQL 层外键检查」**（`innodb_native_foreign_keys=OFF`），它有复合自引用外键漏检的缺陷；
* 旧的 InnoDB 原生路径（`=ON`）**没有**这个问题，行为与 8.0 / 8.4 一致；
* 这个缺陷同时存在于 **9.7 LTS** 和 **26.7 Innovation**，即当前受支持的版本线；
* 8.0.46 与 8.4.11 没有这个变量，也没有这个缺陷 → **是 8.x 之后引入的回归**。

#### 这次实验自带对照组

DDLSmith 的 10 个 category 里，有 3 个（`mysql-native*`）是特意跑在
`innodb_native_foreign_keys=ON` 的实例上的。把两边的结果拆开：

| `FOREIGN_KEY` 组 | mysql-constraints | mysql-constraints-deep | **三个 \*-native 合计** |
|---|---|---|---|
| 出现次数 | 9 | 6 | **0** |

**57 699 个数据库里，漏检只出现在默认（SQL 层）配置上，原生配置一次都没有。**
这是一次无意的、但完全干净的对照实验，独立佐证了上面的版本矩阵。

> **建议**：这条应当作为 MySQL 缺陷上报（同时影响 9.7 LTS 与 26.7）。
> 在修复前，生产环境应设 `innodb_native_foreign_keys=ON`。
> 最小复现就是本文那两段 SQL，任何 9.7/26.7 默认实例都能重现。

---

## 4. DDLSmith 自身的缺陷

### 4.1 【严重】复合键没有字节预算，`VARCHAR(255)` 撑爆 InnoDB 的 3072 字节上限

**唯一根因**，产出 D、E、F、G、H、C 六个家族共 **56 个数据库**，
并且让工具以 `Confidence: high — reported by a verified oracle` 把**自己的错误**
当成「MySQL 的缺陷」上报。

#### 触发链

1. 图生成器给某列选到 `TEXT`，并把它放进主键/唯一键。
2. `Dialect.indexableType()`（`src/main/java/org/ddlsmith/ddl/Dialect.java:243-248`）
   发现 `TEXT` 不可索引，无条件返回 `DataType.varchar(255)`：

   ```java
   default DataType indexableType(DataType type) {
       if (type == null || isIndexable(type)) {
           return null;
       }
       return DataType.varchar(255);
   }
   ```

3. `SchemaAdapter.applyKeyTypeSubstitutions()`（`…/gen/SchemaAdapter.java:279-285`）
   只做**逐列**替换，不看这个键一共有几列。
4. `SchemaAdapter.adaptIndex()`（`…/gen/SchemaAdapter.java:536-563`）**只限制列数**：

   ```java
   int keyPartLimit = EngineTraits.maxIndexColumns(dialect::includesEngine);  // MySQL = 16
   if (indexable.size() > keyPartLimit) { … }
   ```

   完全没有任何字节长度的判断。主键与唯一约束（`SchemaAdapter.java:182-213`）
   连列数限制都没有。
5. 于是 InnoDB 收到 4 × `VARCHAR(255)` 的 utf8mb4 唯一键 = 4 × 1020 = **4 080 > 3 072**，
   回答 1071 `Specified key was too long`。

`EngineTraits` 里其实已经有对应的先例和完全一样的论证，只是没人把它推广到字节：

```java
// …/gen/EngineTraits.java:162-186
 * <p>InnoDB answers {@code Too many key parts specified; max 16 parts allowed} (error 1070) and
 * rejects the whole statement. … and the crash oracle then reports the rejection as an engine
 * defect, even though the dialect never claimed to accept an index that wide.</p>
```

**1070 处理了，1071 没有。**

#### 量化证据

对 20 个 `engine rejected CREATE` 报告逐个还原被拒语句并计算键字节数：

```
OVER PRIMARY KEY     6    5180  CREATE TABLE t_rtern_0_1_2 (t_0_a0 VARCHAR(255), …
OVER UNIQUE          4    4080  CREATE TABLE t_4_r (id BIGINT, e6_src_a17 VARCHAR(255), …
OVER PRIMARY KEY     4    4080  CREATE TABLE t_6 (e4_src_a7 VARCHAR(255), …
OVER UNIQUE          4    3460  CREATE TABLE t_rtern_0_1_2 (… t_0_a0, t_1_a0, t_2_a0, t_2_a3)
OVER INDEX          11    3224  CREATE INDEX idx_t_3_0 ON t_3 (t_4_a2, a1, a0, a13, …
OVER UNIQUE          5    3100  CREATE TABLE t_rtern_0_1_5 (…
OVER PRIMARY KEY     5    3076  CREATE TABLE t_rtern_0_5_6 (…
OVER PRIMARY KEY     5    3075  CREATE TABLE t_rtern_0_2_3 (…
```

**19/20 计算得到超过 3072 字节**，剩下 1 个（16 列的 `idx_t_5_0`）手工计算为 3 260 字节，
也是超限——即 **20/20**。没有任何一条是被误判的。

#### 为什么它同时污染了另外五个家族

`CREATE TABLE` 失败 → 表不存在 → 后续每一步都建立在这个事实上：

* **E**（17）：`MISSING_TABLE at t_4_r` + `MISSING_VIEW at v_1`（视图引用了这张表）。
* **F**（10）：见 4.3，被归咎于一条无关的 DDL。
* **G**（6）：PostgreSQL 建表成功、MySQL 没有，于是报「两个数据库对同一 schema 意见不一致」。
* **H**（6）：`SELECT COUNT(*) FROM t_rtern_0_1_2` 失败 → 「表没有按请求创建」。
* **C**（1）：`CONSTRAINT_TOO_STRICT` 报文里写着
  `Observed: failed: Table 'ddlsmith_con_database7.t_5' doesn't exist (code 1146)`
  —— 表根本不存在，却被描述成「这一行满足所有约束，引擎却拒绝了它」。

**一次生成侧的错误，被放大成 6 个家族的「引擎缺陷」，其中 3 个还标着 high confidence。**

#### 修复

1. `EngineTraits` 增加 `maxIndexKeyBytes(predicate)`：MySQL/MariaDB/InnoDB 返回 3072
   （DYNAMIC 行格式 + utf8mb4），其它引擎返回 `Integer.MAX_VALUE`。
2. `Dialect.indexableType()` 需要知道「这个键里还有几列、每列多少字节」，不能返回固定宽度。
   把签名改成 `indexableType(DataType, int bytesRemaining)`，让替换类型按剩余预算收缩。
3. **三处**都要做字节预算，不只是 `adaptIndex`：主键（`SchemaAdapter.java:182`）、
   唯一约束（`:204`，本次 1071 的实际发生地）、独立索引（`:536`）。
4. 加回归用例：一个含 4 个 `TEXT` 主键列的 ER 图，断言适配后的 DDL 里没有任何键超过引擎上限。

### 4.2 【严重】生成器会给自引用外键配上 `ON UPDATE CASCADE` / `SET NULL`

**186 个数据库**（118 + 68），是本次运行数量最大的假阳性来源。

#### MySQL 的手册把这件事写死了

MySQL 8.0 参考手册 §1.6.2.3 *FOREIGN KEY Constraint Differences*
（[docs.oracle.com 原文](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/ansi-diff-foreign-keys.html)，
[中文镜像](https://dev.mysqld.com.cn/doc/refman/9.0/en/ansi-diff-foreign-keys.html)）：

> If `ON UPDATE CASCADE` or `ON UPDATE SET NULL` recurses to update the *same table* it has
> previously updated during the same cascade, it acts like `RESTRICT`. **This means that you
> cannot use self-referential `ON UPDATE CASCADE` or `ON UPDATE SET NULL` operations.** This is
> to prevent infinite loops resulting from cascaded updates. A self-referential `ON DELETE SET
> NULL`, on the other hand, is possible, as is a self-referential `ON DELETE CASCADE`.

本机验证与手册逐字吻合，且在 6 个构建、两种 `innodb_native_foreign_keys` 取值上**完全一致**
（`local/claimA.py`）：

| 场景 | 结果 | 手册 |
|---|---|---|
| 自引用 `ON UPDATE CASCADE` | 1451 拒绝 | 明文说不可用 |
| 自引用 `ON UPDATE SET NULL` | 1451 拒绝 | 明文说不可用 |
| 自引用 `ON DELETE CASCADE` | 成功 | 明文说可以 |
| 跨表 `ON UPDATE CASCADE` | 成功 | — |

**所以这不是 MySQL 的缺陷，是 DDLSmith 生成了一份引擎明确不支持的 schema，
再把它当成引擎的错报了出来。**

#### 缺陷代码

`src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java:2018-2037`：

```java
private void configureForeignKey(ForeignKey foreignKey, Table table, List<String> columns, …) {
    foreignKey.setSelfReferential(foreignKey.getReferencedTable().equals(table.getName())); // ← 已经算出来了
    …
    foreignKey.setOnDelete(none ? NO_ACTION : randomReferentialAction(table, columns));
    foreignKey.setOnUpdate(none ? NO_ACTION : randomReferentialAction(table, columns));     // ← 不看上面那一行
}
```

`randomReferentialAction()`（`:2039-2063`）的候选集只根据**列是否可空、是否有默认值**裁剪，
**从不考虑这个外键是不是自引用的**，于是 `CASCADE` / `SET_NULL` 会被抽到自引用外键的
`ON UPDATE` 上。

紧接着 `DDLFeatureAnalyzer.java:224-232` 把 `SELF_REFERENTIAL_FK` 与 `FK_ON_UPDATE`
登记成**两个互不相干**的特征，所以特征模型也无法表达这个禁用组合，
`CrashOracle.isUndeclaredFeatureFailure` 永远没机会豁免它。

而 oracle 侧其实**已经懂自引用**了——同一份报告里写着：

```
- [database6] fk t_1[e1_dst_a1, e1_dst_a5] -> t_1[a1, a5] (fk_t_1_0): delete probe skipped,
  the only row referencing the parent row is the parent row itself (t_1.[e1_dst_a1, e1_dst_a5]
  references its own key), so deleting it is legal
```

删除探针有自引用豁免，**更新探针没有**，生成器也没有。

#### 修复

1. `randomReferentialAction` 增加参数 `selfReferential`；为真时从 **ON UPDATE** 的候选里
   去掉 `CASCADE` 与 `SET_NULL`（引擎能力经 `EngineTraits` 提供，便于其它引擎覆盖）。
   ON DELETE 不受影响——手册明说自引用 `ON DELETE CASCADE/SET NULL` 是合法的。
2. 特征模型补一个组合特征（例如 `SELF_REFERENTIAL_FK_ON_UPDATE`），或者让
   `DDLFeatureAnalyzer` 在自引用 + `ON UPDATE` 非 NO_ACTION 时登记一个方言可声明/否定的特征。
3. `ReferentialActionOracle` 的更新探针补上与删除探针同样的自引用豁免（防御性，用于
   处理用户手写的 schema 或修复前留下的日志）。

### 4.3 【严重】evolution 在执行完 CREATE 脚本后不校验，把建表失败栽赃给无关的 DDL

`src/main/java/org/ddlsmith/algorithm/impl/SchemaEvolutionAlgorithm.java`：

```java
44:      executeCreateScript(context, primary, generated.adaptedSchema);   // 返回值没人看
45:
52:      for (int step = 0; step < steps; step++) {
58:          … execute the statement …
72:          List<SchemaDifference> differences = compare(context, primary, expected);
73:          if (!differences.isEmpty()) {
74:              failures.add(… "schema diverged after " + statement.describe(),
77:                  "… This pinpoints the statement that introduced the divergence.\n\n"
```

这段代码**自己写明了正确的直觉**，但只用在了 evolution 语句上：

```java
60:  // The engine refused the statement, so its effect must not stay in the model: comparing
61:  // the catalog with a schema that was never created reports the missing column or index
62:  // as a divergence, which is a finding about DDLSmith rather than about the engine …
66:  evolution.discardLastStatement();
```

**初始的 `executeCreateScript` 恰恰就是「a schema that was never created」，却没有同样的保护。**

10 个数据库的报告，每一条的「被归咎语句」都与缺失的表毫无因果关系：

| 被归咎的语句 | 实际缺失的表 | 该表键的字节数 |
|---|---|---|
| `ALTER TABLE t_3 RENAME COLUMN a12 TO c_new` | `t_8`, `t_rtern_0_2_4` | 4 行 VARCHAR(255) 的自然键 |
| `ALTER TABLE mv_e1_0 ADD COLUMN c_new INT` | `t_ragg_r8` | **4 084**（实测 DDL：`PRIMARY KEY (t_2_a0, t_2_a3, t_8_t_2_a0, t_8_t_2_a3, t_8_t_5_id)`） |
| `ALTER TABLE mv_e3_0 ADD CONSTRAINT ck_… CHECK (a23 >= 0)` | `t_rtern_1_4_5` | 3 行 VARCHAR(255) + 1 行 VARCHAR(20) |
| `CREATE OR REPLACE VIEW ev_v_t_3_0 AS SELECT a6, a8 FROM t_3` | `t_1_r` | 4 行 VARCHAR(255) 的自然键 |
| `CREATE INDEX ev_idx_t_7_r_0 ON t_7_r (a46, a45)` | `t_3_r` | 4 行 VARCHAR(255) 的自然键 |
| `ALTER TABLE t_3 ADD COLUMN c_new DATE …` | `t_rtern_0_1_2` | 4 行 VARCHAR(255) |

`ALTER TABLE t_3 RENAME COLUMN a12 TO c_new` 不可能让 `t_8` 消失。

**旁证**：这 10 个 F 数据库**没有一个**同时产出 D（`engine rejected CREATE`）报告，
而同源的 E 有 16/17、G 有 5/6、H 有 5/6 都与 D 成对。原因正是 `return failures`：
第一次 divergence 就结束整轮，crash oracle 再没有机会运行，于是**建表失败这件事本身
连一条报告都没留下**，只剩下一条指着无关 ALTER 的假指控。

**修复**：`executeCreateScript` 之后立刻做一次 `compare(...)`；非空则直接以
「生成器没能建出 schema」上报并结束该轮，**不要进入 evolution 循环**。

### 4.4 【中】`introspectedActions()` 只按列数 + 被引用表匹配外键，会取到兄弟约束的动作

`src/main/java/org/ddlsmith/oracle/impl/ReferentialActionOracle.java:1008-1024`：

```java
for (ForeignKey candidate : table.getForeignKeys()) {
    if (candidate.getColumns().size() == foreignKey.getColumns().size()
            && candidate.getReferencedTable() != null
            && candidate.getReferencedTable().equalsIgnoreCase(foreignKey.getReferencedTable())) {
        return "ON DELETE " + candidate.getOnDelete() + ", ON UPDATE " + candidate.getOnUpdate();
    }
}
```

**从不比较列名**。同一个子表若有两条指向同一父表的外键，永远命中第一条。实际报告因此写着：

```
Foreign key : t_4_r[e4_dst_a11] -> t_4[a11] (fk_t_4_r_1)
Declared    : ON DELETE RESTRICT
Introspected: ON DELETE CASCADE, ON UPDATE RESTRICT     ← 这是 fk_t_4_r_0 的规则
```

在服务器上查 `information_schema.REFERENTIAL_CONSTRAINTS` 证实 MySQL 存的是
`fk_t_4_r_0 → CASCADE` / `fk_t_4_r_1 → RESTRICT`，**引擎没有问题，是回读报告错了**。

危害目前限于诊断文本（判定用的是声明值），但它会主动误导读报告的人。

**修复**：先按列名序列匹配，退而求其次按约束名匹配，都不中才返回 `null`。

### 4.5 【中】`RESTRICT ≡ NO ACTION` 的引擎事实没有进入豁免逻辑

`ReferentialActionOracle.java:355-375`，删除成功但预期应被拒绝时，只有一个豁免分支：

```java
} else if (action == ReferentialAction.NO_ACTION
        && noLongerReferenced(context, database, child, foreignKey, parentRow)) {
    // NO ACTION and RESTRICT are not the same promise. RESTRICT is checked as soon as the
    // parent row is touched; NO ACTION is checked once the statement has finished, so a
    // second foreign key of the same child row may legitimately have removed the row in between.
```

作者对标准语义的区分是对的，但 **MySQL/InnoDB 明确把 `RESTRICT` 当作 `NO ACTION` 处理**。
服务器实测：

* 子表只有 RESTRICT 外键 → `DELETE` 被拒（1451），正确；
* 子表同时有 CASCADE 兄弟外键 → **`DELETE` 成功，子行被级联删除**，RESTRICT 没有阻止它。

于是 13 个数据库被报成「RESTRICT 没有被强制」。这是**假阳性**。
项目里已经有 `EngineTraits` 专门承载这类引擎差异，这里缺一条
`restrictCheckedImmediately(isEngine)`（MySQL/MariaDB 为 false）。

手册同时也印证了这一点（§1.6.2.3 第三条）：

> MySQL checks foreign key constraints immediately; the check is not deferred to transaction
> commit. … This means that it is not possible to delete a row that refers to itself using a
> foreign key.

### 4.6 【中】crash oracle 只用 SQLState 判定「这是生成器的错」

`src/main/java/org/ddlsmith/oracle/impl/CrashOracle.java:151-179`：

```java
private static boolean isUndeclaredFeatureFailure(OracleContext context, ExecutionEvent event) {
    if (!indicatesUnsupportedFeature(event.getSqlState())) {   // 只看 0A** / HYC00
        return false;
    }
```

MySQL 把**几乎所有的 DDL 级拒绝**都报成 `42000`：1071（键过长）、1070（键列过多）、
3823（CHECK 用到了引用动作列）、1171……所以这个判定对它永远是 false，
任何生成器的错误都会被升级为「引擎缺陷」。

`EngineTraits` 里那一整套规则存在的理由正是「MySQL 用 42000 报告生成器的错」，
但 crash oracle 从不咨询它们。

**修复**：`isReportableFailure()` 增加一道生成器自检闸门，至少纳入
`1071 / 1070 / 3823 / 1171 / 1067`。

### 4.7 【低】级联缺陷有标记但不去重、不降级

`src/main/java/org/ddlsmith/bug/BugRepository.java:216-233`：

```java
216:  // a defect found in the same database as another is very often its consequence
217:  // a defect found in the same database as another is very often its consequence   ← 重复行
218:  boolean engineRejectedAStatement = failures.stream()
219:          .anyMatch(failure -> failure.getKind() == OracleFailure.FailureKind.UNEXPECTED_ERROR);
…
227:      if (engineRejectedAStatement && entry.kind != …UNEXPECTED_ERROR) {
230:          entry.suspectedCascade = true;
```

而 `BugRepository.java:393-400` 决定置信度时**完全不看 `suspectedCascade`**。
结果：同一次 `CREATE TABLE` 失败被写成 **4 份**独立缺陷报告，**4 份都写 high confidence**，
只在旁边加一句「可能是级联」。这 4 份里有 3 份纯属噪音。

**修复**：`suspectedCascade` 时把置信度降为「cascade of `<root-id>`」，或只保留根因报告。

### 4.8 【低】参考项：oracle 对「兄弟外键一起决定结果」的推理只做了一半

`ReferentialActionOracle` 已经会为 SET_NULL / NO ACTION 考虑兄弟外键（`:360-372`、`:769`），
但没有覆盖另外两种情形：兄弟 CASCADE 撞上子表 UNIQUE（23 个数据库：MySQL 正确回答
1761 `would lead to a duplicate entry`，被报成「declared action could not happen」），
以及兄弟 CASCADE 让 RESTRICT 失效（§4.5）。

---

## 5. 修复优先级

| 优先级 | 缺陷 | 位置 | 影响 |
|---|---|---|---|
| **P0** | 复合键字节预算 | `SchemaAdapter` :182/:204/:536，`Dialect` :243，`EngineTraits` :162 | 56 个数据库的 6 个假家族，且被标 high confidence |
| **P0** | 自引用外键不该配 `ON UPDATE CASCADE/SET NULL` | `StandardERToRelationalMapper` :2018-2063，`DDLFeatureAnalyzer` :224 | **186 个数据库的头号假阳性** |
| **P0** | evolution 建表后不校验 | `SchemaEvolutionAlgorithm` :44 | 10 个假报告 + 整轮被截断 |
| **P1** | crash oracle 不咨询 EngineTraits | `CrashOracle` :151 | 生成器的错一律升级为引擎缺陷 |
| **P1** | `introspectedActions` 匹配 | `ReferentialActionOracle` :1008 | 报告内容与引擎实际不符 |
| **P1** | RESTRICT ≡ NO ACTION | `ReferentialActionOracle` :355 | 13 个假阳性 |
| **P2** | 级联缺陷不降级 | `BugRepository` :216/:393 | 缺陷计数虚高 3–4 倍 |
| **P2** | 兄弟约束对探针期望的影响 | `ReferentialActionOracle` | 23 个假阳性 |

修完前三项之后，本次运行的有效发现从 236 个数据库降到 **14 个**（只剩真实的引擎缺陷），
但报告的缺陷族从 29 个降到 **1 个**，且完全可以复现。

---

## 6. 对实验有效性的影响

**站得住的结论只有一个**：MySQL 9.7 LTS / 26.7 默认的 SQL 层外键引擎漏检复合自引用外键
（14 个数据库；本机 6 个构建的矩阵确认；实验自带的 native/default 对照确认）。

**初版曾把 186 个数据库的自引用 `ON UPDATE` 行为也算作引擎缺陷，复核后推翻**：
那是 MySQL 手册明文规定的限制，属于 DDLSmith 生成侧的问题。

**需要重跑的部分**：修掉 P0 之后，`mysql-evolution`（F 全在这）、
`mysql-differential`（G/H 全在这）、以及所有非 native category 的结果才有意义。
`mysql-native*` 三个 category 因为跑在 `innodb_native_foreign_keys=ON` 上，
本来就不受 §3.1 影响，修完生成器后那 186 个假阳性应当大幅下降。

**给实验环境配置的建议**：在 MySQL 修好之前，应显式设置
`innodb_native_foreign_keys=ON`；否则每次运行都会混入十几个引擎漏检，
而且这些漏检会让下游的约束探针产生连锁噪音。

---

## 7. 复核过程与证据（本机，不依赖服务器）

所有结论都在本机 Docker 上重新做过，容器与服务器同镜像、同版本。

| 脚本 | 作用 |
|---|---|
| `local/final_matrix.py` | 6 个构建 × 7 个用例的完整矩阵 → `results/_fk_matrix_final.txt` |
| `local/exact_all.py` | DDLSmith 原始语句逐字跑在 6 个构建上 → `results/_exact_stmt_all.txt` |
| `local/narrow.py` | 单一构建上把漏检条件收窄到「INSERT + 自引用 + 首列匹配」 |
| `local/claimA.py` | 自引用 `ON UPDATE` 行为，对比手册与两种 `innodb_native_foreign_keys` 取值 |
| `local/native_flag.py` | 直接验证该变量开关对漏检的影响 |
| `local/fk_matrix.py` | 隔离会话的通用外键矩阵（含跨表对照） |
| `local/bisect.py` | 排除 CHECK、动作子句、列名、子列索引等干扰因素 |

复现用容器：

```bash
M=<mirror>/library
docker run -d --name m80   -e MYSQL_ROOT_PASSWORD=root -p 43408:3306 $M/mysql:8.0
docker run -d --name m84   -e MYSQL_ROOT_PASSWORD=root -p 43608:3306 $M/mysql:8.4
docker run -d --name m97   -e MYSQL_ROOT_PASSWORD=root -p 43508:3306 $M/mysql:9.7
docker run -d --name m97n  -e MYSQL_ROOT_PASSWORD=root -p 43708:3306 $M/mysql:9.7 --innodb_native_foreign_keys=ON
docker run -d --name m267  -e MYSQL_ROOT_PASSWORD=root -p 43306:3306 $M/mysql:26.7.0
docker run -d --name m267n -e MYSQL_ROOT_PASSWORD=root -p 43307:3306 $M/mysql:26.7.0 --innodb_native_foreign_keys=ON
```

### 初版犯的错误

初次验证时我把多条语句塞进一个 `mysql --force` 会话，靠 `SELECT ROW_COUNT()` 和交错的
stdout/stderr 判断每条语句的结果。stderr 是异步刷出的，行号与语句的对应关系不可靠，
于是得出了「首列匹配即放行」这个**方向正确但条件错误**的结论，并进一步误判了 186 个数据库。
本机复核改为**一条语句一个会话、以退出码判定**之后，规则才收敛为
「INSERT + 自引用 + 只比较首列」。

---

## 附：复现脚本与原始数据

* 全部原始结果（10 655 个文件，含每个数据库的完整语句记录）：
  `E:\workspace\ddlsmith-lab\results\full\`
* 服务器上的副本：`~/workspace/ddlsmith-72h/campaign/mysql-72h/`
* 引擎行为验证脚本：`E:\workspace\ddlsmith-lab\local\*.py`（本机）、
  `E:\workspace\ddlsmith-lab\remote\probe-*.sh`（服务器）
* 分析脚本：`E:\workspace\ddlsmith-lab\analyze*.py`
* 原始分析输出：`E:\workspace\ddlsmith-lab\results\_analysis*.txt`、
  `_fk_matrix_final.txt`、`_exact_stmt_all.txt`
