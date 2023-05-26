package me.guliaev.seabattle.room

import Room.GameData
import io.circe.{Encoder, Json}

import java.util.UUID

final case class RoomId(value: UUID)
object RoomId {
  def random: RoomId = RoomId(UUID.randomUUID)
  def fromString(roomId: String): RoomId = RoomId(UUID.fromString(roomId))
  implicit val encoder: Encoder[RoomId] = (roomId: RoomId) => Json.fromString(roomId.value.toString)
}

final case class Room(
  id: RoomId = RoomId.random,
  data: GameData = GameData()
)

object Room {
  def create: Room = Room(RoomId.random, data = GameData())

  final case class Point(x: Int, y: Int)

  final case class Ship(points: Seq[Point]) {
    def shot(point: Point): (Boolean, Ship) =
      if (points.contains(point))
        (true, Ship(points.filterNot(_ == point)))
      else (false, this)
  }

  final case class UserData(channelId: String, ships: Seq[Ship])

  final case class GameData(
    userData1: Option[UserData] = None,
    userData2: Option[UserData] = None,
    moveChannelId: Option[String] = None,
    started: Boolean = false,
    finished: Boolean = false
  ) {
    def userDataMap: Map[String, Seq[Ship]] =
      Seq(userData1, userData2).collect { case Some(value) =>
        value.channelId -> value.ships
      }.toMap

    def updateShips(channelId: String, ships: Seq[Ship]): GameData =
      copy(
        userData1 = userData1
          .map(data =>
            data.copy(ships =
              if (data.channelId == channelId) ships
              else data.ships
            )
          ),
        userData2 = userData2
          .map(data =>
            data.copy(ships =
              if (data.channelId == channelId) ships
              else data.ships
            )
          )
      )
  }
}
