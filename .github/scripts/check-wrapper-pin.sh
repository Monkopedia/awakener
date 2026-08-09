#!/bin/sh
# Is the Gradle distribution actually pinned — not "is the pin right", but "is there one"?
#
#   check-wrapper-pin.sh [-C <dir>] [--properties <path>]
#
# Exactly one line on stdout, and one of three exit statuses:
#
#   pinned <sha256>      0   distributionSha256Sum is present and is a well-formed sha256
#   UNPINNED: <reason>   1   it is absent, empty, or malformed — this build verifies nothing
#   UNKNOWN: <reason>    2   the check could not be made, and nothing above is being claimed
#
# **WHY THIS EXISTS: the pin rejects a wrong value and accepts a missing one.** Measured on
# 9.5.1 with a fresh GRADLE_USER_HOME each time, four edits to the one line:
#
#   distributionSha256Sum=deadbeef…   EXIT=1  rejected, "Verification of Gradle distribution failed!"
#   distributionSha256Sum=            EXIT=1  rejected
#   (line deleted entirely)           EXIT=0  DOWNLOADED, ACCEPTED, RAN. Silently unverified.
#   distributionSha256Sum=bafc141b…   EXIT=0  accepted
#
# A control that rejects a wrong value but accepts a missing one is not a control. The wrapper
# has no opinion about absence because absence *is* its default — it is the pre-#101 behaviour,
# and it is reached by deleting one line. Nothing else in this repository notices:
# `gradle/actions/wrapper-validation` checks the wrapper **jar** against Gradle's published list
# and never opens gradle-wrapper.properties, and no Gradle task can hold the property either,
# because by the time any Gradle code runs the unverified distribution has already been unpacked
# and executed. So the assertion has to happen in a shell, before ./gradlew is invoked at all,
# which is what this is and why it is not a test.
#
# Deletion is the realistic accident, not corruption. A merge-conflict resolution across this
# file drops a line without announcing it, and the 9.5.1 -> 9.7.0 bump #101 defers has to rewrite
# exactly this line. One thing it is *not*: `./gradlew wrapper --gradle-version <v>` was expected
# to strip the sum during a bump and **does not** — it preserves the value and strips only the
# explanatory comments. That was checked rather than assumed, and it lowers the odds without
# removing the case.
#
# **What this deliberately does NOT check: whether the value is the right one.** A 64-hex string
# of zeroes passes here and is rejected by the wrapper; a deleted line passes the wrapper and is
# rejected here. The two gates catch disjoint failures and neither subsumes the other, so the
# matrix beside this file asserts the zeroed case passes — an unpinned-vs-wrong confusion here
# would silently turn two controls into one. Nor does it check that the wrapper *evaluates* the
# pin: that is the `Make the distribution checksum load-bearing` step in build.yml, which is a
# third distinct failure (present, correct, and never read).
#
# Switches are env vars rather than `Flags` objects because nothing here is on a JVM classpath
# and an agent runs this from a shell prompt. Defaults are the behaviour that would otherwise be
# hard-coded.
#
#   AWAKENER_WRAPPER_PROPERTIES  (default gradle/wrapper/gradle-wrapper.properties)
#                                the properties file to judge, relative to the working directory
#
# `-C` exists so this is never chained behind a `cd` — a `cd` that fails in front of a `&&` chain
# is how #122 and #123 got an answer the check never reached. A directory that cannot be entered
# is a reason the check could not be made, so it is reported as one.
#
# Written for POSIX sh, no `set -e`: every failure here is a result to report. Deliberately uses
# no grep — the parse is `sed` and shell `case`, so the answer does not depend on which grep is
# first on PATH.
set -u

FILE=${AWAKENER_WRAPPER_PROPERTIES:-gradle/wrapper/gradle-wrapper.properties}
DIR=''
KEY=distributionSha256Sum

usage() {
    sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
}

# Printed on stdout, because stdout is what a caller reads back and the reason is part of the
# result.
unpinned() {
    printf 'UNPINNED: %s\n' "$1"
    exit 1
}

unknown() {
    printf 'UNKNOWN: %s\n' "$1"
    exit 2  # the status that means "could not check"
}

while [ $# -gt 0 ]; do
    case $1 in
        -C|--properties)
            if [ $# -lt 2 ]; then
                unknown "$1 needs a value"
            fi
            case $1 in
                -C)          DIR=$2 ;;
                --properties) FILE=$2 ;;
            esac
            shift 2
            ;;
        -h|--help) usage; exit 0 ;;
        *) unknown "unrecognised argument '$1'" ;;
    esac
done

if [ -n "$DIR" ]; then
    cd "$DIR" 2>/dev/null || unknown "cannot enter directory '$DIR'"
fi

[ -e "$FILE" ] || unknown "no such file: '$FILE' (cwd $(pwd))"
[ -f "$FILE" ] || unknown "'$FILE' is not a regular file"
[ -r "$FILE" ] || unknown "'$FILE' is not readable"

# A properties file with no distributionUrl is not a wrapper properties file, and reporting it
# UNPINNED would be a claim about a pin that has nothing to pin. That is "could not check".
url=$(sed -n 's/^[[:space:]]*distributionUrl[[:space:]]*[=:].*/present/p' "$FILE" | tail -n 1)
[ -n "$url" ] || unknown "'$FILE' has no distributionUrl, so it is not a wrapper properties file"

# Two passes rather than one, because "absent" and "present but empty" both yield an empty value
# and want different messages. A commented-out line matches neither pattern: `#` is not
# whitespace, so `# distributionSha256Sum=…` reads as absent, which is what it is.
present=$(sed -n "s/^[[:space:]]*${KEY}[[:space:]]*[=:].*/present/p" "$FILE" | tail -n 1)
[ -n "$present" ] ||
    unpinned "'$FILE' has no ${KEY}. The wrapper will download the distribution and run it \
without verifying anything, and will exit 0 doing so."

value=$(sed -n "s/^[[:space:]]*${KEY}[[:space:]]*[=:][[:space:]]*\\(.*\\)\$/\\1/p" "$FILE" |
    tail -n 1 | tr -d '\r' | sed 's/[[:space:]]*$//')

[ -n "$value" ] ||
    unpinned "${KEY} in '$FILE' is present but empty. The wrapper rejects this one \
(exit 1), so it fails closed — but it is still not a pin."

# 64 lowercase hex. Java's Install.verifyDownloadChecksum compares the *string* it computed
# against this one, so an uppercase or truncated value can never match anything and is a pin
# that only ever fails — which reads as a compromised download rather than as a typo.
case $value in
    *[!0-9a-f]*)
        unpinned "${KEY} in '$FILE' is '$value', which is not lowercase hex. The wrapper \
compares strings, so this can never match and every build reads as a tampered distribution."
        ;;
esac
[ "${#value}" -eq 64 ] ||
    unpinned "${KEY} in '$FILE' is ${#value} hex characters, not 64. A truncated sha256 \
can never match what the wrapper computes."

printf 'pinned %s\n' "$value"
exit 0
