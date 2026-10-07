# ER → 关系模式映射审计（ER-to-relational mapping audit）

审计对象：`ERDiagram` → `RelationalSchema` 这一步，即
[StandardERToRelationalMapper](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java)
（2323 行）以及它的后处理
[SchemaRepair](../src/main/java/org/ddlsmith/mapping/impl/SchemaRepair.java)、
[SchemaInvariants](../src/main/java/org/ddlsmith/mapping/impl/SchemaInvariants.java)。

本文只回答一个问题：**给定任意一张 ER/EER 图，这个 mapper 是否把它完整、正确地变成了关系模式。**
（图生成、DDL 方言适配、oracle 不在范围内，只在必要处引用。）

---

## 0. 结论

**结构上：完整、健壮。** 1500 次真实流水线运行 + 21 段手工构造场景中 `SchemaInvariants.validate` 零违例；
mapper 从不抛异常；
每个 ER 属性要么落成列、要么在 `MappingTrace` 的 drop ledger 里有理由。经典算法的主干规则
（1:N 三种策略、M:N 两种、n 元主键裁剪、递归、弱实体、多值/复合/派生属性、三种 ISA 策略、类别、聚合）
都实现正确，我逐条验证过（见 §3）。

**语义上：有 3 个高频缺陷和若干中频缺陷，都在 `1:1 MERGE_TABLES` 与类别/ISA 的边角上。**
最严重的一条是：**合并两张实体表时，被合并表的全部外键、CHECK、索引被静默丢弃**；
其次是：**只要 1:1 的任一端是部分参与，合并后的表就强制该端实体必须存在**——
这已经不是那张 ER 图的模式了。第三条：两个实体本来就同表时，1:1 关系**完全没有被映射**。

最严重的三条（都与 `1:1 MERGE_TABLES` 有关）不在 [ER-GAPS.md](ER-GAPS.md) 的清单里——那份清单关注的是
"生成器能不能造出某构造"，本文关注的是"mapper 拿到构造之后做得对不对"；ER-GAPS 的 B9/B10/B11
则由本文独立复现并确认（见 §2.8–§2.10）。

---

## 1. 审计方法与证据

| 手段 | 说明 |
| --- | --- |
| 逐行阅读 | `StandardERToRelationalMapper` 全文 2323 行，以及 `SchemaInvariants` / `SchemaRepair` / `MappingTrace` / `common.schema.*` |
| 手工构造 ER 图探针 | [docs/er_features/probes/ErMappingShapeProbe.java](er_features/probes/ErMappingShapeProbe.java) —— 19 个编号场景（21 段输出），覆盖随机流水线造不出来的组合，打印模式并把合并表渲染成 H2/SQLite/DuckDB 的 DDL |
| 真实分布计数探针 | [docs/er_features/probes/ErMergeProbe.java](er_features/probes/ErMergeProbe.java) —— 按 `Pipeline.generate` 的顺序跑真实流水线，统计合并带来的丢失 |
| 端到端执行 | 把合并表的 DDL 真的在 H2 / SQLite / DuckDB 上执行（见 §2.11） |
| 交叉核对 | 与 [ER-MODEL.md](ER-MODEL.md)、[ER-GAPS.md](ER-GAPS.md)、[ER-EER-FEATURE-CATALOGUE.md](ER-EER-FEATURE-CATALOGUE.md) 的既有结论对齐，独立复现 B9/B10/B11 |
| 现有检查的覆盖 | `mvn test -Dtest=MappingTest` 28 个用例全部通过；同一路径下的 `SelfCheck` 跑了 55212 次映射、22228 条断言、0 违例 |

现有测试与 `SelfCheck` 之所以漏掉本文的缺陷，是因为它们断言的是**结构**（不变式、trace 账本、
策略是否被记录），而不是**语义**（生成的模式是否仍然只允许那张 ER 图允许的实例）。
本文的每条发现都需要把 ER 输入与产出的约束集对照着读才能看出来。

复现命令（JDK 17）：

```powershell
E:\Java\jdk-17\bin\javac.exe -nowarn -encoding UTF-8 -cp "target\classes" -d target\probe docs\er_features\probes\ErMappingShapeProbe.java docs\er_features\probes\ErMergeProbe.java
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" ErMappingShapeProbe
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" ErMergeProbe 800
```

> 注意：`MappingOptions.random()` 必须在图/ER 生成**之后**抽取，才与 `Pipeline.generate` 一致。
> 若在 `Randomly.setSeed` 之后立刻抽，连续种子的第一个 `java.util.Random` 取值高度相关，
> 会得到"某种 1:1 策略占 70%"这类假象（我第一版探针就踩了这个坑，已修正）。

---

## 2. 缺陷清单

可达性用词：**高频** = 随机流水线经常出现；**常见** = 需要特定选项组合；**罕见** = 需要巧合；
**潜在** = 当前生成器造不出来，但手写/其它前端构造的 ER 图会命中（mapper 声称"任何 ER 图都不抛异常"）。
每条给出可复现的探针场景编号。

### 2.1 高：`MERGE_TABLES` 丢弃被合并表的外键与 CHECK

* 位置：[`attemptMerge`](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L1027-L1106)
  只搬走了列（L1054-1062）和 UNIQUE 约束（L1064-1070），随后 `schema.removeTable(...)`（L1071）。
  被删表的 `foreignKeys`、`checkConstraints`、`indexes` **没有任何代码处理**。
* 现象（探针场景 2、3）：被合并的是 ISA 子类表时，继承键列 `id_2`（`derivedFrom=isa:E1`）留在表里，
  但指向 `t_1` 的外键没了；被合并的是类别表时，两条成员外键和"至少属于一个超类"的 CHECK 一起消失。
* 频率（`ErMergeProbe 800`）：14 条类别外键 + 5 条联合 CHECK + 3 条 ISA 外键被吞；
  3000 次运行的口径是 61 + 22 + 8 = 91 条。合并本身出现在约 20% 的运行里。
* 加重情节：trace 只写了一句 "t_2 merged into t_3"，**没有记录任何约束丢失**，
  违反了项目自己"任何丢失都必须在 trace 里说明理由"的约定（见 `MappingTrace.recordDroppedAttribute` 的 javadoc）。
* 影响：模式比 ER 图弱得多，而且是静默的——oracle 只会去检查"声明了的东西"，不会发现少声明的约束。

### 2.2 高：`MERGE_TABLES` 在部分参与下过度约束

* 位置：合并的守卫只有 `first.weak || second.weak || isShared(...) || isReferenced(...)`
  （[L1046](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L1046)），
  **完全没有看参与约束**。而被合并表的代理键列一定是 NOT NULL
  （`addSurrogate` L1969 `setNullable(false)`；`setPrimaryKey` L2077-2082 强制 PK 列 NOT NULL），
  自然键列同理。
* 现象（探针场景 4）：两端都是 `(0,1)` 的 1:1，合并后 `t_1` 是
  `(id NOT NULL, a NOT NULL, id_2 NOT NULL, b NOT NULL)`——一行必须同时含 E1 和 E2，
  于是"没有 E2 的 E1"无法表示。
* 频率：800 次运行里 174 张合并表，其中 **168 次合并的 1:1 至少一端是 PARTIAL，
  且 168 次都强制了被合并端存在**（3000 次口径 564/577）。
* 经典算法（mapper 的 javadoc 自称实现的就是 Elmasri/Navathe 算法）对 1:1 的规则是：
  两端都 total → 合并；一端 total → 外键放 total 端并 NOT NULL；都不 total → 外键任一端、可空。
  所以 `MERGE_TABLES` 应当以"两端都 total"为前置条件，否则应回落到 `FK_ON_TOTAL_SIDE`。

### 2.3 高：两个实体本来就同表时，1:1 关系被映射成"空操作"

* 位置：`attemptMerge` 的 `first.table == second.table` 分支
  （[L1036-1045](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L1036-L1045)）
  只记了一条 trace 就返回；随后 `mapRelationship` 看到 `mergedRelationships` 命中直接 return
  （[L1182-1185](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L1182-L1185)）。
  于是既没有外键、也没有 UNIQUE——而 `mapOneToOne` 的其它策略会给出自引用外键 + UNIQUE（L1276-1287）。
* 现象（探针场景 5）：单表 ISA 层次里两个子类之间的 1:1，`t_1` 上没有任何列或约束表示 R1，
  trace 只说 "both entities already share table t_1; nothing to merge"。
* 频率：800 次运行 9 次（同表来源：单表 ISA 层次、之前的合并结果）。
* 影响：关系及其基数（1:1 的唯一性）彻底丢失，且提示语让人以为"无需处理"。

### 2.4 中：类别（union type）成员没有唯一性

* 位置：[`mapCategory`](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L713-L770)
  给每个超类加一条可空外键（L732-743），再补一个代理主键（L745-747），但**没有对成员列加 UNIQUE**。
* 现象（探针场景 7）：`t_x` 的 PK 是 `id`，`t_1_id` / `t_2_id` 只是可空列，
  同一个 E1 实例可以被两行 `t_x` 引用——同一个类别实体被表示两次。
* 正确做法：类别实例的身份来自它所引用的那个超类实例，因此每个成员外键列（或在互斥超类前提下的一对列）
  应当唯一。
* 频率：常见（类别是随机图里最常见的 EER 构造之一，每次都有这个洞）。

### 2.5 中：超类键是复合键时，类别的"至少一个成员"CHECK 语义错误

* 位置：L748-760 把**所有**成员列拼成 `c1 IS NOT NULL OR c2 IS NOT NULL OR ...`。
* 现象（探针场景 8）：超类 E1 的键是 `(a,b)` 时得到
  `CHECK t_1_a IS NOT NULL OR t_1_b IS NOT NULL OR t_2_x IS NOT NULL`。
  一行只要 `t_1_a=5, t_1_b=NULL` 就通过检查；而 SQL 的复合外键默认 MATCH SIMPLE，
  只要有一列为 NULL 就**完全不检查**——于是这一行既不满足联合成员语义，也不指向任何超类实例。
* 正确写法：`(t_1_a IS NOT NULL AND t_1_b IS NOT NULL) OR (t_2_x IS NOT NULL)`。
* 频率：`keyStrategy=NATURAL_KEY` 且超类有复合自然键时（生成器给"至少两个强制槽位"的实体 30% 概率造复合键）。

### 2.6 中：`TABLE_PER_CLASS` 下共享子类的主键只覆盖一个父类

* 位置：[`mapIsaTablePerClass` L517](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L517)
  `info.pkColumns = applyKeyStrategy(table, primaryRoot)`——只取 `primaryRoot` 的键。
  而 `allAncestors`（L507-511）确实把两个父类的属性都放进了子类表。
* 现象（探针场景 11）：E3 是 E1、E2 的共享子类时，`t_3` 的 PK 是 `[a]`，
  E2 的键列 `b` 只是 NOT NULL 列、无 UNIQUE——两行 `t_3` 可以指向同一个 E2 实例。
* 影响：共享子类的身份不唯一。`TABLE_PER_SUBCLASS_WITH_FK` 已经正确处理了这点（父键并集做 PK，L566-594），
  `TABLE_PER_CLASS` 没有。

### 2.7 中：`TABLE_PER_CLASS` 用代理键时，层次里的身份不共享

* 位置：`applyKeyStrategy`（[L2118-2143](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L2118-L2143)）
  为每个表独立生成代理键。
* 现象（探针场景 14b）：`t_1`（根）和 `t_2`（子类）各有自己的 `id`（两张表各有一个独立的代理键列），
  同一个实例在两表里的键值毫无关系，也没有外键相连——层次只存在于名字上，无法 join。
  `NATURAL_KEY` 时两表键值一致，所以这是两个代理键策略特有的问题。
* 备注：文档只声明"每个策略都物化全部继承属性"，对身份只字未提；这属于未声明的语义弱化。

### 2.8 中：弱实体同时是 ISA 子类时，owner 外键静默丢失

* 位置：`mapIsaTablePerClass`（L494-533）与 `mapIsaWithForeignKey`（L535-607）**从不读 `isWeak()`**；
  只有 `mapIsaSingleTable` 会写一条 note（L460-463）。
* 现象（探针场景 10）：E2 既是 E1 的子类又是 E3 的弱实体时，
  `TABLE_PER_SUBCLASS_WITH_FK` 下 `t_2` 只有指向 `t_1` 的外键，**没有指向 `t_3`（owner）的外键**，
  部分键 `k` 变成普通 NOT NULL 列，**并且没有任何提示**。
* 这与 ER-GAPS 的 B9 一致（此处独立复现，并确认"无提示"）。
* 可达性：潜在（当前生成器规定弱实体不参与 ISA 链接）。

### 2.9 中：弱实体同时是类别时，weakness 静默丢失

* 位置：`mapEntityByName` 的分支顺序是 aggregation → **category** → weak → strong
  （[L688-696](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L688-L696)），
  而 `mapCategory` 完全不看 `isWeak()` / `ownerEntity` / `ownerRelationship` / 部分键。
* 现象（探针场景 18）：`W` 被当成普通类别映射——`t_w` 里没有 owner 外键，
  部分键 `k` 退化成普通 NOT NULL 列，trace 里**一个字都没有**提到 weakness 丢了。
* 可达性：潜在（B10 的确认）。

### 2.10 中：`ownerEntity` 有、`ownerRelationship` 没有时，识别关系被映射两次

* 位置：`mapWeakEntity` 建好 owner 外键后，**只在 `ownerRelationship != null` 时**才登记
  `handledIdentifying`（[L939-942](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L939-L942)）；
  `mapRelationship` 因此认为这条识别关系还没处理（L1186-1200），又按普通 1:N 映射一遍。
* 现象（探针场景 6）：`t_2` 上出现两条指向 `t_1` 的外键 `t_1_id`（在 PK 里）和 `t_1_id_2`（NOT NULL）。
* 可达性：潜在（生成器总是同时设置两个字段）；ER-GAPS B11 的确认。

### 2.11 中：合并后的表会出现两个 identity 列

* 位置：合并直接搬列（L1054-1062），被合并表的代理键保留了 `isIdentity`。
* 现象（探针场景 1）：`t_1(id IDENTITY, name, id_2 IDENTITY, label)`。
* 端到端结果（把 DDL 真跑一遍）：
  * **H2 接受**（`GENERATED BY DEFAULT AS IDENTITY` 可以出现两次，执行成功）；
  * **SQLite** 由 `SchemaAdapter.enforceIdentityRestrictions`
    （[L642-670](../src/main/java/org/ddlsmith/ddl/gen/SchemaAdapter.java#L642-L670)）摘掉第二个 identity，
    并有 adapt-note；MySQL/MariaDB 同理（`EngineTraits.identityRequiresKeyColumn` L102-112）；
  * **DuckDB** 本来就不支持 identity，两个都被摘掉。
* 结论：目前不会造成误报，但 `SchemaAdapter` 自己的注释（L637-640）已经承认"这是 mapper 合并产生的"，
  属于 mapper 应该修掉、而不是下游兜住的产物。

### 2.12 低：结构性链接被当成 1:1 合并候选，且拒绝理由误导

* 位置：`mergeOneToOneEntities` 只排除 `isGeneralization()` 和递归
  （[L1017](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L1017)），
  没有排除 `isCategoryLink()` / `isAggregationLink()`——尽管 `ERRelationship.isStructuralLink()`（L123）就是干这个的，
  而且生成器自己一直在用它。
* 现象（探针场景 3 的 note）：类别成员链接 RC1/RC2 被当成 1:1 去合并，失败提示写成
  "cannot be merged safely (weak entity or shared/referenced table)"，与真实原因无关。
* 目前实际被 `isReferenced` 挡住（类别表已经引用了超类表），所以只是提示误导 + 潜在风险：
  一旦某条成员外键因故没建出来，超类表就可能被合并进类别表。

### 2.13 低：trace 在"一个属性对应多张表的多列"时有歧义

* 位置：`MappingTrace.recordAttribute` 用 `HashMap<attribute, column>`（[L83-85](../src/main/java/org/ddlsmith/mapping/MappingTrace.java#L83-L85)），
  后写覆盖先写。
* 现象（代码层）：`TABLE_PER_CLASS` 下同一个逻辑属性同时存在于根表与子类表
  （探针场景 14b 的 `t_1.a` 与 `t_2.a`），而 `recordAttribute` 是 `put`，后写覆盖先写，
  trace 只能记住其中一个（先建子类表、后建根表，于是最终留下的是根表那一列）。
* 影响：只影响可读性与下游对 trace 的解读，`SelfCheck` 的属性账本仍然通过。

### 2.14 低：类别+ISA 子类时 trace 的说明与事实不符

* 位置：`mapRelationship` 对 `isCategoryLink()` 固定写
  "category membership link ... is represented by the mapping of the category itself"（L1165-1171）。
  但当该类别实体同时是 ISA 子类时，`mapIsaHierarchies` 先跑并把它按 ISA 策略映射，
  类别分支永远不会执行——成员关系根本没有被表示，trace 却声称已表示。
* 现象（探针场景 19）：`X` 被并进 `t_4`（单表 ISA），`t_1`/`t_2` 上没有任何成员列或外键，
  而 trace 写着 "category membership link RC1 is represented by the mapping of the category itself"。
* 可达性：潜在；对"手写 ER 图"这种输入是**假信息**，比沉默更糟。

---

## 3. 已逐条核对正确的部分

| 构造 | 规则 | 结论 |
| --- | --- | --- |
| 强实体 | 自然键 / 代理键 / 代理键+自然键唯一 | 正确；`id` 属性名冲突有专门处理（L846-855） |
| 弱实体 | owner 键 + 部分键做 PK；或代理键 + (owner,部分键) 唯一；owner 外键 CASCADE | 正确（场景 6 的 `t_1_id`+`k` 复合 PK；代理键变体由 `MappingTest.weakEntitySurrogateKey` 覆盖） |
| 弱实体链 | 递归解析 owner，深度上限 16 | 正确（`MappingTest.weakEntityChain`） |
| 识别关系 | 不重复映射为关联；其属性落在弱实体表且不进键 | 正确（正常路径） |
| 1:N | 多端外键 / 一端外键+UNIQUE / 连接表(多端唯一) | 正确；NOT NULL 跟随"接收键那一端"的参与约束（L1317、L1327） |
| M:N | 连接表（双外键 PK）/ 代理键+双外键唯一 | 正确 |
| n 元 | 连接表 + 主键裁剪规则 | 正确：`(1,N,N)` → PK 取两个 MANY 端外键；`(1,1,N)` → 三列全取（探针场景 12） |
| 递归 | 1:1（外键+UNIQUE）、1:N、M:N；角色名做列前缀 | 正确（场景 15：`e1_dst_id` + UNIQUE + 自引用外键） |
| 单参与者关系 | 自引用外键 | 代码层核对正确（生成器造不出来，`mapUnary` 是死代码） |
| 多值属性 | 独立表两种策略；复合多值展平为多值列；owner 外键 CASCADE；单表 ISA 下引用共享表主键 | 正确（场景 14a/14b） |
| 复合属性 | 递归展平，深度保护 8 | 正确 |
| 派生属性 | OMIT / MATERIALIZE / GENERATED_COLUMN；作为键时强制物化 | 正确 |
| ISA 三策略 | 单表(判别列+CHECK)、每类一表、子类表+FK(父键并集做 PK) | 正确（含共享子类的并集 PK，L566-594） |
| 单表 ISA 完备性 | total 时判别值不含根，partial 时含根 | 正确（场景 14a 的 `CHECK dtype IN ('E2','E1')` 就是 partial 的形态；total 的形态由 `MappingTest.specializationCompleteness` 覆盖） |
| 聚合 | 聚合实体=内层 M:N 连接表；外层 1:N 的外键落在连接表；外层引用连接表 PK | 正确（场景 13） |
| 结构性链接 | ISA/类别/聚合链接不作为关联映射 | 正确（且属性丢失都有 note） |
| 结构性不变式 | `SchemaInvariants` 20 类检查 + `SchemaRepair` 兜底 | 1500 次随机运行 + 19 个手工场景 0 违例 |
| 生成列表达式 | 生成的列表达式是否引用了同表不存在的列 | **已核查为干净**：`ErMergeProbe 800` 里 177 个生成列、0 个悬空引用。原因是 `markDerivedAttributes` 在 ISA 属性裁剪**之后**运行（`DefaultGraphToERMapper` L146-147），被引用属性一定是该实体保留的属性；且属性名 `a<全局槽位号>` 全局唯一，合并/单表都不会改名 |
| 列改名后的表达式 | 保留字改名是否同步改写 CHECK / 生成列表达式 | 正确（`SchemaAdapter` L424-427） |

---

## 4. "任意 ER 图"的边界：模型本身表达不了的东西

这些不是 mapper 的实现错误，而是 `ermodel` 的数据模型决定的边界——它们决定了"任意 ER 图"到底能有多任意：

1. **没有候选键/次要键**：`ERAttribute` 只有 `primaryKey` 一个布尔位。ER 图上第二条下划线（"另一个候选键"）
   无法表达，也就永远不会变成 `UNIQUE`。ER-GAPS A17。
2. **(min,max) 只有 min∈{0,1}、max∈{1,N}**：`(2,5)`、恰好一个（total 类别）、至少一个（total 参与的另一侧）
   都不可表达。`Participation` 是二值的。
3. **参与约束只有"接收键的那一侧"能被强制**：TOTAL 只翻译成外键列的 NOT NULL。
   "每个 X 至少参与一次"（被引用侧 total、连接表参与者的 total）在标准 SQL 里无法声明——
   ER-MODEL §5.1 已承认，CATALOGUE §0.2 也指出 CHECK 是逐行的、`CREATE ASSERTION` 无人实现。
4. **specialization 的 disjoint/overlapping/completeness**：只有单表策略的判别列 CHECK 近似了完备性；
   overlapping 无法表达（mapper 会写 note）；两个 FK 策略什么都不表达、也什么都不说（见 §2 与探针场景 9）。
5. **关系上的多值属性**：没有表可以承载（需要以参与者组合为键），mapper 会丢弃并记入 ledger（ER-MODEL 已承认）。
6. **弱实体多 owner、类别 total/partial、谓词定义子类、有序关系、属性默认值、用户自定义域、实体簇**
   ——模型层面没有对应字段（ER-MODEL §1.6 已列）。
7. **一切以名字为键**：同名实体/关系无法表达（`mapRemainingEntities` L234-258 会给第二条同名实体记一条 drop）。

---

## 5. 建议的修复顺序

| 优先级 | 动作 | 位置 |
| --- | --- | --- |
| P0 | 合并时把被删表的 `foreignKeys` / `checkConstraints` / `indexes` 一并迁移到目标表（列名按 `renames` 改写），或干脆拒绝合并这类表 | `attemptMerge` L1054-1071 |
| P0 | 给合并加前置条件：两端参与都是 TOTAL 才允许合并，否则回落 `FK_ON_TOTAL_SIDE`（并在 trace 写明原因） | `attemptMerge` L1046 |
| P0 | 两个实体已同表时不要走"空操作"分支，改走 `mapOneToOne` 的自引用外键 + UNIQUE 路径 | L1036-1045 |
| P1 | 类别成员列加 UNIQUE（互斥超类时可用"恰好一个"的 CHECK 加强） | `mapCategory` L745-747 |
| P1 | 类别联合 CHECK 按超类分组：每个超类的复合键要整体 IS NOT NULL | `mapCategory` L748-760 |
| P1 | `TABLE_PER_CLASS` 共享子类的键取所有父类键的并集（与 FK 策略保持一致） | L517 |
| P1 | 弱实体 + ISA 子类：至少在两个 FK 策略里给出 note（能建 owner 外键更好） | L494-533 / L535-607 |
| P1 | 弱实体 + 类别：先判 weak 再判 category，或两者都处理并写 note | `mapEntityByName` L688-696 |
| P2 | `ownerEntity` 存在时也登记 `handledIdentifying`（识别关系不该映射两次） | L939-942 |
| P2 | 合并时清掉被合并列的 identity 标志（或保留但改名语义化） | L1054-1062 |
| P2 | 合并候选排除 `isStructuralLink()`；提示语按真实原因区分 | L1017 / L1032-1049 |
| P3 | trace 支持"一个属性 → 多张表的列"，或明确记录主表 | `MappingTrace` L83-85 |
| P3 | `TABLE_PER_CLASS` 用代理键时，子类表的键应与父类共享（否则层次无法关联） | `applyKeyStrategy` L2118-2143 |

---

## 6. 与既有文档的关系

* [ER-GAPS.md](ER-GAPS.md) 的 A 系列（可达性）与 C 系列（文档与实现不符）本次未重复审计；
  本文补充的是"mapper 语义正确性"这一类，按严重度排序（见 §2）。
* 独立复现并确认了 B9（弱实体+ISA 子类丢 owner 外键）、B10（弱实体+类别丢 weakness）、B11（重复外键）。
* ER-MODEL 已承认的开放项"被合并实体的键失去唯一性"本次确认仍然存在（探针场景 17 的自然键版本）：
  `MERGE_TABLES` + `NATURAL_KEY` 时 `t_1` 只有 `PK [code]`，E2 的键列 `number` 只是 NOT NULL 列，
  没有 UNIQUE——同一个 E2 可以在表里出现两次。
