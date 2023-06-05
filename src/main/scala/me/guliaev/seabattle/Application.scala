package me.guliaev.seabattle

import me.guliaev.seabattle.connection.repo.InMemoryConnectionRepo
import me.guliaev.seabattle.room.controller.RoomController
import me.guliaev.seabattle.room.repo.InMemoryRoomRepo
import me.guliaev.seabattle.room.service.RoomServiceImpl
import pureconfig._
import pureconfig.generic.auto._
import zio.http.middleware.Cors.CorsConfig
import zio.http.model.Method
import zio.http.{Server, ServerConfig}
import zio.{ExitCode, ZIO, ZIOAppDefault}
import zio.http.middleware.HttpRouteMiddlewares.cors

object Application extends ZIOAppDefault {
  final case class ServiceConfiguration(port: Int)

  val corsConfig: CorsConfig =
    CorsConfig(
      allowedOrigins = { _ => true },
      allowedMethods = Some(Set(Method.POST))
    )

  override val run: ZIO[Any, Throwable, ExitCode] = for {
    config <- ZIO
      .fromEither(ConfigSource.default.load[ServiceConfiguration])
      .mapError(ex => new RuntimeException(ex.toList.mkString(",")))
    server <- Server
      .serve(new RoomController().app @@ cors(corsConfig))
      .provide(
        ServerConfig.live(ServerConfig.default.port(config.port)),
        Server.live,
        InMemoryRoomRepo.layer,
        InMemoryConnectionRepo.layer,
        RoomServiceImpl.layer
      )
      .fork
    _ <- ZIO.logInfo(s"Server is live at http://localhost:${config.port}")
    _ <- server.join
  } yield ExitCode.success
}
