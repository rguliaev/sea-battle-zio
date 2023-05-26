package me.guliaev.seabattle.connection.repo

import me.guliaev.seabattle.connection.Connection
import zio._

trait ConnectionRepo {
  def insert(connection: Connection): Task[Connection]
  def find(id: String): Task[Option[Connection]]
  def findUnsafe(id: String): Task[Connection]
  def update(id: String, connection: Connection): Task[Connection]
  def delete(id: String): Task[Unit]
  def list: Task[Seq[Connection]]
}

object ConnectionRepo {
  def insert(
    connection: Connection
  ): ZIO[ConnectionRepo, Throwable, Connection] =
    ZIO.serviceWithZIO[ConnectionRepo](_.insert(connection))

  def find(id: String): ZIO[ConnectionRepo, Throwable, Option[Connection]] =
    ZIO.serviceWithZIO[ConnectionRepo](_.find(id))

  def findUnsafe(
    id: String
  ): ZIO[ConnectionRepo, Throwable, Connection] =
    ZIO.serviceWithZIO[ConnectionRepo](_.findUnsafe(id))

  def update(
    id: String,
    connection: Connection
  ): ZIO[ConnectionRepo, Throwable, Connection] =
    ZIO.serviceWithZIO[ConnectionRepo](_.update(id, connection))

  def delete(id: String): ZIO[ConnectionRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[ConnectionRepo](_.delete(id))

  def list: ZIO[ConnectionRepo, Throwable, Seq[Connection]] =
    ZIO.serviceWithZIO[ConnectionRepo](_.list)
}
