package me.guliaev.seabattle.connection.repo

import me.guliaev.seabattle.ZioRunner
import me.guliaev.seabattle.connection.Connection
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import zio.http.Channel
import zio.http.socket.WebSocketFrame
class InMemoryConnectionRepoSpec extends AnyFlatSpec with MockFactory with ZioRunner {
  "InMemoryConnectionRepo" should "insert" in new Wiring {
    val result: Option[Connection] = run(
      (for {
        _ <- ConnectionRepo.insert(connection)
        found <- ConnectionRepo.find(connection.id)
      } yield found).provide(InMemoryConnectionRepo.layer)
    )

    assert(result.contains(connection))
  }

  it should "update" in new Wiring {
    val result: Connection = run(
      (for {
        _ <- ConnectionRepo.insert(connection)
        updated <- ConnectionRepo.update(connection.id, connection)
      } yield updated).provide(InMemoryConnectionRepo.layer)
    )

    assert(result == connection)
  }

  it should "delete" in new Wiring {
    val result: Option[Connection] = run(
      (for {
        _ <- ConnectionRepo.insert(connection)
        _ <- ConnectionRepo.delete(connection.id)
        found <- ConnectionRepo.find(connection.id)
      } yield found).provide(InMemoryConnectionRepo.layer)
    )

    assert(result.isEmpty)
  }

  it should "list" in new Wiring {
    val result: Seq[Connection] = run(
      (for {
        _ <- ConnectionRepo.insert(connection)
        _ <- ConnectionRepo.insert(connection.copy(id = "anotherId"))
        connections <- ConnectionRepo.list
      } yield connections).provide(InMemoryConnectionRepo.layer)
    )

    assert(result.length == 2)
  }

  it should "findUnsafe" in new Wiring {
    val result: Connection = run(
      (for {
        _ <- ConnectionRepo.insert(connection)
        found <- ConnectionRepo.findUnsafe(connection.id)
      } yield found).provide(InMemoryConnectionRepo.layer)
    )

    assert(result == connection)
  }

  trait Wiring {
    val channel: Channel[WebSocketFrame] = mock[Channel[WebSocketFrame]]
    val connection: Connection = Connection("id", channel)
  }
}
