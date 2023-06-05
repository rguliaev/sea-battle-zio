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
        .mapError(_ => NotYourMove)
      enemyData <- ZIO
        .fromOption(room.data.userShipMap.find(_._1 != channelId))
        .mapError(_ => InconsistentData)
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
