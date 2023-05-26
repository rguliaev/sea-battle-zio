package me.guliaev.seabattle.room.service

import io.circe.generic.extras.auto._
import me.guliaev.seabattle.connection.Connection
import me.guliaev.seabattle.connection.repo.ConnectionRepo
import me.guliaev.seabattle.room.Room.UserData
import me.guliaev.seabattle.room.RoomController.ChannelJsonExtension
import me.guliaev.seabattle.room.repo.RoomRepo
import me.guliaev.seabattle.room.{Room, RoomId}
import zio.{ZIO, ZLayer}
import zio.http.socket.WebSocketFrame
import me.guliaev.seabattle.http.{
  EndGame,
  SetShips,
  Shot,
  ShotResult,
  StartGame,
  UserReady,
  WaitForSecondPlayer,
  YourMove
}
import zio.http.Channel
import me.guliaev.seabattle.room.RoomController._

class RoomServiceImpl extends RoomService {
  def handleHandshake(
    roomId: RoomId,
    channel: Channel[WebSocketFrame]
  ): ZIO[RoomRepo with ConnectionRepo, Throwable, Unit] = {
    for {
      roomOpt <- RoomRepo.find(roomId)
      _ <- roomOpt match {
        case Some(room) if room.data.userData1.isEmpty =>
          val channelId = channel.id
          val gameData = room.data.copy(
            userData1 = Some(UserData(channelId, Nil)),
            moveChannelId = Some(channelId)
          )
          ConnectionRepo.insert(Connection(channelId, channel)) *>
            RoomRepo.update(roomId, room.copy(data = gameData)) *>
            channel.sendJson(WaitForSecondPlayer)

        case Some(room @ Room(_, data @ Room.GameData(Some(userData1), None, Some(_), _, _))) =>
          val channelId = channel.id
          val gameData =
            data.copy(userData2 = Some(UserData(channelId, Nil)))
          for {
            connection1 <- ConnectionRepo.findUnsafe(userData1.channelId)
            connection2 <- ConnectionRepo.insert(Connection(channelId, channel))
            _ <- RoomRepo.update(roomId, room.copy(data = gameData))
            _ <- ZIO.collectAllParDiscard(
              Seq(connection1.channel.sendJson(SetShips), connection2.channel.sendJson(SetShips))
            )
          } yield ()
        case _ => channel.close(false)
      }
    } yield ()
  }

  def handleShot(
    shot: Shot,
    id: RoomId,
    channel: Channel[WebSocketFrame]
  ): ZIO[RoomRepo with ConnectionRepo, Throwable, Unit] =
    for {
      room <- RoomRepo.findUnsafe(id)
      channelId <- ZIO
        .fromOption(room.data.moveChannelId.filter(_ == channel.id))
        .mapError(_ => new RuntimeException("Inconsistent data"))
      enemyData <- ZIO
        .fromOption(room.data.userDataMap.find(_._1 != channelId))
        .mapError(_ => new RuntimeException("Inconsistent data"))
      (enemyChannelId, enemyShips) = enemyData
      enemyConnection <- ConnectionRepo.findUnsafe(enemyChannelId)
      shotResults = enemyShips.map(_.shot(shot.toPoint))
      _ <-
        if (shotResults.exists(_._1))
          if (shotResults.forall(_._2.points.isEmpty))
            RoomRepo.update(
              room.id,
              room.copy(data = room.data.copy(finished = true))
            ) *> ZIO.collectAllParDiscard(
              Seq(
                channel.sendJson(ShotResult(shot.x, shot.y, hit = true)),
                channel.sendJson(EndGame(win = true)),
                enemyConnection.channel
                  .sendJson(ShotResult(shot.x, shot.y, hit = true)),
                enemyConnection.channel.sendJson(EndGame(win = false))
              )
            )
          else
            RoomRepo.update(
              room.id,
              room.copy(data = room.data.updateShips(enemyChannelId, shotResults.map(_._2)))
            ) *> ZIO.collectAllParDiscard(
              Seq(channel, enemyConnection.channel).map(_.sendJson(ShotResult(shot.x, shot.y, hit = true)))
            )
        else
          RoomRepo.update(
            room.id,
            room.copy(data = room.data.copy(moveChannelId = Some(enemyChannelId)))
          ) *> ZIO.collectAllParDiscard(
            Seq(channel, enemyConnection.channel).map(
              _.sendJson(ShotResult(shot.x, shot.y, hit = false))
            ) :+ enemyConnection.channel.sendJson(YourMove)
          )
    } yield ()

  def handleUserReady(
    event: UserReady,
    roomId: RoomId,
    ch: Channel[WebSocketFrame]
  ): ZIO[ConnectionRepo with RoomRepo, Throwable, Unit] = {
    RoomRepo.find(roomId).flatMap {
      case Some(room) if !room.data.started && event.ships.nonEmpty =>
        val newRoom = {
          val temp =
            if (room.data.userData1.exists(_.channelId == ch.id)) {
              room.copy(data =
                room.data.copy(
                  userData1 = room.data.userData1.map(_.copy(ships = event.ships))
                )
              )
            } else {
              room.copy(data =
                room.data.copy(
                  userData2 = room.data.userData2.map(_.copy(ships = event.ships))
                )
              )
            }
          if (
            temp.data.userData1.exists(
              _.ships.nonEmpty
            ) && temp.data.userData2.exists(_.ships.nonEmpty)
          ) temp.copy(data = temp.data.copy(started = true))
          else temp
        }

        for {
          _ <- RoomRepo.update(newRoom.id, newRoom)
          _ <-
            if (newRoom.data.started)
              (
                newRoom.data.userData1.map(_.channelId),
                newRoom.data.userData2.map(_.channelId)
              ) match {
                case (Some(channel1), Some(channel2)) =>
                  ZIO
                    .collectAllPar(
                      Seq(
                        ConnectionRepo.findUnsafe(channel1),
                        ConnectionRepo.findUnsafe(channel2)
                      )
                    )
                    .flatMap(seq =>
                      ZIO.collectAllParDiscard(
                        seq.map { connection =>
                          for {
                            _ <- connection.channel.sendJson(StartGame)
                            _ <-
                              if (
                                newRoom.data.moveChannelId
                                  .contains(connection.id)
                              ) {
                                connection.channel.sendJson(YourMove)
                              } else ZIO.unit
                          } yield ()
                        }
                      )
                    )
                case _ =>
                  ZIO.unit
              }
            else ZIO.unit
        } yield ()
      case x => ZIO.logInfo(s"Wrong! ($x)")
    }
  }

  def start(): ZIO[RoomRepo, Throwable, RoomId] =
    RoomRepo
      .insert(Room.create)
      .map(_.id)
}

object RoomServiceImpl {
  def layer: ZLayer[Any, Nothing, RoomServiceImpl] =
    ZLayer.succeed(new RoomServiceImpl())
}
