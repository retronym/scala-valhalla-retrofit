object Obj {
  val eager: Int = 42
  var mutableV: Int = 7
  lazy val lazi: String = { println("init"); "hi" }
}
abstract class A(val base: Int)
case class B(_1: Int) extends A(_1)
