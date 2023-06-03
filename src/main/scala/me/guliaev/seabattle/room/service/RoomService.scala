package me.guliaev.seabattle.room.service

import me.guliaev.seabattle.connection.repo.ConnectionRepo
import me.guliaev.seabattle.http._
import me.guliaev.seabattle.room.repo.RoomRepo
import me.guliaev.seabattle.room.RoomId
import zio.ZIO
import zio.http._
import zio.http.socket.WebSocketFrame

trait RoomService {
  def handleHandshake(
    roomId: RoomId,
    ch: Channel[WebSocketFrame]
  ): ZIO[RoomRepo with ConnectionRepo, Throwable, Unit]
  def handleShot(
    shot: Shot,
    id: RoomId,
    ch: Channel[WebSocketFrame]
  ): ZIO[RoomRepo with ConnectionRepo, Throwable, Unit]
  def handleUserReady(
    event: UserReady,
    roomId: RoomId,
    ch: Channel[WebSocketFrame]
  ): ZIO[RoomRepo with ConnectionRepo, Throwable, Unit]
  def handleStart(): ZIO[RoomRepo, Throwable, RoomId]
}

object RoomService {
  def handleHandshake(
    roomId: RoomId,
    ch: Channel[WebSocketFrame]
  ): ZIO[RoomService with RoomRepo with ConnectionRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[RoomService](_.handleHandshake(roomId, ch))

  def handleShot(
    shot: Shot,
    id: RoomId,
    ch: Channel[WebSocketFrame]
  ): ZIO[RoomService with RoomRepo with ConnectionRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[RoomService](_.handleShot(shot, id, ch))

  def handleUserReady(
    event: UserReady,
    roomId: RoomId,
    ch: Channel[WebSocketFrame]
  ): ZIO[RoomService with RoomRepo with ConnectionRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[RoomService](_.handleUserReady(event, roomId, ch))

  def handleStart(): ZIO[RoomService with RoomRepo, Throwable, RoomId] =
    ZIO.serviceWithZIO[RoomService](_.handleStart())
}
