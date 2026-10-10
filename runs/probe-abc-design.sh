#!/bin/sh
# Design-verification probes for extending the graph-conformance oracle with A (negative probes),
# B (path queries) and C (aggregates / NULL / order / distinct).
#
# Runs INSIDE the experiment's MySQL container (26.7.0, mysql:latest, default SQL-engine foreign-key
# path). Every case creates its own scratch namespace and drops it. Each case prints what the engine
# actually answered, with the client's ERROR line (which carries the vendor code and the SQLSTATE).
M="mysql -uroot -proot --batch --skip-column-names"
N=0

begin() { N=$((N+1)); $M -e "DROP DATABASE IF EXISTS ddlsmith_d$N; CREATE DATABASE ddlsmith_d$N;" 2>/dev/null; DB=ddlsmith_d$N; }
say()  { printf '%-72s ' "$1"; }
run()  { err=$($M -e "$1" 2>&1 | grep -v '^mysql: \[Warning\]'); if [ -z "$err" ]; then printf 'ACCEPTED\n'; else printf '%s\n' "$err" | head -1; fi; }
done_all() { for i in $(seq 1 $N); do $M -e "DROP DATABASE IF EXISTS ddlsmith_d$i" 2>/dev/null; done; }

echo "server: $($M -e 'SELECT CONCAT(@@version, "  sql_mode=", @@sql_mode)' 2>/dev/null)"
echo
echo "== 1. error classification on INSERT (what a 'must reject' probe may count as enforcement) =="
begin; $M -e "CREATE TABLE $DB.p (k INT NOT NULL, PRIMARY KEY (k)); CREATE TABLE $DB.c (id INT NOT NULL, f1 INT, PRIMARY KEY (id), CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES $DB.p (k)); INSERT INTO $DB.p VALUES (1);" 2>/dev/null
say "1a dangling foreign key value"; run "INSERT INTO $DB.c VALUES (1, 999)"

begin; $M -e "CREATE TABLE $DB.t (id INT NOT NULL, a7 VARCHAR(100) COLLATE utf8mb4_0900_bin, PRIMARY KEY (id), UNIQUE (a7)); INSERT INTO $DB.t VALUES (1, 'x');" 2>/dev/null
say "1b duplicate unique value"; run "INSERT INTO $DB.t VALUES (2, 'x')"

begin; $M -e "CREATE TABLE $DB.t (id INT NOT NULL, n INT, PRIMARY KEY (id), CONSTRAINT ck CHECK (n < 3));" 2>/dev/null
say "1c check constraint violated"; run "INSERT INTO $DB.t VALUES (1, 5)"

begin; $M -e "CREATE TABLE $DB.t (id INT NOT NULL, a INT NOT NULL, PRIMARY KEY (id));" 2>/dev/null
say "1d explicit NULL into NOT NULL"; run "INSERT INTO $DB.t VALUES (1, NULL)"

begin; $M -e "CREATE TABLE $DB.t (id INT NOT NULL, s SMALLINT, PRIMARY KEY (id));" 2>/dev/null
say "1e out-of-range value (not an integrity error)"; run "INSERT INTO $DB.t VALUES (1, 40000)"

begin; $M -e "CREATE TABLE $DB.t (id INT NOT NULL, v CHAR(5), PRIMARY KEY (id));" 2>/dev/null
say "1f string truncation (not an integrity error)"; run "INSERT INTO $DB.t VALUES (1, 'abcdef')"

begin; $M -e "CREATE TABLE $DB.p (k1 INT NOT NULL, k2 INT NOT NULL, PRIMARY KEY (k1,k2)); CREATE TABLE $DB.c (id INT NOT NULL, f1 INT, f2 INT, PRIMARY KEY (id), CONSTRAINT c1 FOREIGN KEY (f1,f2) REFERENCES $DB.p (k1,k2));" 2>/dev/null
say "1g partial-NULL composite key (MATCH SIMPLE must accept)"; run "INSERT INTO $DB.c VALUES (1, 999, NULL)"

echo
echo "== 2. which index enforces a foreign key, and the NULL-neighbour shape (defect 005) =="
mk() { begin; $M -e "CREATE TABLE $DB.p (k INT NOT NULL, PRIMARY KEY (k)); CREATE TABLE $DB.c (id INT NOT NULL, f1 INT, other INT, PRIMARY KEY (id), CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES $DB.p (k)); INSERT INTO $DB.p VALUES (1);" 2>/dev/null; }
mk; say "2a no secondary index, other=NULL"; run "INSERT INTO $DB.c VALUES (1, 999, NULL)"
mk; $M -e "CREATE UNIQUE INDEX i ON $DB.c (f1, other);" 2>/dev/null; say "2b UNIQUE (f1, other), other=NULL"; run "INSERT INTO $DB.c VALUES (1, 999, NULL)"
mk; $M -e "CREATE INDEX i ON $DB.c (f1, other);" 2>/dev/null; say "2c INDEX (f1, other), other=NULL"; run "INSERT INTO $DB.c VALUES (1, 999, NULL)"
mk; $M -e "CREATE INDEX i1 ON $DB.c (f1); CREATE INDEX i2 ON $DB.c (f1, other);" 2>/dev/null; say "2d both (f1) and (f1,other), other=NULL"; run "INSERT INTO $DB.c VALUES (1, 999, NULL)"
mk; $M -e "CREATE INDEX i1 ON $DB.c (f1); CREATE INDEX i2 ON $DB.c (f1, other);" 2>/dev/null; say "2e both indexes, other=7"; run "INSERT INTO $DB.c VALUES (1, 999, 7)"
mk; $M -e "CREATE UNIQUE INDEX i ON $DB.c (f1, other, id);" 2>/dev/null; say "2f UNIQUE (f1, other, id), other=NULL"; run "INSERT INTO $DB.c VALUES (1, 999, NULL)"
mk; $M -e "CREATE INDEX i ON $DB.c (f1, other);" 2>/dev/null; $M -e "INSERT INTO $DB.c VALUES (1, 1, NULL);" 2>/dev/null
say "2g UPDATE of the key itself (row has other=NULL)"; run "UPDATE $DB.c SET f1 = 999 WHERE id = 1"

echo
echo "== 3. aggregate / NULL / ordering semantics, under the pinned collation utf8mb4_0900_bin =="
begin; $M -e "CREATE TABLE $DB.t (id INT NOT NULL, v VARCHAR(100) COLLATE utf8mb4_0900_bin, n INT, d DATE, PRIMARY KEY (id)); INSERT INTO $DB.t VALUES (1,'HF',10,'2024-01-01'),(2,'hf',NULL,'2024-02-02'),(3,'B',30,'2024-03-03'),(4,NULL,NULL,NULL),(5,NULL,50,'2024-05-05');" 2>/dev/null
say "3a COUNT(*), COUNT(v), COUNT(n), COUNT(DISTINCT v)"; $M -e "SELECT CONCAT('count*=',COUNT(*),' count(v)=',COUNT(v),' count(n)=',COUNT(n),' count(distinct v)=',COUNT(DISTINCT v)) FROM $DB.t" 2>/dev/null
say "3b SUM/MIN/MAX over all-NULL column n=NULL rows"; $M -e "SELECT CONCAT('sum=',COALESCE(SUM(n),'NULL'),' min=',COALESCE(MIN(n),'NULL'),' max=',COALESCE(MAX(n),'NULL')) FROM $DB.t WHERE id IN (2,4)" 2>/dev/null
say "3c SUM/MIN/MAX over empty selection"; $M -e "SELECT CONCAT('sum=',COALESCE(SUM(n),'NULL'),' min=',COALESCE(MIN(n),'NULL'),' max=',COALESCE(MAX(n),'NULL')) FROM $DB.t WHERE id < 0" 2>/dev/null
say "3d MIN/MAX on binary-collation text"; $M -e "SELECT CONCAT('min=',MIN(v),' max=',MAX(v)) FROM $DB.t WHERE v IS NOT NULL" 2>/dev/null
say "3e equality on binary-collation text"; $M -e "SELECT CONCAT('rows matching HF=',COUNT(*)) FROM $DB.t WHERE v = 'HF'" 2>/dev/null
say "3f ORDER BY code-point, LIMIT 2 OFFSET 1"; $M -e "SELECT GROUP_CONCAT(v ORDER BY v) FROM (SELECT v FROM $DB.t WHERE v IS NOT NULL ORDER BY v LIMIT 2 OFFSET 1) x" 2>/dev/null
say "3g NULL predicates: IS NULL / = NULL / IS NOT NULL"; $M -e "SELECT CONCAT('isnull=',SUM(v IS NULL),' eqnull=',SUM(v = NULL),' notnull=',SUM(v IS NOT NULL)) FROM $DB.t" 2>/dev/null
say "3h GROUP BY with an aggregate (ONLY_FULL_GROUP_BY shape)"; $M -e "SELECT CONCAT('groups=',COUNT(*)) FROM (SELECT n, COUNT(*) c FROM $DB.t GROUP BY n) x" 2>/dev/null
say "3i MIN over an empty table (one row, NULL)"; $M -e "CREATE TABLE $DB.e (n INT)" 2>/dev/null; $M -e "SELECT CONCAT('rows=',COUNT(*), ' value=',COALESCE(MIN(n),'NULL')) FROM (SELECT MIN(n) AS m FROM $DB.e) y JOIN $DB.e ON 1=0" 2>/dev/null || true; $M -e "SELECT CONCAT('min-is-null=', MIN(n) IS NULL) FROM $DB.e" 2>/dev/null

echo
echo "== 4. composed joins (the shapes the path queries would issue) =="
begin; $M -e "CREATE TABLE $DB.a (k INT NOT NULL, PRIMARY KEY (k)); CREATE TABLE $DB.b (id INT NOT NULL, ak INT NOT NULL, ck INT NOT NULL, PRIMARY KEY (id), FOREIGN KEY (ak) REFERENCES $DB.a (k), FOREIGN KEY (ck) REFERENCES $DB.c2 (k)); CREATE TABLE $DB.c2 (k INT NOT NULL, PRIMARY KEY (k)); INSERT INTO $DB.a VALUES (1),(2); INSERT INTO $DB.c2 VALUES (10),(20); INSERT INTO $DB.b VALUES (100,1,10),(101,2,20);" 2>/dev/null
say "4a two-hop join a-b-c2"; $M -e "SELECT CONCAT('pairs=',GROUP_CONCAT(a.k,'-',c2.k ORDER BY a.k)) FROM $DB.b JOIN $DB.a a ON $DB.b.ak=a.k JOIN $DB.c2 c2 ON $DB.b.ck=c2.k" 2>/dev/null || $M -e "SELECT CONCAT('pairs=',GROUP_CONCAT(a.k,'-',c2.k ORDER BY a.k)) FROM $DB.b JOIN $DB.a ON $DB.b.ak=$DB.a.k JOIN $DB.c2 ON $DB.b.ck=$DB.c2.k" 2>/dev/null
begin; $M -e "CREATE TABLE $DB.t (id INT NOT NULL, pid INT, v INT, PRIMARY KEY (id), FOREIGN KEY (pid) REFERENCES $DB.t (id)); INSERT INTO $DB.t VALUES (1,NULL,1),(2,1,2),(3,2,3),(4,2,4);" 2>/dev/null
say "4b recursive 2-hop self-join"; $M -e "SELECT CONCAT('walks=',GROUP_CONCAT(a.id,'-',c.id ORDER BY a.id, c.id)) FROM $DB.t a JOIN $DB.t b ON b.pid=a.id JOIN $DB.t c ON c.pid=b.id" 2>/dev/null
begin; $M -e "CREATE TABLE $DB.l (k INT NOT NULL, PRIMARY KEY (k)); CREATE TABLE $DB.r (k INT NOT NULL, PRIMARY KEY (k)); CREATE TABLE $DB.jt (id INT NOT NULL, lk INT NOT NULL, rk INT NOT NULL, PRIMARY KEY (id), UNIQUE (lk, rk), FOREIGN KEY (lk) REFERENCES $DB.l (k), FOREIGN KEY (rk) REFERENCES $DB.r (k)); INSERT INTO $DB.l VALUES (1),(2); INSERT INTO $DB.r VALUES (10),(20); INSERT INTO $DB.jt VALUES (100,1,10),(101,1,20),(102,2,20);" 2>/dev/null
say "4c M:N edge reconstruction through the join table"; $M -e "SELECT CONCAT('edges=',GROUP_CONCAT(l.k,'-',r.k ORDER BY l.k, r.k)) FROM $DB.jt JOIN $DB.l l ON $DB.jt.lk=l.k JOIN $DB.r r ON $DB.jt.rk=r.k" 2>/dev/null

echo
echo "== 5. 1:1 second child (unique foreign key) and probe cleanup =="
begin; $M -e "CREATE TABLE $DB.p (k INT NOT NULL, PRIMARY KEY (k)); CREATE TABLE $DB.c (id INT NOT NULL, f1 INT NOT NULL, other INT, PRIMARY KEY (id), UNIQUE (f1), CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES $DB.p (k)); INSERT INTO $DB.p VALUES (1); INSERT INTO $DB.c VALUES (1,1,7);" 2>/dev/null
say "5a second child for the same 1:1 parent"; run "INSERT INTO $DB.c VALUES (2,1,8)"
say "5b delete of an accepted violating row (probe cleanup)"; run "DELETE FROM $DB.c WHERE id = 2"

done_all
$M -e "SELECT CONCAT('scratch namespaces left: ', COUNT(*)) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'ddlsmith_d%'" 2>/dev/null
