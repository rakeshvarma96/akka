/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.rendering

import java.nio.charset.Charset
import scala.collection.immutable
import akka.event.LoggingAdapter
import akka.http.model._
import akka.http.model.headers._
import akka.http.engine.rendering.RenderSupport._
import akka.http.util._
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.util.ByteString
import HttpEntity._

/**
 * INTERNAL API
 */
private[http] object BodyPartRenderer {

  def streamed(boundary: String,
               nioCharset: Charset,
               partHeadersSizeHint: Int,
               log: LoggingAdapter): PushPullStage[Multipart.BodyPart, Source[ChunkStreamPart, Unit]] =
    new PushPullStage[Multipart.BodyPart, Source[ChunkStreamPart, Unit]] {
      var firstBoundaryRendered = false

      override def onPush(bodyPart: Multipart.BodyPart, ctx: Context[Source[ChunkStreamPart, Unit]]): Directive = {
        val r = new CustomCharsetByteStringRendering(nioCharset, partHeadersSizeHint)

        def bodyPartChunks(data: Source[ByteString, Unit]): Source[ChunkStreamPart, Unit] = {
          val entityChunks = data.map[ChunkStreamPart](Chunk(_))
          (chunkStream(r.get) ++ entityChunks).mapMaterialized((_) ⇒ ())
        }

        def completePartRendering(): Source[ChunkStreamPart, Unit] =
          bodyPart.entity match {
            case x if x.isKnownEmpty       ⇒ chunkStream(r.get)
            case Strict(_, data)           ⇒ chunkStream((r ~~ data).get)
            case Default(_, _, data)       ⇒ bodyPartChunks(data)
            case IndefiniteLength(_, data) ⇒ bodyPartChunks(data)
          }

        renderBoundary(r, boundary, suppressInitialCrLf = !firstBoundaryRendered)
        firstBoundaryRendered = true
        renderEntityContentType(r, bodyPart.entity)
        renderHeaders(r, bodyPart.headers, log)
        ctx.push(completePartRendering())
      }

      override def onPull(ctx: Context[Source[ChunkStreamPart, Unit]]): Directive = {
        val finishing = ctx.isFinishing
        if (finishing && firstBoundaryRendered) {
          val r = new ByteStringRendering(boundary.length + 4)
          renderFinalBoundary(r, boundary)
          ctx.pushAndFinish(chunkStream(r.get))
        } else if (finishing)
          ctx.finish()
        else
          ctx.pull()
      }

      override def onUpstreamFinish(ctx: Context[Source[ChunkStreamPart, Unit]]): TerminationDirective = ctx.absorbTermination()

      private def chunkStream(byteString: ByteString): Source[ChunkStreamPart, Unit] =
        Source.single(Chunk(byteString))

    }

  def strict(parts: immutable.Seq[Multipart.BodyPart.Strict], boundary: String, nioCharset: Charset,
             partHeadersSizeHint: Int, log: LoggingAdapter): ByteString = {
    val r = new CustomCharsetByteStringRendering(nioCharset, partHeadersSizeHint)
    if (parts.nonEmpty) {
      for (part ← parts) {
        renderBoundary(r, boundary, suppressInitialCrLf = part eq parts.head)
        renderEntityContentType(r, part.entity)
        renderHeaders(r, part.headers, log)
        r ~~ part.entity.data
      }
      renderFinalBoundary(r, boundary)
    }
    r.get
  }

  private def renderBoundary(r: Rendering, boundary: String, suppressInitialCrLf: Boolean = false): Unit = {
    if (!suppressInitialCrLf) r ~~ CrLf
    r ~~ '-' ~~ '-' ~~ boundary ~~ CrLf
  }

  private def renderFinalBoundary(r: Rendering, boundary: String): Unit =
    r ~~ CrLf ~~ '-' ~~ '-' ~~ boundary ~~ '-' ~~ '-'

  private def renderHeaders(r: Rendering, headers: immutable.Seq[HttpHeader], log: LoggingAdapter): Unit = {
    headers foreach renderHeader(r, log)
    r ~~ CrLf
  }

  private def renderHeader(r: Rendering, log: LoggingAdapter): HttpHeader ⇒ Unit = {
    case x: `Content-Length` ⇒
      suppressionWarning(log, x, "explicit `Content-Length` header is not allowed. Use the appropriate HttpEntity subtype.")

    case x: `Content-Type` ⇒
      suppressionWarning(log, x, "explicit `Content-Type` header is not allowed. Set `HttpRequest.entity.contentType` instead.")

    case x: RawHeader if (x is "content-type") || (x is "content-length") ⇒
      suppressionWarning(log, x, "illegal RawHeader")

    case x ⇒ r ~~ x ~~ CrLf
  }
}
