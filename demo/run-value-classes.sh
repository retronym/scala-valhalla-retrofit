#!/usr/bin/env bash
# Phase 1 demo: promote erased `extends AnyVal` classes to real JEP 401 value
# classes (integral underlying), gating out float/double. Run from repo root:
#   ./demo/run-value-classes.sh
set -euo pipefail
cd "$(dirname "$0")/.."
source demo/common.sh
require_preview_jdk; require_tools; require_built
SCALA_LIB="$(scala_lib)"

echo "### 1. compile erased AnyVal value classes with stock scalac"
rm -rf demo/vc-out demo/vc-rw && mkdir -p demo/vc-out
scalac -d demo/vc-out demo/src/ValueClasses.scala 2>&1 | quiet
echo "    UserId stock flags (identity class):"
"$JH/bin/javap" -v demo/vc-out/UserId.class | grep -E "^  flags:" | head -1

echo
echo "### 2. offline-promote (float/double gated by default), inspect result"
"$JH/bin/java" -cp "$JAR" au.id.zaugg.strictinit.Rewriter --value-classes demo/vc-out demo/vc-rw 2>&1
echo "    UserId promoted (value class — ACC_IDENTITY cleared):"
"$JH/bin/javap" -p demo/vc-rw/UserId.class | grep "class UserId"

echo
echo "### 3. run a driver compiled against ORIGINAL classes against the PROMOTED ones"
"$JH/bin/javac" -cp "demo/vc-out:$SCALA_LIB" -d demo/vc-rw demo/VCDriver.java
"$JH/bin/java" --enable-preview -Xverify:all -cp "demo/vc-rw:$SCALA_LIB" VCDriver 2>&1 | quiet

echo
echo "### 4. same, via the LOAD-TIME agent on the ORIGINAL classes (valueclass mode)"
"$JH/bin/javac" -cp "demo/vc-out:$SCALA_LIB" -d demo/vc-out demo/VCDriver.java
"$JH/bin/java" --enable-preview -Xverify:all \
  -javaagent:"$JAR=valueclass;include=UserId,Money,Ratio,VCDriver;verbose" \
  -cp "demo/vc-out:$SCALA_LIB" VCDriver 2>&1 | quiet
