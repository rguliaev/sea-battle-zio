package me.guliaev.seabattle.connection.repo

import me.guliaev.seabattle.connection.Connection
import me.guliaev.seabattle.http.ApiError
import zio._
import zio.http.Channel
import zio.http.socket.WebSocketFrame

import scala.collection.mutable

final case class InMemoryConnectionRepo(
  table: Ref[mutable.Map[String, Channel[WebSocketFrame]]]
) extends ConnectionRepo {

  override def insert(connection: Connection): Task[Connection] =
    table
      .updateAndGet(_.addOne(connection.id -> connection.channel))
      .map(_ => connection)

  override def update(id: String, connection: Connection): Task[Connection] =
    table.update(_.addOne(id, connection.channel)).map(_ => connection)

  override def find(id: String): Task[Option[Connection]] =
    table.get.map(_.get(id).map(Connection(id, _)))

  override def findUnsafe(id: String): Task[Connection] =
    find(id).someOrFail(ApiError(s"Connection is not found: $id"))

  override def delete(id: String): Task[Unit] =
    table.update { s => s.remove(id); s }

  override def list: Task[Seq[Connection]] =
    table.get.map { s =>
      s.map { case (connectionId, channel) =>
        Connection(connectionId, channel)
      }.toSeq
    }
}

object InMemoryConnectionRepo {
  def layer: ZLayer[Any, Nothing, InMemoryConnectionRepo] =
    ZLayer.fromZIO(
      Ref
        .make(mutable.Map.empty[String, Channel[WebSocketFrame]])
        .map(new InMemoryConnectionRepo(_))
    )
}
