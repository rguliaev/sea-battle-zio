package me.guliaev.seabattle.room

import me.guliaev.seabattle.connection.repo.ConnectionRepo
import me.guliaev.seabattle.http._
import me.guliaev.seabattle.room.repo.RoomRepo
import zio.{Task, ZIO}
import zio.http.ChannelEvent.{ChannelRead, ChannelUnregistered, UserEventTriggered}
import zio.http.ChannelEvent.UserEvent.{HandshakeComplete, HandshakeTimeout}
import zio.http.model.Method
import zio.http.socket.{WebSocketChannelEvent, WebSocketFrame}
import zio.http._
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._
import io.circe.syntax._
import me.guliaev.seabattle.room.RoomController._
import io.circe.parser._
import me.guliaev.seabattle.room.service.RoomService

class RoomController {
  def createWsApp(roomId: RoomId): Http[
    RoomService with RoomRepo with ConnectionRepo,
    Throwable,
    WebSocketChannelEvent,
    Unit
  ] = {
    Http.collectZIO[WebSocketChannelEvent] {
      case ChannelEvent(ch, UserEventTriggered(event)) =>
        event match {
          case HandshakeTimeout =>
            ZIO.logInfo(s"Connection failed! ChannelId: ${ch.id}")
          case HandshakeComplete =>
            RoomService.handleHandshake(roomId, ch)
        }
      case ChannelEvent(ch, ChannelRead(WebSocketFrame.Text(msg))) =>
        parse(msg).flatMap(_.as[WsEvent]) match {
          case Right(event @ UserReady(ships)) if ships.nonEmpty => RoomService.handleUserReady(event, roomId, ch)
          case Right(UserReady(Nil))                             => ch.sendJson(ApiError("No ships defined"))
          case Right(event @ Shot(_, _))                         => RoomService.handleShot(event, roomId, ch)
          case Left(error) =>
            ZIO.logError(error.getMessage) *> ch.sendJson(
              ApiError(error.getMessage)
            )
          case msg => ZIO.logInfo("Unknown message: " + msg)
        }
      case ChannelEvent(ch, ChannelUnregistered) =>
        RoomRepo
          .delete(roomId)
          .flatMap(_ => ZIO.logInfo(s"ChannelUnregistered: ${ch.id}"))
    }
  }

  private val wsApp: App[RoomService with RoomRepo with ConnectionRepo] = {
    Http.collectZIO[Request] { case Method.GET -> !! / "room" / id =>
      createWsApp(RoomId.fromString(id)).toSocketApp.toResponse
    }
  }

  private val httpApp: App[RoomService with RoomRepo] =
    Http.collectZIO[Request] { case Method.POST -> !! / "start" =>
      RoomService
        .start()
        // TODO add on fly converter
        .mapError(ex => Response.json(ApiError(s"Something went wrong: ${ex.getMessage}").asJson.noSpaces))
        .map(roomId => Response.json(RoomIdResponse(roomId).asJson.noSpaces))
    }

  val app: Http[
    RoomService with RoomRepo with ConnectionRepo,
    Response,
    Request,
    Response
  ] = wsApp ++ httpApp
}

object RoomController {
  implicit val jsonConfig: Configuration =
    Configuration.default.withDiscriminator("type")

  implicit class ChannelJsonExtension(ch: Channel[WebSocketFrame]) {
    def sendJson(body: WsEvent)(implicit
      encoder: Encoder[WsEvent]
    ): Task[Unit] =
      ch.writeAndFlush(WebSocketFrame.text(body.asJson.noSpaces))
  }
}
