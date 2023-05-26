package me.guliaev.seabattle.room.repo

import me.guliaev.seabattle.ZioRunner
import me.guliaev.seabattle.room.Room.{GameData, UserData}
import me.guliaev.seabattle.room.{Room, RoomId}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec

class InMemoryRoomRepoSpec extends AnyFlatSpec with MockFactory with ZioRunner {
  "InMemoryRoomRepo" should "insert" in new Wiring {
    val result: Option[Room] = run(
      (for {
        _ <- RoomRepo.insert(room)
        found <- RoomRepo.find(room.id)
      } yield found).provide(InMemoryRoomRepo.layer)
    )

    assert(result.contains(room))
  }

  it should "update" in new Wiring {
    val updated: Room = room.copy(data = GameData(Some(UserData("id", Nil))))
    val result: Option[Room] = run(
      (for {
        _ <- RoomRepo.insert(room)
        _ <- RoomRepo.update(room.id, updated)
        found <- RoomRepo.find(room.id)
      } yield found).provide(InMemoryRoomRepo.layer)
    )

    assert(result.contains(updated))
  }

  it should "delete" in new Wiring {
    val result: Option[Room] = run(
      (for {
        _ <- RoomRepo.insert(room)
        _ <- RoomRepo.delete(room.id)
        found <- RoomRepo.find(room.id)
      } yield found).provide(InMemoryRoomRepo.layer)
    )

    assert(result.isEmpty)
  }

  it should "list" in new Wiring {
    val result: Seq[Room] = run(
      (for {
        _ <- RoomRepo.insert(room)
        _ <- RoomRepo.insert(room.copy(id = RoomId.random))
        rooms <- RoomRepo.list
      } yield rooms).provide(InMemoryRoomRepo.layer)
    )

    assert(result.length == 2)
  }

  it should "findUnsafe" in new Wiring {
    val result: Room = run(
      (for {
        _ <- RoomRepo.insert(room)
        found <- RoomRepo.findUnsafe(room.id)
      } yield found).provide(InMemoryRoomRepo.layer)
    )

    assert(result == room)
  }

  trait Wiring {
    val room: Room = Room(RoomId.random)
  }
}
