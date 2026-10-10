#!/usr/bin/env bash
#
# Linux twin of runs/mysql-campaign/census.ps1: turns a pile of failure logs into a deduplicated list
# of distinct defects.
#
# Why this is the first step of a campaign and not the last: a large run writes one log per generated
# database, and one defect appears in dozens of them with different generated identifiers. Reading
# them one by one at a few thousand findings is not possible, and fixing them from a 50-database
# sample is how a fix list ends up incomplete.
#
# The unit of the census is one *difference kind within one log*, not one log: a log that reports both
# a lost CHECK constraint and an invisible index is two defects, and grouping whole logs would report
# them as one signature for every combination that happens to occur.
#
# Each unit is reduced to a signature, kind | difference kind or engine error code | markers, where
# the markers are the evidence-shaped hints that separate the defect families seen so far on this
# engine (a charset introducer in a CHECK clause, a parenthesised negative literal, a foreign key
# whose referential action blocks a CHECK constraint, and so on). A new signature either matches a
# known family or is genuinely new.
#
# Layout: <log-root>/**/logs/<engine>/*-failure-*.log, and a log belongs to the census when the
# directory holding it is named after the engine. The walk is recursive because a campaign root nests
# a round directory per round and a category directory below it - 12h-b/12hb08/mysql-native/logs/mysql
# - while a per-category root (runs/mysql-campaign/mysql) keeps the category one level lower still.
#
# Output: census.tsv (machine) and census.md (report material) in --out-dir, the same listing on
# stdout, and a trailing tally by kind that the PowerShell original leaves to its reader.
#
# Usage: census-failures.sh --log-root DIR [--engine mysql] [--out-dir DIR]
#
set -uo pipefail
export LC_ALL=C LANG=C

LOG_ROOT=''
ENGINE='mysql'
OUT_DIR=''

while [ $# -gt 0 ]; do
    case "$1" in
        --log-root)
            [ $# -ge 2 ] || { echo "census-failures.sh: --log-root needs a value" >&2; exit 2; }
            LOG_ROOT="$2"; shift 2 ;;
        --engine)
            [ $# -ge 2 ] || { echo "census-failures.sh: --engine needs a value" >&2; exit 2; }
            ENGINE="$2"; shift 2 ;;
        --out-dir)
            [ $# -ge 2 ] || { echo "census-failures.sh: --out-dir needs a value" >&2; exit 2; }
            OUT_DIR="$2"; shift 2 ;;
        *) echo "census-failures.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

[ -n "$LOG_ROOT" ] || { echo "census-failures.sh: --log-root is required" >&2; exit 2; }

# This twin also runs under Git Bash on the campaign machine, where the round scripts pass roots as
# "E:\workspace\...". Folding the separator here is what keeps the log column of census.tsv in one
# form, instead of the "E:\workspace\...\12hb08\mysql/logs/mysql/x.log" mix that find prints for a
# Windows-style root.
LOG_ROOT="${LOG_ROOT//\\//}"
OUT_DIR="${OUT_DIR//\\//}"

# The one hard failure: nothing can be censused without the logs. Every other problem below - a
# missing category directory, an unreadable or empty log, a log with no kind line - is reported and
# does not stop the run, because a census that refuses to produce numbers is worse than a census with
# a footnote.
[ -d "$LOG_ROOT" ] || { echo "census-failures.sh: log root '$LOG_ROOT' does not exist" >&2; exit 2; }

if [ -z "$OUT_DIR" ]; then
    # Split-Path -Parent: the census belongs next to the log root, not inside the evidence
    parent="${LOG_ROOT%/}"
    parent="${parent%/*}"
    [ "$parent" = "${LOG_ROOT%/}" ] && parent=''
    OUT_DIR="${parent:+$parent/}census"
fi
# The directory is created even when there is nothing to write, which is what the PowerShell version
# does; failing to create it IS fatal, because every path below would otherwise report success while
# writing nothing.
mkdir -p "$OUT_DIR" || { echo "census-failures.sh: cannot create out dir '$OUT_DIR'" >&2; exit 1; }

CENSUS_TSV="$OUT_DIR/census.tsv"
CENSUS_MD="$OUT_DIR/census.md"

# Logs are collected and then sorted path component by path component rather than by raw string: a
# plain sort orders "mysql-constraints-deep/logs/..." before "mysql-constraints/logs/..." because '-'
# sorts below '/' in the C locale, while the PowerShell original walks the tree in directory order.
# Component order also makes the census reproducible, so two rounds can be diffed; the PowerShell
# version's choice of sample log for a family depends on the order the filesystem hands out entries.
#
# The file-name and directory-name tests are case-insensitive on purpose: the original's -Filter and
# -eq are both case-insensitive on Windows, and the two harnesses are meant to read the same logs.
mapfile -t LOGS < <(
    find "$LOG_ROOT" -type f -iname '*-failure-*.log' -print |
        sort -t / -k 1,1 -k 2,2 -k 3,3 -k 4,4 -k 5,5 -k 6,6 -k 7,7 -k 8,8 -k 9,9 -k 10,10
)
KEPT=()
for path in "${LOGS[@]:-}"; do
    [ -n "$path" ] || continue
    category="${path%/*}"
    category="${category##*/}"
    if [ "${category,,}" = "${ENGINE,,}" ]; then KEPT+=("$path"); fi
done
# Assigned with an explicit test rather than "${KEPT[@]:-}": that expansion yields one empty element
# for an empty array, which reads as "one log found" and would send an engine with no log at all
# through the whole census instead of reporting that there is nothing to census.
LOGS=()
if [ "${#KEPT[@]}" -gt 0 ]; then LOGS=("${KEPT[@]}"); fi
LOG_COUNT=${#LOGS[@]}

if [ "$LOG_COUNT" -eq 0 ]; then
    echo "no '$ENGINE' failure log under $LOG_ROOT"
    exit 0
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/census-failures.XXXXXX")" || {
    echo "census-failures.sh: cannot create a temporary directory" >&2; exit 1; }
trap '[ -n "${WORK:-}" ] && rm -rf "$WORK"' EXIT

# One path per line: no quoting problem for a path with spaces, and no argument list has to hold a
# whole campaign's worth of logs.
printf '%s\n' "${LOGS[@]}" > "$WORK/logs.txt"

cat > "$WORK/census.awk" <<'AWK'
# Per-log extraction and cross-log grouping in one pass. The program is written for the POSIX awk that
# every Linux box has; the only extensions used are `delete array`, ERRNO and /dev/stderr.

function trim(s) {
    sub(/^[ \t\r]+/, "", s)
    sub(/[ \t\r]+$/, "", s)
    return s
}

function warn(text) {
    print "census-failures.sh: WARNING: " text > "/dev/stderr"
}

# The evidence-shaped hints. Every one of them is a defect family that has actually been observed on a
# real server, so a signature either matches one of them or deserves a look. The markers are tested on
# a lowercased copy because the PowerShell original uses -match, which is case-insensitive, whereas
# its Field() below uses [regex]::Match, which is not - hence the two different kinds of test here.
#
# Three patterns had to be rewritten rather than copied: POSIX ERE has no lookbehind, so the "not
# preceded by a word character or a dot" of `(?<![\w.])-` becomes the alternation `(^|[^a-z0-9_.])-`,
# and `\b` becomes `(^|[^a-z0-9_])`. They are equivalent for a yes/no test on one string.
#
# The engine escapes the quotes of a rendered expression (`_utf8mb4\'e1\'`), hence the optional
# backslash. That pattern also fires on a quoted identifier that merely ends in a digit after an
# underscore (`'ddlsmith_rnd_database73'`), which is what the PowerShell version does too.
function markers(text,   s, m, n, i, j, t, out) {
    s = tolower(text)
    n = 0
    if (s ~ /_[a-z][a-z0-9]*\\?'/) m[n++] = "charset-introducer"
    if (s ~ /(^|[^a-z0-9_.])-[[:space:]]*\([[:space:]]*[0-9]/) m[n++] = "negative-literal-parenthesised"
    if (s ~ /(^|[^a-z0-9_])from[[:space:]]*\([[:space:]]*[a-z_][a-z0-9_]*[[:space:]]*\./) m[n++] = "view-qualified-join"
    if (s ~ /(^|[^a-z0-9_])as[[:space:]]+[a-z_][a-z0-9_]*[[:space:]]*[,)]/) m[n++] = "view-auto-alias"
    if (s ~ /actual[[:space:]]*<none>/) m[n++] = "nothing-reported"
    if (s ~ /referential action/) m[n++] = "check-on-referential-action-column"
    if (s ~ /too many key parts/) m[n++] = "index-column-limit"
    if (s ~ /too long|max key length/) m[n++] = "index-key-length-limit"
    if (s ~ /already exists/) m[n++] = "object-already-exists"
    if (s ~ /not supported|not yet supported/) m[n++] = "feature-not-supported"
    # Sort-Object -Unique: an ASCII sort is the same order for these names, and the check order above
    # is deliberately not alphabetical.
    for (i = 1; i < n; i++) {
        t = m[i]
        for (j = i - 1; j >= 0 && m[j] > t; j--) m[j + 1] = m[j]
        m[j + 1] = t
    }
    out = ""
    for (i = 0; i < n; i++) out = out (i ? "," : "") m[i]
    return out
}

# The header fields: the first match in the file wins, like [regex]::Match, so a "kind :" line inside a
# group block below the header cannot displace the header's own kind.
function feed(x,   t, name) {
    sub(/\r$/, "", x)

    if (kind == "" && x ~ /^kind[[:space:]]+:[[:space:]]+[^[:space:]]/) {
        t = x; sub(/^kind[[:space:]]+:[[:space:]]+/, "", t); sub(/[[:space:]].*$/, "", t); kind = t
    }
    if (oracle == "" && x ~ /^oracle[[:space:]]+:/) {
        t = x; sub(/^oracle[[:space:]]+:[[:space:]]+/, "", t); oracle = trim(t)
    }
    if (db == "" && x ~ /^database[[:space:]]+:[[:space:]]+[^[:space:]]/) {
        t = x; sub(/^database[[:space:]]+:[[:space:]]+/, "", t); sub(/[[:space:]].*$/, "", t)
        sub(/@.*$/, "", t); db = t
    }
    if (seed == "" && x ~ /^seed[[:space:]]+:[[:space:]]+[0-9]/) {
        t = x; sub(/^seed[[:space:]]+:[[:space:]]+/, "", t); sub(/[^0-9].*$/, "", t); seed = t
    }
    if (ecode == "" && x ~ /^ErrorCode[[:space:]]+:[[:space:]]+[0-9]/) {
        t = x; sub(/^ErrorCode[[:space:]]+:[[:space:]]+/, "", t); sub(/[^0-9].*$/, "", t); ecode = t
    }
    if (msg == "" && x ~ /^Error[[:space:]]+:/) {
        t = x; sub(/^Error[[:space:]]+:[[:space:]]*/, "", t); if (t != "") msg = trim(t)
    }
    if (sqlstate == "" && x ~ /^SQLState[[:space:]]+:[[:space:]]+[^[:space:]]/) {
        t = x; sub(/^SQLState[[:space:]]+:[[:space:]]+/, "", t); sub(/[[:space:]].*$/, "", t); sqlstate = t
    }
    if (failureline == "" && x ~ /^DDLSmith failure:/) {
        t = x; sub(/^DDLSmith failure:[[:space:]]*/, "", t); failureline = trim(t)
    }

    # A line of ten or more '=' ends a section, and therefore a (database, constraint) group block.
    if (x ~ /^={10,}[[:space:]]*$/) { flushblock(); return }

    if (x ~ /^group[[:space:]]+:[[:space:]]+[^[:space:]]/) {
        hasgroup = 1
        t = x; sub(/^group[[:space:]]+:[[:space:]]+/, "", t); sub(/[[:space:]].*$/, "", t); groupname = t
    }
    if (x ~ /^Foreign key[[:space:]]+:[[:space:]]+/) {
        t = x; sub(/^Foreign key[[:space:]]+:[[:space:]]+/, "", t); fk = trim(t)
    }

    # The difference detail lines. They are the unit of the census, grouped by the kind they belong to:
    # a log that lost a table and a view reports MISSING_TABLE and MISSING_VIEW as two defects. The
    # indentation is tested line by line; the PowerShell regex could in principle reach across a line
    # break, but no log the tool writes is indented by a single space.
    if (x ~ /^[[:space:]]{2,}[A-Z_]+ at /) {
        t = trim(x)
        name = t; sub(/ at .*$/, "", name)
        if (name in firstline) {
            evid[name] = evid[name] " " t
        } else {
            firstline[name] = t
            evid[name] = t
            eorder[nNames++] = name
        }
    }

    blocktext = blocktext " " x
}

# A CONSTRAINT_NOT_ENFORCED report has no "NAME at ..." difference lines: it prints one block per
# (database, constraint) group, headed by "group : <name>". Counting the whole log as one unit hid the
# second group of a log that reported two, and the group name - which is what says *which* referential
# action was not honoured - never reached the census at all. The groups are the units.
function flushblock(   e, ex) {
    if (hasgroup) {
        e = blocktext
        gsub(/[[:space:]]+/, " ", e)
        e = trim(e)
        nBlocks++
        bEvi[nBlocks] = e
        bGroup[nBlocks] = groupname
        ex = (fk != "" ? fk : e)
        if (length(ex) > 190) ex = substr(ex, 1, 190) "..."
        bEx[nBlocks] = ex
    }
    blocktext = ""
    hasgroup = 0
    groupname = ""
    fk = ""
}

function finish(path,   i, name, ev, ex, detail) {
    if (nNames > 0) {
        for (i = 0; i < nNames; i++) {
            name = eorder[i]
            ex = firstline[name]
            if (length(ex) > 190) ex = substr(ex, 1, 190) "..."
            emit(kind, name, markers(evid[name]), db, seed, ex, path, oracle)
        }
        return
    }
    if (nBlocks > 0) {
        for (i = 1; i <= nBlocks; i++) {
            emit(kind, bGroup[i], markers(bEvi[i]), db, seed, bEx[i], path, oracle)
        }
        return
    }
    # A crash, a harness exception or anything else without a difference list: one unit, and the
    # engine's own words are the only evidence there is. This example is deliberately not truncated -
    # the PowerShell version truncates only the two paths above.
    ev = trim(msg " " sqlstate)
    detail = (ecode != "" ? "engine-" ecode : "no-difference-list")
    ex = msg
    if (ex == "") ex = failureline
    emit(kind, detail, markers(ev), db, seed, ex, path, oracle)
}

function emit(k, d, mk, database, sd, example, logpath, orc,   key, mkey) {
    key = k "|" d
    if (!(key in famCount)) {
        famOrder[nFam++] = key
        famKind[key] = k
        famDetail[key] = d
        famOracle[key] = orc
        famDb[key] = database
        famSeed[key] = sd
        famLog[key] = logpath
        famEx[key] = example
    }
    famCount[key]++
    mkey = key SUBSEP mk
    if (!(mkey in mkCount)) mkName[key, mkIndex[key]++] = mk
    mkCount[mkey]++
    units++
    if (k == "") {
        # Not fatal and not skipped: the unit is counted with an empty kind, exactly as the PowerShell
        # version counts it, but a reader has to be told that a log could not be attributed.
        nokind++
        if (nokind <= 5) warn(logpath ": no 'kind' line; counted under an empty kind")
    }
}

# The marker breakdown of one family: Group-Object Markers | Sort-Object Count -Descending, with an
# empty marker string spelled "(none)".
function breakdown(key,   i, j, n, names, cnts, t, c, label, out) {
    n = mkIndex[key] + 0
    for (i = 0; i < n; i++) {
        names[i] = mkName[key, i]
        cnts[i] = mkCount[key SUBSEP names[i]]
    }
    for (i = 1; i < n; i++) {
        t = names[i]; c = cnts[i]
        for (j = i - 1; j >= 0 && cnts[j] < c; j--) { names[j + 1] = names[j]; cnts[j + 1] = cnts[j] }
        names[j + 1] = t
        cnts[j + 1] = c
    }
    out = ""
    for (i = 0; i < n; i++) {
        label = (names[i] == "" ? "(none)" : names[i])
        out = out (i ? "; " : "") label "=" cnts[i]
    }
    return out
}

{
    path = $0
    if (path == "") next

    kind = ""; oracle = ""; db = ""; seed = ""; ecode = ""; msg = ""; sqlstate = ""; failureline = ""
    nNames = 0; delete evid; delete firstline
    nBlocks = 0; delete bEvi; delete bGroup; delete bEx
    blocktext = ""; hasgroup = 0; groupname = ""; fk = ""

    if ((rc = (getline line < path)) < 0) {
        warn(path ": cannot be read (" ERRNO "); skipped")
        close(path)
        next
    }
    # A zero-byte log carries no evidence; the PowerShell original drops it as well, because
    # Get-Content -Raw returns nothing and its `if (-not $text) { continue }` reads that as "nothing to
    # census" rather than as a defect.
    if (rc == 0) { close(path); next }

    do {
        feed(line)
    } while ((getline line < path) > 0)
    close(path)

    finish(path)
}

END {
    # The primary grouping is (kind, difference kind) - the defect - and not the full signature: the
    # markers are evidence about a defect, and a defect whose evidence varies is still one defect. The
    # marker breakdown is reported inside the family, so nothing is lost. Ties keep first-appearance
    # order, which makes the census reproducible; the PowerShell version's Sort-Object is not stable, so
    # two families with the same occurrence count can come out in either order there.
    for (i = 1; i < nFam; i++) {
        t = famOrder[i]
        for (j = i - 1; j >= 0 && famCount[famOrder[j]] < famCount[t]; j--) famOrder[j + 1] = famOrder[j]
        famOrder[j + 1] = t
    }
    # The fields of this internal table are separated by 0x1f and not by a tab: bash's `read` treats a
    # tab as IFS whitespace, so it collapses a run of tabs and drops the ones at the start of the line,
    # which silently shifts every column of a record whose kind or database is empty - and a log with
    # no kind line is exactly such a record. 0x1f is not whitespace and cannot occur in a log line.
    for (i = 0; i < nFam; i++) {
        key = famOrder[i]
        printf "%d\037%s\037%s\037%s\037%s\037%s\037%s\037%s\037%s\n", famCount[key], famKind[key],
            famDetail[key], breakdown(key), famDb[key], famSeed[key], famLog[key], famEx[key], famOracle[key]
        if (!(famKind[key] in kseen)) { kseen[famKind[key]] = 1; korder[nKinds++] = famKind[key] }
        ktotal[famKind[key]] += famCount[key]
    }

    printf "%d\t%d\n", units, nFam > totalsfile
    for (i = 1; i < nKinds; i++) {
        t = korder[i]
        for (j = i - 1; j >= 0 && ktotal[korder[j]] < ktotal[t]; j--) korder[j + 1] = korder[j]
        korder[j + 1] = t
    }
    for (i = 0; i < nKinds; i++) print korder[i] "\037" ktotal[korder[i]] > kindsfile

    if (nokind > 5) warn(nokind " log(s) in total carried no 'kind' line")
}
AWK

# awk opens its two side files only when it has a line to put in them, and a run with no defect unit
# at all legitimately has no kind to tally; the file is created here so that the summary below reads an
# empty tally instead of failing on a missing redirect target.
: > "$WORK/kinds.tsv"
awk -v totalsfile="$WORK/totals.tsv" -v kindsfile="$WORK/kinds.tsv" \
    -f "$WORK/census.awk" "$WORK/logs.txt" > "$WORK/families.tsv"
AWK_STATUS=$?
# A part-written census is worse than none: it reads as a complete answer while missing evidence. A
# run in which every log was empty writes a totals line with two zeroes, which is a real answer and is
# reported as such; a run whose awk died writes nothing at all.
if [ "$AWK_STATUS" -ne 0 ] || [ ! -s "$WORK/totals.tsv" ]; then
    echo "census-failures.sh: the census pass failed (awk exit $AWK_STATUS); no census files written" >&2
    exit 1
fi

read -r UNITS FAMILIES < "$WORK/totals.tsv"

# census.tsv: occurrences, kind, detail, markerBreakdown, sampleDatabase, sampleSeed, log - the same
# columns in the same order as the PowerShell version's, because the result tables and the fix lists
# are read out of this file by other tooling. The internal table is read with 0x1f as the separator
# (see the awk END block); the file itself is written with tabs, like the original's.
{
    printf 'occurrences\tkind\tdetail\tmarkerBreakdown\tsampleDatabase\tsampleSeed\tlog\n'
    while IFS=$'\037' read -r occ kind detail markers_text database seed log example oracle; do
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$occ" "$kind" "$detail" "$markers_text" "$database" "$seed" "$log"
    done < "$WORK/families.tsv"
} > "$CENSUS_TSV"

# census.md: report material. The example is written verbatim: the PowerShell version's
# `-replace '\|','\|'` replaces a pipe with a pipe, so an example containing one is not escaped there
# either, and the two files are meant to be diffable.
{
    printf '# Failure census: %s\n' "$ENGINE"
    printf '\n'
    printf 'Source: `%s`  \n' "$LOG_ROOT"
    printf 'Logs read: **%s**  \n' "$LOG_COUNT"
    printf 'Defect units: **%s**  \n' "$UNITS"
    printf 'Distinct defects: **%s**\n' "$FAMILIES"
    printf '\n'
    printf '| # | occurrences | kind | difference | markers | example | sample log |\n'
    printf '| - | ----------- | ---- | ---------- | ------- | ------- | ---------- |\n'
    index=1
    while IFS=$'\037' read -r occ kind detail markers_text database seed log example oracle; do
        printf '| %s | %s | %s | %s | %s | %s | `%s` |\n' \
            "$index" "$occ" "$kind" "$detail" "$markers_text" "$example" "${log##*/}"
        index=$(( index + 1 ))
    done < "$WORK/families.tsv"
} > "$CENSUS_MD"

echo "census of ${ENGINE}: ${LOG_COUNT} log(s), ${UNITS} defect unit(s), ${FAMILIES} distinct defect(s)"
echo
while IFS=$'\037' read -r occ kind detail markers_text database seed log example oracle; do
    printf '[%4s] %s | %s | %s\n' "$occ" "$kind" "$oracle" "$detail"
    printf '       markers: %s\n' "$markers_text"
    printf '       example: %s\n' "$example"
done < "$WORK/families.tsv"
echo
echo "written: $CENSUS_TSV"
echo "written: $CENSUS_MD"

# The PowerShell version's listing above doubles as the report, so its reader has to tally the kinds
# by hand to answer "how much of this is one defect family". These two lines answer it directly; they
# are the only output that is not part of the ported behaviour.
echo
echo "defects by kind (occurrences):"
while IFS=$'\037' read -r kind total; do
    printf '  %-26s %s\n' "${kind:-(no kind)}" "$total"
done < "$WORK/kinds.tsv"
echo "worst defects:"
head -n 5 "$WORK/families.tsv" | while IFS=$'\037' read -r occ kind detail markers_text database seed log example oracle; do
    printf '  [%4s] %s | %s\n' "$occ" "$kind" "$detail"
done

exit 0
