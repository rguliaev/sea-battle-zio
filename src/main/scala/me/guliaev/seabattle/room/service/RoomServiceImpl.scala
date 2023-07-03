package me.guliaev.seabattle.room.service

import io.circe.generic.extras.auto._
import me.guliaev.seabattle.connection.Connection
import me.guliaev.seabattle.connection.repo.ConnectionRepo
import me.guliaev.seabattle.http.ApiError.{ChannelNotFound, GameAlreadyStarted, InconsistentData, NotYourMove}
import me.guliaev.seabattle.room.Room.UserData
import me.guliaev.seabattle.room.repo.RoomRepo
import me.guliaev.seabattle.room.{Room, RoomId}
import zio.{ZIO, ZLayer}
import zio.http.socket.WebSocketFrame
import me.guliaev.seabattle.http._
import me.guliaev.seabattle.room.controller.BaseController._
import zio.http.Channel

class RoomServiceImpl extends RoomService {
  def handleHandshake(
    roomId: RoomId,
    channel: Channel[WebSocketFrame]
  ): ZIO[RoomRepo with ConnectionRepo, Throwable, Unit] = {
    for {
      roomOpt <- RoomRepo.find(roomId)
      _ <- roomOpt match {
        case Some(room) =>
          (room.data.userData1, room.data.userData2) match {
            case (None, None) =>
              val gameData = room.data.copy(
                userData1 = Some(UserData(channel.id, Nil)),
                moveChannelId = Some(channel.id)
              )
              ConnectionRepo.insert(Connection(channel.id, channel)) *>
                RoomRepo.update(roomId, room.copy(data = gameData)) *>
                channel.sendJson(WaitForSecondPlayer)

            case (Some(userData1), None) if channel.id != userData1.channelId =>
              val gameData =
                room.data.copy(userData2 = Some(UserData(channel.id, Nil)))
              for {
                connection1 <- ConnectionRepo.findUnsafe(userData1.channelId)
                connection2 <- ConnectionRepo.insert(Connection(channel.id, channel))
                _ <- RoomRepo.update(roomId, room.copy(data = gameData))
                message = if (!room.data.started) SetShips else Continue
                _ <- ZIO.collectAllParDiscard(
                  Seq(connection1.channel.sendJson(message), connection2.channel.sendJson(message))
                )
              } yield ()
            case (None, Some(userData2)) if channel.id != userData2.channelId =>
              val gameData =
                room.data.copy(userData2 = Some(UserData(channel.id, Nil)))
              for {
                connection1 <- ConnectionRepo.findUnsafe(userData2.channelId)
                connection2 <- ConnectionRepo.insert(Connection(channel.id, channel))
                _ <- RoomRepo.update(roomId, room.copy(data = gameData))
                message = if (!room.data.started) SetShips else Continue
                _ <- ZIO.collectAllParDiscard(
                  Seq(connection1.channel.sendJson(message), connection2.channel.sendJson(message))
                )
              } yield ()
            case _ => channel.close(false)
          }
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
        .mapError(_ => NotYourMove)
      enemyData <- ZIO
        .fromOption(room.data.userShipMap.find(_._1 != channelId))
        .mapError(_ => InconsistentData)
      (enemyChannelId, enemyShips) = enemyData
      enemyConnection <- ConnectionRepo.findUnsafe(enemyChannelId)
      shotResults = enemyShips.map(_.shot(shot.toPoint))
      _ <- shotResults.find(_._1) match {
        case Some((true, ship)) =>
          if (shotResults.forall(_._2.points.isEmpty))
            RoomRepo.update(
              room.id,
              room.copy(data =
                room.data
                  .copy(finished = true)
                  .updateShips(enemyChannelId, Nil)
              )
            ) *> ZIO.collectAllParDiscard(
              Seq(
                channel.sendJson(ShotResult(shot.x, shot.y, hit = true, kill = true)) *> channel.sendJson(
                  EndGame(win = true)
                ),
                enemyConnection.channel
                  .sendJson(ShotResult(shot.x, shot.y, hit = true, kill = true)) *> enemyConnection.channel.sendJson(
                  EndGame(win = false)
                )
              )
            )
          else
            RoomRepo.update(
              room.id,
              room.copy(data = room.data.updateShips(enemyChannelId, shotResults.map(_._2).filter(_.points.nonEmpty)))
            ) *> ZIO.collectAllParDiscard(
              Seq(channel, enemyConnection.channel).map(
                _.sendJson(ShotResult(shot.x, shot.y, hit = true, kill = ship.points.isEmpty))
              )
            )
        case _ =>
          RoomRepo.update(
            room.id,
            room.copy(data = room.data.copy(moveChannelId = Some(enemyChannelId)))
          ) *> ZIO.collectAllParDiscard(
            Seq(channel, enemyConnection.channel).map(
              _.sendJson(ShotResult(shot.x, shot.y, hit = false))
            ) :+ enemyConnection.channel.sendJson(YourMove)
          )
      }
    } yield ()

  def handleUserReady(
    event: UserReady,
    roomId: RoomId,
    ch: Channel[WebSocketFrame]
  ): ZIO[ConnectionRepo with RoomRepo, Throwable, Unit] = {
    RoomRepo.findUnsafe(roomId).flatMap { room =>
      if (room.data.started) ZIO.fail(GameAlreadyStarted)
      else {
        for {
          room <- (room.data.userData1, room.data.userData2) match {
            case (Some(UserData(channelId, _)), userData2Opt) if channelId == ch.id =>
              ZIO.succeed(
                room.copy(data =
                  room.data.copy(
                    userData1 = room.data.userData1.map(_.copy(ships = event.ships)),
                    started = userData2Opt.exists(_.ships.nonEmpty)
                  )
                )
              )
            case (userData1Opt, Some(UserData(channelId, _))) if channelId == ch.id =>
              ZIO.succeed(
                room.copy(data =
                  room.data.copy(
                    userData2 = room.data.userData2.map(_.copy(ships = event.ships)),
                    started = userData1Opt.exists(_.ships.nonEmpty)
                  )
                )
              )
            case _ => ZIO.fail(ChannelNotFound)
          }
          updatedRoom <- RoomRepo.update(room.id, room)
          _ <- if (updatedRoom.data.started) startGame(updatedRoom) else ZIO.unit
        } yield ()
      }
    }
  }

  private def startGame(room: Room): ZIO[ConnectionRepo, Throwable, Unit] =
    (
      room.data.userData1.map(_.channelId),
      room.data.userData2.map(_.channelId)
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
                    if (room.data.moveChannelId.contains(connection.id)) {
                      connection.channel.sendJson(YourMove)
                    } else ZIO.unit
                } yield ()
              }
            )
          )
      case _ =>
        ZIO.fail(InconsistentData)
    }

  def handleStart(): ZIO[RoomRepo, Throwable, RoomId] =
    RoomRepo
      .insert(Room.create)
      .map(_.id)
}

object RoomServiceImpl {
  def layer: ZLayer[Any, Nothing, RoomServiceImpl] =
    ZLayer.succeed(new RoomServiceImpl())
}
