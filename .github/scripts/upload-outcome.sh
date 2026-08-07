#!/usr/bin/env bash
# The body of the `test-artifacts` job: given the build job's result and the two upload steps'
# outcomes, say whether the evidence for this run was captured, and go red if it was not.
#
#   BUILD=<needs.build.result> XML=<xml-upload outcome> HTML=<html-upload outcome> upload-outcome.sh
#
# It is a script rather than fifteen lines of YAML because #99 was found by running those fifteen
# lines by hand over thirteen inputs, and a check that can only be exercised by pushing to a
# branch is one nobody exercises. `.github/scripts/upload-outcome-matrix.sh` is its suite.
#
# **Why the upload steps report from here at all.** They carry `continue-on-error`, so a failure
# to upload cannot red the check named `build` — #57 is the run where a symlink loop in the upload
# reported the build as broken while every test passed. That makes them silent, which #57 also
# rules out, so this job re-raises the same failure under a name that says what actually broke.
#
# **The defect this version fixes.** The first one reddened on the literal string `failure` and on
# nothing else. Run over thirteen inputs, `''`, `skipped` and `typo-not-an-outcome` all exited 0.
# Rename a step `id`, or drop the `outputs:` block, and `${{ steps.upload-xml.outcome }}`
# evaluates to the empty string — the job then printed a `::notice` saying it measured nothing,
# and went green forever. The author anticipated the lost-runner case, which is why the notice
# exists; **broken wiring reads identically to it**, and there is nothing else left to notice it:
# `continue-on-error` rewrites `conclusion` to `success` and the API's `steps[]` carries no
# `outcome`, so across 119 runs the two upload steps show `{'success': 21}` by conclusion while
# five of those 21 were red in the logs. This job is the only place an upload failure is legible.
#
# So the classification is inverted. Rather than naming the one value that is bad, it names the
# two that are benign — `success`, and `cancelled` for a run that was stopped mid-step — and
# everything else is a red, including the empty string and including `skipped`. `skipped` is on
# the red side deliberately and it is a departure from #99's recommendation: both upload steps are
# declared `if: always()`, so a skipped outcome means the workflow no longer runs the step this
# check reports on, and that is broken wiring wearing the coat of a no-verdict.
#
# **The one exemption, and why it is narrow.** When *both* outcomes are empty and the build job
# did not succeed, this reports a notice and exits 0. A lost runner, or a job that failed before
# either step was reached, produces exactly that — and a red here would be a positive claim that
# the artifact wiring is broken, drawn from a run whose own red is already the signal. The
# exemption does not extend to a *successful* build: `if: always()` steps run and report, so two
# empty outcomes under a green build is the wiring failure, not the absence of one. It is
# self-correcting either way — fix the build, and the next green run reds here if the wiring is
# what was wrong.
set -uo pipefail

BUILD=${BUILD:-}
XML=${XML:-}
HTML=${HTML:-}

echo "This check reports one thing: whether an artifact upload step failed."
echo "It is not a claim that the evidence is complete — read the build check for that."
echo "build job: ${BUILD:-unknown}"
echo "JUnit XML upload: ${XML:-no-outcome-reported}"
echo "HTML report upload: ${HTML:-no-outcome-reported}"

if [ -z "$XML" ] && [ -z "$HTML" ]; then
  if [ "$BUILD" = success ]; then
    echo "::error title=No upload outcome::The build job succeeded and neither upload step reported an outcome. Both are declared \`if: always()\`, so they ran — which leaves the wiring between them and this job: a renamed step \`id\`, or a missing \`outputs:\` entry. Nothing vouches for the evidence of this run."
    exit 1
  fi
  # The build job ended without either upload step reporting an outcome — a lost runner, or a
  # failure before the steps were reached. Nothing was measured, so say that rather than passing
  # silently as though it had been, and rather than asserting a fault this cannot see.
  echo "::notice title=No upload outcome::The build job (result: ${BUILD:-unknown}) reported no upload outcome at all, so this check measured nothing. Its own red is the signal for this run."
  exit 0
fi

rc=0

unexpected() {
  echo "::error title=Upload outcome unrecognised::The $1 upload reported '${2:-<empty>}', which is not an outcome this check knows how to read. An empty value means the wiring broke — a renamed step \`id\`, or a missing \`outputs:\` entry — and a \`skipped\` one means the step is no longer run despite being declared \`if: always()\`. Either way nothing here vouches for the evidence."
}

classify() {
  case ${2:-} in
    success)
      return 0
      ;;
    cancelled)
      echo "::notice title=${1} upload cancelled::The run was stopped while the $1 upload was in flight, so there is no verdict on it either way."
      return 0
      ;;
    failure)
      echo "::error title=${1} not uploaded::The $1 artifact failed to upload. The build's own result is unaffected; what is missing is the evidence for it."
      return 1
      ;;
    *) unexpected "$1" "${2:-}"; return 1 ;;
  esac
}

classify "JUnit XML" "$XML" || rc=1
classify "HTML report" "$HTML" || rc=1

if [ "$rc" = "1" ] && [ "$BUILD" != "success" ]; then
  echo "::notice title=Read both checks::The build job's own result was ${BUILD:-unknown}. That is a separate fact from the upload problem above; neither one implies the other."
fi

exit $rc
