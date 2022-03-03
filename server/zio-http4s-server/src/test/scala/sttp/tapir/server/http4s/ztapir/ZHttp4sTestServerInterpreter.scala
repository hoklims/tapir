package sttp.tapir.server.http4s.ztapir

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all._
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{HttpApp, HttpRoutes}
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.http4s.ztapir.ZHttp4sTestServerInterpreter._
import sttp.tapir.server.tests.TestServerInterpreter
import sttp.tapir.tests.Port
import sttp.tapir.ztapir.ZServerEndpoint
import zio.interop.catz._
import zio.interop.catz.implicits._
import zio.{Clock, RIO}

import scala.concurrent.ExecutionContext

object ZHttp4sTestServerInterpreter {
  type F[A] = RIO[Clock, A]
  type Routes = WebSocketBuilder2[F] => HttpRoutes[F]
  type ServerOptions = Http4sServerOptions[F, F]
}

class ZHttp4sTestServerInterpreter extends TestServerInterpreter[RIO[Clock, *], ZioStreams with WebSockets, ServerOptions, Routes] {
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override def route(es: List[ZServerEndpoint[Clock, ZioStreams with WebSockets]], interceptors: Interceptors): Routes = {
    val serverOptions: ServerOptions = interceptors(Http4sServerOptions.customInterceptors[RIO[Clock, *], RIO[Clock, *]]).options
    ZHttp4sServerInterpreter(serverOptions).fromWebSocket(es).toRoutes
  }

  override def server(routes: NonEmptyList[Routes]): Resource[IO, Port] = {
    val service: WebSocketBuilder2[RIO[Clock, *]] => HttpApp[RIO[Clock, *]] =
      wsb => routes.map(_.apply(wsb)).reduceK.orNotFound

    val serverResource = BlazeServerBuilder[RIO[Clock, *]]
      .withExecutionContext(ExecutionContext.global)
      .bindHttp(0, "localhost")
      .withHttpWebSocketApp(service)
      .resource
      .map(_.address.getPort)

    // Converting a zio.RIO-resource to an cats.IO-resource
    val runtime = implicitly[zio.Runtime[Clock]]
    Resource
      .eval(IO.fromFuture(IO(runtime.unsafeRunToFuture(serverResource.allocated))))
      .flatMap { case (port, release) =>
        Resource.make(IO.pure(port))(_ => IO.fromFuture(IO(runtime.unsafeRunToFuture(release))))
      }
  }
}
