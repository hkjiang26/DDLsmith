#!/usr/bin/env python3
"""Sub-classify every failure log of the 12h campaign by the probe that produced it, block by block.

A failure log holds one or more "disagreement" blocks, each introduced by a `database : X` header and
separated by rules of `=`. The census in the run directory groups these by the *reporting* signature,
which lumps every graph-conformance disagreement into "no-difference-list"; this script goes back to
the logs and extracts the probe itself, including the nested parenthetical that names the index
(`a NULL beside the key (idx_t_4_0)`), which is the marker that separates the known foreign-key defect
from anything new.
"""
import os
import re
import sys
from collections import Counter, defaultdict

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser(
    "~/workspace/ddlsmith-12h/campaign/mysql-12h-conformance")

HEADER = re.compile(r"^database\s+:\s+(\S+)\s*$", re.M)
# The log preamble has a `database  : X` line (two spaces, aligned with `kind`/`oracle`/`seed`) which is
# NOT a block header - it is always followed by `seed`. A real block header is followed by the probe,
# either directly (`check`), or after a `group`/`kind`/`occurrences` preamble (`Constraint`,
# `Foreign key`). Discriminating on the `seed` follower is what separates them without also dropping
# the referential-action and constraint-enforcement blocks the way a probe-only rule did.
SEED_FOLLOWER = re.compile(r"^seed\s*:")
ORACLE = re.compile(r"^oracle\s+:\s+(\S+)", re.M)
CHECK = re.compile(r"^check\s+:\s+(.*)$", re.M)
CONSTRAINT = re.compile(r"^Constraint\s+:\s+(.*)$", re.M)
FK = re.compile(r"^Foreign key\s+:\s+(.*)$", re.M)
DECLARED = re.compile(r"^Declared\s+:\s+(.*)$", re.M)
ROW = re.compile(r"^Row\s+:\s+(.*)$", re.M)


def outer_paren(line):
    """Text of the first top-level parenthetical, nested parens included."""
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
    """Split a log into its disagreement blocks (each starts at a `database : X` header line)."""
    lines = text.splitlines()
    starts = [i for i, l in enumerate(lines)
              if HEADER.match(l) and not (i + 1 < len(lines) and SEED_FOLLOWER.match(lines[i + 1]))]
    out = []
    for n, s in enumerate(starts):
        # the block runs to the line before the next block header
        e = starts[n + 1] if n + 1 < len(starts) else len(lines)
        out.append("\n".join(lines[s:e]))
    return out


def classify(block, oracle):
    if oracle == "graph-conformance":
        m = CHECK.search(block)
        if not m:
            return "graph-conformance/?", "?"
        line = m.group(1).strip()
        head = line.split("(")[0].strip()
        cls = head.split(" of ")[0].strip() if " of " in head else head.split()[0]
        return "graph-conformance/" + cls, outer_paren(line) or "(none)"
    if oracle == "constraint-enforcement":
        m = CONSTRAINT.search(block)
        if not m:
            return "constraint-enforcement/?", "?"
        line = m.group(1).strip()
        return "constraint-enforcement/" + line.split()[0], outer_paren(line) or "(none)"
    if oracle == "referential-action":
        d = DECLARED.search(block)
        i = re.search(r"^Introspected:\s*(.*)$", block, re.M)
        return "referential-action", "%s | introspected: %s" % (
            d.group(1).strip() if d else "?", i.group(1).strip() if i else "?")
    return oracle or "?", "?"


def family_of(ref):
    """Collapse the per-index refinement into the mechanism family it belongs to."""
    r = ref.lower()
    if r.startswith("negative probe: a null beside the key"):
        return "NULL_BESIDE_KEY"
    if r.startswith("negative probe: a reference with no parent"):
        return "REFERENCE_WITH_NO_PARENT"
    if r.startswith("negative probe: the key moved by update"):
        return "KEY_MOVED_BY_UPDATE"
    if "cascade" in r:
        return "REFERENTIAL_ACTION_CASCADE"
    if r.startswith("(probe)"):
        return "CONSTRAINT_ENFORCEMENT"
    return "OTHER:" + ref[:60]


counts = Counter()
families = Counter()
family_logs = defaultdict(set)
refinements = defaultdict(Counter)
examples = {}
rep_dumped = set()
logs = 0
null_row = Counter()

for dirpath, _d, filenames in os.walk(ROOT):
    for fn in filenames:
        if "failure" not in fn or not fn.endswith(".log"):
            continue
        path = os.path.join(dirpath, fn)
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        logs += 1
        om = ORACLE.search(text)
        oracle = om.group(1) if om else "?"
        # the log-level oracle header is the first one; blocks carry their own, but they agree
        for b in blocks_of(text):
            bom = ORACLE.search(b)
            boracle = bom.group(1) if bom else oracle
            cls, ref = classify(b, boracle)
            counts[cls] += 1
            refinements[cls][ref] += 1
            examples.setdefault((cls, ref), path)
            fam = family_of(ref)
            families[fam] += 1
            family_logs[fam].add(path)
            if fam not in rep_dumped:
                rep_dumped.add(fam)
                with open("/tmp/rep-%s.txt" % fam.replace("/", "_"), "w") as fh:
                    fh.write("FILE: %s\n\n%s\n" % (path, b))
            rm = ROW.search(b)
            if rm:
                null_row[("null" in rm.group(1).lower(), cls)] += 1

print("failure logs : %d   disagreement blocks : %d" % (logs, sum(counts.values())))
print()
print("== MECHANISM FAMILIES (the thing that matters) ==")
for k, v in families.most_common():
    print("  %-28s blocks=%-6d distinctLogs=%d" % (k, v, len(family_logs[k])))
print()
print("== by probe class ==")
for k, v in counts.most_common():
    print("  %-46s %6d" % (k, v))
print()
print("== distinct refinement strings per class ==")
for cls, _ in counts.most_common():
    print("  %-46s %6d distinct=%d" % (cls, counts[cls], len(refinements[cls])))
print()
print("representative block of each family written to /tmp/rep-<FAMILY>.txt")

