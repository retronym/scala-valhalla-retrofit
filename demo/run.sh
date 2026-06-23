#!/usr/bin/env bash
# End-to-end demo: scalac -> load-time strict-init agent -> JEP 401 preview JVM.
# Run from the repo root:  ./demo/run.sh
set -euo pipefail
cd "$(dirname "$0")/.."

JH="${JAVA_HOME_PREVIEW:-jdk/jdk-27.jdk/Contents/Home}"
JAR=target/strict-init-retrofit.jar
SCALA_LIB="$(cs fetch org.scala-lang:scala3-library_3:3.7.0 2>/dev/null | tr '\n' ':')"
quiet() { grep -v -E "WARNING|reporting|will be removed|Unsafe::" || true; }

[ -f "$JAR" ] || { echo "build the agent first: mvn -DskipTests package"; exit 1; }

echo "### 1. compile Scala repros (object eager/lazy/var + case class) with stock scalac"
rm -rf demo/out demo/driver && mkdir -p demo/out demo/driver
scalac -d demo/out demo/src/Repros.scala 2>&1 | quiet
scalac -cp "$SCALA_LIB:demo/out" -d demo/driver demo/Driver.scala 2>&1 | quiet
echo "    stock bytecode version:"
"$JH/bin/javap" -v demo/out/B.class | grep -E "minor version|major version"

echo
echo "### 2. run the ORIGINAL classes through the LOAD-TIME agent on the preview JVM"
"$JH/bin/java" --enable-preview -Xverify:all \
  -javaagent:"$JAR=include=Obj,A,B;verbose" \
  -cp "demo/driver:demo/out:$SCALA_LIB" Driver 2>&1 | quiet

echo
echo "### 3. prove the JVM actually ENFORCES the retrofitted flag (negative test)"
ASM="$(cs fetch org.ow2.asm:asm:9.10.1 2>/dev/null | tr '\n' ':')"
"$JH/bin/javac" -cp "$ASM" -d demo/neg demo/neg/Gen.java
"$JH/bin/java" -cp "$ASM:demo/neg" Gen >/dev/null
"$JH/bin/javac" -d demo/neg demo/neg/RunPlain.java demo/neg/RunStrict.java
"$JH/bin/java" --enable-preview -Xverify:all -cp demo/neg RunPlain 2>&1 | quiet | tail -1
"$JH/bin/java" --enable-preview -Xverify:all -cp demo/neg RunStrict 2>&1 | grep -E "VerifyError" | head -1
