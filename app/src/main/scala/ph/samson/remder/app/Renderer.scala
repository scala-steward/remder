package ph.samson.remder.app

import java.awt.Desktop
import java.io.ByteArrayOutputStream
import java.util

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import better.files.File
import com.typesafe.scalalogging.StrictLogging
import net.sourceforge.plantuml.SourceStringReader
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.{FencedCodeBlock, Node}
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.{
  CoreHtmlNodeRenderer,
  HtmlNodeRendererContext,
  HtmlRenderer
}
import ph.samson.remder.app.Presenter.Present

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.sys.process.Process
import scala.util.{Failure, Success, Try}

class Renderer(presenter: ActorRef) extends Actor with ActorLogging {
  import Renderer._

  private val extensions = Seq(TablesExtension.create()).asJava
  private val parser = Parser
    .builder()
    .extensions(extensions)
    .build()
  private val renderer = HtmlRenderer
    .builder()
    .nodeRendererFactory((context: HtmlNodeRendererContext) =>
      new PlantUmlRenderer(context)
    )
    .extensions(extensions)
    .build()

  private def styled(title: String, htmlBody: String) = {
    s"""|<html>
        |  <head>
        |    <title>$title</title>
        |    <style>$DefaultCss</style>
        |  </head>
        |  <body>
        |    $htmlBody
        |  </body>
        |</html>
        |""".stripMargin
  }

  override def receive: Receive = {
    case ToViewer(markdown) =>
      presenter ! Present(
        styled(
          markdown.nameWithoutExtension,
          renderer.render(markdown.fileReader(parser.parseReader))
        )
      )
    case ToBrowser(markdown) =>
      val content = markdown.contentAsString
      val hash = content.hashCode
      val target = OutDir / s"remder-$hash.html"
      log.debug("rendering: {}", target)
      if (target.notExists) {
        target.writeText(
          styled(
            markdown.nameWithoutExtension,
            renderer.render(parser.parse(content))
          )
        )
      }
      launchBrowser(target)
  }
}

object Renderer extends StrictLogging {
  val OutDir: File =
    sys.env.get("REMDER_OUTDIR").map(File(_)).getOrElse(File.temp)

  val DefaultCss: String = Source.fromResource("default.css").mkString

  case class ToViewer(markdown: File)
  case class ToBrowser(markdown: File)

  def props(presenter: ActorRef): Props = Props(new Renderer(presenter))

  val BrowserLaunchers: List[File => Try[Unit]] =
    List(
      file => Try(Desktop.getDesktop.browse(file.uri)),
      file =>
        Try(Process(s"xdg-open ${file.uri}").!).flatMap {
          case 0   => Success(())
          case err =>
            Failure(new RuntimeException(s"xdg-open exited with $err"))
        }
    )

  def launchBrowser(
      file: File,
      launchers: List[File => Try[Unit]] = BrowserLaunchers,
      failures: List[Throwable] = Nil
  ): Unit = {
    launchers match {
      case launcher :: rest =>
        launcher(file) match {
          case Success(value)     => value
          case Failure(exception) =>
            launchBrowser(file, rest, failures :+ exception)
        }
      case Nil =>
        Console.err.println("Failed to launch browser")
        for (e <- failures) {
          logger.debug("launch brower failed", e)
        }
    }
  }

  class PlantUmlRenderer(context: HtmlNodeRendererContext)
      extends NodeRenderer
      with StrictLogging {
    import PlantUmlRenderer._

    implicit val ec = ExecutionContext.global

    private val writer = context.getWriter
    private val default = new CoreHtmlNodeRenderer(context)

    override def getNodeTypes: util.Set[Class[_ <: Node]] =
      util.Collections.singleton(classOf[FencedCodeBlock])

    override def render(node: Node): Unit =
      node match {
        case fcb: FencedCodeBlock if NodeTypes.contains(fcb.getInfo) =>
          logger.debug(s"rendering ${fcb.getInfo}")
          val nodeType = fcb.getInfo
          val source = fcb.getLiteral
          val hash = source.hashCode
          val target = OutDir / s"$hash.png"
          val targetDesc = OutDir / s"$hash.desc"

          val rendering = Future {
            val (description, bytes) = if (target.isReadable) {
              logger.debug(s"reusing $target")
              targetDesc.contentAsString -> target.byteArray
            } else {
              logger.debug(s"rendering $target")
              val os = new ByteArrayOutputStream()
              val desc =
                new SourceStringReader(
                  s"@${start(nodeType)}\n$source\n@${end(nodeType)}"
                ).outputImage(os).getDescription
              val output = os.toByteArray
              target.writeByteArray(output)
              targetDesc.writeText(desc)
              desc -> output
            }

            logger.debug(s"rendered $target")
            val rendered = java.util.Base64.getEncoder.encodeToString(bytes)
            val dataUri = s"data:image/png;base64,$rendered"
            val attrs = new util.HashMap[String, String]()
            attrs.put("src", dataUri)
            attrs.put("title", description)

            writer.line()
            writer.tag("img", attrs, true)
            writer.line()
          }

          logger.debug(s"waiting for $target")
          try {
            Await.result(rendering, 3.seconds)
            logger.debug(s"rendered: $target")
          } catch {
            case ex: Throwable =>
              logger.warn(s"Failed rendering $target", ex)
              default.render(fcb)
          }
        case other => default.render(other)
      }
  }

  object PlantUmlRenderer {
    val NodeTypes = Set("plantuml", "uml", "salt", "ditaa", "dot", "jcckit")

    def start(nodeType: String) =
      nodeType match {
        case "plantuml" => "startuml"
        case other      => s"start$other"
      }

    def end(nodeType: String) =
      nodeType match {
        case "plantuml" => "enduml"
        case other      => s"end$other"
      }
  }
}
