#!/bin/sh
# Local secret guard (not part of the repo). Usage:
#   secret-scan.sh staged          -> scan what is about to be committed
#   secret-scan.sh range <a>..<b>  -> scan commits about to be pushed
PATTERN='gsk_[A-Za-z0-9]{20,}|sk-or-v1-[A-Za-z0-9]{20,}|sk-(proj-)?[A-Za-z0-9_-]{32,}|sk-ant-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{35}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|^\+[[:space:]]*(storePassword|keyPassword)[[:space:]]*=[[:space:]]*[^[:space:]k"<][^[:space:]]{3,}'
BADFILES='(^|/)(keystore\.properties|local\.properties|\.env|[^/]*\.jks|[^/]*\.keystore|[^/]*\.p12)$'
if [ "$1" = "range" ]; then
  files=$(git log --format= --name-only --diff-filter=AM "$2" | sort -u)
  diff=$(git log -p --format= "$2")
else
  files=$(git diff --cached --name-only --diff-filter=AM)
  diff=$(git diff --cached -U0)
fi
fail=0
bad=$(printf '%s\n' "$files" | grep -E "$BADFILES")
if [ -n "$bad" ]; then echo "secret-scan: refusing secret-bearing file(s):"; echo "$bad"; fail=1; fi
hits=$(printf '%s\n' "$diff" | grep '^+' | grep -nE "$PATTERN")
if [ -n "$hits" ]; then echo "secret-scan: possible secret(s) in added lines:"; printf '%s\n' "$hits" | cut -c1-120; fail=1; fi
[ $fail -eq 0 ] || echo "secret-scan: blocked. Remove the secret (or use --no-verify only if you are sure it is a false positive)."
exit $fail
