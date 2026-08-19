#!/usr/bin/env bash
# Read tests=/skipped=/failures=/errors= straight out of the JUnit XML, compare what ran against
# what the tree says should have run, and put both in the run summary.
#
# CLAUDE.md makes those four attributes the control that catches a run which verified nothing: a
# tool-gated test now reports as genuinely skipped, so skipped="0" means those tests really
# executed. Until now that control could only be applied by someone with a checkout — CI threw
# the XML away (#65). Printing the numbers into the run summary means the check can be applied by
# reading the run, and the uploaded artifact is the backup for anyone who wants to diff or
# re-parse.
#
# **It must never print the reassurance from a failed measurement.** "skipped is zero, so every
# tool-gated test executed" is the exact claim CLAUDE.md tells agents to lean on, and a parse
# that read nothing produces 0/0/0 — which renders as that same sentence unless the script
# distinguishes "measured zero skips" from "measured nothing". That is the defect this repo keeps
# finding (a skip reported as PASSED, a cache replay of a run that verified nothing), and
# printing it into the run summary is where it would be most believed. So every total is
# normalised to an integer before any comparison, and the no-measurement case is reported as no
# measurement.
#
# **A partial measurement is not a measurement either** (#83). "No `<testsuite>` anywhere" was
# the only failure this distinguished at first, and the same reassurance prints just as readily
# from a parse that read four files out of five: the unread one contributes zero, the total looks
# like a total, and nothing in the output says a file could not be read. So `awk` reports how
# many files it actually got a `<testsuite>` out of, that is compared against how many the glob
# matched, and a shortfall renders the counts as a floor with the reassurance withheld.
#
# **And a measurement of the wrong set is not a measurement of this build** (#97). Everything
# above answers "did I read what was there". Nothing answered "was what was there all of it".
# Four mechanisms are now known that make a module produce no results at all — `enabled = false`
# on its test tasks, a module never wired to `check`, a cached task replayed as up-to-date, a run
# filtered to an empty set — and every one of them renders as an honest, smaller table with
# `skipped="0"` under it. Measured on `00c6d9e`: one line in `wm/build.gradle.kts` took the total
# from 279 to 178 with `BUILD SUCCESSFUL`, `skipped="0"`, the reassurance printed in full, and no
# warning from any control in the build, the workflow or this script. The route does not matter
# and enumerating routes is losing; the invariant they all break is one — **an aggregate reports
# on what it ran, never on what it should have run**. So this script now reads the other half of
# the comparison out of `.github/scripts/test-floors`, a committed per-module floor, and **gates**
# on it.
#
# Gating is a change of kind, and it is deliberate: everything above reports, because Gradle
# already fails the build on a test failure and the REQUIRE flags already fail it on a missing
# tool, so a second gate there could only add false reds. The floor comparison is the opposite —
# it is the only thing in the repository that holds this property, and a warning nobody is
# obliged to read is exactly what #97 describes being ignored. So a shortfall against the floor,
# an unusable floor file, or a floor file that disagrees with the build's own module roster all
# exit non-zero. Nothing else here does.
#
# **What it declines to report is deliberate too** (#143). A floor *below* what ran is the state
# the tree is supposed to be in — `CLAUDE.md` and the head of the floor file both say adding tests
# needs no edit there — so a line naming every module that has grown past its floor says only that
# the repository grew, and says it on every run for ever. It did: it named `wm` on every build from
# 2026-08-08, byte-identical, and not one reader acted on it. A warning that fires on sanctioned
# behaviour does not get acted on; it teaches its readers to skip the part of the page it prints
# in, which is the same page the shortfall error prints on. So the ordinary case is carried by the
# per-module `floor` column, which already sits beside the count, and the prose line fires only
# where a floor has decayed past the point of catching the loss of one average test class. It does
# not claim the floor has stopped catching everything: at the moment the note fires the floor
# still reds any loss larger than the gap. What it has lost is the unit — a class could go with
# the gate still green — and the gap only widens from there.
#
# `.github/scripts/test-summary-matrix.sh` holds this script's suite, and it is not optional
# reading if you edit here: it asserts the behaviour above *and* asserts that each guard,
# removed, brings a case back red. `./gradlew check` runs it.
#
# Deliberately mawk-compatible (that is /usr/bin/awk on ubuntu-latest): no asorti, no gawk
# extensions, no ENDFILE.
set -uo pipefail

# Resolved before the `cd` below, and with builtins only. The PATH this runs under is a standing
# assertion about what it may shell out to — the matrix builds a bare one holding exactly bash,
# grep, sort, head, cat and tee — and `dirname` is not on it.
case $0 in
  */*) script_dir=${0%/*} ;;
  *) script_dir=. ;;
esac
script_dir=$(CDPATH= cd -P -- "$script_dir" 2>/dev/null && pwd) || script_dir=.

# The floor file, and the module roster to check it against. Both are overridable because the
# suite has to point them at fixtures; neither is optional in the sense that mattered before —
# an absent floor file is a failure, not a reason to fall back to reporting only what was found.
FLOORS=${AWAKENER_TEST_FLOORS:-$script_dir/test-floors}
ROSTER=${AWAKENER_TEST_MODULES:-}

# `exit 0` on a failed `cd` was one more way to measure nothing and call it fine.
cd "${1:-.}" || {
  echo "::error title=Test counts unreadable::Could not enter '${1:-.}' to read test results from. Nothing was measured."
  exit 1
}

# Everything goes to stdout, and to the step summary as well when there is one. The summary is
# where a human will look; the log is the only one of the two an API can read back, which matters
# when the reader is an agent asked to confirm what a run established.
emit() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    tee -a "$GITHUB_STEP_SUMMARY"
  else
    cat
  fi
}

# Anything that is not a plain non-negative integer becomes 0, so no branch below can be reached
# with an empty or garbage value. Without this, a missing awk leaves the totals empty,
# `[ "" -gt 0 ]` errors, and — with no `set -e` — execution falls through to the else branch and
# prints the reassurance.
num() {
  case ${1:-} in
    '' | *[!0-9]*) printf '0' ;;
    *) printf '%s' "$1" ;;
  esac
}

# ------------------------------------------------------------------ the floor file
#
# Parsed strictly, because every way of failing to parse it ends in the same place: a floor that
# constrains nothing, silently. A malformed line is not skipped, a duplicate module is not
# last-one-wins, and a file that declares no module at all is an error rather than a vacuous pass.
floor_names=()
floor_mins=()
floors_error=''
# Summed as the file is read, not as the counts are compared: the total floor is a property of
# the tree, and it has to render in the total row even when the parse failed and there is nothing
# to compare it against. That column against `*unread*` is the whole point — it says what this
# run was supposed to have produced, on the one rendering that cannot say what it did produce.
tot_floor=0

floor_problem() {
  if [ -z "$floors_error" ]; then floors_error=$1; else floors_error="$floors_error; $1"; fi
}

floor_min_of() {
  local i=0
  while [ "$i" -lt "${#floor_names[@]}" ]; do
    if [ "${floor_names[$i]}" = "$1" ]; then
      printf '%s' "${floor_mins[$i]}"
      return 0
    fi
    i=$((i + 1))
  done
  return 1
}

if [ ! -f "$FLOORS" ]; then
  floor_problem "no file at $FLOORS"
elif [ ! -r "$FLOORS" ]; then
  floor_problem "$FLOORS is not readable"
else
  fl_no=0
  while read -r fl_name fl_min fl_rest || [ -n "${fl_name:-}" ]; do
    fl_no=$((fl_no + 1))
    case ${fl_name:-} in '' | '#'*) continue ;; esac
    case $fl_name in
      *[!A-Za-z0-9_-]*)
        floor_problem "line $fl_no: '$fl_name' is not a module name"
        continue
        ;;
    esac
    case ${fl_min:-} in
      '' | *[!0-9]*)
        floor_problem "line $fl_no: '${fl_min:-}' is not a test count for $fl_name"
        continue
        ;;
    esac
    case ${fl_rest:-} in
      '' | '#'*) ;;
      *)
        floor_problem "line $fl_no: trailing '$fl_rest' after '$fl_name $fl_min'"
        continue
        ;;
    esac
    if floor_min_of "$fl_name" >/dev/null; then
      floor_problem "line $fl_no: $fl_name is declared twice"
      continue
    fi
    floor_names+=("$fl_name")
    floor_mins+=("$fl_min")
    tot_floor=$((tot_floor + fl_min))
  done < "$FLOORS"
  if [ "${#floor_names[@]}" -eq 0 ] && [ -z "$floors_error" ]; then
    floor_problem "$FLOORS declares no module, so it constrains nothing"
  fi
fi

# ------------------------------------------------------------------ the module roster
#
# The floor file says what each module should run. This says which modules there are, and it is
# the half that has to come from **outside the run** — because every comparison against what the
# run produced shares the run's blind spot. A module that produced no XML contributes zero to the
# counts *and* zero to any expectation derived from those counts, and `0` is not below `0`: an
# expectation derived from the same run is green in exactly the failure it exists to catch. So
# the roster is read from the build's own declaration of what it contains, never from the
# results, and a floor file that does not cover it exactly is an error in both directions.
#
# `AWAKENER_TEST_MODULES` first, which is what `:root:verifyTestFloors` passes — the build's own
# `subprojects`, so no parsing and no drift. Failing that, `settings.gradle.kts` in the results
# root, which is where the CI step runs and is deliberately *not* a list in a YAML file: a
# hand-kept roster is the thing this is preventing, so it must not need one to prevent it. That
# fallback also means the property survives the Gradle task being disabled, which is precisely
# the class of failure at issue.
if [ -z "$floors_error" ] && [ -z "$ROSTER" ] && [ -f settings.gradle.kts ]; then
  ROSTER=$(awk '
      /^[ \t]*include[ \t]*\(/ {
        line = $0
        while (match(line, /"[^"]*"/)) {
          mod = substr(line, RSTART + 1, RLENGTH - 2)
          line = substr(line, RSTART + RLENGTH)
          sub(/.*:/, "", mod)
          if (mod != "") printf "%s ", mod
        }
      }
    ' settings.gradle.kts 2>/dev/null)
  if [ -z "$ROSTER" ]; then
    # Present and unreadable is not the same as absent, and only one of the two is a reason to
    # stop checking. A settings file this cannot parse means the roster silently stopped being
    # enforced, which is the failure wearing the coat of the thing it disables.
    floor_problem "settings.gradle.kts is present and no module could be read from it, so the floor file was compared against nothing"
  fi
fi

if [ -z "$floors_error" ] && [ -n "$ROSTER" ]; then
  roster_words=" ${ROSTER//,/ } "
  for want in ${ROSTER//,/ }; do
    if ! floor_min_of "$want" >/dev/null; then
      floor_problem "the build includes '$want' and $FLOORS does not declare it"
    fi
  done
  fi_i=0
  while [ "$fi_i" -lt "${#floor_names[@]}" ]; do
    case $roster_words in
      *" ${floor_names[$fi_i]} "*) ;;
      *) floor_problem "$FLOORS declares '${floor_names[$fi_i]}', which is not a module in this build" ;;
    esac
    fi_i=$((fi_i + 1))
  done
fi

shopt -s nullglob
files=(*/build/test-results/*/*.xml)
shopt -u nullglob

# ------------------------------------------------------------------ the parse
#
# Accumulate each file whole before matching, rather than matching line by line: a
# `<testsuite …>` tag split across lines is still one tag, and a per-line regex silently reads it
# as zero suites. Buffers are per file and these files are small.
modules=''
total=''
awk_rc=0
if [ "${#files[@]}" -gt 0 ]; then
  counts=$(
    awk '
      function attr(text, name) {
        if (match(text, name "=\"[^\"]*\"")) {
          return substr(text, RSTART + length(name) + 2, RLENGTH - length(name) - 3) + 0
        }
        return 0
      }
      function flush(file,   m, parts, rest, tag, got) {
        if (file == "") return
        split(file, parts, "/")
        m = parts[1]
        rest = buf
        got = 0
        while (match(rest, /<testsuite [^>]*>/)) {
          tag = substr(rest, RSTART, RLENGTH)
          rest = substr(rest, RSTART + RLENGTH)
          got = 1
          seen[m] = 1
          suites[m] += 1;                 SUITES += 1
          t[m] += attr(tag, "tests");     T += attr(tag, "tests")
          s[m] += attr(tag, "skipped");   S += attr(tag, "skipped")
          f[m] += attr(tag, "failures");  F += attr(tag, "failures")
          e[m] += attr(tag, "errors");    E += attr(tag, "errors")
        }
        # Per file, not per suite: the question downstream is "did every file the glob matched
        # contribute", and a file holding two suites must not pay for one holding none.
        if (got) READ += 1
      }
      FNR == 1 { flush(cur); cur = FILENAME; buf = "" }
      { buf = buf $0 " " }
      END {
        flush(cur)
        for (m in seen) printf "%s %d %d %d %d %d\n", m, suites[m], t[m], s[m], f[m], e[m]
        printf "TOTAL %d %d %d %d %d %d\n", SUITES, T, S, F, E, READ
      }
    ' "${files[@]}" 2>/dev/null
  )
  awk_rc=$?
  modules=$(printf '%s\n' "$counts" | grep -v '^TOTAL ' | sort)
  total=$(printf '%s\n' "$counts" | grep '^TOTAL ' | head -1)
fi

tot_suites=''; tot_tests=''; tot_skipped=''; tot_failures=''; tot_errors=''; tot_read=''
if [ -n "$total" ]; then
  read -r _ tot_suites tot_tests tot_skipped tot_failures tot_errors tot_read <<< "$total"
fi
tot_suites=$(num "$tot_suites")
tot_tests=$(num "$tot_tests")
tot_skipped=$(num "$tot_skipped")
tot_failures=$(num "$tot_failures")
tot_errors=$(num "$tot_errors")
tot_read=$(num "$tot_read")

# ------------------------------------------------------------------ floors against counts
#
# `modules` is empty when the glob matched nothing, and that is not a special case here: every
# declared module is then missing, which is exactly what it means.
floor_failures=()
floor_drift=''

counts_for() {
  local m su te sk fa er
  while read -r m su te sk fa er; do
    if [ "$m" = "$1" ]; then
      printf '%s %s %s %s %s' "$su" "$te" "$sk" "$fa" "$er"
      return 0
    fi
  done <<< "$modules"
  return 1
}

evaluate_floors() {
  local i=0 name min row su te span m rest
  while [ "$i" -lt "${#floor_names[@]}" ]; do
    name=${floor_names[$i]}
    min=${floor_mins[$i]}
    i=$((i + 1))
    if ! row=$(counts_for "$name"); then
      if [ "$min" -gt 0 ]; then
        floor_failures+=("\`$name\` produced no test results at all; the floor says $min")
      fi
      continue
    fi
    read -r su te _ _ _ <<< "$row"
    te=$(num "$te")
    su=$(num "$su")
    # A floor that everything has grown past still catches a module reporting nothing, and stops
    # catching anything smaller — which is how a floor quietly becomes a rubber stamp. That decay
    # is worth a line of prose, and it is prose rather than a gate because raising a floor is
    # never *required*. But the gap at which it is worth saying is not "any gap at all": one test
    # added is the sanctioned state, and a note that fires on it fires always.
    #
    # `span` is the threshold, and it is measured from this same run rather than fixed here: the
    # module's own mean tests-per-suite, which is what one of its test classes is worth. A gap at
    # or above it means a whole average-sized class could stop running with this gate still green,
    # which is the floor having stopped constraining at the granularity it was committed for — not
    # having stopped constraining altogether, since a loss larger than the gap still reds. Below
    # it the `floor` column in the table carries the number and nothing is being asked of anyone.
    # A module with more suites than tests has no meaningful class size, so the threshold floors
    # at 1 and any drift there is reported.
    #
    # "Derived from the run" is the claim, and the matrix holds it: `driftup` and `driftwide` are
    # the same gap of 30 with opposite verdicts, differing only in module shape, so no constant
    # written here can satisfy both. Without that row a hard-coded 25 passed the whole suite —
    # and a constant is not a harmless simplification, it silences the note permanently for any
    # module smaller than it.
    span=1
    if [ "$su" -gt 0 ] && [ $((te / su)) -gt 1 ]; then span=$((te / su)); fi
    if [ "$te" -lt "$min" ]; then
      floor_failures+=("\`$name\` reported $te tests, below the committed floor of $min")
    elif [ $((te - min)) -ge "$span" ]; then
      floor_drift="$floor_drift \`$name\` $min→$te,"
    fi
  done
  while read -r m rest; do
    [ -n "$m" ] || continue
    if ! floor_min_of "$m" >/dev/null; then
      floor_failures+=("\`$m\` reported tests and is not declared in the floor file, so nothing says what it should have run")
    fi
  done <<< "$modules"
}

# The parse produced nothing usable: awk failed or is absent, or it ran and found no
# `<testsuite>` element in files that exist. Truncated or corrupt XML from a hard-killed test
# worker lands here too. Files were present, so this is not the empty-glob case — it is a failed
# measurement, and the one thing it must not do is read as a good one.
measured=yes
nofiles=no
if [ "${#files[@]}" -eq 0 ]; then
  nofiles=yes
  measured=no
elif [ "$awk_rc" -ne 0 ] || [ -z "$total" ] || [ "$tot_suites" -eq 0 ]; then
  measured=no
  echo "::warning title=Test counts unreadable::${#files[@]} JUnit XML file(s) matched, but no <testsuite> element could be read from them (awk exit ${awk_rc}). The counts below are absent, not zero."
fi

# Floors are compared against a measurement, so a failed parse suppresses the comparison rather
# than turning into a shortfall that says something it cannot know. The unreadable case is loud
# on its own account and withholds the reassurance already. An empty glob is different: nothing
# was unreadable, there was simply nothing, and every declared module really did produce nothing.
if [ -z "$floors_error" ] && { [ "$measured" = yes ] || [ "$nofiles" = yes ]; }; then
  evaluate_floors
fi

floors_rc=0
floors_note=''
if [ -n "$floors_error" ]; then
  floors_rc=1
  echo "::error title=Test floor file unusable::$floors_error"
  floors_note="> **The test floor file could not be used**: $floors_error. Nothing here compared what ran against what should have run, so the counts above describe the subset that happened to report — which is the reading this whole script exists to stop being believed."
elif [ "${#floor_failures[@]}" -gt 0 ]; then
  floors_rc=1
  for ff in "${floor_failures[@]}"; do
    echo "::error title=Test floor not met::${ff//\`/}"
  done
  floors_note="> **Coverage is below the committed floor** (\`$FLOORS\`), which is what this build says it should have run:"
  for ff in "${floor_failures[@]}"; do
    floors_note="$floors_note
> - $ff"
  done
  floors_note="$floors_note
>
> A module reports nothing for reasons that are rarely deliberate — a disabled test task, a module never wired to \`check\`, a cached task replayed as up-to-date, a run filtered to an empty set — and every one of them renders as an honest, smaller table with \`skipped\` at zero. If the drop is intended, lower the floor in the commit that causes it."
fi

# Some of it read, and some of it did not (#83). The totals below are then a sum over a subset —
# arithmetically fine, and a lie about coverage, because nothing on the page says which subset.
# `skipped=0` over four files out of five is the same rendering as `skipped=0` over five, and the
# reader who could tell them apart is the one who already knew the answer.
unread=0
partial=no
if [ "$measured" = yes ] && [ "$tot_read" -lt "${#files[@]}" ]; then
  partial=yes
  unread=$(( ${#files[@]} - tot_read ))
  echo "::warning title=Test counts incomplete::${unread} of ${#files[@]} JUnit XML file(s) yielded no <testsuite> element. The counts below are a lower bound, not a total."
fi

if [ "$nofiles" = yes ]; then
  # Either nothing got as far as running a test, or the glob is wrong. Those look identical from
  # here, which is exactly how #65 survived, so say so out loud rather than staying quiet about it.
  echo "::warning title=No JUnit XML::No files matched */build/test-results/*/*.xml. Either no test task ran, or the path this workflow reads is wrong."
  {
    echo "### Test counts (from the JUnit XML)"
    echo
    echo 'No JUnit XML found under `*/build/test-results/*/*.xml`.'
    if [ -n "$floors_note" ]; then
      echo
      echo "$floors_note"
    fi
  } | emit
  exit "$floors_rc"
fi

# The names to print: everything that reported, plus everything the floor file declares. A
# declared module that produced nothing has to appear as a row, because a module that is simply
# absent from a table is the one thing a reader cannot notice.
row_names() {
  local m rest
  while read -r m rest; do
    [ -n "$m" ] || continue
    printf '%s\n' "$m"
  done <<< "$modules"
  if [ "${#floor_names[@]}" -gt 0 ]; then
    printf '%s\n' "${floor_names[@]}"
  fi
}

{
  echo "### Test counts (from the JUnit XML)"
  echo
  echo "| module | suites | tests | skipped | failures | errors | floor |"
  echo "|---|---:|---:|---:|---:|---:|---:|"
  if [ "$measured" = yes ]; then
    while read -r m; do
      [ -n "$m" ] || continue
      if fl=$(floor_min_of "$m"); then :; else fl='none'; fi
      if row=$(counts_for "$m"); then
        read -r su te sk fa er <<< "$row"
        printf '| `%s` | %s | %s | %s | %s | %s | %s |\n' "$m" "$su" "$te" "$sk" "$fa" "$er" "$fl"
      else
        printf '| `%s` | — | — | — | — | — | **%s, and it reported nothing** |\n' "$m" "$fl"
      fi
    done <<< "$(row_names | sort -u)"
    # The label carries the caveat, because the table is the part that gets copied out of the
    # summary and pasted into a PR body without the prose under it.
    if [ "$partial" = yes ]; then
      row_label='**total (lower bound)**'
    else
      row_label='**total**'
    fi
    printf '| %s | **%s** | **%s** | **%s** | **%s** | **%s** | **%s** |\n' \
      "$row_label" "$tot_suites" "$tot_tests" "$tot_skipped" "$tot_failures" "$tot_errors" \
      "$tot_floor"
  else
    printf '| **total** | *unread* | *unread* | *unread* | *unread* | *unread* | **%s** |\n' \
      "$tot_floor"
  fi
  echo
  if [ -n "$floors_note" ]; then
    echo "$floors_note"
  fi
  if [ "$measured" = no ]; then
    echo "> **These counts were not measured.** ${#files[@]} JUnit XML file(s) were found, but no \`<testsuite>\` element could be read from them — a missing or broken \`awk\`, or truncated XML from a killed test worker. **This says nothing about whether the tests ran or skipped**; do not read it as \`skipped=0\`."
  elif [ "$tot_tests" -eq 0 ]; then
    echo "> **No tests were counted**, across ${tot_suites} suite(s). A suite element that reports zero tests is not evidence that anything executed, so the \`skipped\` figure carries no reassurance here."
  else
    # A shortfall and a non-zero skip count are separate facts, and both get said. The
    # reassurance is the only line withheld, because it is the only one that would be false.
    if [ "$partial" = yes ]; then
      echo "> **${unread} of ${#files[@]} JUnit XML file(s) could not be read.** The counts above are a lower bound, not a total, and \`skipped\` carries no reassurance: a file that yielded no \`<testsuite>\` contributed zero to every column, including that one."
    fi
    if [ "$tot_skipped" -gt 0 ]; then
      echo '> `skipped` is not zero. A tool-gated test that skipped verified nothing; check which one.'
    elif [ "$partial" = no ] && [ "$floors_rc" -eq 0 ]; then
      echo '> `skipped` is zero, so every tool-gated test executed rather than opting out.'
    fi
    if [ -n "$floor_drift" ]; then
      echo "> Floors that have stopped constraining:${floor_drift%,}. Each gap is at least that module's own mean tests-per-suite, so a whole average test class could stop running with this gate still green. What is left is not nothing — the floor still catches any loss larger than the gap — but a test class is the unit a floor is worth committing in, and this one no longer catches that. Growth on its own is not on this line — adding tests needs no edit, and the \`floor\` column above already carries it. Raise these in \`$FLOORS\` off a CI run on a merged tree, which is the only tree whose counts will still be true after the merge."
    fi
  fi
} | emit

exit "$floors_rc"
