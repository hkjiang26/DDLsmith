#!/usr/bin/env python3
"""Pick a stratified sample of failure logs for the innodb_native_foreign_keys A/B.

The A/B asks one question: is every disagreement the campaign recorded a defect of the SQL-engine
foreign-key path (which is where the filed defects 002/003/004/005 live, and which
innodb_native_foreign_keys=ON replaces), or is anything left over when that path is taken away?

So the sample is drawn per mechanism family, not uniformly: a family with 2 088 members and a family
with 1 member both have to be represented, or the A/B answers only for the loud one.

Writes a TSV: family, count, one path per line for the sample.
"""
import os
import random
import re
import sys
from collections import defaultdict

ROOT = sys.argv[1]
PER_FAMILY = int(sys.argv[2]) if len(sys.argv) > 2 else 8
OUT = sys.argv[3] if len(sys.argv) > 3 else "/tmp/ab-sample.tsv"

HEADER = re.compile(r"^database\s+:\s+(\S+)\s*$", re.M)
SEED_FOLLOWER = re.compile(r"^seed\s*:")
CHECK = re.compile(r"^check\s+:\s+(.*)$", re.M)
CONSTRAINT = re.compile(r"^Constraint\s+:\s+(.*)$", re.M)
FK = re.compile(r"^Foreign key\s+:\s+(.*)$", re.M)
ORACLE = re.compile(r"^oracle\s+:\s+(\S+)", re.M)


def outer_paren(line):
    i = line.find("(")
    if i < 0:
        return ""
    depth = 0
    for j in range(i, len(line)):
        if line[j] == "(":
            depth += 1
        elif line[j] == ")":
            depth -= 1
            if depth == 0:
                return line[i + 1:j].strip()
    return line[i + 1:].strip()


def blocks_of(text):
    lines = text.splitlines()
    starts = [i for i, l in enumerate(lines)
              if HEADER.match(l) and not (i + 1 < len(lines) and SEED_FOLLOWER.match(lines[i + 1]))]
    return ["\n".join(lines[s:(starts[n + 1] if n + 1 < len(starts) else len(lines))])
            for n, s in enumerate(starts)]


def family(block, oracle):
    if oracle == "referential-action":
        return "REFERENTIAL_ACTION"
    if oracle == "constraint-enforcement":
        return "CONSTRAINT_ENFORCEMENT"
    m = CHECK.search(block)
    if not m:
        return "GRAPH_CONFORMANCE_OTHER"
    r = outer_paren(m.group(1)).lower()
    if r.startswith("negative probe: a null beside the key"):
        return "NULL_BESIDE_KEY"
    if r.startswith("negative probe: a reference with no parent"):
        return "REFERENCE_WITH_NO_PARENT"
    if r.startswith("negative probe: the key moved by update"):
        return "KEY_MOVED_BY_UPDATE"
    return "GRAPH_CONFORMANCE_OTHER"


by_family = defaultdict(list)
for dirpath, _d, filenames in os.walk(ROOT):
    for fn in filenames:
        if "failure" not in fn or not fn.endswith(".log"):
            continue
        path = os.path.join(dirpath, fn)
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        om = ORACLE.search(text)
        oracle = om.group(1) if om else "?"
        fams = {family(b, oracle) for b in blocks_of(text)}
        for f in fams:
            by_family[f].append(path)

random.seed(20261011)
lines = []
for fam in sorted(by_family):
    paths = sorted(by_family[fam])
    # families small enough to test exhaustively are tested exhaustively
    picked = paths if len(paths) <= PER_FAMILY else random.sample(paths, PER_FAMILY)
    print("%-26s total=%-6d sampled=%d" % (fam, len(paths), len(picked)))
    for p in picked:
        lines.append("%s\t%s" % (fam, p))

with open(OUT, "w") as fh:
    fh.write("family\tlog\n")
    fh.write("\n".join(lines) + "\n")
print("written: %s (%d logs)" % (OUT, len(lines)))
