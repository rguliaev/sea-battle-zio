package me.guliaev.seabattle.room.service

import me.guliaev.seabattle.ZioRunner
import me.guliaev.seabattle.connection.Connection
import me.guliaev.seabattle.connection.repo.ConnectionRepo
import me.guliaev.seabattle.room.Room.{GameData, UserData}
import me.guliaev.seabattle.room.repo.RoomRepo
import me.guliaev.seabattle.room.{Room, RoomId}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import zio.{ZIO, ZLayer}
import zio.http.Channel
import zio.http.socket.WebSocketFrame

class RoomServiceSpec extends AnyFlatSpec with MockFactory with ZioRunner {
  "RoomService" should "start" in new Wiring {
    (roomRepoMock.insert _).expects(*).returning(ZIO.succeed(room))
    val result: RoomId = run(RoomService.handleStart().provide(RoomServiceImpl.layer, roomRepoLayer))
    assert(result == room.id)
  }

  it should "handleHandshake for first user" in new Wiring {
    val gameData: GameData =
      GameData(userData1 = Some(UserData(connection1.id, Nil)), moveChannelId = Some(connection1.id))
    val updatedRoom: Room = Room(room.id, data = gameData)

    (roomRepoMock.find _).expects(room.id).returning(ZIO.succeed(Some(room)))
    (channelMock1.id(_: zio.Trace)).expects(*).returning(connection1.id)
    (roomRepoMock.update _).expects(room.id, updatedRoom).returning(ZIO.succeed(updatedRoom))
    (connectionRepoMock.insert _).expects(connection1).returning(ZIO.succeed(connection1))
    // TODO verify ws messages!
    (channelMock1.writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace)).expects(*, *, *).returning(ZIO.unit)

    run(
      RoomService
        .handleHandshake(room.id, channelMock1)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  it should "handleHandshake for second user" in new Wiring {
    val gameData: GameData =
      GameData(userData1 = Some(UserData(connection1.id, Nil)), moveChannelId = Some(connection1.id))
    val roomWithUser: Room = room.copy(data = gameData)
    val updatedGameData: GameData = gameData.copy(userData2 = Some(UserData(connection2.id, Nil)))
    val updatedRoom: Room = Room(room.id, data = updatedGameData)

    (roomRepoMock.find _).expects(room.id).returning(ZIO.succeed(Some(roomWithUser)))
    (channelMock2.id(_: zio.Trace)).expects(*).returning(connection2.id)
    (connectionRepoMock.findUnsafe _).expects(connection1.id).returning(ZIO.succeed(connection1))
    (connectionRepoMock.insert _).expects(connection2).returning(ZIO.succeed(connection2))
    (roomRepoMock.update _).expects(room.id, updatedRoom).returning(ZIO.succeed(updatedRoom))
    (channelMock1.writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace)).expects(*, *, *).returning(ZIO.unit)
    (channelMock2.writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace)).expects(*, *, *).returning(ZIO.unit)

    run(
      RoomService
        .handleHandshake(room.id, channelMock2)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  // TODO handleHandshake errors
  // TODO handleUserReady
  // TODO handleShot

  trait Wiring {
    val channelMock1: Channel[WebSocketFrame] = mock[Channel[WebSocketFrame]]
    val channelMock2: Channel[WebSocketFrame] = mock[Channel[WebSocketFrame]]
    val connection1: Connection = Connection("connection1", channelMock1)
    val connection2: Connection = Connection("connection2", channelMock2)
    val room: Room = Room.create
    val roomRepoMock: RoomRepo = mock[RoomRepo]
    val roomRepoLayer: ZLayer[Any, Nothing, RoomRepo] = ZLayer.fromZIO(ZIO.succeed(roomRepoMock))
    val connectionRepoMock: ConnectionRepo = mock[ConnectionRepo]
    val connectionRepoLayer: ZLayer[Any, Nothing, ConnectionRepo] = ZLayer.fromZIO(ZIO.succeed(connectionRepoMock))
  }
}
