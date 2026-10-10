#!/usr/bin/env python3
"""What the conformance oracle actually puts in, and what it actually asks.

    python3 analyze-oracle-strength.py <reach-run-dir>     e.g. ~/workspace/ddlsmith-2h/preflight/reach

Reads the --log-each-select statement logs (one per database) and reports:

  * rows per table the oracle inserted (how much multiplicity any single check can ever see);
  * how much of the built schema it manages to populate, and how many foreign keys it gets to check;
  * the question mix, family by family, and the probes it issued;
  * per-database totals, so the spread is visible next to the averages.

The statement log is written in execution order, and the oracle's own order is fixed: the tables are
emptied and the plan is inserted, then every question is asked, and only then are the probes issued (a
probe inserts a row, and a row that is not part of the instance must not be visible to a question about
it). The split below uses that: everything after the first SELECT is probe traffic — an INSERT or UPDATE
is a probe, a DELETE is a probe being undone.
"""
import glob
import os
import re
import statistics
import sys
from collections import Counter


def classify_select(sql):
    """The families of SemanticQueryBuilder, recognised by the shape of the statement."""
    s = sql.lower()
    joins = s.count(" join ")
    if joins >= 2 or (joins == 1 and " join " in s and re.search(r"\bfrom\s+\S+\s+\w+\s+join\s+\S+\s+\w+\s+join", s)):
        return "two-hop path"
    if "order by" in s and "limit" in s:
        return "ordering window"
    if "min(" in s or "max(" in s:
        return "minimum and maximum"
    if "count(distinct" in s:
        return "distinct values"
    if "count(" in s and "group by" in s:
        return "cardinality (children per parent)"
    if "count(" in s and "," in s.split(" from ")[0]:
        return "NULL counts (COUNT(*), COUNT(col))"
    if "count(" in s:
        return "row count"
    if "left join" in s:
        return "dangling (must be empty)"
    if joins == 1:
        return "join (pairs the graph holds)"
    if re.search(r"\bfrom\s+v_", s) or "ddlsmith_unfolded" in s:
        return "view unfolding"
    return "plain table read"


def main(root):
    files = sorted(glob.glob(os.path.join(root, "logs", "*", "*-cur.log")))
    if not files:
        print("no *-cur.log under %s/logs/*" % root)
        return 2

    rows_per_table = []
    tables_per_db = []
    schema_tables = []
    fk_per_db = []
    multi_per_db = []
    mix = Counter()
    per_db = []
    probes_total = 0

    for path in files:
        inserts = Counter()
        selects = Counter()
        created = set()
        fk_seen = 0
        probes = 0
        cleanups = 0
        seen_a_select = False
        for line in open(path, encoding="utf-8", errors="replace"):
            s = line.strip()
            low = s.lower()
            fk_seen += low.count("foreign key")
            if low.startswith("select"):
                seen_a_select = True
                selects[classify_select(s)] += 1
            elif low.startswith("insert into"):
                if seen_a_select:
                    probes += 1
                    continue
                t = re.match(r"insert into\s+([`\w.]+)", s, re.I)
                if t:
                    inserts[t.group(1).split(".")[-1].strip("`")] += 1
            elif low.startswith("update"):
                if seen_a_select:
                    probes += 1
            elif low.startswith("delete") and seen_a_select:
                cleanups += 1
            elif low.startswith("create table"):
                t = re.match(r"create table\s+(?:if not exists\s+)?([`\w.]+)", s, re.I)
                if t:
                    created.add(t.group(1).split(".")[-1].strip("`"))
        if not inserts and not selects:
            continue
        counts = sorted(inserts.values(), reverse=True)
        rows_per_table.extend(counts)
        tables_per_db.append(len(inserts))
        schema_tables.append(len(created))
        fk_per_db.append(fk_seen)
        multi_per_db.append(sum(1 for c in counts if c > 1))
        mix.update(selects)
        probes_total += probes
        questions = sum(selects.values())
        per_db.append((os.path.basename(path).replace("-cur.log", ""), sum(counts), len(inserts),
                       questions, probes, cleanups))

    fk_total = sum(fk_per_db)
    fk_questions = (mix["join (pairs the graph holds)"] + mix["cardinality (children per parent)"]
                    + mix["dangling (must be empty)"])
    print("databases analysed            : %d" % len(per_db))
    print("rows the oracle inserted      : %.1f per database" % statistics.mean([r[1] for r in per_db]))
    print("tables in the built schema    : %.1f per database, of which populated: %.1f (%.0f%%)" %
          (statistics.mean(schema_tables), statistics.mean(tables_per_db),
           100.0 * sum(tables_per_db) / sum(schema_tables)))
    print("foreign keys in the schema    : %.1f per database; foreign-key questions: %d, i.e. %.1f foreign"
          " keys checked per database (%.0f%% of them)" %
          (statistics.mean(fk_per_db), fk_questions, fk_questions / 3.0 / len(per_db),
           100.0 * (fk_questions / 3.0) / fk_total if fk_total else 0))
    print("questions per database        : %.1f (min %d, median %d, max %d)" %
          (statistics.mean([r[3] for r in per_db]), min(r[3] for r in per_db),
           int(statistics.median([r[3] for r in per_db])), max(r[3] for r in per_db)))
    print("probes per database           : %.1f (min %d, max %d); undone again: %.1f" %
          (statistics.mean([r[4] for r in per_db]), min(r[4] for r in per_db), max(r[4] for r in per_db),
           statistics.mean([r[5] for r in per_db])))
    print("facts asserted per database   : %.1f (questions + probes)" %
          statistics.mean([r[3] + r[4] for r in per_db]))
    print("tables with more than one row : %.1f per database (max %d in one database)" %
          (statistics.mean(multi_per_db), max(multi_per_db)))
    print()
    print("question mix over all databases:")
    total_q = sum(mix.values())
    for k, v in mix.most_common():
        print("  %-34s %5d  (%2.0f%%)" % (k, v, 100.0 * v / total_q))
    print("  %-34s %5d" % ("TOTAL questions", total_q))
    print("  %-34s %5d" % ("probes (negative assertions)", probes_total))
    print()
    print("per database: name / rows / tables / questions / probes / undos")
    for row in sorted(per_db, key=lambda r: r[3] + r[4]):
        print("  %-12s %4d %4d %4d %4d %4d" % row)
    return 0


if __name__ == "__main__":
    sys.exit(main(os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else ".")))
