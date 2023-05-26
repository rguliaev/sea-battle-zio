package me.guliaev.seabattle.room.repo

import me.guliaev.seabattle.room.{Room, RoomId}
import zio._

import scala.collection.mutable

final case class InMemoryRoomRepo(table: Ref[mutable.Map[RoomId, Room]]) extends RoomRepo {

  override def insert(room: Room): Task[Room] =
    table
      .updateAndGet(_.addOne(room.id -> room))
      .map(_ => room)

  override def update(id: RoomId, room: Room): Task[Room] =
    table.update(_.addOne(id, room)).map(_ => room)

  override def find(id: RoomId): Task[Option[Room]] =
    table.get.map(_.get(id))

  override def findUnsafe(id: RoomId): Task[Room] =
    find(id).someOrFail(new RuntimeException(s"Room is not found: $id"))

  override def delete(id: RoomId): Task[Unit] =
    table.update { s => s.remove(id); s }

  override def list: Task[Seq[Room]] =
    table.get.map { s => s.values.toSeq }
}

object InMemoryRoomRepo {
  def layer: ZLayer[Any, Nothing, InMemoryRoomRepo] =
    ZLayer.fromZIO(
      Ref
        .make(mutable.Map.empty[RoomId, Room])
        .map(new InMemoryRoomRepo(_))
    )
}
