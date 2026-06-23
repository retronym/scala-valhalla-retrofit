object Driver {
  def main(args: Array[String]): Unit = {
    val b = B(99)
    println(s"B._1 = ${b._1}, base = ${b.base}")
    println(s"Obj.eager = ${Obj.eager}, mutableV = ${Obj.mutableV}")
    Obj.mutableV = 123
    println(s"Obj.lazi = ${Obj.lazi}")
    println(s"MODULE$$ identity: ${Obj eq Obj}")
    println("OK: strict-init classes loaded, verified and ran")
  }
}
