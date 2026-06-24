#!/usr/bin/env bash
# Generate before/after bytecode diffs for every transform the README describes.
#
# For each population (strict statics, pre-super param accessors, AnyVal value
# classes, @ValueClass case classes, config-driven stdlib types) it compiles the
# stock Scala class, runs the offline Rewriter, and diffs `javap -v` of the two.
#
# The retrofit only flips access-flag bits and bumps the classfile version, so
# the *interesting* diff is tiny. To keep it that way we project each class down
# to its structural lines (class header, version, class/member access flags)
# before diffing — that drops constant-pool renumbering and untouched code,
# which ASM may reorder even though no instruction byte changes.
#
# Usage:
#   ./demo/bytecode-diffs.sh            # print all sections as markdown to stdout
#   ./demo/bytecode-diffs.sh --write    # refresh the blocks embedded in README.md
#
# A section is embedded in the README between marker comments:
#   <!-- bytecode-diff:KEY -->  ...generated...  <!-- /bytecode-diff:KEY -->
set -euo pipefail
cd "$(dirname "$0")/.."
source demo/common.sh

MODE="${1:-print}"
[ "$MODE" = "--write" ] && MODE=write || MODE=print

require_preview_jdk; require_tools; require_built; require_annotations

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
B="$WORK/before"; A="$WORK/after"; mkdir -p "$B" "$A"

# --- compile the demo sources (stock scalac) and promote them offline ----------
scalac -d "$B" demo/src/ValueClasses.scala demo/src/Repros.scala >/dev/null 2>&1
scalac -cp "$ANN" -d "$B" demo/src/CaseClasses.scala >/dev/null 2>&1
"$JH/bin/java" -cp "$JAR" au.id.zaugg.strictinit.Rewriter --value-classes "$B" "$A" >/dev/null 2>&1

# stdlib types we cannot annotate: extract from scala-library, promote via config
SLIB="$(cs fetch org.scala-lang:scala-library:2.13.16 2>/dev/null | head -1 || true)"
if [ -n "${SLIB:-}" ] && [ -f "$SLIB" ]; then
  (cd "$B" && unzip -oq "$SLIB" 'scala/Option*.class' 'scala/Some*.class')
  "$JH/bin/java" -cp "$JAR" au.id.zaugg.strictinit.Rewriter \
    --config config/scala-stdlib.conf "$B" "$A" >/dev/null 2>&1
fi

# Project a class to the lines the retrofit can touch: class header, classfile
# version, and every access-flags line (class- and member-level). BSD awk has no
# \b, so match a literal " class "/" interface " in the column-0 header line.
proj() {
  "$JH/bin/javap" -p -v "$1" | awk '
    /^[a-zA-Z].* (class|interface) / { print; next }   # class header
    /^  (minor|major) version:/      { print; next }   # classfile version
    /^  flags: \(0x/                 { print; next }   # class access flags
    /^  [a-zA-Z].*;[ ]*$/            { print; next }   # member declarations
    /^    flags: \(0x/               { print; next }   # member access flags
  '
}

# Emit a ```diff block for one class (relative path under the before/after dirs).
diff_class() {
  # diff exits 1 when files differ (always, here) — don't let pipefail abort.
  { diff -U1 <(proj "$B/$1") <(proj "$A/$1") || true; } | tail -n +3
}

# Render a named section: heading caption + one or more class diffs.
section() {
  local key="$1" caption="$2"; shift 2
  echo "<!-- bytecode-diff:$key -->"
  echo '```diff'
  echo "$caption"
  local c
  for c in "$@"; do diff_class "$c"; done
  echo '```'
  echo "<!-- /bytecode-diff:$key -->"
}

render_all() {
  section strict-static \
    "# object Obj { val eager; lazy val lazi }  —  module + val statics go strict" \
    'Obj$.class'
  echo
  section strict-instance \
    "# case class B(_1: Int)  —  pre-super param accessor goes strict (no promotion)" \
    'B.class'
  echo
  section value-anyval \
    "# final class UserId(val raw: Int) extends AnyVal  —  erased wrapper -> value class" \
    'UserId.class'
  echo
  section value-caseclass \
    "# @ValueClass final case class Complex(re: Int, im: Int)  —  multi-field opt-in" \
    'Complex.class'
  if [ -f "$A/scala/Some.class" ]; then
    echo
    section value-config \
      "# config-driven (config/scala-stdlib.conf): a stdlib type you cannot annotate" \
      'scala/Option.class' 'scala/Some.class'
  fi
}

if [ "$MODE" = print ]; then
  render_all
  exit 0
fi

# --write: replace each <!-- bytecode-diff:KEY --> .. <!-- /bytecode-diff:KEY -->
# block in README.md with freshly generated content, in place.
README=README.md
[ -f "$README" ] || die "README.md not found"
TMP_OUT="$WORK/sections"; render_all > "$TMP_OUT"

python3 - "$README" "$TMP_OUT" <<'PY'
import re, sys
readme_path, sections_path = sys.argv[1], sys.argv[2]
readme = open(readme_path).read()
gen = open(sections_path).read()

blocks = {}
for m in re.finditer(r'<!-- bytecode-diff:(\S+) -->\n(.*?)<!-- /bytecode-diff:\1 -->',
                      gen, re.S):
    blocks[m.group(1)] = m.group(2)

missing = []
def repl(m):
    key = m.group(1)
    if key not in blocks:
        missing.append(key); return m.group(0)
    return f'<!-- bytecode-diff:{key} -->\n{blocks[key]}<!-- /bytecode-diff:{key} -->'

new = re.sub(r'<!-- bytecode-diff:(\S+) -->\n.*?<!-- /bytecode-diff:\1 -->',
             repl, readme, flags=re.S)

present = set(re.findall(r'<!-- bytecode-diff:(\S+) -->', readme))
unused = [k for k in blocks if k not in present]
if not present:
    print(f"no bytecode-diff markers found in {readme_path}; nothing written")
elif new != readme:
    open(readme_path, 'w').write(new)
    print(f"updated {len(present - set(missing))} block(s) in {readme_path}")
else:
    print(f"all {len(present)} block(s) already up to date in {readme_path}")
if missing:
    print("WARNING: markers with no generated content:", ", ".join(missing))
if unused:
    print("note: generated but no marker in README:", ", ".join(unused))
PY
