#!/usr/bin/env bash
# Config-driven promotion of stdlib types we cannot annotate (scala.util.Either /
# Left / Right), via config/scala-stdlib.conf. Also shows the honest gates:
# scala.Option (non-static inner) and Range (stateful super) are NOT promotable.
#   ./demo/run-config-classes.sh
set -euo pipefail
cd "$(dirname "$0")/.."
source demo/common.sh
require_preview_jdk; require_tools; require_built
CONF=config/scala-stdlib.conf

echo "### 1. fetch + extract scala-library 2.13"
SLIB="$(cs fetch org.scala-lang:scala-library:2.13.16 2>/dev/null | head -1)"
rm -rf demo/cfg-slib demo/cfg-rw && mkdir -p demo/cfg-slib
(cd demo/cfg-slib && unzip -oq "$SLIB")

echo
echo "### 2. promote per $CONF (offline). Note the gated reasons."
"$JH/bin/java" -cp "$JAR" au.id.zaugg.strictinit.Rewriter --config "$CONF" demo/cfg-slib demo/cfg-rw 2>&1 \
  | grep -iE "value-class\].*(scala/util/(Either|Left|Right) |scala/Option |scala/Some |Range )" \
  | grep -vE 'Either\$|Left\$|Right\$|Option\$|Some\$' || true
# Add a couple of gated examples explicitly for the demo:
"$JH/bin/java" -cp "$JAR" au.id.zaugg.strictinit.Rewriter \
  --value-classes-list 'scala.Option,scala.Some,scala.collection.immutable.Range$Inclusive' \
  demo/cfg-slib /tmp/cfg-gated 2>&1 \
  | grep -iE 'skip value-class (scala/Option|scala/Some|scala/collection/immutable/Range[$]Inclusive) ' | head -3

echo
echo "### 3. run a driver compiled against ORIGINAL against the PROMOTED stdlib"
mkdir -p demo/cfg-driver
cat > demo/cfg-driver/EitherDriver.java <<'EOF'
import scala.util.Left; import scala.util.Right; import scala.util.Either;
public class EitherDriver {
  public static void main(String[] a) {
    Left<String,Integer>  l1 = new Left<>("e"),  l2 = new Left<>("e");
    Right<String,Integer> r1 = new Right<>(7),   r2 = new Right<>(7);
    System.out.println("Left(e)  == Left(e)  [acmp] : " + (l1 == l2) + "   (promoted: == by state)");
    System.out.println("Right(7) == Right(7) [acmp] : " + (r1 == r2));
    System.out.println("Left vs Right (as Either)   : " + (((Either<String,Integer>) l1) == ((Either<String,Integer>) r1)));
    System.out.println("l1.value() = " + l1.value() + ", r1.value() = " + r1.value());
  }
}
EOF
"$JH/bin/javac" -cp "$SLIB" -d demo/cfg-driver demo/cfg-driver/EitherDriver.java
"$JH/bin/java" --enable-preview -Xverify:all -cp "demo/cfg-rw:demo/cfg-driver" EitherDriver 2>&1 | quiet

echo
echo "### 4. Option/Some promote too (Option\$WithFilter's nested-class identity flags"
echo "        are fixed transparently; load-time agent + narrow include; a"
echo "        for-comprehension exercises the non-static WithFilter inner class)"
SCALA3="$(cs fetch org.scala-lang:scala3-library_3:3.7.0 2>/dev/null | tr '\n' ':')"
cat > demo/cfg-driver/SomeFor.scala <<'EOF'
object SomeFor {
  def main(args: Array[String]): Unit = {
    val r = for { x <- Some(21) if x > 0 } yield x * 2   // -> Option.withFilter -> WithFilter
    println(s"for-comprehension guard via WithFilter: $r")
    println(s"Some(1) == Some(1) by state: ${Some(1) == Some(1)}")
  }
}
EOF
scalac -d demo/cfg-driver demo/cfg-driver/SomeFor.scala 2>&1 | quiet
"$JH/bin/java" --enable-preview -Xverify:all \
  -javaagent:"$JAR=valueclass;config=$CONF;include=scala.Option,scala.Some,scala.None,SomeFor" \
  -cp "demo/cfg-driver:$SCALA3" SomeFor 2>&1 | quiet
