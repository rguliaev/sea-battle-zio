package me.guliaev.seabattle.room.controller

import io.circe.generic.extras.auto._
import io.circe.parser._
import io.circe.syntax._
import me.guliaev.seabattle.connection.repo.ConnectionRepo
import me.guliaev.seabattle.http.ApiError.InconsistentData
import me.guliaev.seabattle.http._
import me.guliaev.seabattle.room.RoomId
import me.guliaev.seabattle.room.repo.RoomRepo
import me.guliaev.seabattle.room.service.RoomService
import zio._
import zio.http.ChannelEvent.UserEvent.{HandshakeComplete, HandshakeTimeout}
import zio.http.ChannelEvent.{ChannelRead, ChannelUnregistered, UserEventTriggered}
import zio.http._
import zio.http.model.Method
import zio.http.socket.{WebSocketChannelEvent, WebSocketFrame}

class RoomController extends BaseController {
  private def createWsApp(roomId: RoomId): Http[
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
          case Right(event @ Shot(_, _))                         => RoomService.handleShot(event, roomId, ch)
          case Left(error) =>
            ZIO.logError(error.getMessage) *> ch.sendJson(
              ApiError(error.getMessage)
            )
          case msg => ZIO.logInfo("Unknown message: " + msg)
        }
      case ChannelEvent(ch, ChannelUnregistered) => RoomService.unRegisterChannel(roomId, ch)
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
        .handleStart()
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
