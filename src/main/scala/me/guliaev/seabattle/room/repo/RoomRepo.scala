package me.guliaev.seabattle.room.repo

import me.guliaev.seabattle.room.{Room, RoomId}
import zio._

trait RoomRepo {
  def insert(room: Room): Task[Room]
  def find(id: RoomId): Task[Option[Room]]
  def findUnsafe(id: RoomId): Task[Room]
  def update(id: RoomId, room: Room): Task[Room]
  def delete(id: RoomId): Task[Unit]
  def list: Task[Seq[Room]]
}

object RoomRepo {
  def insert(room: Room): ZIO[RoomRepo, Throwable, Room] =
    ZIO.serviceWithZIO[RoomRepo](_.insert(room))

  def find(id: RoomId): ZIO[RoomRepo, Throwable, Option[Room]] =
    ZIO.serviceWithZIO[RoomRepo](_.find(id))

  def findUnsafe(
    id: RoomId
  ): ZIO[RoomRepo, Throwable, Room] = ZIO.serviceWithZIO[RoomRepo](_.findUnsafe(id))

  def update(id: RoomId, room: Room): ZIO[RoomRepo, Throwable, Room] =
    ZIO.serviceWithZIO[RoomRepo](_.update(id, room))

  def delete(id: RoomId): ZIO[RoomRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[RoomRepo](_.delete(id))

  def list: ZIO[RoomRepo, Throwable, Seq[Room]] =
    ZIO.serviceWithZIO[RoomRepo](_.list)
}
