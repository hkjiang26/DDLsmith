import io, collections

SRC = r"E:\workspace\DDLSmith\runs\_resources.tsv"
rows = []
with io.open(SRC, encoding="utf-8") as fh:
    header = None
    for line in fh:
        line = line.rstrip("\n").rstrip("\r")
        if not line.strip():
            continue
        if header is None:
            header = line.split("\t")
            continue
        rows.append(line.split("\t"))

print("HEADER (%d cols): %s" % (len(header), header))
print("DATA ROWS: %d" % len(rows))
print()
print("ts range: %s .. %s" % (rows[0][0], rows[-1][0]))
print()
for i, col in enumerate(header):
    vals = [r[i] for r in rows if i < len(r)]
    nonempty = [v for v in vals if v.strip() != ""]
    empty = len(vals) - len(nonempty)
    num = []
    for v in nonempty:
        try:
            num.append(float(v))
        except ValueError:
            pass
    if not num:
        print("%-22s non-numeric/empty (nonempty=%d, empty=%d)" % (col, len(nonempty), empty))
        continue
    mx = max(num)
    mn = min(num)
    mxrow = rows[[j for j, r in enumerate(rows) if j < len(rows) and r[i].strip() != "" and float(r[i]) == mx][0]]
    print("%-22s max=%-18s min=%-18s nonempty=%-4d empty=%-4d  max@%s" % (col, ("%g" % mx), ("%g" % mn), len(nonempty), empty, mxrow[0]))
