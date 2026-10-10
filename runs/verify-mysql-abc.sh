#!/usr/bin/env bash
#
# Does the strengthened oracle now ask the engine to refuse what the schema forbids — and does it stay
# silent where it used to be wrong?
#
# The two-hour MySQL experiment measured the old oracle's blind spot exactly: on the database where
# `constraint-enforcement` found bugs/mysql/005, `graph-conformance` asked 32 questions and reported
# nothing, because every question it can ask is about an instance that is legal by construction. The
# probes added for that direction have to change that measurement, and they must not pay for it with the
# false positives the same run exposed (two false-positive classes were about *rejections* being
# misread, so a new family of deliberate rejections is exactly where a regression would show up).
#
# Two expectations, both measured on the live server:
#
#   probe   the database bugs/mysql/005 was found in, with the conformance oracle ALONE: it has to report
#           CONSTRAINT_NOT_ENFORCED now, naming a negative probe (the NULL-beside-the-key shape)
#   silent  the databases where the oracle used to report a false positive: it has to report nothing at
#           all — not the old class, and not a new one from the probes
#
# Usage: verify-mysql-abc.sh [--jar PATH] [--out-dir DIR] [--config FILE] [--parallel N] [--only SUB]
#
set -uo pipefail
export LC_ALL=C LANG=C

JAR="${HOME}/workspace/ddlsmith-2h/DDLSmith/target/DDLSmith.jar"
OUT="${HOME}/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/abc-verification"
CONFIG="${HOME}/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/config.json"
PARALLEL=3
ONLY=''

while [ $# -gt 0 ]; do
    case "$1" in
        --jar) JAR="$2"; shift 2 ;;
        --out-dir) OUT="$2"; shift 2 ;;
        --config) CONFIG="$2"; shift 2 ;;
        --parallel) PARALLEL="$2"; shift 2 ;;
        --only) ONLY="$2"; shift 2 ;;
        *) echo "verify-mysql-abc.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

count() { # $1 file, $2 regex matching the whole line
    local v
    v=$(grep -oE "$2" "$1" 2>/dev/null | head -1 | grep -oE '[0-9][0-9.,]*[km]?' | head -1)
    v="${v//,/}"
    case "$v" in
    *k | *K) v=$(awk -v x="${v%[kK]}" 'BEGIN{printf "%d", x*1000}') ;;
    *m | *M) v=$(awk -v x="${v%[mM]}" 'BEGIN{printf "%d", x*1000000}') ;;
    esac
    case "$v" in
    '' | *[!0-9]*) echo 0 ;;
    *) echo "$v" ;;
    esac
}

PLAN_MIX='mysql --oracle graph-conformance --graph-generator mix'
PLAN_HYBRID='mysql --oracle graph-conformance --graph-generator hybrid'
PLAN_TREE='mysql --oracle graph-conformance --graph-generator tree'
PLAN_RANDOM='mysql --oracle graph-conformance --graph-generator random'

# name|plan|seed|slot|expectation|why
CASES=(
"blind-spot-005|$PLAN_MIX|5600001|4|probe|c2h01/all-deep database12: the database the engine defect bugs/mysql/005 was found in; the old oracle asked 32 questions and reported nothing"
"fixed-conf-hybrid-db24|$PLAN_HYBRID|5400003|0|silent|used to be a collation false positive; the probes must not replace it with another"
"fixed-conf-hybrid-db28|$PLAN_HYBRID|5400003|4|silent|the same class"
"fixed-conf-hybrid-db31|$PLAN_HYBRID|5400003|7|silent|the same class"
"fixed-conf-tree-db18|$PLAN_TREE|15400002|2|silent|the same class, on tree graphs"
"fixed-conf-tree-db20|$PLAN_TREE|15400002|4|silent|the same class"
"fixed-conf-random-db24|$PLAN_RANDOM|25200003|0|silent|used to be the generated-column range class"
)

mkdir -p "$OUT"
: >"$OUT/summary.tsv"
printf 'case\tverdict\texit\tdatabases\tunreachable\tstatements\tqueries\tfailures\tkinds\texpectation\n' \
    >"$OUT/summary.tsv"

run_case() { # name plan seed slot expectation why
    local name=$1 plan=$2 seed=$3 slot=$4 expectation=$5 why=$6
    local dir="$OUT/$name"
    local schema="ddlsmith_abc_$(echo "$name" | tr -c 'a-z0-9' '_')"
    rm -rf "$dir"
    mkdir -p "$dir"
    (
        cd "$dir" || exit 1
        # shellcheck disable=SC2086
        exec timeout --signal=TERM --kill-after=60 900 java -Xmx1400m -jar "$JAR" $plan \
            --config "$CONFIG" --schema-name "$schema" --random-seed "$seed" --input-slot "$slot" \
            --num-tries 1 --threads 1 --timeout-seconds 600 --quiet --bug-dir "$dir/bugs"
    ) >"$dir/run.out" 2>"$dir/run.err"
    local code=$?
    local databases unreachable statements queries failures kinds probe_finding conformance_finding verdict
    databases=$(count "$dir/run.out" '^[[:space:]]*[0-9,]+[[:space:]]+databases$')
    unreachable=$(count "$dir/run.out" '^[[:space:]]*[0-9,]+[[:space:]]+databases could not be reached$')
    statements=$(count "$dir/run.out" '^[[:space:]]*[0-9.,]+[km]?[[:space:]]+successfully executed statements$')
    queries=$(count "$dir/run.out" '^[[:space:]]*[0-9.,]+[km]?[[:space:]]+queries$')
    failures=$(grep -c 'FAILURE \[' "$dir/run.out" 2>/dev/null || true)
    kinds=$(grep -o 'FAILURE \[[A-Za-z_]*\]' "$dir/run.out" 2>/dev/null | sort | uniq -c | tr -s ' ' | tr '\n' ' ')
    conformance_finding=$(grep -l 'oracle[[:space:]]*:[[:space:]]*graph-conformance' "$dir"/logs/*/*.log 2>/dev/null | head -1)
    probe_finding=$(grep -l 'negative probe' "$dir"/logs/*/*.log 2>/dev/null | head -1)
    if [ "$databases" -lt 1 ] || [ "$unreachable" -gt 0 ] || [ "$statements" -lt 1 ]; then
        # a run that tested nothing produces exactly the output a fixed oracle produces
        verdict='INVALID'
    elif [ "$expectation" = probe ]; then
        if [ -n "$probe_finding" ] && [ -n "$conformance_finding" ]; then
            verdict='PASS'
        else
            verdict='FAIL'
        fi
    else
        if [ -n "$conformance_finding" ]; then
            verdict='FAIL'
        else
            verdict='PASS'
        fi
    fi
    printf '%-26s %-7s exit=%-3s db=%s unreachable=%s stmts=%-5s queries=%-5s failures=%-3s kinds=%-24s\n' \
        "$name" "$verdict" "$code" "$databases" "$unreachable" "$statements" "$queries" "$failures" \
        "${kinds:-none}"
    printf '%-26s         %s\n' '' "$why"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$verdict" "$code" "$databases" "$unreachable" \
        "$statements" "$queries" "$failures" "${kinds:-none}" "$expectation" >>"$OUT/summary.tsv"
}

echo "jar   : $JAR"
echo "out   : $OUT"
echo "cases : ${#CASES[@]}$([ -n "$ONLY" ] && echo " (filtered by '$ONLY')")"
echo

running=0
for entry in "${CASES[@]}"; do
    IFS='|' read -r name plan seed slot expectation why <<<"$entry"
    if [ -n "$ONLY" ] && [[ "$name" != *"$ONLY"* ]]; then
        continue
    fi
    run_case "$name" "$plan" "$seed" "$slot" "$expectation" "$why" &
    running=$(( running + 1 ))
    if [ "$running" -ge "$PARALLEL" ]; then
        wait
        running=0
    fi
done
wait

echo
echo "=== summary (${SECONDS}s wall) ==="
cat "$OUT/summary.tsv"

REAP="${HOME}/workspace/ddlsmith-2h/DDLSmith/runs/reap-databases.sh"
if [ -f "$REAP" ]; then
    echo
    echo "=== reaping the namespaces these runs created ==="
    SUDO_PASS_FILE="${SUDO_PASS_FILE:-${HOME}/workspace/ddlsmith-2h/.sudo-pass}" bash "$REAP" \
        --namespace-pattern '^ddlsmith_abc_[a-z0-9_]+_database[0-9]+$' \
        --mysql-container "${MYSQL_CONTAINER:-ddlsmith-2h-mysql}" \
        --skip-postgres --log-path "$OUT/reap.log" \
        || echo "reap reported errors (see $OUT/reap.log)"
    tail -2 "$OUT/reap.log" 2>/dev/null || true
fi

echo
if grep -qE $'\t(FAIL|INVALID)\t' "$OUT/summary.tsv"; then
    echo "VERDICT: FAIL - a case did not behave as required, or did not test anything"
    exit 1
fi
echo "VERDICT: PASS - the blind spot is closed and the fixed databases stay silent"
