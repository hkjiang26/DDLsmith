#!/usr/bin/env python3
"""Acceptance: replay the campaign's failing inputs against the baseline and the fixed build.

Each command from the campaign's "Reproduce this run" is executed twice, once per jar, under a
schema name of its own so that the two runs cannot collide in the server. The failure titles each
build produces are compared line by line.

Before running it, put these in ``runs/accept/`` — none of it is versioned, because all of it is
either scratch or environment-specific:

  config.json              the servers to replay against. The keys have to be ``mysql@26.7`` and
                           ``postgres@18.6`` for the extracted commands to resolve; the ports used
                           are in runs/FIXES-MYSQL-CAMPAIGN.md.
  DDLSmith-baseline.jar    ``mvn package`` of the sources before the fixes
  DDLSmith-fixed.jar       ``mvn package`` of the sources after them

The ``replay-*.txt`` inputs next to this script are versioned. Each is the list of
``Reproduce this run`` lines the campaign wrote into its bug reports, one per line.
"""
import os
import re
import subprocess
import sys
import collections
import concurrent.futures

HERE = os.path.dirname(os.path.abspath(__file__))
# The inputs live next to this script and are versioned; the jars and the raw logs are scratch and
# stay under accept/, which .gitignore keeps out of the repository.
OUT = os.path.join(HERE, "accept", "out")
CONFIG = os.path.join(HERE, "accept", "config.json")
WORKERS = 6
JARS = {
    "base": os.path.join(HERE, "accept", "DDLSmith-baseline.jar"),
    "fix": os.path.join(HERE, "accept", "DDLSmith-fixed.jar"),
}
KINDS = ["rejected", "mismatch", "diverged", "differential", "constraint"]
LIMIT = {"constraint": 30}          # the constraint family is 365 near-identical commands
JAVA = os.environ.get("JAVA_EXE", "java")

FAILURE_LINE = re.compile(r"^FAILURE \[(\w+)\] (.*?) -> ", re.M)
STATS = re.compile(r"^\s*(\d+) databases$", re.M)


def commands(kind):
    path = os.path.join(HERE, f"replay-{kind}.txt")
    lines = [l.strip() for l in open(path, encoding="utf-8") if l.strip()]
    return lines[:LIMIT.get(kind, len(lines))]


def run(jar, args, schema_suffix, bug_dir):
    argv = [JAVA, "-jar", jar]
    rewritten = []
    skip = False
    for i, a in enumerate(args):
        if skip:
            skip = False
            continue
        if a == "--schema-name":
            rewritten += ["--schema-name", args[i + 1] + schema_suffix]
            skip = True
            continue
        rewritten.append(a)
    argv += rewritten
    argv += ["--config", CONFIG, "--database", "mysql@26.7",
             "--bug-dir", bug_dir]
    try:
        proc = subprocess.run(argv, capture_output=True, text=True, timeout=900)
    except subprocess.TimeoutExpired:
        return dict(exit=-1, failures=[("TIMEOUT", "")], databases=0)
    text = proc.stdout + proc.stderr
    failures = [(m.group(1), m.group(2)) for m in FAILURE_LINE.finditer(text)]
    databases = STATS.search(text)
    return dict(exit=proc.returncode, failures=failures,
                databases=int(databases.group(1)) if databases else 0)


def main():
    os.makedirs(OUT, exist_ok=True)
    jobs = []
    labels = []
    for kind in KINDS:
        for index, command in enumerate(commands(kind)):
            seed = re.search(r"--random-seed (\d+)", command).group(1)
            slot = re.search(r"--input-slot (\d+)", command)
            label = f"{kind}-{index:03d}-s{seed}-slot{slot.group(1) if slot else '0'}"
            labels.append((kind, label))
            for build, jar in JARS.items():
                # a schema name of its own per run: the databases are named after it, so nothing
                # collides and the whole matrix can run concurrently
                bug_dir = os.path.join(OUT, build, label)
                os.makedirs(bug_dir, exist_ok=True)
                jobs.append((label, build,
                             (jar, command.split(), f"_r{index:02d}{build[0]}", bug_dir)))

    print(f"{len(jobs)} runs, {WORKERS} at a time", flush=True)
    results = collections.defaultdict(dict)
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = {pool.submit(run, *job[2]): job[:2] for job in jobs}
        done = 0
        for future in concurrent.futures.as_completed(futures):
            label, build = futures[future]
            results[label][build] = future.result()
            done += 1
            if done % 10 == 0 or done == len(jobs):
                print(f"  {done}/{len(jobs)} runs done", flush=True)

    rows = [(kind, label, results[label]) for kind, label in labels]

    print("\n" + "=" * 104)
    print("PER INPUT")
    print("=" * 104)
    for kind, label, result in rows:
        b, f = result["base"], result["fix"]
        mark = "CHANGED" if b["failures"] != f["failures"] else "same   "
        print(f"{label:<40} base={len(b['failures']):>2} fix={len(f['failures']):>2}  {mark}"
              f"   base dbs={b['databases']} fix dbs={f['databases']}")

    print("\n" + "=" * 104)
    print("SUMMARY BY FAMILY")
    print("=" * 104)
    print(f"{'kind':<14}{'inputs':>7}{'base failures':>15}{'fix failures':>14}"
          f"{'base titles':>13}{'fix titles':>12}{'reached db (b/f)':>18}")
    for kind in KINDS:
        sel = [r for r in rows if r[0] == kind]
        bf = sum(len(r[2]['base']['failures']) for r in sel)
        ff = sum(len(r[2]['fix']['failures']) for r in sel)
        bd = len({t for r in sel for t in r[2]['base']['failures']})
        fd = len({t for r in sel for t in r[2]['fix']['failures']})
        bc = sum(1 for r in sel if r[2]['base']['databases'] > 0)
        fc = sum(1 for r in sel if r[2]['fix']['databases'] > 0)
        print(f"{kind:<14}{len(sel):>7}{bf:>15}{ff:>14}{bd:>13}{fd:>12}{str(bc) + '/' + str(fc):>18}")

    print("\n" + "=" * 104)
    print("DISTINCT FAILURE TITLES")
    print("=" * 104)
    base_titles = collections.Counter(t for r in rows for t in r[2]['base']['failures'])
    fix_titles = collections.Counter(t for r in rows for t in r[2]['fix']['failures'])
    print("\n--- only in the baseline: the false findings the fixes must remove ---")
    for (k, t), n in sorted(base_titles.items()):
        if (k, t) not in fix_titles:
            print(f"{n:>4}  [{k}] {t[:135]}")
    print("\n--- only in the fixed build: must be empty, or justified ---")
    for (k, t), n in sorted(fix_titles.items()):
        if (k, t) not in base_titles:
            print(f"{n:>4}  [{k}] {t[:135]}")
    print("\n--- in both: the real engine defects that must survive ---")
    for (k, t), n in sorted(fix_titles.items()):
        if (k, t) in base_titles:
            print(f"{base_titles[(k, t)]:>4}/{n:<4} [{k}] {t[:135]}")

    with open(os.path.join(OUT, "replay.log"), "w", encoding="utf-8") as fh:
        for kind, label, result in rows:
            fh.write(f"### {label}\n")
            for build in JARS:
                fh.write(f"  {build}: exit={result[build]['exit']} "
                         f"databases={result[build]['databases']}\n")
                for k, t in result[build]["failures"]:
                    fh.write(f"      [{k}] {t}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
