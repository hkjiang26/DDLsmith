# DDLSmith MySQL 实机验收报告

本报告记录 DDLSmith 对 **MySQL 的第一次可信大规模实测**。与
[ACCEPTANCE-2.md](ACCEPTANCE-2.md) §8.1 的区别在于：那里只有一次 20 库的普查，结论是「剩下的全是
归一化尾巴，尚未修完」；本报告把那根尾巴**量化、定位、修完并同种子复验**，然后才开规模。

* 被测对象：`target/DDLSmith.jar`（Java 17，Maven 构建）
* 引擎：MySQL **26.7.0**（容器 `ddlsmith-mysql`，宿主 33306）、PostgreSQL **18.6**（容器
  `ddlsmith-pg`，宿主 15432，差分类别用）
* 环境：Windows，JDK 17，MySQL `max_connections=151`
* 编排：`runs/mysql-campaign/`（配置、回收、普查、线程标定、一轮编排、客户端/服务器安全回放器）
* 原始数据：`runs/mysql-campaign/{baseline,census1,verify1,verify2,mysql1,sweep1}/`

---

## 1. 结论摘要

| 阶段 | 规模 | 失败 | 判定 |
| ---- | ---- | ---- | ---- |
| `baseline`（修复前，50 库样本） | 50 库 | 58 | 全部工具缺陷 |
| `census1`（修复前普查） | 923 库 | **1 057** | 11 类缺陷 / 1 499 缺陷单元，**没有一条是引擎缺陷** |
| `verify1`（修复后，同种子） | 886 库 | **0** | 与 `census1` 相同的种子区间 |
| `verify2`（修复后，新种子） | 1 296 库 | **0** | 种子 2300000 / 2400000 / 2500000 |
| `verify4`（交付构建，默认 oracle） | 700 库 | **0** | 种子 2100000 起 7 块，前两块与 `census1` 同种子 |
| `mysql3`（六类并行规模化） | 862 库 + 905 次渲染 | 7（4 类） | 见 §8.8：两个工具缺陷当场定性并修复，两个是实验性 oracle 的误报 |

**关键数字：同一批种子上 1 057 条失败变成 0 条。** 11 类缺陷归并为 **4 个根因**，全部是 DDLSmith
自身的缺陷——这正是本工具最危险的失效模式（把工具的错误报成引擎的缺陷），因此第一步的全部工作就是把
它们消除，然后才允许开规模。

**开规模之后又暴露出 12 个缺陷**，同样是工具缺陷（两个编排层的命名空间缺陷、一类按引擎名分派的特性
失效、InnoDB 的三处实现细节、以及五类"生成器发出了引擎会拒绝的语句"）。它们全部只在规模化或多类别
并行时才出现——单类别 2 882 库的跑法一直是绿的。逐条见 §8.3–§8.8。

顺带修掉三处让尾巴得以长期隐形的**自检盲区**（MySQL / PostgreSQL 两个实机自检此前**从未绿过**），
并新增两组**直接向服务器提问**的规则探针。

---

## 2. 为什么不能直接开规模

规模化的代价是线性的，误报的代价不是。修复前实测吞吐 ≈ 3.4 库/秒（4 线程），一个 30 分钟分块约
6 000 库、约 6 000 条失败日志；而 `census1` 证明这些几乎**全部**是工具缺陷。更糟的是缺陷归档路径：

`run-category.ps1` 给每块用 `--bug-dir bugs-chunk-NNN`，`BugRepository` 会把同一形态的失败聚合成
一份 `confidence: high — reported by a verified oracle` 的报告。跑 6 000 个库，就会在 `bugs/` 里得到
一份高置信度、看起来无懈可击、但**完全错误**的 MySQL 缺陷报告。`baseline` 阶段已经踩过一次：
`health.ps1` 的一次单库探测就归档了 `mysql-001`，内容正是缺陷 2。

所以顺序是：**先清尾巴，再规模化**。

---

## 3. 普查：11 类缺陷，4 个根因

`census.ps1` 把 753 条失败日志聚合成 **1 499 个缺陷单元、11 类缺陷**（明细
`census1/census/census.md` 与 `census.tsv`）：

| # | 次数 | 类型 | 形态 | 根因 |
| - | ---- | ---- | ---- | ---- |
| 1 | 480 | `SCHEMA_MISMATCH` | `MISSING_CHECK_CONSTRAINT` | 表达式归一化（根因 A） |
| 2 | 460 | `SCHEMA_MISMATCH` | `MISSING_INDEX` | 唯一索引不可见（根因 B） |
| 3 | 229 | `SCHEMA_MISMATCH` | `VIEW_DEFINITION` | 视图定义归一化（根因 C） |
| 4 | 146 | `UNEXPECTED_ERROR` | `engine-3823` | 引用动作规则（根因 D） |
| 5 | 144 | `SCHEMA_MISMATCH` | `MISSING_TABLE` | D 的级联 |
| 6 | 25 | `SCHEMA_MISMATCH` | `MISSING_VIEW` | D 的级联 |
| 7 | 7 | `SCHEMA_MISMATCH` | `MISSING_FOREIGN_KEY` | D 的级联 |
| 8 | 3 | `UNEXPECTED_ERROR` | `engine-1070` | InnoDB 16 列索引上限（根因 D'） |
| 9 | 2 | `SCHEMA_MISMATCH` | `FOREIGN_KEY_COLUMNS` | D 的级联 |
| 10 | 2 | `SCHEMA_MISMATCH` | `FOREIGN_KEY_ON_UPDATE` | D 的级联 |
| 11 | 1 | `SCHEMA_MISMATCH` | `FOREIGN_KEY_ON_DELETE` | D 的级联 |

即 **480 + 460 + 229 + 330 = 1 499**。其中 327 个缺陷单元（第 4–7、9–11 项）由**同一个根因**产生。

> 值得一提：最初 50 库的样本只看到 5 类，第 8–11 项完全没出现。这正是「先跑 1 000 库普查再动手」
> 的价值——按 50 库样本开修，会漏掉 `ON UPDATE CASCADE` 这条真正的大头。

---

## 4. 根因与修复

### 根因 A · MySQL 回读的 CHECK 子句里，引号是**反斜杠转义**的（480 条）

`information_schema.check_constraints.check_clause` 对一个声明为 `status <> 'none'` 的 CHECK 返回的
字节是：

```
28 60 73 74 61 74 75 73 60 20 3C 3E 20 5F 75 74 66 38 6D 62 34 5C 27 6E 6F 6E 65 5C 27 29
(  `  s  t  a  t  u  s  `     <  >     _  u  t  f  8  m  b  4  \  '  n  o  n  e  \  '  )
```

`5C 27` 是**一个真的反斜杠加一个真的引号**，不是日志转义。于是：

* 字符集引导符 `_utf8mb4` 后面跟的不是引号，`canonicalExpression` 里针对引导符的规则**永远匹配不上**；
* 负数字面量被写成 `-(92)`（MySQL 用括号而不是引号）。

修复（`introspection/SchemaNormalizer.canonicalExpression`）：

1. 先解除 MySQL 的转义（`\\'` → `'`，限定在引号与反斜杠，避免误伤其它转义序列）；
2. 去掉字符集引导符 `_utf8mb4'x'` → `'x'`；
3. 展开括号化负数字面量 `-(92)` → `-92`；
4. 十进制字面量尾零归一（`0` vs `0.00`）——顺带修掉 PostgreSQL 的 `amount >= (0)`。

前三条由 `runs/tmp-probe/Canon.java` 逐条验证过输入输出。

### 根因 B · 唯一索引对回环 oracle 完全不可见（460 条）

MySQL 把 `CREATE UNIQUE INDEX uidx ON t (a)` **同时**记成：

* `information_schema.statistics` 里的唯一索引 `uidx`，
* `information_schema.table_constraints` 里 `constraint_type='UNIQUE'`、名字同为 `uidx` 的一行。

`MySqlIntrospector` 先读约束、再读索引，然后用 `SchemaNormalizer.isConstraintBackedIndex` 把
「列集合与某个 UNIQUE 约束相同」的唯一索引丢掉——于是这个索引从内省结果里消失。而 DDLSmith 的
期望模型里它**是**索引（不是约束），`compareIndexes` 便报 `MISSING_INDEX`。

**目录里根本无法区分这两者**：`SHOW CREATE TABLE` 对二者都渲染成 `UNIQUE KEY`。所以修复只能落在
比较层，`SchemaNormalizer.compareIndexes`：

> 同列集合的 UNIQUE 约束，视为满足该唯一索引。

这是 `compareUniqueConstraints` 早已实现的反方向等价（「唯一索引也强制唯一性，因此接受它作为唯一约束
的等价物」）的镜像，本轮只是把对称性补全。修好后自检里 `unique=1`（期望）对 `unique=2`（实际）
仍然不等，但 `diff` 为空——正是这一处归一化在起作用。

### 根因 C · 视图定义的三处改写（229 条）

MySQL 把 DDLSmith 的 `select a.t_0_id, b.id from t_1 a join t_0 b on ...` 回读成：

```sql
select a.t_0_id as t_0_id,b.id as id
from (ddlsmith_database0.t_1 a join ddlsmith_database0.t_0 b on((a.t_0_id = b.id)))
```

三处需要归一（`SchemaNormalizer.canonicalQuery`）：

1. **同名自动别名**：MySQL 给每列都加 `as <列名>`，期望侧没有。折叠必须在丢掉 `AS` 关键字**之前**
   做，否则识别不出冗余；用词边界保证 `b.id as id_1`（真别名）不被误折叠。
2. **括号包裹的限定 join**：`from (\`db\`.\`t\` a ...)`。原有的库限定符剥离规则要求限定符紧跟
   `from`/`join`，而括号先出现在那里；改成容忍一个前导括号（括号本来在最后会被整体删除，顺序错了
   就永远修不掉）。
3. **三段式列引用**：视图没有表别名时，MySQL 把列写成 `ddlsmith_selfcheck.parent.id`。只折叠三段式
   ——两段式是别名限定列，折叠会丢掉 join 两侧的区分。
4. 附带：`a, b` 与 `a,b` 的逗号空格差异。

### 根因 D · `ON UPDATE CASCADE` 漏在引用动作规则之外（327 条）

这是最大的一条，而且是**用裸 SQL 在服务器上一条条问出来的**，不是从错误信息猜的。在同一个列上放一个
`CHECK (c <> 1)` 和一个外键，逐个换 `ON DELETE` / `ON UPDATE` 子句：

| 外键子句 | 结果 |
| -------- | ---- |
| `ON UPDATE CASCADE` | **ERROR 3823** |
| `ON UPDATE SET NULL` | **ERROR 3823** |
| `ON UPDATE SET DEFAULT` | **ERROR 3823** |
| `ON DELETE SET NULL` | **ERROR 3823** |
| `ON DELETE SET DEFAULT` | **ERROR 3823** |
| `ON DELETE CASCADE` | 接受 |
| `ON DELETE RESTRICT` | 接受 |
| `ON UPDATE RESTRICT` | 接受 |
| （无动作子句） | 接受 |

规则是：**只有会「写」子表列的动作才与 CHECK 冲突**——`CASCADE` 只删行不改列，所以 `ON DELETE
CASCADE` 是安全的；而 `ON UPDATE CASCADE` 会改列，所以是冲突的。

`SchemaAdapter` 里原有的判定集是「`ON DELETE` ∈ {SET NULL, SET DEFAULT} 或 `ON UPDATE` ∈
{SET NULL, SET DEFAULT}」——**恰好漏了 `ON UPDATE CASCADE`**。一个漏项解释了：

* 146 条 `engine-3823` 崩溃；
* 144 条 `MISSING_TABLE`、25 条 `MISSING_VIEW`、7 条 `MISSING_FOREIGN_KEY`：`CREATE TABLE` 整张失败，
  引用它的表跟着失败，外键与规则差异全是级联；
* 5 条外键列/规则差异（`FOREIGN_KEY_COLUMNS` / `_ON_UPDATE` / `_ON_DELETE`）：同表两条外键指向同一
  父表，比较按「被引用表」配对，而真正失败的那条根本不在目录里。

修复：

* `SchemaAdapter.writesThroughReferentialAction` 补上 `ON UPDATE CASCADE`，并把规则收敛到**这一处**；
  `mentionsReferentialActionColumn` 与新增的 `isReferentialActionTarget` 都调用它，因此适配器建的
  CHECK 与生成器后追加的 CHECK 用的是**同一个判定**（ACCEPTANCE-2 §8.1 第 22 项点明的正是这条守卫
  只覆盖了适配器那一侧）；
* `RandomDDLGenerator.addCheckConstraint` 用 `isReferentialActionTarget` 过滤候选列。

### 根因 D' · InnoDB 单索引 16 列上限（3 条）

`CREATE INDEX idx_t_3_0 ON t_3 (a20, t_1_id, ..., e0_dst_id_2)` —— 18 列，MySQL 回
`ERROR 1070: Too many key parts specified; max 16 parts allowed`。ER 映射器会为表建「随机列子集的索引」，
宽表因此产生引擎不接受宽度的索引。修复：

* 新增 `EngineTraits.maxIndexColumns`（mysql/mariadb = 16，其余无上限）；
* `SchemaAdapter.adaptIndex` 在渲染前截断并记 note——适配器本来就是索引「让路」的地方（它已经在丢弃
  引擎无法索引的列）。

---

## 5. `--reproduce` 的命名空间（工具缺陷，回放器会给出相反判决）

`Reproducer` 原本只执行无参 `getSessionSetupStatements()`，**不准备命名空间**。在客户端/服务器引擎上：
第二次回放同一条日志时，第一次留下的表还在，于是第一条语句就报 `table already exists`，而
`Reproducer` 把它报成 `reproduced after 0 successful statement(s)` 并 **exit 0**——回放脚本把 exit 0
读作「忠实复现」，判决与事实相反。

修复：`Reproducer` 像 `AbstractJdbcProvider.createDatabase` 一样，用**同一个 dialect 方法、同一个库名**
推导命名空间，并 drop → create → session setup。内嵌引擎的两个钩子列表都是空的，所以对它们这条路径
逐字不变（`Dialect.getSessionSetupStatements(schemaName)` 默认就转发到无参版本）。

**验证**：同一条日志连放两次，两次输出逐字相同（都在第 3 条语句失败、都报
`reproduced after 2 successful statement(s)`、exit 0）：

```
===== replay #1 =====   reproduced after 2 successful statement(s)   EXIT=0
===== replay #2 =====   reproduced after 2 successful statement(s)   EXIT=0
```

（这条日志记录的是**修复前**构建发出的语句，所以它仍然在 3823 处失败——`--reproduce` 逐字回放记录的
SQL，这正是它该做的事。修复后的生成器不再产生这条语句。）

---

## 6. 自检盲区：两个实机自检此前从未绿过

尾巴能长期存在，直接原因是**没有任何自检看得见它**。

### 6.1 MySQL 实机自检（`src/test/java/org/ddlsmith/dbms/mysql/SelfCheck.java`）

修复前它其实是**红的**，而且不止一处：

| 问题 | 说明 |
| ---- | ---- |
| 特性数断言停在 75 | `CREATE_INDEX_IF_NOT_EXISTS` / `DROP_INDEX_IF_EXISTS` 早已按实测（`ERROR 1064`）从特性集删除，断言没跟着改 |
| **schema 里一个唯一索引都没有** | 所以 460 条 `MISSING_INDEX` 永远隐形 |
| 没有含字面量的 CHECK | 所以 480 条 `MISSING_CHECK_CONSTRAINT` 永远隐形 |
| 没有超过 16 列的索引 | 所以 `engine-1070` 永远隐形 |
| `STORED` 生成列基于外键列 | 踩中 MySQL 1215，实机段根本走不到回环比较 |
| `FK_TARGET_MUST_EXIST` 未声明 | 与实机行为矛盾（MySQL 确实要求目标表存在） |

现在 `buildLiveSchema` 覆盖：**唯一索引**、含**字符串字面量**的 CHECK、含**负数字面量**的 CHECK、
**18 列索引**（由适配器截到 16）、基于非外键列的 `STORED` 生成列；并且整个 schema 走**与工具相同的
适配路径**（`SchemaAdapter.adapt`），而不是直接渲染模型。结果：

```
390 assertions, 0 failure(s)
MYSQL SELF-CHECK OK
```

### 6.2 PostgreSQL 实机自检

同类问题，独立发现：`FK_TARGET_MUST_EXIST` 未声明（实机第一句就是 `relation "t_1" does not exist`），
且实机段从不设置 `search_path`，因此第 4 句
（`... REFERENCES parent (id)`，未加限定）必然失败。修复后：

```
330 assertions, 0 failure(s)
POSTGRES SELF-CHECK OK
```

### 6.3 新增：直接向服务器提问的规则探针

为了不再从错误信息里「猜」规则，MySQL 自检新增两组**对活库逐条提问**的探针，并把答案写成断言：

* **引用动作规则**：9 种 `ON DELETE` / `ON UPDATE` 子句 × 同列 CHECK，逐条断言错误码必须是 3823
  （或必须被接受）。断言错误码而不是「是否抛异常」，否则因为别的原因被拒绝也会被当成一致。
* **`STORED` 生成列规则**：7 种组合（表达式基于外键列/其它列 × `STORED`/`VIRTUAL` ×
  `CASCADE`/`RESTRICT`/无子句）。

这两组探针现在每次构建都跑一次，规则一旦漂移立刻可见。

---

## 7. 引擎侧的实测结论（可独立复现）

以下全部是**裸 SQL + 引擎原话**，与 DDLSmith 无关。

### 7.1 MySQL 3823：CHECK 与「会写列」的引用动作不能共存

```sql
CREATE TABLE p (k INT PRIMARY KEY);
-- 被拒绝：ERROR 3823
CREATE TABLE a (c INT, CONSTRAINT ca CHECK (c <> 1),
  CONSTRAINT fa FOREIGN KEY (c) REFERENCES p (k) ON UPDATE CASCADE);
-- ERROR 3823 (HY000): Column 'c' cannot be used in a check constraint 'ca':
--                     needed in a foreign key constraint 'fa' referential action.
```

`ON UPDATE CASCADE/SET NULL/SET DEFAULT` 与 `ON DELETE SET NULL/SET DEFAULT` 都触发；
`ON DELETE CASCADE`、`ON DELETE/UPDATE RESTRICT`、无动作子句都不触发。见 §4 根因 D 的表。

### 7.2 MySQL 1215：`STORED` 生成列不能读一个 CASCADE 外键的列

```sql
CREATE TABLE parent (id INT NOT NULL, PRIMARY KEY (id));
-- 被拒绝
CREATE TABLE child (pid INT NOT NULL, qty INT,
  g DECIMAL(12,4) GENERATED ALWAYS AS (pid * 2) STORED,
  PRIMARY KEY (pid),
  CONSTRAINT fk FOREIGN KEY (pid) REFERENCES parent (id) ON DELETE CASCADE);
-- ERROR 1215 (HY000): Cannot add foreign key constraint
```

| 组合 | 结果 |
| ---- | ---- |
| `STORED` 表达式读**外键列** + `ON DELETE CASCADE` | **1215** |
| `STORED` 表达式读**外键列** + `ON UPDATE CASCADE` | **1215** |
| `STORED` 表达式读外键列 + `ON DELETE/UPDATE RESTRICT` | 接受 |
| `STORED` 表达式读外键列 + 无动作子句 | 接受 |
| `STORED` 表达式读**别的列** | 接受 |
| `VIRTUAL` 表达式读外键列 | 接受 |

错误信息**既不提生成列也不提动作**，所以从外部看毫无线索——这也正是 MySQL 实机自检此前走不到回环比较
的原因（它的 `total` 就是 `pid * 2` + `ON DELETE CASCADE`）。

**处置：暂不加适配器守卫。** 923 库普查里一次 1215 都没出现过，说明当前生成路径不会构造这个组合；
按本项目的纪律（守卫必须由证据驱动），现在加守卫是无证据的行为变更。规则已写进自检探针，一旦规模化
跑出 1215，原因一步可见。

### 7.3 MySQL 1064：`CREATE INDEX IF NOT EXISTS` 与 `DROP INDEX IF EXISTS` 都不存在

```sql
CREATE INDEX IF NOT EXISTS i1 ON t (a);
-- ERROR 1064 ... near 'IF NOT EXISTS i1 ON t (a)'
DROP INDEX IF EXISTS i2 ON t;
-- ERROR 1064 ... near 'IF EXISTS i2 ON t'
```

两者早已从特性集删除（ACCEPTANCE-2 §8.1 第 21 项），本轮把自检里残留的旧断言一并清掉。

---

## 8. 规模化

### 8.1 线程标定（`sweep1/sweep.tsv`）

DDL 在 MySQL 上逐语句提交并取字典锁，吞吐不随连接数线性增长，所以拐点必须实测：

| `--threads` | 规模 | 用时 | db/s | ms/db | 连接高水位 |
| ----------- | ---- | ---- | ---- | ----- | ---------- |
| 2 | 50 | 25.0 s | 2.00 | 500.0 | 6 |
| 4 | 100 | 32.0 s | 3.12 | 320.0 | 6 |
| 8 | 200 | 46.0 s | 4.35 | 230.0 | 8 |
| 16 | 400 | 85.4 s | 4.68 | 213.5 | 1 |

8 → 16 只多 7.6%，而差分类别每线程要两条连接，因此本轮取 **8**（对 4 是 +39%）。

### 8.2 六个类别

```powershell
pwsh -File runs\mysql-campaign\run-mysql-round.ps1 -Round mysql1 `
    -DurationSeconds 1200 -ChunkSeconds 300 -ChunkTries 100 -Threads 8
```

类别：`mysql`（默认 oracle）、`mysql-evolution`（`--evolve-steps 25`）、`mysql-constraints`
（`--oracle ALL`）、`mysql-differential`（与 `postgres@18.6` 比）、`mysql-printddl`（5 方言 × 6 图
生成器各打印两遍）、`mysql-reproduce`（回放本轮记录的全部失败日志）。

### 8.3 第一次规模化尝试的发现：并行类别会互相拆掉命名空间

第一次跑六个类别并行（`runs/mysql-campaign/mysql1-collided/`，保留作为证据），立刻出现了
`verify1`/`verify2` 从未见过的失败：

| 类别 | 规模 | 失败 |
| ---- | ---- | ---- |
| `mysql` | 100 库 | **151** |
| `mysql-differential` | 100 库 | **368** |
| `mysql-evolution` | 100 库 | **102** |

日志里的引擎原话是 `Table 't_1' already exists`，而涉及的命名空间是**同一个** `ddlsmith_database0`。

**根因**：每个生成库的命名空间由 `<schema 名>_<生成库名>` 推导，而 DDLSmith 在往里建对象**之前会先
DROP 它**（这是「删掉内嵌库文件」在客户端/服务器引擎上的对应物）。六个类别都用默认的
`--schema-name ddlsmith`，于是全部推导出 `ddlsmith_database0`、`ddlsmith_database1`、……，彼此把对方的
对象删掉：A 类建表 → B 类 DROP 掉这个命名空间再重建 → A 类的下一条语句撞上「表已存在」或「表不存在」。

这不是引擎缺陷，也不是单次运行的缺陷——单次运行每个生成库的名字互不相同，隔离是成立的。缺的是
**并行编排的隔离键**。修复：

* `run-mysql-round.ps1` 给每个类别一个自己的 `--schema-name`（`ddlsmith_rnd` / `ddlsmith_evo` /
  `ddlsmith_con` / `ddlsmith_dif` / `ddlsmith_rep`）；
* `reap.ps1` 的默认回收模式相应扩展为
  `^(ddlsmith(_[a-z]+)?(_database[0-9]+|_reproduce)|ddlsmith_replay|ddlsmith_selfcheck(_rule)?)$`，
  仍然只认 DDLSmith 自己会选的名字（已用 14 个名字逐一验证：`ddlsmith`、`information_schema`、
  别的容器名一律不碰）。

这一条值得单独记下来，因为它是**只有真的开规模才会出现**的失效模式，而且它完美复刻了本工具要防的那种
错误：618 条看起来像引擎缺陷的失败，实际是编排的问题。`run-category.ps1` 的 `--bug-dir bugs-chunk-NNN`
隔离让它们没有污染 `bugs/`，但一个只有 `bugs/` 视角的读者会得到完全错误的结论。

### 8.4 `--compare-with` 侧在服务端引擎上共用一个命名空间（**已修**）

隔离修好之后，`mysql` 类别从 151 条失败变成 **0**，但差分类别仍然是 100 库 → 357 条失败。日志里的引擎
原话是 PostgreSQL 的：

```
DDLSmith failure: engine rejected compare-create although it declares ...
Error      : ERROR: relation "t_1" already exists
CREATE TABLE t_1 (id BIGINT NOT NULL, ...)  -- <-- FAILS HERE   ← 未加限定符
```

查活库：PostgreSQL 的 `public` 里有 **18 张 `t_*` 表**，另有 21 个 `ddlsmith_databaseN` schema。

**根因：创建侧和内省侧都必须知道"对象落在哪个命名空间"，而两边都指向了引擎的默认命名空间。**

| 侧 | 行为 | 位置 |
| -- | ---- | ---- |
| 创建 | 对象不加限定地创建，落到会话所指向的**默认**命名空间 | `CommonDialect.getEffectiveSchemaName` 刻意返回 null，于是 `runSessionSetup(namespace=null)` 三个钩子全空 |
| 内省 | 参与者支持 `QUALIFIED_NAMES` 时读**默认**命名空间 | `OracleSupport.resolveSchemaName` 返回 `getDefaultSchemaName()` |

两边"一致地指向默认命名空间"对内嵌引擎是对的（URL 里的 `{db}` 让每个生成库有独立的库），但对
PostgreSQL 就错了——它的默认命名空间 `public` 是全库共享的。第一个生成库建出 `public.t_1`，第二个就
`relation "t_1" already exists`，之后每次比较读到的是**累积了前面所有生成库**的 schema。

**为什么以前看不见**：只在参与者里有客户端/服务器引擎时出现。两轮验收报告里那个 1 522 库、0 失败的
差分类别用的是三个内嵌引擎，是**真的**绿的。

**修复——两侧必须同时改，这正是本轮踩到的坑：**

1. 新增 `Dialect.includesEngine(String)`，`CommonDialect` 覆写为对成员取或：复合方言必须代表它的每一个成员；
2. 新增 `RelationalSchema.withName(String)`：同一份 schema 要被多个引擎各问一次，每个都要知道自己那份落在
   哪个命名空间（**对象共享，只有名字不同**）；
3. `buildCommonSchema` 给主库的副本起**主库自己的命名空间名**；
4. `addComparisonDatabases` 为参与者推导并**准备**命名空间，并给它一份以该命名空间命名的副本。内嵌引擎的
   两个命名空间钩子列表都是空的，据此判定"这个引擎需不需要命名空间"，因此内嵌路径逐字不变；
5. `resolveSchemaName` 在"intended 存在、但它的表都没有限定名"时优先用 `intended.getName()`，只有**匿名**
   schema 才回落到引擎默认命名空间。

**单侧修复的教训（已回退并记录）**：只做第 4 步（我第一版就是）之后，`public` 干净了、每库的 schema 也建
对了，但内省侧仍读 `public`，于是每张表都 `MISSING_TABLE`——失败只从 357 降到 284。一个"两边一致地错"
被改成"两边不一致地错"，比原来更难读。**两侧一起改之后归零。**

### 8.5 复合方言会丢掉按引擎名分派的特性（**已修**）

同一次差分跑里还有 **61 条 `engine-3823`**：MySQL 又拒绝了本该被守卫丢掉的 CHECK。原因不是 §4 的规则，
而是**查表方式**：`EngineTraits` 的特性按引擎名分派，而差分类别用 `CommonDialect` 适配 schema，它的名字是
`common(mysql+postgres)`——于是 `refusesCheckOnReferentialActionColumn("common(mysql+postgres)")` 返回
false，整条守卫失效。61 张 `CREATE TABLE` 被拒，级联出 57 条 `MISSING_TABLE` 与 137 条
"table cannot be queried"，共约 255 个失败单元。

修复：给两个适配期特性加**谓词重载**（`refusesCheckOnReferentialActionColumn(Predicate)`、
`maxIndexColumns(Predicate)`），调用点传 `dialect::includesEngine`；单一引擎的名字重载保留并委托给它，
既有调用点逐字不变。`CommonDialect.isIndexable` 早就是"对成员取与"的写法，这次只是把按名字查表的这两个
特性补到同一个约定上。

### 8.6 并行编排的第二层与第三层（**已修**）

* **回收脚本越界**：`reap.ps1` 按模式回收**所有**匹配的命名空间，而 `run-category.ps1` 在**每个分块之前**
  都调用它。六个类别并行时，任何一个类别开始新分块都会删掉**其它类别正在使用**的命名空间——于是 §8.3 的
  按类别命名空间隔离形同虚设。修复：`run-category.ps1` 新增 `-ReapPattern`（默认为空 = 保持原行为），
  编排器按该类别 `--schema-name` 传入 `^<schema>_database[0-9]+$`。
* **`reap.ps1` 的 PostgreSQL 默认库是 `postgres`**，而本战役的连接库是 `ddlsmith`，于是它"成功但什么也没
  找到"——21 个泄漏的 schema 一直留着。改为默认 `ddlsmith`。
* **`reap.ps1` 的 DROP SCHEMA 批量语句会静默失败**：100 个 schema 时单条 `psql -tAc` 参数过长，只剩一行
  WARNING。改为分批（20/批）**并回读确认**：残留就报出来。
* **`run-category.ps1` 的 `statements` 列一直读成 0**：工具打印的是缩写形式（`1k successfully executed
  statements`），提取正则只接受数字与逗号。已修（接受 `k`/`m` 后缀）。

### 8.7 `evolution` / `constraints` 类别：新暴露的缺陷（**已修**）

这两个类别在本轮之前从未对 MySQL 跑过（`verify1`/`verify2` 只跑默认 oracle 的 `mysql`）。第一次跑出的
2 类 schema 差异 + 5 类崩溃，逐条拿到引擎原话后**全部**定性为工具缺陷：

| 次数/100库 | 引擎原话 | 判定与修复 |
| ---------- | -------- | ---------- |
| 55 → 39 → **0** | 时间列默认值不带引号；`expected false, actual 0` | 两处归一化：时间类型默认值补引号；布尔字面量与数值拼写折叠（`true`/`false` → `1`/`0`，**对称**，同时覆盖模型自己写 `SET DEFAULT 1` 的情况） |
| 22 + 7 → **0** | — | `ALTER TABLE t MODIFY COLUMN a BIGINT` 把 `a INT NOT NULL` 变成**可空**（实测 `IS_NULLABLE` 由 `NO` 变 `YES`）。AST 的 `AlterColumnType` 只带新类型，没有地方重述属性，所以**动作让路**：新增 `EngineTraits.typeChangeReplacesColumnDefinition` + `hasAttributeBeyondType`。生成器与**变形 oracle** 必须用**同一个**判定——它们曾经不一致：生成器修好后变形 oracle 还剩 17 条 |
| 8 | `near 'CURRENT_TIMESTAMP'`（1064） | MySQL 的 `SET DEFAULT` 只接受字面量或**带括号的表达式**（实测 `SET DEFAULT (CURRENT_TIMESTAMP)` 被接受并记为表达式默认值）。修复：`MySqlDialect.visit(SetDefault)` 对表达式加括号，字面量不动 |
| 8 | `Check constraint 'ck_t_1_1' cannot refer to an auto-increment column`（3818） | 新增 `EngineTraits.refusesCheckOnIdentityColumn`，`addCheckConstraint` 跳过自增列 |
| 3 | `BLOB, TEXT, GEOMETRY or JSON column 'a3' can't have a default value`（1101） | 新增 `EngineTraits.allowsDefaultOn`，`setDefault` 跳过该引擎不接受默认值的类型（只对 MySQL/MariaDB 生效） |
| 2 | `Check constraint 't_5_chk_1' uses column 'c_new', hence column cannot be dropped or renamed`（3959） | 改名候选原来只查表级 CHECK；补上**列内联 CHECK**（`column.getCheckExpression()`）——MySQL 会给内联 CHECK 自动命名 `t_5_chk_1` |
| 1 | `Column 'a1' has a generated column dependency`（3108） | 改名候选补上"某个生成列依赖这一列"的判定 |
| 1 | `COLUMN_GENERATED`（变形） | 与上一条同源：改名/改类型都不能碰生成列依赖的列 |

**`constraints`（`--oracle ALL`）**：它的崩溃类别与 `evolution` 共用上面这些守卫；本轮结束后它仍保留
README §12 的实验性标注（`constraint-enforcement` 里那条基于错误文本关键字的守卫仍是按措辞判定）。

### 8.8 六类并行：第一个完整轮次（`mysql2` 无效留档，`mysql3` 为验收轮）

**第一轮 `mysql2`（保留作为证据，判定为无效）**

| 类别 | 规模（每块 100 库） | 失败 | 判定 |
| ---- | ------------------- | ---- | ---- |
| `mysql` | 100 / 块 | **0**（隔离修复后） | 有效 |
| `mysql-differential` | 100 / 块 | 357 → 284 | **无效**：§8.4 的命名空间缺陷 |
| `mysql-evolution` | 100 / 块 | 89 | **无效**：§8.6 的回收越界 + §8.7 的未修类别 |
| `mysql-constraints` | 100 / 块 | 53 | 同上，样本更小 |
| `mysql-printddl` | 每轮约 900 × 2 | 0 | 有效（不需要数据库） |
| `mysql-reproduce` | — | — | 本轮无稳定日志可放（失败都是块内正在写入的） |

**这一轮不能作为 MySQL 的验收结果**，但它是"只有开规模才会出现"的失效模式最直接的证据：两层编排缺陷
（§8.3 的命名空间、§8.6 的回收越界）+ 一类按引擎名分派的特性失效（§8.5），全部在 100 库的规模上第一次
暴露，而单类别 2 182 库的跑法是绿的。

**第二轮 `mysql3`（验收轮）**

**第二轮 `mysql3`（六类并行，900 秒预算，8 线程，`--schema-name` + 回收前缀隔离）**

| 类别 | 规模 | 失败 | 与第一轮对比 |
| ---- | ---- | ---- | ------------ |
| `mysql` | 300 库 | **0** | 0 → 0 |
| `mysql-differential` | 300 库 | **0** | 357/100 → **0/300** |
| `mysql-evolution` | 113 库 | 4 | 89/100 → 4/113 |
| `mysql-constraints` | 149 库 | 3 | 53/100 → 3/149 |
| `mysql-printddl` | 905 次迭代 × 2 遍 | **0 问题** | 0 → 0 |
| `mysql-reproduce` | 5 次回放 | 4 复现 / 1 未复现 / **0 异常** | 无稳定日志 → 5 次 |
| **合计** | **862 库 + 905 次渲染** | **7 条日志 / 7 个缺陷单元 / 4 类** | 601 条失败 → **7** |

普查出的 4 类，逐条定性：

| 次数 | 形态 | 引擎原话 / 实测 | 判定 |
| ---- | ---- | --------------- | ---- |
| 3 | `COLUMN_DEFAULT`（evolution） | `ALTER TABLE t_4 ALTER COLUMN a2 SET DEFAULT (CURRENT_TIMESTAMP)` 之后 `COLUMN_DEFAULT` 是 **NULL**。实测：**主键列**上这条语句被接受（`EXTRA=DEFAULT_GENERATED`）但**什么都不存** | **工具缺陷，已修**：`setDefault` 跳过键列（`setNotNull` 早就这么做）。这一类是被 §8.7 的 1064 修复**暴露**出来的——在那之前这条语句直接被拒，模型与引擎都不会走到"接受了但没生效"这一步 |
| 1 | `UNEXPECTED_ERROR engine-1553`（evolution） | `ALTER TABLE t_6 DROP INDEX uq_t_6_0` → `Cannot drop index 'uq_t_6_0': needed in a foreign key constraint` | **工具缺陷，已修**：新增 `EngineTraits.requiresIndexForForeignKey`，`dropGeneratedConstraint` 不再删掉外键正踩着的那个索引（InnoDB 会复用已覆盖该列的索引，而不是另建一个） |
| 2 | `CONSTRAINT_NOT_ENFORCED`（constraints） | `UPDATE t_7 SET id = 1000067 WHERE id = 64` → **ERROR 1451**；外键是**自引用**的 `t_7[e7_dst_id] -> t_7[id]`，声明 `ON UPDATE CASCADE` | **引擎偏差（初判为工具缺陷，已更正）**：MySQL 接受自引用外键上的 `ON UPDATE CASCADE`，然后按 `RESTRICT` 行事——官方手册在 *FOREIGN KEY Constraint Differences* 里把它写成 "Deviation from SQL standards"。详见 §12 |
| 1 | `DATA_MISMATCH`（constraints） | 同一批数据的两侧渲染不同：`(1, 2000-01-01 00:00:06.0)` 对 `(1, 2000-01-01T00:00:06)` | **工具缺陷，已修**：主键元组里的时间列按「驱动返回的 Java 类型」而不是「列的声明类型」比较。模型侧是 `java.sql.Timestamp`，MySQL Connector/J 返回 `java.time.LocalDateTime`，两者 `toString()` 的日期分隔符与小数秒写法都不同。见 §12 |

**第三轮 `mysql4`（交付构建上的验收轮，六类并行，600 秒预算）**

| 类别 | 规模 | 失败 | 判定 |
| ---- | ---- | ---- | ---- |
| `mysql` | 298 库 | **0** | 绿 |
| `mysql-evolution` | 93 库 | **0** | 绿（上一轮 4） |
| `mysql-differential` | 274 库 | **0** | 绿 |
| `mysql-constraints` | 136 库 | 3 | 见下：实验性 oracle 的误报 |
| `mysql-printddl` | 534 次迭代 × 2 遍 | **0 问题** | 绿 |
| `mysql-reproduce` | 2 次回放 | 2 复现 / **0 异常** | 绿 |
| **合计** | **801 库 + 534 次渲染** | **3 条日志 / 3 个缺陷单元 / 2 类** | 均为同一个形态 |

**最后的 3 条不是同一个形态，当时的定性是错的**（2026-10-05 复核，见 §12）：

```
DDLSmith failure: 1 declared constraint(s) not honoured (CONSTRAINT_NOT_ENFORCED x1)
- skipped table t_1_r: it references t_0, which could not be populated
```

当时的判断是「日志自己写着探针根本没跑成，却仍报 1 个声明的约束未被遵守」，因此把它归到
`ConstraintEnforcementOracle` 的期望模型缺口上。复核发现：**那几行 `skipped table …` 是同一份日志里
别的表的注记，产生这条 finding 的探针跑了**，而且跑在一个**自引用**外键上（`t_7[e7_dst_id] -> t_7[id]`，
声明 `ON UPDATE CASCADE`），服务器真的拒绝了 `UPDATE t_7 SET id = 1000067 WHERE id = 64`，错误码 1451。
用裸 SQL 逐条问服务器（§12）后确认这是 **MySQL 自己文档化的偏差**，不是工具误报。

三条的最终归属：

| # | 形态 | 归属 | 处置 |
| - | ---- | ---- | ---- |
| 2 | `CONSTRAINT_NOT_ENFORCED`（自引用 `ON UPDATE CASCADE`） | **MySQL 的引擎偏差**（手册列为对 SQL 标准的偏离） | 保留为发现，归档 `bugs/mysql/001`；报告补上引擎原话；自检新增规则探针（§12） |
| 1 | `DATA_MISMATCH`（时间列主键元组） | **工具缺陷** | 已修：按列的声明类型归一化时间值（§12） |

**达到的状态**：受信任的 oracle 组合（`crash` + `schema-round-trip` + `idempotence` + `differential-schema`
+ `metamorphic-alter`）在 MySQL 上 **801 库 / 0 发现**；`--oracle ALL` 剩下的发现只有**一条真实存在的
引擎偏差**，已归档并带裸 SQL 复现。

---

## 9. 本轮到底验证了什么

**已验证（同种子 A/B，可复现）**

* `mysql` 类别（默认 oracle：`crash` + `schema-round-trip` + `idempotence`）在修复后的构建上
  **2 182 库 / 0 失败**；同一批种子在修复前是 1 057 条失败。这是本报告最硬的一条结论。
* **交付构建上的同种子终验**（`verify4`，种子 2100000 起、7 个分块）：**700 库 / 0 失败 / exit 0**，
  其中前两块与 `census1` 报出 1 057 条失败的那两块**同种子**（2100000、2200000）。这一步是必要的，
  因为 `verify1`/`verify2` 用的 jar 之后又进了 3 项改动（PostgreSQL 的 `FK_TARGET_MUST_EXIST` 声明、
  表达式归一化里的括号化数值、时间类型默认值补引号）。
* 4 个根因（§4）各自的修复都有裸 SQL 证据，且 `mvn clean package` **272 tests / 0 failures**（本轮之后为
  277，见 §12）。
* MySQL 实机自检 **390 断言 / 0 失败**（含两组对活库提问的规则探针；本轮之后为 397 断言、三组探针，
  见 §12）；PostgreSQL 实机自检
  **330 断言 / 0 失败**；SQLite 312 断言、H2、`EndToEndCheck` 全通过。
* `--reproduce` 的命名空间修复：同一条日志连放两次输出逐字相同（§5）。
* **六类并行的规模化轮次**：`mysql3` **862 库 + 905 次渲染 / 7 条发现**（§8.8），
  其中两个工具缺陷当场定性并修复；**交付构建上的 `mysql4` 是 801 库 + 534 次渲染 / 3 条发现**。
  那 3 条当时的定性（"同一个误报形态"）**已在 §12 更正**：2 条是 MySQL 文档化的引擎偏差，1 条是工具缺陷
  （已修）；修复后的同规模轮次 `mysql5` 见 §12.7。
* 受信任的 oracle 组合（`crash` + `schema-round-trip` + `idempotence` + `differential-schema` +
  `metamorphic-alter`）在交付构建上：`mysql` 298 库 0 失败、`mysql-evolution` 93 库 0 失败、
  `mysql-differential` 274 库 0 失败、`mysql-printddl` 534×2 次渲染 0 问题、
  `mysql-reproduce` 2 次回放 0 异常。

**未完成（明确列出，不要当成绿的）**

* ~~**实验性 DML oracle 的 3 条误报**~~ —— **2026-10-05 复核后撤销**：其中 2 条是 MySQL 文档化的引擎偏差
  （自引用 `ON UPDATE CASCADE`，见 §12），1 条是 `PivotRowOracle` 的时间列比较缺陷（已修）。
* `verify1` 与 `census1` 的分块预算不同，第二块的种子覆盖有约 37 个库的差集（§10）。
* ~~DuckDB 实机自检仍有 1 项**既有**失败~~ —— **已修**（§12）：那条断言要求渲染 `ON DELETE CASCADE` 时
  抛异常，而 `DuckDbDialect` 早已刻意改成不抛（抛出会把引擎的回答变成工具崩溃，正好丢掉要找的发现）。
  现在改为对着活库断言「语句发到引擎、引擎拒绝」，DuckDB 自检恢复常绿。
* MySQL 1215 规则已实测但**未加守卫**，理由见 §7.2（923 库普查里一次都没出现，无证据的行为变更不做）。
  自检探针仍然钉着这条规则，规模化一旦跑出 1215，原因一步可见。
* `constraints`（`--oracle ALL`）保留实验性标注：`constraint-enforcement` 里那条基于错误文本关键字的
  守卫（`violatesAConstraintTheRowGeneratorDoesNotModel`）仍是按措辞判定，换个引擎措辞就可能重新出现
  误报。本轮修掉的是**时间列比较**这一类，不是那句措辞判定。

---

## 10. 诚实边界

* **逐条定性的完整判据**是"裸 SQL + 引擎原话"。本报告 §7 的三条规则（3823、1215、1064）与 §8.6–§8.8 的
  六条（InnoDB 外键隐式索引、唯一索引被当成主键索引、`MODIFY COLUMN` 丢 `NOT NULL`、主键列上的
  `SET DEFAULT` 被接受但不生效、TEXT 不能有默认值、CHECK 不能引用自增列）都达到了这个标准。
* **`verify1` 与 `census1` 的种子覆盖并不完全重合。** 两者前 500 个种子相同（2100000 起），但第二块
  因为分块时间预算分别只跑到 423 与 386 个库（`census1` 的第二块更慢，因为它要为每条失败写 20 KB
  日志）。因此「同种子上 1 057 → 0」严格说来是**同一批种子区间**上的结论；`verify2` 另用三个全新种子
  区间补了 1 296 个库，`verify4` 又在交付构建上补了 700 库。
* **分块间的命名空间回收占了可观的墙钟时间。** 每个生成库在服务器上是一个真库，`DROP DATABASE` 实测
  约 0.35 s，因此一轮里"回收上一块"与"跑这一块"的时间是同一量级。这是"每个生成库一个专属命名空间"
  这一设计的固有代价，不是缺陷；但它意味着预算不等于测试时间。若按块给 `--database-prefix` 加前缀并
  全程 `--keep-databases`、结束时统一回收，可把这笔开销挪出预算。
* **DuckDB 实机自检的那 1 项既有失败已于 2026-10-05 修掉**（§12）。它断言 `ON DELETE CASCADE` 在渲染时
  抛 `UnsupportedOperationException`，而 `DuckDbDialect` 已刻意改成不抛（`DuckDbDialect.java:432` 的注释
  写明是故意的：抛出会把引擎的回答变成工具崩溃，正好丢掉 crash oracle 要找的发现）。这是断言过期，
  现在改成对着活库断言两件事——渲染器必须把语句发出去，引擎必须拒绝它。**一个恒红的自检本身就是
  盲区**——MySQL 与 PostgreSQL 的自检此前正是这样把整条尾巴藏了几个月。
* **`constraints` 类别仍属实验性。** README §12 的保留意见依然成立：`constraint-enforcement` 里那条基于
  错误文本关键字的守卫（`violatesAConstraintTheRowGeneratorDoesNotModel`）仍是按措辞判定，换个引擎措辞
  就可能重新出现误报。
* **`reap.ps1` 的 Postgres 默认库曾是 `postgres`**，而本战役的连接库是 `ddlsmith`，于是回收"成功但什么
  也没找到"——21 个泄漏的 schema 一直留着。已改为默认 `ddlsmith`。这类"静默地什么都没做"的脚本行为，
  与 §5 里回放器把 exit 0 读成复现是同一类错误；同一段代码里还有两处同类问题（DROP SCHEMA 批量语句过长
  时只剩一行 WARNING、以及 `psql` 对空结果打印空行导致每次都报"1 个 schema 仍残留"），都已修。
* **`run-category.ps1` 的 `statements` 列一直静默读成 0。** 工具打印的是缩写形式
  （`1k successfully executed statements`），而提取正则只接受数字与逗号，于是永远匹配不上。已修
  （接受 `k`/`m` 后缀）。一个恒为 0 的统计列与"这一轮什么都没执行"在汇总表里长得一模一样——与本报告
  反复出现的失效模式（静默地什么都没做）同一类。
* 服务器上 `ddlsmith_replay` / `ddlsmith_selfcheck` 是工具自己的遗留命名空间（前者来自 `--reproduce`
  修复前的 `--database` 变通做法）。`reap.ps1` 的默认模式已扩展为
  `^(ddlsmith(_[a-z]+)?(_database[0-9]+|_reproduce)|ddlsmith_replay|ddlsmith_selfcheck(_rule)?)$`，
  已用 14 个名字逐一验证只认 DDLSmith 自己会选的名字。

---

## 11. 本轮改动的文件

生产代码：

| 文件 | 改动 |
| ---- | ---- |
| `introspection/SchemaNormalizer.java` | 表达式/查询/索引比较的归一化（根因 A、B、C）；布尔与数值拼写折叠；`isConstraintBackedIndex` 增加名字判据（§8.6 前一轮、§8.7） |
| `ddl/gen/SchemaAdapter.java` | 引用动作规则收敛到一处并补上 `ON UPDATE CASCADE`；索引按引擎上限截断；两个适配期特性改用 `dialect::includesEngine`（§8.5） |
| `ddl/gen/EngineTraits.java` | 新增 `maxIndexColumns`、`typeChangeReplacesColumnDefinition`、`hasAttributeBeyondType`、`allowsDefaultOn`、`refusesCheckOnIdentityColumn`，并为两个特性加谓词重载（§8.5、§8.7） |
| `ddl/gen/RandomDDLGenerator.java` | 追加的 CHECK 走同一条引用动作守卫；自增列不得进 CHECK；类型变更在 MySQL 上让路；改名避开内联 CHECK 与生成列依赖；默认值跳过不支持的类型（§8.7） |
| `dbms/mysql/MySqlIntrospector.java` | 时间类型默认值补引号；识别 InnoDB 为外键隐式创建的索引（§8.6 前一轮、§8.6） |
| `dbms/mysql/MySqlDialect.java` | 声明 `FK_TARGET_MUST_EXIST`（行为不变：`Dialect` 本来就 OR 了 `EngineTraits`）；`SET DEFAULT` 对表达式加括号（§8.7） |
| `dbms/postgres/PostgresDialect.java` | 声明 `FK_TARGET_MUST_EXIST` |
| `algorithm/AbstractTestingAlgorithm.java` | 差分类别：主库 schema 起自己的命名空间名；参与者准备并命名自己的命名空间（§8.4） |
| `oracle/impl/OracleSupport.java` | 有名（而非匿名）的 intended schema 优先于引擎默认命名空间（§8.4，两侧修复的另一半） |
| `oracle/impl/IdempotenceOracle.java` | 探测 schema 名按**解析出的命名空间**做作用域（§8.6） |
| `oracle/impl/MetamorphicAlterOracle.java` | 类型变更与生成器用同一个 `EngineTraits` 判定（§8.7） |
| `common/schema/RelationalSchema.java` | 新增 `withName`：同一份 schema 被多个引擎各问一次时，各自带上自己的命名空间 |
| `ddl/Dialect.java`、`ddl/CommonDialect.java` | 新增 `includesEngine`：复合方言代表它的每个成员（§8.4、§8.5） |
| `Reproducer.java` | 回放前推导并准备命名空间 |
| `oracle/impl/PivotRowOracle.java` | 主键元组里的时间值改按**列的声明类型**归一化（`canonicalTemporal`），不再按驱动返回的 Java 类型拼字符串（§12） |
| `oracle/impl/ReferentialActionOracle.java` | 探针改为返回 `ExecutionEvent`，发现里带上引擎原话（`Engine said: … code 1451`），使「裸 SQL + 引擎原话」这条判据在报告里自足（§12） |

测试与基础设施：

| 文件 | 改动 |
| ---- | ---- |
| `dbms/mysql/SelfCheck.java` | 实机 schema 覆盖四类盲区；走适配路径；新增两组规则探针；清理过期特性断言；**新增 `checkSelfReferentialCascadeRule`**（5 种动作 + 2 个对照，逐条断言错误码 1451 / 接受，§12） |
| `dbms/duckdb/SelfCheck.java` | 过期断言改为「渲染器必须把 `ON DELETE CASCADE` 发到引擎，引擎必须拒绝」，DuckDB 自检恢复常绿（§12） |
| `oracle/impl/PivotRowOracleCanonicalTest.java` | 新增：时间列的规范形式（`Timestamp` / `LocalDateTime` / `LocalDate` / `LocalTime` / SQLite 文本）、小数秒、以及「真的不同仍然不同」 |
| `dbms/mysql/MySqlDialectTest.java` | 特性数 73 → 74，登记 `FK_TARGET_MUST_EXIST` |
| `dbms/postgres/SelfCheck.java`、`PostgresDialectTest.java` | 同上（100 → 101）；实机段设置 `search_path` |
| `runs/run-category.ps1` | 新增 `-ReapPattern`（默认空 = 原行为）；`statements` 列接受 `k`/`m` 缩写（§8.6） |
| `runs/mysql-campaign/reap.ps1` | 默认回收模式；PostgreSQL 默认库 `postgres` → `ddlsmith`；DROP SCHEMA 分批并回读确认（§8.6） |
| `runs/mysql-campaign/run-mysql-round.ps1`、`run-mysql-reproduce.ps1` | 每类别一个 `--schema-name` 与一个回收前缀；回放器改为只带 `--schema-name`（工具内已自备命名空间） |
| `runs/mysql-campaign/README.md` | §0 现状与根因表；Phase 1 标记完成；§5 记录两层编排缺陷；§0.1 交付后复核 |

回归：**`mvn clean package` → 277 tests, 0 failures**（本轮新增 5 个）；实机自检 MySQL **397 断言 / 0 失败**、
PostgreSQL **330 / 0**；内嵌引擎 SQLite **312 断言**、H2、DuckDB **SELF-CHECK PASSED**、`EndToEndCheck`
全部通过（DuckDB 的那 1 项既有失败已在本轮修掉，见 §12）。

---

## 12. 交付后的复核：§9 / §10 里剩下的三项（2026-10-05）

交付构建（`target/DDLSmith.jar`，16:13:40）之后，把 §9「未完成」和 §10「诚实边界」里列出的每一项重新拿
证据过了一遍。结论是：**三项里两项是本文自己的定性错误，一项是真的工具缺陷**，现在全部处理完。

### 12.1 复核的方法

* 每一项都**回到它自己的种子**重跑（`--random-seed <该日志记录的 seed> --num-tries 1`，同一算法、同一
  oracle 组合），而不是读日志猜；
* 凡是关于引擎行为的判断，一律用**裸 SQL 在活库上问**（`docker exec ddlsmith-mysql mysql`），并且逐条
  换参数把规则的边界走出来，而不是从一条错误信息外推；
* 规则只有查到**官方手册原文**才写成结论。

### 12.2 两条 `CONSTRAINT_NOT_ENFORCED`：不是误报，是 MySQL 文档化的偏差

§8.8 当时的定性是「日志自己写着 `skipped table t_1_r: it references t_0, which could not be populated`
——**探针根本没跑成**，却仍报『1 个声明的约束未被遵守』」。**这句话读错了日志**：那几行 `skipped table …`
是同一份日志里**别的表**的注记（`t_1_r` / `t_2` / `t_3_r`），而产生这条 finding 的探针跑了，跑在一个
**自引用**外键上：

```
Foreign key : t_7[e7_dst_id] -> t_7[id] (fk_t_7_0)
Declared    : ON UPDATE CASCADE
SQL: UPDATE t_7 SET id = 1000067 WHERE id = 64
```

裸 SQL 逐条问服务器（每张表都是 `(1, NULL)` 与 `(2, 1)` 两行，探针移动第 1 行的键）：

| 自引用外键的声明 | `UPDATE t SET id = 1000 WHERE id = 1` |
| ---------------- | ------------------------------------- |
| 无动作子句 | **ERROR 1451** |
| `ON UPDATE RESTRICT` | ERROR 1451 |
| `ON UPDATE CASCADE` | **ERROR 1451**——声明被接受，然后被无视 |
| `ON UPDATE SET NULL` | **ERROR 1451**——同样 |
| `ON UPDATE NO ACTION` | ERROR 1451 |
| `ON UPDATE CASCADE`，但没有子行指向该父行 | 接受，键移动成功 |
| `ON UPDATE CASCADE`，外键指向**另一张表** | 接受，子行跟着改 |

也就是说，规则是关于**递归**的，不是关于动作的。MySQL 手册在 *FOREIGN KEY Constraint Differences*
（"Deviation from SQL standards"）里写明：*"If `ON UPDATE CASCADE` or `ON UPDATE SET NULL` recurses to
update the same table it has previously updated during the same cascade, it acts like `RESTRICT`. This
means that you cannot use self-referential `ON UPDATE CASCADE` or `ON UPDATE SET NULL` operations."*

**处置：保留为发现，不压制。**

1. 这正是本工具要找的东西——引擎接受一条声明，然后不执行它。生成器继续发出这个组合，oracle 继续报。
2. 发现里**补上引擎原话**：`ReferentialActionOracle.runProbe` 改为返回 `ExecutionEvent`，报告里多一行
   `Engine said: failed: Cannot delete or update a parent row: … (SQLState 23000, code 1451)`。原报告的
   「引擎原话」栏只有注记，读的人必须回到服务器才能知道原因。
3. 归档 [`bugs/mysql/001-n-declared-constraint-s-not-honoured-constraint-not-enforced.md`](../bugs/mysql/001-n-declared-constraint-s-not-honoured-constraint-not-enforced.md)，
   并加上维护者注记（规则表、裸 SQL、手册原文、以及"这不是工具误报"的说明）。
4. MySQL 实机自检新增 `checkSelfReferentialCascadeRule`：5 种动作逐条断言错误码 **1451**，外加两个对照
   （无子行 → 接受；指向另一张表 → 接受并跟着改）。规则一旦漂移，自检立刻可见。

### 12.3 一条 `DATA_MISMATCH`：真的工具缺陷，已修

`SELECT t_2_a6, t_1_a5 FROM t_6_r ORDER BY t_2_a6, t_1_a5` 的两侧是**同一批四行**：

```
Expected: [(1, 2000-01-01 00:00:06.0), (0, 2000-01-01 00:00:05.0), (0, 2000-01-01 00:00:07.0), (1, 2000-01-01 00:00:05.0)]
Observed: [(0, 2000-01-01T00:00:05), (0, 2000-01-01T00:00:07), (1, 2000-01-01T00:00:05), (1, 2000-01-01T00:00:06)]
```

主键是 `(t_2_a6 BOOLEAN, t_1_a5 DATETIME)`。**根因**：`PivotRowOracle.canonical` 把值交给
`DataModel.normalizeForComparison` 之后直接 `toString()`，于是比较的是**驱动返回的 Java 类型**而不是
**列的声明类型**：

* 期望侧来自行生成器的模型，时间列一律是 `java.sql.Timestamp` → `2000-01-01 00:00:06.0`；
* 观察侧来自 MySQL Connector/J 的 `getObject()`，DATETIME 是 `java.time.LocalDateTime` →
  `2000-01-01T00:00:06`。

用活库确认了驱动为各时间类型返回什么（`runs/tmp-probe/ProbeDefaults.java`）：DATE → `java.sql.Date`、
TIME → `java.sql.Time`、DATETIME → `java.time.LocalDateTime`。也就是说，只要主键里有时间列，这个 oracle
就会报一条假差异。

**修复**（`oracle/impl/PivotRowOracle.java`）：新增 `canonicalTemporal`，按**列的声明类型**归一化——
DATE 只比日期、TIME 只比时刻、TIMESTAMP/TIMESTAMPTZ 比日期+时刻，小数秒去掉尾零；模型侧、驱动侧、
以及 SQLite 那种"存进去的文本"都走同一个函数。**这不会削弱检查**：引擎真的截断或强制转换过的值仍然不同
（回归测试 `PivotRowOracleCanonicalTest` 5 个用例，含"不同时刻仍然不同"）。

**验证**：种子 2400000 重跑，`exit 0`（修复前该种子报 `DATA_MISMATCH`）。

### 12.4 DuckDB 自检的恒红断言

§10 把它列为"已知项"。它断言渲染 `ON DELETE CASCADE` 时抛 `UnsupportedOperationException`，而
`DuckDbDialect.renderReferentialActions` 早已刻意不抛——抛出会把引擎的回答变成工具崩溃，正好丢掉 crash
oracle 要找的发现（`DuckDbDialectTest` 早就把这条契约写成了测试）。**断言过期**，不是行为缺陷。

**修复**：改为对着活库断言两件事——渲染器必须把语句发出去（`cascadeSql.contains("ON DELETE CASCADE")`），
引擎必须拒绝它。现在自检打印：

```
ok   ON DELETE CASCADE reached the engine and was rejected: Parser Error: FOREIGN KEY constraints cannot use CASCADE, SET NULL or SET DEFAULT
SELF-CHECK PASSED
```

### 12.5 顺带更正的文档

* `docs/ACCEPTANCE.md` §8 的处置表里「PostgreSQL/MySQL 的实机验证 | **仍未完成**——本机 5432/3306 依旧没有
  服务」是**错的**（它描述的是端口，不是发生过的事：实机验证用的是另起的容器端口 33306 / 15432），已更正
  并指向本报告；
* `README.md` §9/§10/§11/§12：测试数 272 → 277、新增 `PivotRowOracleCanonicalTest`、新增 MySQL 自引用
  `ON UPDATE CASCADE` 这条引擎发现、§12 关于三条发现的定性更正；
* `runs/mysql-campaign/README.md`：§0 表与 §0.1（本轮复核的完整记录）。

### 12.6 复核后的验证

| 项 | 结果 |
| -- | ---- |
| `mvn clean package` | **277 tests, 0 failures**（新增 5 个） |
| MySQL 实机自检 | **397 断言 / 0 失败**（新增第三组规则探针） |
| PostgreSQL 实机自检 | **330 断言 / 0 失败** |
| DuckDB 实机自检 | **SELF-CHECK PASSED**（原 1 项恒红失败已修） |
| 种子 2400000（`DATA_MISMATCH`） | **exit 0**，假差异消失 |
| 种子 2300020 / 2300077（`CONSTRAINT_NOT_ENFORCED`） | 仍报同一条，但报告里带着 `code 1451` 的引擎原话 |
| 交付前顺带核查 | `DefaultValueOracle` 的比较路径也按 Java 类型拼字符串，但生成器在 MySQL 上对 DATE/TIME 发的是 `'2000-01-01'` / `'00:00:00'`，驱动返回的 `java.sql.Date` / `java.sql.Time` 的 `toString()` 恰好就是这两个字面量；TIMESTAMP 列走 `CURRENT_TIMESTAMP`（不走字面量比较）。**当前生成路径踩不到，因此按证据驱动的纪律不加改动**，已在此记录以备将来 |

### 12.7 修复后的规模化轮次（`mysql5`）

复核之后在**同一个交付构建**上重跑六类并行（30 分钟预算、8 线程、每类独立 `--schema-name` 与回收前缀）：

```powershell
pwsh -File runs\mysql-campaign\run-mysql-round.ps1 -Round mysql5 `
    -DurationSeconds 1800 -ChunkSeconds 300 -ChunkTries 100 -Threads 8
```

| 类别 | 规模 | 失败 | 说明 |
| ---- | ---- | ---- | ---- |
| `mysql`（默认 oracle） | **492 库** | **0** | 种子 2100000–2500000，5 个分块 |
| `mysql-evolution`（`--evolve-steps 25`） | **166 库** | **0** | 每库 25 步 `ALTER TABLE`，每步回读目录 |
| `mysql-constraints`（`--oracle ALL`） | **226 库** | **6** | **1 个缺陷**：全部是 §12.2 的自引用 `ON UPDATE CASCADE` 偏差 |
| `mysql-differential`（对 PostgreSQL 18.6） | **408 库** | **0** | 种子 2400000–2800000 |
| `mysql-printddl`（5 方言 × 6 生成器，各打印两遍） | **1 651 次迭代 × 2 遍** | **0 问题** | 两次打印逐字相同 |
| `mysql-reproduce`（回放本轮全部失败日志） | **6 次回放** | 6 复现 / 0 未复现 / **0 异常** | 判决与记录一致 |
| **合计** | **1 292 库 + 1 651 次渲染** | **6 条日志 / 1 个缺陷单元 / 1 类** | 全部是同一个已归档的引擎偏差 |

**6 条发现逐条核对**：全部是 `fk-on-update-CASCADE`，外键全部**自引用**（`t_3[e3_dst_id] -> t_3[id]`、
`t_4[e4_dst_id] -> t_4[id]`、`t_5[e5_dst_a17] -> t_5[a17]`、`t_2[e2_dst_id] -> t_2[id]`、
`t_7[e7_dst_id] -> t_7[id]`），引擎原话全部是 **code 1451**，全部已归档为 `bugs/mysql/001`。

**这一轮最重要的两条读数**：

* 受信任的 oracle（`mysql` + `evolution` + `differential`）**1 066 库 / 0 失败**；
* `constraints` 类别里那个曾被误判为「误报」的种子区间（2400000）现在 **0 失败**——修复后的构建在
  规模化路径上也不再产生 `DATA_MISMATCH`，与 §12.3 的单种子复跑一致。

吞吐：`mysql` 类别 492 库用 1 041 s（六类并行、8 线程），约 0.47 库/秒；整轮墙钟约 35 分钟，含分块间
命名空间回收与结束时普查。

---

## 13. 缺陷挖掘轮：12 小时，两个引擎缺陷（2026-10-06）

§1–§12 回答的是「工具在 MySQL 上是不是绿的」，答案是肯定的。这一轮换了一个问题：**MySQL 还错在哪**。
类别组合因此按「历史上真正找出过引擎缺陷的是什么」来选，而组合本身就是被检验的变量。完整报告见
[runs/mysql-campaign/12h-b/REPORT.md](../runs/mysql-campaign/12h-b/REPORT.md)，逐条定性见同目录的
[CLASSIFICATION.md](../runs/mysql-campaign/12h-b/CLASSIFICATION.md)。

### 13.1 设计：两个实例、同一版本

MySQL 9.6 把外键强制与级联处理从 InnoDB 搬进了 SQL 引擎，`innodb_native_foreign_keys` 是回到旧路径的
开关。**同一版本的同一台服务器上同时存在这两条实现**，因此让它们对同一份 schema 各自作答，两条路径的
分歧就必然是其中一条的缺陷，且不含跨版本混杂因素。本轮起了第二个实例（`:33307`，
`--innodb_native_foreign_keys=TRUE`）作为对照，并把三个类别对（`constraints`/`native`、
`constraints-deep`/`native-deep`、`native-evolution`/`evolution-deep`）设为**同一批种子、同一个图生成器**，
使差异只可能来自外键路径。

同时**去掉了 `printddl`**：上一轮它渲染了 46 769 份 schema、两次打印逐字相同、问题为 0，而且它从不连接
服务器，找不出引擎缺陷。它的预算转给了真正会去违反约束的那些 oracle。

### 13.2 两个新的引擎缺陷

| 归档 | 形态 | 路径 | 判定 |
| ---- | ---- | ---- | ---- |
| [`bugs/mysql/004`](../bugs/mysql/004-self-referencing-composite-foreign-key-not-enforced.md) | **自引用的复合外键完全不强制**：两个键列都非 NULL、父行不存在，行照样写进去，表里不断堆积悬挂行 | 仅 SQL 引擎路径 | 已确认，最小复现确定可重复 |
| [`bugs/mysql/003`](../bugs/mysql/003-cascading-delete-refused-when-a-self-referencing-cascade-chain-is-three-rows-deep.md) | **自引用 `ON DELETE CASCADE` 的链到三行时，级联删除被整条拒绝**（`ERROR 6575`，两行可以、三行不行，且什么都不删） | 仅 SQL 引擎路径 | 已确认，8.0.28 与本机 InnoDB 路径均正常 |

两条都是**回归**，而且都不在目录里可见：`TABLE_CONSTRAINTS`、`REFERENTIAL_CONSTRAINTS` 与
`SHOW CREATE TABLE` 都照常报告那个根本没被执行的约束。加上上一轮归档的 `bugs/mysql/002`，
**三个缺陷全部位于 9.6 新引入的 SQL 引擎外键路径，且没有一条能被读目录的 oracle 发现**——只有"真的去
违反约束"的 oracle 能找到。这就是本轮把 `constraint-enforcement` 系列给了十个类别中的三个的理由。

`004` 的触发条件**尚未完全隔离**：确定需要**自引用的复合**外键（非自引用、单列、建在 `UNIQUE` 键上的
变体都被正确拒绝），但除此之外的区分条件未确定。早先一次读数提示"表上额外的、覆盖外键列的索引"是必要条件，
**后来对同一组对照更仔细地重跑（`probe-distinct.sql`）显示两种变体都被接受，因此该说法已撤回**；剩余差异在
键列的类型与取值上，未进一步隔离。**机理未确定**，报告里也这么写。

### 13.3 工具自身的缺陷：两个 oracle 期望模型与一处恒红自检

* **`referential-action` 的级联期望模型只有一层**。`cascadingRemovalCount` 只数"通过子表的级联键到达被删
  父行"的子行，不跟着级联再走一层；于是当父表自身还有自引用 `ON DELETE CASCADE` 时，被级联删掉的孙行
  及其子行从未被计入。`probe-cascade-model.sql` 把这个形状搭出来并同时打印模型与引擎的答案（两条路径
  完全一致）：模型期望剩 2 行，引擎剩 1 行。**引擎是对的，模型少算了一层。** 这一条正是发现 `003` 的入口。
* **InnoDB 的 3 072 字节键宽上限没有建模**。适配器建了 16 列上限（`maxIndexColumns`），没有建键宽上限，
  于是映射层会发出引擎建不起来的复合自然键（`ERROR 1071`）。更麻烦的是**失败报告没有说出自己的原因**：
  它指责的是那条成功的 `ALTER TABLE`，而被拒的 `CREATE TABLE` 只有读日志开头并去问服务器才看得见。
* **一处恒红的自检**：`checkForeignKeyIndexRebindingRule` 原来只按版本串（`startsWith("26.7")`）设限，
  于是在 InnoDB 路径的对照服务器上——那里引擎是对的——它仍然断言 `bugs/mysql/002` 存在，自检**永远是红的**。
  已修：新增 `usesNativeForeignKeyPath`，两个探针都改为按**外键路径**设限。

### 13.4 新增的自检探针

`MySqlSelfCheck` 新增两条对着活库提问的探针，把两个新缺陷钉住，使"某个 MySQL 版本把它修好了"会**让自检
失败**，而不是让 oracle 悄悄变安静：

```
-- a cascading delete over a self-referencing hierarchy --
  server 26.7.0, innodb_native_foreign_keys=OFF (the SQL-engine path)
  chain of 2 rows -> accepted, 0 parent row(s) left
  chain of 3 rows -> refused 6575, 3 parent row(s) and 3 child row(s) left
419 assertions, 0 failure(s)          ← SQL 引擎路径
413→417 assertions, 0 failure(s)      ← InnoDB 路径，断言的是相反的行为
```

两条探针都带对照，因此不会"靠拒绝一切"而通过：部分 NULL 的复合键必须仍然被接受（`MATCH SIMPLE`），
合法子行必须仍然被接受。

### 13.5 工具为什么没自己报出 `003`（已定位，未修）

`referential-action` 确实会探自引用 `ON DELETE CASCADE` 上的删除，但当"唯一引用父行的行就是父行自己"时
它**正确地**跳过。每一轮的每一个含递归关系的库都命中这条守卫。读 `RowGenerator.resolveForeignKeys` 可以
解释原因：它从"表里已有的行"里挑父行，而这个集合**包含正在构造的这一行**——该行的键列此时已经赋值——
一旦挑中自己，外键列就被写成自己的键值，于是这一行成了自引用行，也就不再是"级联可以从它出发"的行。
自引用外键因此被填成一堆自引用行而不是一条层级，`003` 需要的三行链从不出现。

修法两处（**本轮刻意不做**，因为改动生成器会让正在跑的 jar 失去意义）：(1) 为自引用关系生成一条小层级
（根 + 子 + 偶尔的孙）；(2) 探针优先选"被**另一行**引用的父行"。

### 13.6 诚实边界

* 两个缺陷都**没有查过 MySQL 官方 tracker**：本环境访问 `bugs.mysql.com` 返回 HTTP 403，"是否已有人报过"
  **不成立**。
* `003` **未做版本二分**：确认于 26.7.0、不存在于 8.0.28 与 26.7.0 的 InnoDB 路径；引入它的版本是 9.6 或更晚。
  引擎说"max tables limit of 30"时到底在数什么，**未知**——它不是表的数量、不是子表的数量、也不是被删的行数。
* `004` 的**机理未确定**（触发条件是实测的）。
* 两个工具缺陷（§13.3 前两条）**只做了诊断，没有修**：一个是 oracle 期望模型，一个是映射层选择，都属于
  维护者的决定，而且中途改源码会让正在测试的 jar 失效。
* **受信任的 oracle 组合在这一轮依然一条都没报**。所有发现都来自 `--oracle ALL` 系列，或来自顺着它们的一条
  报告往下追。这说明的是"剩下的 MySQL 缺陷长在哪里"，而不是"其它 oracle 没用"——`crash`、
  `schema-round-trip`、`idempotence` 正是让**工具**保持诚实的那一组。

## 14. 两小时一致性实验与随后的修复（2026-10-10）

这一节记录的不是引擎缺陷，而是**工具自己的三处误报**：两小时实验（`runs/REPORT-MYSQL-2H.md`）用
`graph-conformance` 与 `--oracle ALL` 跑了 3 506 个数据库、13 条失败，分成 4 类——1 类是 MySQL 的真实缺陷
（`bugs/mysql/005`，已单独归档），另外 3 类全是 DDLSmith 自己的问题。三处都已修复，并且**回到失败的同一个
数据库**上做了 A/B 复测（基线 = 修复前提交 `f3411b2`）。

### 14.1 大小写：默认排序规则不是模型的相等

MySQL 服务端默认 `utf8mb4_0900_ai_ci` 大小写与重音**都不敏感**，于是 `UNIQUE` 列先接受 `'HF'` 再以 `1062`
拒绝 `'hf'`；`CHECK (a <> 'x')` 会拒绝 `'X'`（3819）；`ENUM('a','A')` 在 `CREATE TABLE` 阶段就被拒（1291）——
三者实测于 26.7.0（`runs/probe-mysql-collation-choice*.sh`）。这不是探针的问题，而是**声明出来的 schema 与
引擎实现的 schema 不是同一个东西**：模型的相等是 Java 的 `String.equals`。

修法放在方言与适配层，而不是让每个 oracle 都去理解排序规则：

* `Dialect.getStringCollation()`（默认 null）；`MySqlDialect` 返回 `utf8mb4_0900_bin`；
* `SchemaAdapter.applyStringCollation` 给 mapper 产出的**每一个字符列**声明该排序规则（每 schema 一条 note）。

选 `utf8mb4_0900_bin` 而不是 `utf8mb4_bin`，依据是手册按排序规则给出的 pad 属性，并在容器里实测：
`utf8mb4_bin` 是 **PAD SPACE**（尾部空格不参与比较，`'a' = 'a '` 为真），`utf8mb4_0900_bin` 是 **NO PAD**
（`'a' ≠ 'a '`）且按码点比较——后者正是 Java 的相等。`utf8mb4_0900_bin` 自 MySQL 8.0.17 引入；面向更老服务端
（或 MariaDB，它没有 `0900` 系列）的方言应覆写该方法用 `utf8mb4_bin`。外键两侧排序规则必须一致（手册
§15.1.20.5；实测不一致报 `3780`），这正是"给所有字符列都声明"而不是"只给部分列声明"的原因。

此外，oracle 侧保留一道兜底：重复键被拒时，它用引擎自己的 `=` 去把那条已存储的行查出来，只有该行在模型看来
**确实是另一个值**时才记为 note；查不到这样的行，仍然照旧报 `CONSTRAINT_TOO_STRICT`——真缺陷不会被放过。

### 14.2 生成列：值适合自己那一列，不等于适合它派生出的那一列

`a10 SMALLINT GENERATED ALWAYS AS (a1 + 1) STORED` 配上 `a1 INT`：`a1 = 1028000` 完全合法，MySQL 却以
`1264 Out of range value for column 'a10'` 拒绝整行（实测；手册只在"越界与溢出"一节给出 1264/22003，没有专门
写生成列的情形）。修法两层：`ConformancePlanner.fitGeneratedColumnOperands` 把 mapper 实际会写的表达式形状
（`<列> ± <字面量>`）的算子**反向夹**进生成列类型的范围；材料化器读不懂的表达式（如 `a * b`）则由
`ConformancePopulator.valueComputedByTheEngine` 记为 note——只对 SQLSTATE 类别 `22` 且消息点名了生成列的
拒绝生效。

### 14.3 引用动作探针：越界的新键值

探针给父键写"新值"时用的是固定步长且不做范围检查，于是出现了
`UPDATE t_4 SET a18 = 1000019 WHERE a18 = 16`（`a18 SMALLINT`）——MySQL 拒绝语句本身（`1264`），而 oracle
把这次拒绝读成"声明的动作没能发生"。修法：`DataModel.fitToType` / `integralBounds` 把新值夹进列的范围（列比
步长还窄时改走另一个方向），并且**不是约束类拒绝就不算发现**（SQLSTATE 类别非 `23`，例如 `1264`=22003、
`3811`/`3819` 这类 CHECK 拒绝），改为"探针无效"的 note。

### 14.4 复测：同一个数据库，两个构建

`runs/verify-mysql-fixes.sh` 把 13 条失败日志各自的 (plan, seed, slot) 变成 13 个单数据库进程
（`--num-tries 1 --input-slot <slot> --random-seed <seed>`，因此数据库会**自己跑完**而不会被超时截断），
每个用例一个独立命名空间（同一 schema 名会互相 DROP，第一次尝试就踩了这个坑），并带活性闸门（没连上库、
或一条语句都没执行，一律记 `INVALID` 而不是通过）。

| 运行 | 结果 |
| ---- | ---- |
| 修复前构建（`f3411b2`），要求每条日志都复现 | **13 / 13 复现**（oracle、kind 一致） |
| 修复后构建，要求 12 条误报消失 | **12 / 12 消失**（连"被挡住的下一个 oracle"都没出现） |
| 修复后构建，要求引擎缺陷仍在 | **仍然报出**：`constraint-enforcement` / `CONSTRAINT_NOT_ENFORCED` / `FOREIGN_KEY`，行 `t_3_id = 9223372036854775806`、`t_2_id = NULL`，索引 `uidx_t_4_0` —— 与 `bugs/mysql/005` 完全同一签名 |

复现出来的那条日志与实验当天的原始日志**逐行只差三处**：数据库名、新增的排序规则 note、以及 schema 里多出来
的 `COLLATE` 子句。同一容器上的实机自检（`org.ddlsmith.dbms.mysql.SelfCheck`）在修复后为
**419 assertions / 0 failure(s)**。原始输出见 `runs/verify-mysql-fixes-2h.txt`。

### 14.5 诚实边界

* 只回答"这 13 条没了吗、引擎缺陷还在吗"，**没有重测比率**：要重测 `CONSTRAINT_TOO_STRICT` 的可信度，得再跑
  一轮多小时（见 `runs/REPORT-MYSQL-2H.md` §5 第 4 条、§7.3）。
* 排序规则**不下推到复合方言**：差分运行的 `CommonDialect` 没有一个所有成员都接受的排序规则，因此保持各引擎
  默认；也就是说差分类别上这个误报类别依然存在（本轮未测）。
* 手册引用取自 Oracle 官方镜像 `docs.oracle.com/cd/E17952_01/mysql-8.0-en/…`：本次会话 `dev.mysql.com`
  持续返回 HTTP 403，9.x 没有镜像，因此 9.x 文档未查。
* MySQL 8.0.17 以下与 MariaDB 会因未知排序规则拒绝 `CREATE TABLE`（错误 1273）——这是本次改动唯一的兼容性
  代价，已在方言 javadoc 与 README §11 写明，覆写 `getStringCollation()` 即可回退。
