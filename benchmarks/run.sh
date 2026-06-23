#!/usr/bin/env bash
# JMH benchmarks: a promoted value class vs an identical reference case class,
# with the built-in allocation profiler (-prof gc). The retrofit agent is wired
# into the FORKED JVMs (load-time, the path of least resistance): it promotes the
# @ValueClass data class while the un-annotated one stays a reference class, so
# both run side by side in the same fork.
#
#   ./benchmarks/run.sh                 # all benchmarks, gc profiler
#   ./benchmarks/run.sh scalarize       # only the scalarization benchmarks
#   ./benchmarks/run.sh -f 2 -wi 5 -i 10
set -euo pipefail
cd "$(dirname "$0")/.."
source demo/common.sh
require_preview_jdk

AGENT=agent/target/strict-init-retrofit.jar
BENCH=benchmarks/target/benchmarks.jar
[ -f "$AGENT" ] || die "agent jar missing. Build first:  mvn -DskipTests package"
[ -f "$BENCH" ] || die "benchmarks jar missing. Build first:  mvn -DskipTests package"

# Forked JVMs get --enable-preview and the agent in valueclass mode; the include
# filter limits promotion to VPoint (RPoint is left as a plain reference class).
"$JH/bin/java" --enable-preview -jar "$BENCH" \
  -jvmArgsAppend "--enable-preview -javaagent:$AGENT=valueclass;include=au.id.zaugg.bench.VPoint" \
  -prof gc \
  "$@"
