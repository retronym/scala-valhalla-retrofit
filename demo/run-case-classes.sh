#!/usr/bin/env bash
# Phase 2 demo: opt a final case class of final fields into value-class
# translation with @ValueClass (valhalla-annotations). Run from repo root:
#   ./demo/run-case-classes.sh
set -euo pipefail
cd "$(dirname "$0")/.."

JH="${JAVA_HOME_PREVIEW:-jdk/jdk-27.jdk/Contents/Home}"
JAR=agent/target/strict-init-retrofit.jar
ANN="$(echo annotations/target/valhalla-annotations-*.jar)"
SCALA_LIB="$(cs fetch org.scala-lang:scala3-library_3:3.7.0 2>/dev/null | tr '\n' ':')"
quiet() { grep -v -E "WARNING|reporting|will be removed|Unsafe::" || true; }

[ -f "$JAR" ] || { echo "build first: mvn -DskipTests package"; exit 1; }
[ -f "$ANN" ] || { echo "build first: mvn -DskipTests package"; exit 1; }

echo "### 1. compile @ValueClass-annotated case classes against valhalla-annotations"
rm -rf demo/cc-out demo/cc-rw && mkdir -p demo/cc-out
scalac -cp "$ANN" -d demo/cc-out demo/src/CaseClasses.scala 2>&1 | quiet

echo
echo "### 2. promote (offline). @ValueClass opts in; float/double gated unless allowFloating=true"
"$JH/bin/java" -cp "$JAR" au.id.zaugg.strictinit.Rewriter --value-classes demo/cc-out demo/cc-rw 2>&1 \
  | grep -E "value-class\]" | grep -v '\$'

echo
echo "### 3. run a driver compiled against ORIGINAL classes against the PROMOTED ones"
"$JH/bin/javac" -cp "demo/cc-out:$SCALA_LIB" -d demo/cc-rw demo/CCDriver.java
"$JH/bin/java" --enable-preview -Xverify:all -cp "demo/cc-rw:$SCALA_LIB" CCDriver 2>&1 | quiet

echo
echo "### 4. same via the LOAD-TIME agent (valueclass mode) on the ORIGINAL classes"
"$JH/bin/javac" -cp "demo/cc-out:$SCALA_LIB" -d demo/cc-out demo/CCDriver.java
"$JH/bin/java" --enable-preview -Xverify:all \
  -javaagent:"$JAR=valueclass;verbose;include=Complex,Mixed,WithDouble,Vec2,NotAnnotated,CCDriver" \
  -cp "demo/cc-out:$SCALA_LIB" CCDriver 2>&1 | quiet | grep -E "value-class\]|==" | grep -v '\$'
