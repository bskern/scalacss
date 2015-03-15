import argonaut._
import Argonaut._

object Caniuse {

  implicit class ExtAny[A](val _a: A) extends AnyVal {
    def |>[AA, B](f: AA => B)(implicit g: A => AA): B = f(g(_a))
  }

  def str: Json => String = _.string.get.toString
  implicit def unpackO[A](a: Option[A]): A = a.get

  def camel(s: String) = "[_-]+([a-z\\d])".r.replaceAllIn(s, _.group(1).toUpperCase)

  def htmlsafe(s: String) = s.replace("<","&lt;").replace(">","&gt;")

  def main(args: Array[String]): Unit = {

    val dataTxt = io.Source.fromFile("caniuse/data.json").mkString
    println("Parsing...")
    val json = Parse.parse(dataTxt).fold(sys.error, identity)

    // ====================================================================================================

    val N = new NaturalOrderComparator()
    def consolidate(m: MSS): MSS =
      m.toStream.sortWith((a,b) => N.compare(a._1, b._1)<0)
          .foldLeft(Map.empty[String, String]){case (q,(a,b)) => q.updated(b, q.get(b).fold(a)(_+","+a)) }
          .toStream.map{case (a,b) => (b,a)}.toMap
    val removeAt: String => String =
      _.replaceFirst("^@","")

    val agentkey0: String => String = {
      case "ie"      => "IE"
      case "firefox" => "Firefox"
      case "chrome"  => "Chrome"
      case "safari"  => "Safari"
      case "opera"   => "Opera"
      case "ios_saf" => "IOSSafari"
      case "op_mini" => "OperaMini"
      case "android" => "AndroidBrowser"
      case "op_mob"  => "OperaMobile"
      case "bb"      => "BlackberryBrowser"
      case "and_chr" => "AndroidChrome"
      case "and_ff"  => "AndroidFirefox"
      case "ie_mob"  => "IEMobile"
      case "and_uc"  => "AndroidUC"
    }

    val agentkey: String => String =
      k => String.format("%-17s", agentkey0(k))

    case class Agent(
      key: String,
      browser: String,
      versions: List[String],
      prefix: String,
      prefixExceptions: Map[String,String]) {

      def allPrefixes = prefixExceptions.values.toSet + prefix
    }

    val agents = {
      val jo = json.field("agents").obj.get
        jo.fields.map{k =>
        val j = jo(k).get
        Agent(k |> agentkey,
          j.field("browser") |> str,
          j.field("versions").array.get.filterNot(_.isNull).map(str),
          j.field("prefix") |> str,
          j.field("prefix_exceptions").map(_.jdecode[MSS].toOption.get |> consolidate) getOrElse Map.empty)
      }
    }

    println(s"Found ${agents.size} agents...")

    type MSS = Map[String, String]

    case class Data(
      key: String,
      title: String,
      desc: String,
      spec: String,
      stats: Map[String, MSS]) {

      val scalaval = "^css([A-Z])".r.replaceAllIn(camel(key), _.group(1).toLowerCase)
    }

    val dataj = json.cursor --\ "data" focus
    val cssj = dataj.assoc.get.filter(kv =>
                 (+kv._2 --\ "categories" focus).toString.toUpperCase contains "CSS")
    val data = cssj.map{ case (k,v) =>
      Data(k.toString,
        v.field("title") |> str |> removeAt,
        v.field("description") |> str,
        v.field("spec") |> str,
        (+v --\ "stats" focus).jdecode[Map[String, MSS]].toOption.get mapValues consolidate)
    }.sortBy(_.scalaval)

    println(s"Found ${data.size} CSS properties...")

    // ====================================================================================================
    val obj = "CanIUse"
    val pkg = "japgolly.scalacss"
    val fout = s"../core/src/main/scala/${pkg.replace('.','/')}/$obj.scala"

    val fmtstr: String => String =
      s => s""""$s""""

    val fmtsup: String => String =
      s => {
        def x = if (s.contains("x")) "X" else ""
        s.head match {
          case 'y' => s"Full$x"
          case 'a' => s"Partial$x"
          case 'n' => "Unsupported"
          case 'p' => "Unsupported" // Don't ask me why. Eg: css-fixed, css-grid
          case 'u' => "Unknown"
          case _   => sys error s"What? '$s'"
        }
      }

    def fmtmap[K,V](f: K => String, g: V => String): Map[K,V] => String =
      // m => m.toList.foldLeft("Map.empty"){case (q,(k,v)) => s"$q.updated(${f(k)},${g(v)})" }
      m => if (m.isEmpty) "Map.empty" else
      m.toStream.map{case (k,v) => (f(k),g(v))}.sortBy(_._1)
        .foldLeft("Map("){case (q,(k,v)) => s"$q$k -> $v, "}.dropRight(2) + ")"

    def fmtpref2(p: String) = String.format("%-6s", p)

    def fmtpref(p: String): String = {
      val p2 = fmtpref2(p)
      s"""case object $p2 extends Prefix("$p")"""
    }

    // def fmtAgent(a: Agent) = {
      // import a._
      // s"""/** $browser */
    // val $key = Agent("$prefix", ${fmtmap(fmtstr, fmtstr)(prefixExceptions)})
// """}

    def fmtAgent(a: Agent) = {
      import a._
      val p2 = fmtpref2(prefix)
      s"""val $key = Agent($p2, ${fmtmap(fmtstr, identity[String])(prefixExceptions)})"""
    }

    def fmtData(d: Data) = {
      import d._
      val stats2 = stats.mapValues(m => m.toList.map{case(a,b) => (b,a)}.toMap)
      s"""/**
   * ${htmlsafe(title)}
   *
   * ${htmlsafe(desc)}
   *
   * $spec
   */
  def $scalaval: Subject = ${fmtmap((s: String) => "\n    "+agentkey(s), fmtmap(fmtsup, fmtstr))(stats2)}
"""}

    val prefixes = agents.map(_.allPrefixes).reduce(_ ++ _).toList.sorted

    val output = s"""package $pkg

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
// NOTICE: This file is generated by misc/caniuse.scala
//
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

object $obj {
  type VerStr  = String
  type Subject = Map[Agent, Map[Support, VerStr]]

  sealed trait Support
  object Support {
    case object Unsupported extends Support
    case object Unknown     extends Support
    case object Partial     extends Support
    case object Full        extends Support
    case object PartialX    extends Support
    case object FullX       extends Support
  }

  sealed abstract class Prefix(val value: String)
  object Prefix {
    ${prefixes map fmtpref mkString "\n    "}
  }

  import Prefix._

  final case class Agent(prefix: Prefix, prefixExceptions: Map[VerStr, Prefix])
  object Agent {
    ${agents.sortBy(_.key) map fmtAgent mkString "\n    "}
  }

  import Agent._
  import Support._

  ${data map fmtData mkString "\n  "}
}
""".replaceAll(" +\n","\n")

    // println(s"\n${"-"*100}\n$output")

    println(s"Writing to $fout")
    val bytes = output.getBytes("UTF-8")
    import java.nio.file._
    Files.write(Paths.get(fout), bytes)
    println(s"Wrote ${bytes.length} bytes.")

    println("Done.")
  }
}