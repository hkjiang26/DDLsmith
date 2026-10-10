#!/usr/bin/env bash
#
# Re-runs the exact databases the two-hour MySQL experiment recorded a failure for, so that "the
# false positive is gone" and "the engine defect is still reported" are measured rather than assumed.
#
# Why one process per database. A database's content is decided by its (seed, slot) alone: Main seeds
# a task with `randomSeed + i / databasesPerGraph` and tells it slot `i % databasesPerGraph` (or
# `--input-slot` when that is not 0). A single-database run is task 0 of its own run, so
#
#     --random-seed <the seed the failure log names> --input-slot <the slot it names> --num-tries 1
#
# regenerates exactly that database - and, just as important, the run finishes on its own instead of
# being cut off by the run's timeout. A finding that is absent because the database never finished
# would otherwise look exactly like a finding that is absent because it was fixed. (The first version
# of this script passed the group's seed instead of the task's, which is one graph earlier: twelve of
# the thirteen cases stopped reproducing, and the one that still did was the case whose graphIndex was
# 0, where the two numbers are the same.)
#
# Why every case gets a schema name of its own. A generated database's namespace is
# `<schema name>_<database name>`, and DDLSmith drops that namespace before it creates it. Cases that
# share a schema name therefore drop each other's objects when they run at the same time: the first
# attempt at this script ran four cases in parallel with one schema name per category and produced
# SCHEMA_MISMATCH, METAMORPHIC, DATA_MISMATCH and UNEXPECTED_ERROR findings that were all the
# orchestration - the exact failure mode the campaign's own README warns about. Each case is now
# isolated, and each keeps its failure logs in its own working directory.
#
# Both directions are measured, by running the same case list twice with two builds:
#   * the baseline build (the commit the campaign ran) has to reproduce every recorded failure, which
#     is what shows the harness reproduces the campaign's databases at all;
#   * the fixed build has to produce no finding for the twelve false positives and still report the
#     engine defect.
#
# Usage: verify-mysql-fixes.sh [--jar PATH] [--out-dir DIR] [--config FILE] [--parallel N]
#                              [--only SUBSTRING] [--expectation-override KIND]
set -uo pipefail
export LC_ALL=C LANG=C

JAR="${HOME}/workspace/ddlsmith-2h/DDLSmith/target/DDLSmith.jar"
OUT="${HOME}/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/fix-verification"
CONFIG="${HOME}/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/config.json"
PARALLEL=4
ONLY=''
OVERRIDE=''

while [ $# -gt 0 ]; do
    case "$1" in
        --jar) JAR="$2"; shift 2 ;;
        --out-dir) OUT="$2"; shift 2 ;;
        --config) CONFIG="$2"; shift 2 ;;
        --parallel) PARALLEL="$2"; shift 2 ;;
        --only) ONLY="$2"; shift 2 ;;
        --expectation-override) OVERRIDE="$2"; shift 2 ;;
        *) echo "verify-mysql-fixes.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

# One number out of the run's summary block, with the abbreviations DDLSmith uses for large counts
# ("1k successfully executed statements") normalised to an integer - the same reading run-category.sh
# does, and for the same reason: a count that reaches a comparison as the string "1k" decides a branch
# with an error message instead of with a number.
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

PLAN_ALL_DEEP='mysql --algorithm constraints --oracle ALL --insert-rows 10 --max-constraint-probes 60 --graph-generator mix'
PLAN_ALL_PREF='mysql --algorithm constraints --oracle ALL --graph-generator preferential'
PLAN_CONF_HYBRID='mysql --oracle graph-conformance --graph-generator hybrid'
PLAN_CONF_TREE='mysql --oracle graph-conformance --graph-generator tree'
PLAN_CONF_RANDOM='mysql --oracle graph-conformance --graph-generator random'

# name|plan|group seed|slot|expectation|oracle the campaign's log names|what that log recorded
CASES=(
"c2h01-all-deep-db11|$PLAN_ALL_DEEP|5600001|3|none|graph-conformance|c2h01/all-deep database11: CONSTRAINT_TOO_STRICT, a stored generated column narrower than its operand"
"c2h01-all-deep-db12|$PLAN_ALL_DEEP|5600001|4|engine|constraint-enforcement|c2h01/all-deep database12: CONSTRAINT_NOT_ENFORCED, the engine defect of bugs/mysql/005 (a foreign key is not checked when a column of its index is NULL)"
"c2h01-all-pref-db11|$PLAN_ALL_PREF|5600001|3|none|graph-conformance|c2h01/all-preferential database11: CONSTRAINT_TOO_STRICT, a generated column narrower than its operand"
"c2h01-all-pref-db17|$PLAN_ALL_PREF|5500002|1|none|referential-action|c2h01/all-preferential database17: CONSTRAINT_NOT_ENFORCED, the ON UPDATE probe asked for a value outside the key's range"
"c2h01-conf-hybrid-db24|$PLAN_CONF_HYBRID|5400003|0|none|graph-conformance|c2h01/conf-hybrid database24: CONSTRAINT_TOO_STRICT, two values that differ only in case"
"c2h01-conf-hybrid-db28|$PLAN_CONF_HYBRID|5400003|4|none|graph-conformance|c2h01/conf-hybrid database28: the same class"
"c2h01-conf-hybrid-db31|$PLAN_CONF_HYBRID|5400003|7|none|graph-conformance|c2h01/conf-hybrid database31: the same class"
"c2h02-all-deep-db8|$PLAN_ALL_DEEP|15600001|0|none|referential-action|c2h02/all-deep database8: CONSTRAINT_NOT_ENFORCED, the same out-of-range probe"
"c2h02-all-pref-db5|$PLAN_ALL_PREF|15500000|5|none|referential-action|c2h02/all-preferential database5: the same out-of-range probe"
"c2h02-conf-tree-db18|$PLAN_CONF_TREE|15400002|2|none|graph-conformance|c2h02/conf-tree database18: CONSTRAINT_TOO_STRICT, two values that differ only in case"
"c2h02-conf-tree-db20|$PLAN_CONF_TREE|15400002|4|none|graph-conformance|c2h02/conf-tree database20: the same class"
"c2h03-all-pref-db13|$PLAN_ALL_PREF|25700001|5|none|referential-action|c2h03/all-preferential database13: CONSTRAINT_NOT_ENFORCED, the same out-of-range probe (the one log the campaign never replayed)"
"c2h03-conf-random-db24|$PLAN_CONF_RANDOM|25200003|0|none|graph-conformance|c2h03/conf-random database24: CONSTRAINT_TOO_STRICT, a generated column narrower than its operand"
)

mkdir -p "$OUT"

run_case() { # name plan seed slot expectation recorded-oracle note
    local name=$1 plan=$2 seed=$3 slot=$4 expectation=$5 oracle=$6 note=$7
    local dir="$OUT/$name"
    # one namespace per case, so parallel cases cannot drop each other's objects
    local schema="ddlsmith_fix_$(echo "$name" | tr -c 'a-z0-9' '_')"
    [ -n "$OVERRIDE" ] && expectation="$OVERRIDE"
    rm -rf "$dir"
    mkdir -p "$dir"
    (
        # its own working directory: the tool writes its raw failure logs to ./logs/<engine>/
        cd "$dir" || exit 1
        # shellcheck disable=SC2086
        exec timeout --signal=TERM --kill-after=60 900 java -Xmx1400m -jar "$JAR" $plan \
            --config "$CONFIG" --schema-name "$schema" --random-seed "$seed" --input-slot "$slot" \
            --num-tries 1 --threads 1 --timeout-seconds 600 --quiet --bug-dir "$dir/bugs"
    ) >"$dir/run.out" 2>"$dir/run.err"
    local code=$?
    local failures kinds logs identity databases unreachable statements verdict
    failures=$(grep -c 'FAILURE \[' "$dir/run.out" 2>/dev/null || true)
    kinds=$(grep -o 'FAILURE \[[A-Za-z_]*\]' "$dir/run.out" 2>/dev/null | sort | uniq -c | tr -s ' ' | tr '\n' ' ')
    logs=$(find "$dir/logs" -name '*failure*.log' 2>/dev/null | wc -l)
    identity=$(grep -h -m1 '^seed' "$dir"/logs/*/*.log 2>/dev/null | sed -e 's/  */ /g' -e 's/ (slot/ slot/')
    # The run has to have tested something: a database that could not be reached, or a run that
    # executed no statement, produces exactly the output a fixed false positive produces - no failure -
    # and reading that as "the fix works" is the trap this script exists to avoid.
    databases=$(count "$dir/run.out" '^[[:space:]]*[0-9,]+[[:space:]]+databases$')
    unreachable=$(count "$dir/run.out" '^[[:space:]]*[0-9,]+[[:space:]]+databases could not be reached$')
    statements=$(count "$dir/run.out" '^[[:space:]]*[0-9.,]+[km]?[[:space:]]+successfully executed statements$')
    verdict='?'
    if [ "$databases" -lt 1 ] || [ "$unreachable" -gt 0 ] || [ "$statements" -lt 1 ]; then
        verdict='INVALID'
    else
        case "$expectation" in
        none)
            if grep -q "oracle[[:space:]]*:[[:space:]]*$oracle" "$dir"/logs/*/*.log 2>/dev/null; then
                verdict='FAIL'
            elif [ "$failures" -gt 0 ]; then
                # the recorded false positive is gone, and another oracle fired on the same database -
                # which is what a masked failure looks like once the one in front of it is fixed. The
                # kinds column says which, and it is read as a new question rather than as a regression.
                verdict='PASS*'
            else
                verdict='PASS'
            fi
            ;;
        engine)
            if grep -q "oracle[[:space:]]*:[[:space:]]*$oracle" "$dir"/logs/*/*.log 2>/dev/null; then
                verdict='PASS'
            else
                verdict='FAIL'
            fi
            ;;
        recorded | any)
            if grep -q "oracle[[:space:]]*:[[:space:]]*$oracle" "$dir"/logs/*/*.log 2>/dev/null; then
                verdict='PASS'
            else
                verdict='FAIL'
            fi
            ;;
        esac
    fi
    printf '%-26s %-7s exit=%-3s db=%s unreachable=%s stmts=%-5s failures=%-3s logs=%-3s kinds=%-30s\n' \
        "$name" "$verdict" "$code" "$databases" "$unreachable" "$statements" "$failures" "$logs" \
        "${kinds:-none}"
    printf '%-26s         seed="%s"\n' '' "${identity:-<no failure log>}"
    printf '%-26s         expected %s: %s\n' '' "$expectation" "$note"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$verdict" "$code" "$databases" "$unreachable" \
        "$statements" "$failures" "$logs" "${kinds:-none}" "$expectation" >>"$OUT/summary.tsv"
}

: >"$OUT/summary.tsv"
printf 'case\tverdict\texit\tdatabases\tunreachable\tstatements\tfailures\tlogs\tkinds\texpectation\n' \
    >"$OUT/summary.tsv"

echo "jar     : $JAR"
echo "out     : $OUT"
echo "cases   : ${#CASES[@]}$([ -n "$ONLY" ] && echo " (filtered by '$ONLY')")"
echo

running=0
for entry in "${CASES[@]}"; do
    IFS='|' read -r name plan seed slot expectation oracle note <<<"$entry"
    if [ -n "$ONLY" ] && [[ "$name" != *"$ONLY"* ]]; then
        continue
    fi
    run_case "$name" "$plan" "$seed" "$slot" "$expectation" "$oracle" "$note" &
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
echo
if grep -qE $'\t(FAIL|INVALID)\t' "$OUT/summary.tsv"; then
    echo "VERDICT: FAIL - a case did not behave as required, or did not test anything"
    exit 1
fi

# The runs leave one namespace per generated database behind (DDLSmith drops a namespace before it
# creates it, never after), so they are removed here - the same reap the campaign runs between chunks,
# restricted to the schema names these cases use and to this experiment's own container.
REAP="${HOME}/workspace/ddlsmith-2h/DDLSmith/runs/reap-databases.sh"
if [ -f "$REAP" ]; then
    echo
    echo "=== reaping the namespaces these runs created ==="
    SUDO_PASS_FILE="${SUDO_PASS_FILE:-${HOME}/workspace/ddlsmith-2h/.sudo-pass}" bash "$REAP" \
        --namespace-pattern '^ddlsmith_fix_[a-z0-9_]+_database[0-9]+$' \
        --mysql-container "${MYSQL_CONTAINER:-ddlsmith-2h-mysql}" \
        --skip-postgres --log-path "$OUT/reap.log" \
        || echo "reap reported errors (see $OUT/reap.log)"
    tail -3 "$OUT/reap.log" 2>/dev/null || true
fi

echo
echo "VERDICT: PASS - every case behaved as required"
