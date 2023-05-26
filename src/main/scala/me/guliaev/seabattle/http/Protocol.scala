package me.guliaev.seabattle.http

import me.guliaev.seabattle.room.Room.{Point, Ship}
import me.guliaev.seabattle.room.RoomId

sealed trait WsEvent
final case class UserReady(ships: Seq[Ship]) extends WsEvent
final case class Shot(x: Int, y: Int) extends WsEvent {
  def toPoint: Point = Point(x, y)
}
case object WaitForSecondPlayer extends WsEvent
case object StartGame extends WsEvent
case object SetShips extends WsEvent
case object YourMove extends WsEvent
final case class ShotResult(x: Int, y: Int, hit: Boolean) extends WsEvent
final case class EndGame(win: Boolean) extends WsEvent

sealed trait ApiResponse
final case class ApiError(message: String) extends ApiResponse with WsEvent
final case class RoomIdResponse(roomId: RoomId) extends ApiResponse
