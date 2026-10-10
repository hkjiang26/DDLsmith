import re, collections, io

SRC = r"E:\workspace\DDLSmith\runs\_raw_notes.txt"

rows = []
with io.open(SRC, encoding="utf-8") as fh:
    for line in fh:
        line = line.rstrip("\n").rstrip("\r")
        if not line.strip():
            continue
        m = re.match(r"^(.*?):(\d+):(.*)$", line)
        path, num, rest = m.group(1), int(m.group(2)), m.group(3)
        rest = rest.strip()
        if rest.startswith("- "):
            rest = rest[2:]
        rows.append((path, num, rest))

def round_of(p):
    return p.split("/")[1]

def oracle_of(p):
    base = p.split("/")[-1]
    if "constraint_too_strict" in base:
        return "graph-conformance"
    if "constraint_not_enforced" in base:
        if "c2h01/all-deep/" in p:
            return "constraint-enforcement"
        return "referential-action"
    return "UNKNOWN"

def template(t):
    s = t
    s = re.sub(r"ddlsmith_[A-Za-z0-9_]*", "<SCHEMA>", s)
    s = re.sub(r"database\d+", "<DB>", s)
    s = re.sub(r"\b[0-9MNmn]:[0-9MNmn]\b", "<CARD>", s)
    s = re.sub(r"\bE\d+#\d+\b", "<E>#<K>", s)
    s = re.sub(r"\bE\d+\b", "<E>", s)
    s = re.sub(r"\bRtern_[0-9_]+", "<R>", s)
    s = re.sub(r"\bR\d+\b", "<R>", s)
    s = re.sub(r"\bv_\d+\b", "<VIEW>", s)
    s = re.sub(r"\b(?:fk|ck|pk|uidx|idx)_[A-Za-z0-9_]+", "<CONSTRAINT>", s)
    s = re.sub(r"\b[a-z][a-z0-9_]*\b",
               lambda mo: "<ID>" if ("_" in mo.group(0) or any(c.isdigit() for c in mo.group(0))) else mo.group(0), s)
    s = re.sub(r"\bid\b", "<ID>", s)
    s = re.sub(r"(?:<ID>)+", "<ID>", s)
    s = re.sub(r"`(<[A-Z]+>)`", r"\1", s)
    s = re.sub(r"\b\d+\b", "<N>", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s

def coarse(t):
    return re.sub(r"\(Cannot .*\)\)$", "(<ENGINE-ERROR>)", t)

groups = collections.defaultdict(list)
for path, num, text in rows:
    had = re.match(r"^\[database\d+\]\s*", text)
    body = text[had.end():] if had else text
    groups[template(body)].append((round_of(path), oracle_of(path), path, num, text))

print("TOTAL note lines read:", len(rows))
print("DISTINCT templates (identifiers stripped):", len(groups))

order = sorted(groups.items(), key=lambda kv: (-len(kv[1]), kv[0]))

def emit(title, keyfn):
    g = collections.defaultdict(list)
    for tmpl, items in groups.items():
        g[keyfn(tmpl)].extend(items)
    print()
    print("=== %s === (%d groups)" % (title, len(g)))
    for i, (tmpl, items) in enumerate(sorted(g.items(), key=lambda kv: (-len(kv[1]), kv[0])), 1):
        rnd = collections.Counter(x[0] for x in items)
        orc = collections.Counter(x[1] for x in items)
        print("%2d. count=%d  rounds=%s  oracles=%s" % (i, len(items), dict(sorted(rnd.items())), dict(sorted(orc.items()))))
        print("    TEMPLATE: %s" % tmpl)
        print("    EXAMPLE : %s" % items[0][4])
        print()

print("=== FULL TEMPLATE CENSUS (sorted by count desc) ===")
for i, (tmpl, items) in enumerate(order, 1):
    rnd = collections.Counter(x[0] for x in items)
    orc = collections.Counter(x[1] for x in items)
    print("%2d. count=%d  rounds=%s  oracles=%s" % (i, len(items), dict(sorted(rnd.items())), dict(sorted(orc.items()))))
    print("    TEMPLATE: %s" % tmpl)
    print("    EXAMPLE : %s" % items[0][4])
    print()

emit("COARSE GROUPS (engine error text normalised to <ENGINE-ERROR>)", coarse)

print("=== notes per round ===")
c = collections.Counter(x[0] for x in rows)
for k in sorted(c):
    print("  %s\t%d" % (k, c[k]))
print("=== notes per oracle ===")
c = collections.Counter(oracle_of(x[0]) for x in rows)
for k in sorted(c):
    print("  %s\t%d" % (k, c[k]))
print("=== note-section header texts ===")
c = collections.Counter("graph-conformance" if oracle_of(x[0]) == "graph-conformance" else "constraint/referential" for x in rows)
for k in sorted(c):
    print("  %s\t%d" % (k, c[k]))
