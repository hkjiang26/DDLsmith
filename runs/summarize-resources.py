#!/usr/bin/env python3
"""Turn a campaign's resources.tsv into the verdict the operator actually asks for.

    python3 summarize-resources.py <resources.tsv> [--json]

The question a preflight exists to answer is "will it blow up?", and each of the three ways it could
is a number in this file:

  * memory   - the slice's high-water mark against its 16 GiB ceiling, and the host's available memory
               (which is what the Windows campaign ran out of: ten JVMs onto a host with 1.1 GB free);
  * disk     - free space at the start against free space at the end, plus the MySQL datadir growth
               rate, which is the quantity the reap interval has to beat;
  * CPU      - how much of the 4-core quota was actually used, and how often the kernel had to
               throttle to enforce it.

It reports growth rates rather than totals because a 72-hour run is a total extrapolated from an
hour, and the extrapolation is the only thing that can be wrong.
"""
import json
import sys
from datetime import datetime


def read(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        rows = [ln.rstrip("\n").split("\t") for ln in fh if ln.strip()]
    if not rows:
        return [], []
    header, body = rows[0], rows[1:]
    out = []
    for r in body:
        if len(r) < len(header):
            r = r + [""] * (len(header) - len(r))
        rec = dict(zip(header, r))
        for k, v in rec.items():
            if k == "ts":
                continue
            try:
                rec[k] = float(v) if v not in ("", None) else None
            except ValueError:
                rec[k] = None
        out.append(rec)
    return header, out


def series(rows, key):
    return [r[key] for r in rows if r.get(key) is not None]


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    path = argv[1]
    header, rows = read(path)
    if not rows:
        print(f"no samples in {path}")
        return 1

    def first(k):
        s = series(rows, k)
        return s[0] if s else None

    def last(k):
        s = series(rows, k)
        return s[-1] if s else None

    def peak(k):
        s = series(rows, k)
        return max(s) if s else None

    def minutes():
        try:
            a = datetime.fromisoformat(rows[0]["ts"])
            b = datetime.fromisoformat(rows[-1]["ts"])
            return max((b - a).total_seconds() / 60.0, 1e-9)
        except Exception:
            return float(len(rows))

    span_min = minutes()
    span_h = span_min / 60.0

    disk_first, disk_last = first("disk_free_gb"), last("disk_free_gb")
    data_first = next((v for v in series(rows, "mysql_data_mb")), None)
    data_last = next((v for v in reversed(series(rows, "mysql_data_mb"))), None)
    cpu = series(rows, "cpu_busy_pct")
    throttled = series(rows, "slice_nr_throttled")

    report = {
        "samples": len(rows),
        "span_minutes": round(span_min, 1),
        "slice_memory_peak_mb": peak("slice_mem_peak_mb"),
        "slice_memory_last_mb": last("slice_mem_mb"),
        "host_mem_available_min_mb": min(series(rows, "mem_avail_mb") or [0]),
        "host_mem_available_last_mb": last("mem_avail_mb"),
        "host_swap_used_peak_mb": peak("swap_used_mb"),
        "jvm_count_peak": peak("jvm_count"),
        "jvm_rss_peak_mb": peak("jvm_rss_mb"),
        "docker_mem_peak_mb": peak("docker_mem_mb"),
        "disk_free_first_gb": disk_first,
        "disk_free_last_gb": disk_last,
        "disk_used_gb": None if None in (disk_first, disk_last) else round(disk_first - disk_last, 2),
        "mysql_data_first_mb": data_first,
        "mysql_data_last_mb": data_last,
        "mysql_data_growth_mb": None if None in (data_first, data_last) else round(data_last - data_first, 1),
        "namespaces_peak": peak("namespaces"),
        "namespaces_last": last("namespaces"),
        "cpu_busy_pct_mean": round(sum(cpu) / len(cpu), 1) if cpu else None,
        "cpu_busy_pct_peak": peak("cpu_busy_pct"),
        "slice_throttled_delta": (throttled[-1] - throttled[0]) if len(throttled) > 1 else None,
    }
    if report["slice_memory_peak_mb"] is not None:
        report["slice_memory_headroom_mb"] = round(16384 - report["slice_memory_peak_mb"], 1)

    if "--json" in argv:
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0

    def show(label, value, unit=""):
        print(f"  {label:<40} {value}{unit}")

    print(f"resource profile: {path}")
    print(f"  {'samples':<40} {report['samples']}  over {report['span_minutes']} min")
    print("memory")
    show("slice memory peak (ceiling 16384 MB)", report["slice_memory_peak_mb"], " MB")
    show("slice memory headroom", report.get("slice_memory_headroom_mb"), " MB")
    show("JVM RSS peak", report["jvm_rss_peak_mb"], " MB")
    show("JVMs running (peak)", report["jvm_count_peak"])
    show("host memory available (min)", report["host_mem_available_min_mb"], " MB")
    show("host swap used (peak)", report["host_swap_used_peak_mb"], " MB")
    show("containers memory (peak)", report["docker_mem_peak_mb"], " MB")
    print("disk")
    show("free at start", report["disk_free_first_gb"], " GB")
    show("free at end", report["disk_free_last_gb"], " GB")
    show("consumed over the run", report["disk_used_gb"], " GB")
    show("MySQL datadir growth", report["mysql_data_growth_mb"], " MB")
    if report["mysql_data_growth_mb"] is not None and span_h > 0:
        show("MySQL datadir growth rate", round(report["mysql_data_growth_mb"] / span_h, 1), " MB/h")
    show("namespaces peak", report["namespaces_peak"])
    print("cpu")
    show("host cpu busy (mean of 12 cores)", report["cpu_busy_pct_mean"], " %")
    show("host cpu busy (peak)", report["cpu_busy_pct_peak"], " %")
    show("slice throttle events over the run", report["slice_throttled_delta"])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
