#!/usr/bin/env python3
"""Summarise a two-hour MySQL conformance campaign from its own artefacts.

    python3 summarize-mysql-2h.py <campaign-root>

Reads, and prints:

  * the budget actually spent (progress.tsv) and what it cost the host (resources.tsv);
  * per-category and total databases / statements / failures (every round's summary.tsv);
  * the failure logs grouped into classes by (oracle, kind, engine message shape), because the same
    defect shows up in dozens of databases under different identifiers and the raw count is not the
    number that matters.

Nothing is written; this only reads.
"""
import os
import re
import sys
from collections import Counter, defaultdict

CATEGORIES = ["conf-random", "conf-tree", "conf-smallworld", "conf-hybrid",
              "all-preferential", "all-deep", "baseline-default"]


def read_tsv(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        rows = [line.rstrip("\n").split("\t") for line in fh]
    if not rows:
        return [], []
    return rows[0], rows[1:]


def main(root):
    print("=" * 100)
    print("progress (progress.tsv)")
    print("=" * 100)
    head, rows = read_tsv(os.path.join(root, "progress.tsv"))
    spent = 0
    for row in rows:
        if len(row) < 6:
            continue
        if row[5] == "done":
            spent += int(row[2] or 0)
        print("  round %-2s %-8s budget=%-5s started=%-25s finished=%-25s %s"
              % (row[0], row[1], row[2], row[3], row[4], row[5]))
    print("  round budgets actually run: %d s (%.1f min)" % (spent, spent / 60.0))

    print()
    print("=" * 100)
    print("per category, across every round (each round's <category>/summary.tsv)")
    print("=" * 100)
    print("  %-18s %6s %10s %12s %9s %7s" % ("category", "chunks", "databases", "statements", "failures", "empty"))
    totals = defaultdict(int)
    rounds = sorted(d for d in os.listdir(root) if re.fullmatch(r"c2h\d+[a-z]?", d))
    for cat in CATEGORIES:
        chunks = db = stmt = fail = empty = 0
        for rnd in rounds:
            path = os.path.join(root, rnd, cat, "summary.tsv")
            if not os.path.exists(path):
                continue
            _, srows = read_tsv(path)
            for row in srows:
                if not row or not row[0].isdigit():
                    continue
                chunks += 1
                db += int(row[6] or 0)
                stmt += int(row[7] or 0)
                fail += int(row[8] or 0)
                empty += 0 if row[12] == "ok" else 1
        if chunks == 0:
            continue
        print("  %-18s %6d %10d %12d %9d %7d" % (cat, chunks, db, stmt, fail, empty))
        totals["chunks"] += chunks
        totals["databases"] += db
        totals["statements"] += stmt
        totals["failures"] += fail
        totals["empty"] += empty
    print("  %-18s %6d %10d %12d %9d %7d" % ("TOTAL", totals["chunks"], totals["databases"],
                                             totals["statements"], totals["failures"], totals["empty"]))
    print("  (the statements column is the tool's own figure, printed rounded to thousands above 1000,")
    print("   so it is a lower bound; the databases and failures columns are exact)")

    print()
    print("=" * 100)
    print("failures grouped into classes")
    print("=" * 100)
    classes = defaultdict(list)
    for rnd in rounds:
        for cat in CATEGORIES:
            logdir = os.path.join(root, rnd, cat, "logs", "mysql")
            if not os.path.isdir(logdir):
                continue
            for name in sorted(os.listdir(logdir)):
                if "-failure-" not in name:
                    continue
                path = os.path.join(logdir, name)
                text = open(path, encoding="utf-8", errors="replace").read()
                oracle = re.search(r"^oracle\s*:\s*(\S+)", text, re.M)
                kind = re.search(r"^kind\s*:\s*(\S+)", text, re.M)
                observed = re.search(r"^Observed\s*:\s*(.+)$", text, re.M)
                sql = re.search(r"^SQL\s*:\s*(.+)$", text, re.M)
                oracle = oracle.group(1) if oracle else "?"
                kind = kind.group(1) if kind else "?"
                obs = observed.group(1).strip() if observed else ""
                key = (oracle, kind, classify(obs))
                classes[key].append((rnd, cat, name, obs, sql.group(1).strip() if sql else ""))
    for (oracle, kind, cls), occ in sorted(classes.items(), key=lambda kv: -len(kv[1])):
        print()
        print("  %-22s %-24s occurrences=%d" % (oracle, kind, len(occ)))
        print("    class   : %s" % cls)
        print("    engine  : %s" % occ[0][3][:120])
        print("    example : %s" % occ[0][4][:120])
        print("    seen in : %s" % ", ".join(sorted({"%s/%s" % (r, c) for r, c, _, _, _ in occ})))
    print()
    print("  distinct classes: %d, over %d failure logs"
          % (len(classes), sum(len(v) for v in classes.values())))


    print()
    print("=" * 100)
    print("what it cost the host (resources.tsv)")
    print("=" * 100)
    head, rows = read_tsv(os.path.join(root, "resources.tsv"))
    samples = [r for r in rows if len(r) >= 17]
    if samples:
        def col(i):
            return [float(r[i]) for r in samples if r[i] not in ("", None)]
        minutes = len(samples)  # one sample a minute
        cpu = col(15)
        print("  samples                 : %d, one a minute" % len(samples))
        print("  DDLSmith slice memory   : peak %d MB of the 16384 MB cap (current at the end %d MB)"
              % (max(col(14)), col(13)[-1]))
        print("  DDLSmith slice CPU      : %.0f core-seconds over %d min = %.2f cores average, cap 4"
              % (max(cpu) / 1e6, minutes, max(cpu) / 1e6 / (minutes * 60)))
        print("  DDLSmith slice throttled: %d scheduling periods at the 400%% cap" % max(col(16)))
        print("  DDLSmith JVMs           : up to %d at once, %.0f MB RSS together at the peak"
              % (max(col(9)), max(col(10))))
        print("  MySQL container memory  : peak %d MB of its 16384 MB cap" % max(col(8)))
        print("  live namespaces         : peak %d (reaped between rounds)" % max(col(11)))
        print("  host load1              : max %.2f on 12 cores" % max(col(2)))
        print("  host CPU busy           : max %.1f%%" % max(col(3)))
        print("  host memory available   : min %d MB of %d MB" % (min(col(5)), max(col(4))))
        print("  disk free               : %s -> %s GB" % (col(7)[0], col(7)[-1]))


def classify(observed):
    """The engine message shape, with the generated identifiers removed."""
    text = observed.lower()
    if "duplicate entry" in text:
        return "MySQL refuses a second row that is a duplicate under its collation (1062)"
    if "out of range value" in text and "update" not in text:
        return "a value the oracle planned does not fit the column the engine stores it in (1264)"
    if "the update of the referenced key was rejected" in text:
        return "the referential-action UPDATE probe was itself rejected (out of range)"
    if "succeeded" in text:
        return "the engine stored a row the declared constraint forbids"
    if "cannot add or update a child row" in text:
        return "the engine rejected a dangling reference (correct)"
    return observed[:80] or "(no engine message)"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1])
