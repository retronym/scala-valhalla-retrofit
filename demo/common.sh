# Shared setup + prerequisite checks for the demo scripts. Sourced, not executed.
# Defines: JH, JAR, ANN, quiet(); and require_* guards. Call the guards before
# doing any work so failures are clear up front rather than mid-script.

JH="${JAVA_HOME_PREVIEW:-jdk/jdk-27.jdk/Contents/Home}"
JAR=agent/target/strict-init-retrofit.jar
ANN="$(echo annotations/target/valhalla-annotations-*.jar 2>/dev/null)"

quiet() { grep -v -E "WARNING|reporting|will be removed|Unsafe::" || true; }
die()   { echo >&2; echo "ERROR: $*" >&2; exit 1; }

# A JEP 401 early-access "Valhalla" JDK is mandatory: the retrofit stamps preview
# classfiles (value classes / strict fields) that only such a JVM will load.
require_preview_jdk() {
  if [ ! -x "$JH/bin/java" ]; then
    {
      echo
      echo "ERROR: JEP 401 preview JDK not found at:"
      echo "    $JH"
      echo
      echo "This project needs an early-access \"Valhalla\" build implementing JEP 401"
      echo "(value classes & strict field initialization). Download one from:"
      echo
      echo "    https://jdk.java.net/valhalla/"
      echo
      echo "Then extract it to ./jdk/ (so the default path resolves) or point"
      echo "JAVA_HOME_PREVIEW at its home directory, e.g.:"
      echo
      echo "    JAVA_HOME_PREVIEW=/path/to/jdk-27.jdk/Contents/Home $0"
    } >&2
    exit 1
  fi
  local feature
  feature="$("$JH/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
  if [ -z "$feature" ] || [ "$feature" -lt 27 ]; then
    die "JDK at $JH is Java '${feature:-unknown}', but JEP 401 needs an EA build (Java 27+).
       Get it from https://jdk.java.net/valhalla/ — see the README prerequisites."
  fi
}

require_tools() {
  command -v scalac >/dev/null 2>&1 || die "scalac not found on PATH (install Scala 3)."
  command -v cs     >/dev/null 2>&1 || die "coursier (cs) not found on PATH; needed to fetch the Scala library."
}

require_built() {
  [ -f "$JAR" ] || die "agent jar missing ($JAR). Build first:  mvn -DskipTests package"
}

require_annotations() {
  [ -n "$ANN" ] && [ -f "$ANN" ] || die "valhalla-annotations jar missing. Build first:  mvn -DskipTests package"
}

# Resolve the Scala 3 standard library once (call after require_tools).
scala_lib() { cs fetch org.scala-lang:scala3-library_3:3.7.0 2>/dev/null | tr '\n' ':'; }
