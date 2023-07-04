package me.guliaev.seabattle.room.service

import me.guliaev.seabattle.ZioRunner
import me.guliaev.seabattle.connection.Connection
import me.guliaev.seabattle.connection.repo.ConnectionRepo
import me.guliaev.seabattle.http.{
  Continue,
  Disconnected,
  EndGame,
  SetShips,
  Shot,
  ShotResult,
  StartGame,
  UserReady,
  WaitForSecondPlayer,
  WsEvent,
  YourMove
}
import me.guliaev.seabattle.room.Room._
import me.guliaev.seabattle.room.repo.RoomRepo
import me.guliaev.seabattle.room.{Room, RoomId}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import zio.{Cause, Exit, FiberFailure, ZIO, ZLayer}
import zio.http.Channel
import zio.http.socket.WebSocketFrame
import io.circe.generic.extras.auto._
import io.circe.syntax._
import me.guliaev.seabattle.http.ApiError.{GameAlreadyStarted, NotYourMove}
import org.scalatest.EitherValues
import me.guliaev.seabattle.room.controller.BaseController._

class RoomServiceImplSpec extends AnyFlatSpec with MockFactory with ZioRunner with EitherValues {
  "start" should "create room" in new Wiring {
    (roomRepoMock.insert _).expects(*).returning(ZIO.succeed(room))
    val result: RoomId = run(RoomService.handleStart().provide(RoomServiceImpl.layer, roomRepoLayer))
    assert(result == room.id)
  }

  "handleHandshake" should "add first user" in new Wiring {
    val gameData: GameData =
      GameData(userData1 = Some(UserData(Some(connection1.id), Nil)), moveChannelId = Some(connection1.id))
    val updatedRoom: Room = Room(room.id, data = gameData)
    val expectedWsFrame: WebSocketFrame = WebSocketFrame.text((WaitForSecondPlayer: WsEvent).asJson.noSpaces)

    (roomRepoMock.find _).expects(room.id).returning(ZIO.succeed(Some(room)))
    (channelMock1.id(_: zio.Trace)).expects(*).returning(connection1.id).anyNumberOfTimes()
    (roomRepoMock.update _).expects(room.id, updatedRoom).returning(ZIO.succeed(updatedRoom))
    (connectionRepoMock.insert _).expects(connection1).returning(ZIO.succeed(connection1))
    (channelMock1
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedWsFrame, *, *)
      .returning(ZIO.unit)

    run(
      RoomService
        .handleHandshake(room.id, channelMock1)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  it should "add second user" in new Wiring {
    val gameData: GameData =
      GameData(userData1 = Some(UserData(Some(connection1.id), Nil)), moveChannelId = Some(connection1.id))
    val roomWithUser: Room = room.copy(data = gameData)
    val updatedGameData: GameData = gameData.copy(userData2 = Some(UserData(Some(connection2.id), Nil)))
    val updatedRoom: Room = Room(room.id, data = updatedGameData)
    val expectedWsFrame: WebSocketFrame = WebSocketFrame.text((SetShips: WsEvent).asJson.noSpaces)

    (roomRepoMock.find _).expects(room.id).returning(ZIO.succeed(Some(roomWithUser)))
    (channelMock2.id(_: zio.Trace)).expects(*).returning(connection2.id).anyNumberOfTimes()
    (connectionRepoMock.findUnsafe _).expects(connection1.id).returning(ZIO.succeed(connection1))
    (connectionRepoMock.insert _).expects(connection2).returning(ZIO.succeed(connection2))
    (roomRepoMock.update _).expects(room.id, updatedRoom).returning(ZIO.succeed(updatedRoom))
    (channelMock1
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedWsFrame, *, *)
      .returning(ZIO.unit)
    (channelMock2
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedWsFrame, *, *)
      .returning(ZIO.unit)

    run(
      RoomService
        .handleHandshake(room.id, channelMock2)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  it should "add second user after connection loss" in new Wiring {
    val gameData: GameData =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), Nil)),
        userData2 = Some(UserData(None, Seq(Ship(Nil)))),
        moveChannelId = Some(connection1.id),
        started = true
      )
    val roomWithUser: Room = room.copy(data = gameData)
    val updatedGameData: GameData = gameData.copy(userData2 = Some(UserData(Some(connection2.id), Seq(Ship(Nil)))))
    val updatedRoom: Room = Room(room.id, data = updatedGameData)
    val expectedWsFrame: WebSocketFrame = WebSocketFrame.text((Continue: WsEvent).asJson.noSpaces)

    (roomRepoMock.find _).expects(room.id).returning(ZIO.succeed(Some(roomWithUser)))
    (channelMock2.id(_: zio.Trace)).expects(*).returning(connection2.id).anyNumberOfTimes()
    (connectionRepoMock.findUnsafe _).expects(connection1.id).returning(ZIO.succeed(connection1))
    (connectionRepoMock.insert _).expects(connection2).returning(ZIO.succeed(connection2))
    (roomRepoMock.update _).expects(room.id, updatedRoom).returning(ZIO.succeed(updatedRoom))
    (channelMock1
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedWsFrame, *, *)
      .returning(ZIO.unit)
    (channelMock2
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedWsFrame, *, *)
      .returning(ZIO.unit)

    run(
      RoomService
        .handleHandshake(room.id, channelMock2)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  it should "not add second user if the channel is the same" in new Wiring {
    val gameData: GameData =
      GameData(userData1 = Some(UserData(Some(connection1.id), Nil)), moveChannelId = Some(connection1.id))

    val roomWithUser: Room = room.copy(data = gameData)

    (roomRepoMock.find _).expects(room.id).returning(ZIO.succeed(Some(roomWithUser)))
    (channelMock1.id(_: zio.Trace)).expects(*).returning(connection1.id).anyNumberOfTimes()
    (channelMock1
      .close(_: Boolean)(_: zio.Trace))
      .expects(*, *)
      .returning(ZIO.unit)

    run(
      RoomService
        .handleHandshake(room.id, channelMock1)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  it should "close channel if room is not found" in new Wiring {
    (roomRepoMock.find _).expects(room.id).returning(ZIO.succeed(None))
    (channelMock1.close(_: Boolean)(_: zio.Trace)).expects(*, *).returning(ZIO.unit)

    run(
      RoomService
        .handleHandshake(room.id, channelMock1)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  it should "close channel if room is found, but it is full" in new Wiring {
    val gameData: GameData =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), Nil)),
        userData2 = Some(UserData(Some(connection2.id), Nil)),
        moveChannelId = Some(connection1.id)
      )
    val fullRoom: Room = Room(room.id, data = gameData)

    (roomRepoMock.find _).expects(room.id).returning(ZIO.succeed(Some(fullRoom)))
    (channelMock1.close(_: Boolean)(_: zio.Trace)).expects(*, *).returning(ZIO.unit)

    run(
      RoomService
        .handleHandshake(room.id, channelMock1)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  "handleUserReady" should "add ships for first user" in new Wiring {
    val userReadyEvent: UserReady = UserReady(Seq(Ship(Seq(Point(10, 10)))))
    val testRoom: Room = room.copy(data =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), Nil)),
        userData2 = Some(UserData(Some(connection2.id), Nil)),
        moveChannelId = Some(connection1.id)
      )
    )
    val expectedRoom: Room = testRoom.copy(data =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), userReadyEvent.ships)),
        userData2 = Some(UserData(Some(connection2.id), Nil)),
        moveChannelId = Some(connection1.id)
      )
    )

    (roomRepoMock.findUnsafe _).expects(room.id).returning(ZIO.succeed(testRoom))
    (channelMock1.id(_: zio.Trace)).expects(*).returning(connection1.id)
    (roomRepoMock.update _).expects(room.id, expectedRoom).returning(ZIO.succeed(expectedRoom))

    run(
      RoomService
        .handleUserReady(userReadyEvent, room.id, channelMock1)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  it should "add ships for second user and start game" in new Wiring {
    val userReadyEvent: UserReady = UserReady(Seq(Ship(Seq(Point(10, 10)))))
    val testRoom: Room = room.copy(data =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), userReadyEvent.ships)),
        userData2 = Some(UserData(Some(connection2.id), Nil)),
        moveChannelId = Some(connection1.id)
      )
    )
    val expectedRoom: Room = testRoom.copy(data =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), userReadyEvent.ships)),
        userData2 = Some(UserData(Some(connection2.id), userReadyEvent.ships)),
        moveChannelId = Some(connection1.id),
        started = true
      )
    )
    val expectedStartGameWsFrame: WebSocketFrame = WebSocketFrame.text((StartGame: WsEvent).asJson.noSpaces)
    val expectedYourMoveWsFrame: WebSocketFrame = WebSocketFrame.text((YourMove: WsEvent).asJson.noSpaces)

    (roomRepoMock.findUnsafe _).expects(room.id).returning(ZIO.succeed(testRoom))
    (channelMock2.id(_: zio.Trace)).expects(*).returning(connection2.id).twice()
    (roomRepoMock.update _).expects(room.id, expectedRoom).returning(ZIO.succeed(expectedRoom))
    (connectionRepoMock.findUnsafe _).expects(connection1.id).returning(ZIO.succeed(connection1))
    (connectionRepoMock.findUnsafe _).expects(connection2.id).returning(ZIO.succeed(connection2))
    (channelMock1
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedStartGameWsFrame, *, *)
      .returning(ZIO.unit)
    (channelMock1
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedYourMoveWsFrame, *, *)
      .returning(ZIO.unit)
    (channelMock2
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedStartGameWsFrame, *, *)
      .returning(ZIO.unit)

    run(
      RoomService
        .handleUserReady(userReadyEvent, room.id, channelMock2)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  it should "fail if game is started" in new Wiring {
    val userReadyEvent: UserReady = UserReady(Seq(Ship(Seq(Point(10, 10)))))
    val testRoom: Room = room.copy(data =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), Nil)),
        userData2 = Some(UserData(Some(connection2.id), Nil)),
        moveChannelId = Some(connection1.id),
        started = true
      )
    )

    (roomRepoMock.findUnsafe _).expects(room.id).returning(ZIO.succeed(testRoom))

    val result: Either[Throwable, Unit] = runToEither(
      RoomService
        .handleUserReady(userReadyEvent, room.id, channelMock1)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )

    result.left.value match {
      case FiberFailure(Cause.Fail(GameAlreadyStarted, _)) => succeed
      case _                                               => fail()
    }
  }

  "handleShot" should "fail with NotYourMove" in new Wiring {
    val shotEvent: Shot = Shot(10, 10)
    val testRoom: Room = room.copy(data =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), Nil)),
        userData2 = Some(UserData(Some(connection2.id), Seq(Ship(Seq(Point(10, 10), Point(11, 11)))))),
        moveChannelId = Some(connection1.id),
        started = true
      )
    )

    (roomRepoMock.findUnsafe _).expects(room.id).returning(ZIO.succeed(testRoom))
    (channelMock2.id(_: zio.Trace)).expects(*).returning(connection2.id)

    val result: Either[Throwable, Unit] = runToEither(
      RoomService
        .handleShot(shotEvent, room.id, channelMock2)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )

    result.left.value match {
      case FiberFailure(Cause.Fail(NotYourMove, _)) => succeed
      case x                                        => println(x); fail()
    }
  }

  it should "fail with InconsistentData" in new Wiring {
    val shotEvent: Shot = Shot(10, 10)
    val testRoom: Room = room.copy(data =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), Nil)),
        userData2 = Some(UserData(Some(connection2.id), Seq(Ship(Seq(Point(10, 10), Point(11, 11)))))),
        moveChannelId = Some(connection1.id),
        started = true
      )
    )

    (roomRepoMock.findUnsafe _).expects(room.id).returning(ZIO.succeed(testRoom))
    (channelMock2.id(_: zio.Trace)).expects(*).returning(connection2.id)
    val result: Either[Throwable, Unit] = runToEither(
      RoomService
        .handleShot(shotEvent, room.id, channelMock2)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )

    result.left.value match {
      case FiberFailure(Cause.Fail(NotYourMove, _)) => succeed
      case _                                        => fail()
    }
  }

  it should "handle hit" in new Wiring {
    val shotEvent: Shot = Shot(10, 10)
    val testRoom: Room = room.copy(data =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), Nil)),
        userData2 = Some(UserData(Some(connection2.id), Seq(Ship(Seq(Point(10, 10), Point(11, 11)))))),
        moveChannelId = Some(connection1.id),
        started = true
      )
    )
    val expectedRoom: Room = room.copy(data =
      testRoom.data.copy(
        userData2 = Some(UserData(Some(connection2.id), Seq(Ship(Seq(Point(11, 11))))))
      )
    )
    val expectedHitWsFrame: WebSocketFrame =
      WebSocketFrame.text((ShotResult(10, 10, hit = true): WsEvent).asJson.noSpaces)

    (roomRepoMock.findUnsafe _).expects(room.id).returning(ZIO.succeed(testRoom))
    (connectionRepoMock.findUnsafe _).expects(connection2.id).returning(ZIO.succeed(connection2))
    (roomRepoMock.update _).expects(room.id, expectedRoom).returning(ZIO.succeed(expectedRoom))
    (channelMock1.id(_: zio.Trace)).expects(*).returning(connection1.id)
    (channelMock1
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedHitWsFrame, *, *)
      .returning(ZIO.unit)
    (channelMock2
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedHitWsFrame, *, *)
      .returning(ZIO.unit)

    run(
      RoomService
        .handleShot(shotEvent, room.id, channelMock1)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  it should "handle kill" in new Wiring {
    val shotEvent: Shot = Shot(10, 10)
    val testRoom: Room = room.copy(data =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), Nil)),
        userData2 = Some(UserData(Some(connection2.id), Seq(Ship(Seq(Point(10, 10))), Ship(Seq(Point(20, 20)))))),
        moveChannelId = Some(connection1.id),
        started = true
      )
    )
    val expectedRoom: Room = room.copy(data =
      testRoom.data.copy(
        userData2 = Some(UserData(Some(connection2.id), Seq(Ship(Seq(Point(20, 20))))))
      )
    )
    val expectedHitWsFrame: WebSocketFrame =
      WebSocketFrame.text((ShotResult(10, 10, hit = true, kill = true): WsEvent).asJson.noSpaces)

    (roomRepoMock.findUnsafe _).expects(room.id).returning(ZIO.succeed(testRoom))
    (connectionRepoMock.findUnsafe _).expects(connection2.id).returning(ZIO.succeed(connection2))
    (roomRepoMock.update _).expects(room.id, expectedRoom).returning(ZIO.succeed(expectedRoom))
    (channelMock1.id(_: zio.Trace)).expects(*).returning(connection1.id)
    (channelMock1
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedHitWsFrame, *, *)
      .returning(ZIO.unit)
    (channelMock2
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedHitWsFrame, *, *)
      .returning(ZIO.unit)

    run(
      RoomService
        .handleShot(shotEvent, room.id, channelMock1)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  it should "handle miss" in new Wiring {
    val shotEvent: Shot = Shot(10, 10)
    val testRoom: Room = room.copy(data =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), Nil)),
        userData2 = Some(UserData(Some(connection2.id), Seq(Ship(Seq(Point(11, 11)))))),
        moveChannelId = Some(connection1.id),
        started = true
      )
    )
    val expectedRoom: Room = testRoom.copy(data = testRoom.data.copy(moveChannelId = Some(connection2.id)))
    val expectedShotResultWsFrame: WebSocketFrame =
      WebSocketFrame.text((ShotResult(10, 10, hit = false): WsEvent).asJson.noSpaces)
    val expectedYourMoveWsFrame: WebSocketFrame = WebSocketFrame.text((YourMove: WsEvent).asJson.noSpaces)

    (roomRepoMock.findUnsafe _).expects(room.id).returning(ZIO.succeed(testRoom))
    (connectionRepoMock.findUnsafe _).expects(connection2.id).returning(ZIO.succeed(connection2))
    (roomRepoMock.update _).expects(room.id, expectedRoom).returning(ZIO.succeed(expectedRoom))
    (channelMock1.id(_: zio.Trace)).expects(*).returning(connection1.id)
    (channelMock1
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedShotResultWsFrame, *, *)
      .returning(ZIO.unit)
    (channelMock2
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedShotResultWsFrame, *, *)
      .returning(ZIO.unit)
    (channelMock2
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedYourMoveWsFrame, *, *)
      .returning(ZIO.unit)

    run(
      RoomService
        .handleShot(shotEvent, room.id, channelMock1)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  it should "handle end game" in new Wiring {
    val shotEvent: Shot = Shot(10, 10)
    val testRoom: Room = room.copy(data =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), Nil)),
        userData2 = Some(UserData(Some(connection2.id), Seq(Ship(Seq(Point(10, 10)))))),
        moveChannelId = Some(connection1.id),
        started = true
      )
    )
    val expectedRoom: Room =
      room.copy(data = testRoom.data.copy(userData2 = Some(UserData(Some(connection2.id), Nil)), finished = true))
    val expectedHitWsFrame: WebSocketFrame =
      WebSocketFrame.text((ShotResult(10, 10, hit = true, kill = true): WsEvent).asJson.noSpaces)
    val expectedWinEndGameFrame: WebSocketFrame =
      WebSocketFrame.text((EndGame(win = true): WsEvent).asJson.noSpaces)
    val expectedLossEndGameFrame: WebSocketFrame =
      WebSocketFrame.text((EndGame(win = false): WsEvent).asJson.noSpaces)

    (roomRepoMock.findUnsafe _).expects(room.id).returning(ZIO.succeed(testRoom))
    (connectionRepoMock.findUnsafe _).expects(connection2.id).returning(ZIO.succeed(connection2))
    (roomRepoMock.update _).expects(room.id, expectedRoom).returning(ZIO.succeed(expectedRoom))
    (channelMock1.id(_: zio.Trace)).expects(*).returning(connection1.id)
    (channelMock1
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedHitWsFrame, *, *)
      .returning(ZIO.unit)
    (channelMock1
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedWinEndGameFrame, *, *)
      .returning(ZIO.unit)
    (channelMock2
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedHitWsFrame, *, *)
      .returning(ZIO.unit)
    (channelMock2
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedLossEndGameFrame, *, *)
      .returning(ZIO.unit)

    run(
      RoomService
        .handleShot(shotEvent, room.id, channelMock1)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

  "unRegisterChannel" should "work like a charm" in new Wiring {
    val testRoom: Room = room.copy(data =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), Seq(Ship(Nil)))),
        userData2 = Some(UserData(Some(connection2.id), Seq(Ship(Nil)))),
        moveChannelId = Some(connection1.id)
      )
    )
    val expectedGameData: GameData =
      GameData(
        userData1 = Some(UserData(Some(connection1.id), Seq(Ship(Nil)))),
        userData2 = Some(UserData(None, Seq(Ship(Nil)))),
        moveChannelId = Some(connection1.id)
      )
    val updatedRoom: Room = Room(testRoom.id, data = expectedGameData)
    val expectedWsFrame: WebSocketFrame = WebSocketFrame.text((Disconnected: WsEvent).asJson.noSpaces)

    (connectionRepoMock.delete _).expects(connection2.id)
    (roomRepoMock.findUnsafe _).expects(testRoom.id).returning(ZIO.succeed(testRoom))
    (connectionRepoMock.findUnsafe _).expects(connection1.id).returning(ZIO.succeed(connection1))
    (channelMock2.id(_: zio.Trace)).expects(*).returning(connection2.id).anyNumberOfTimes()
    (roomRepoMock.update _).expects(testRoom.id, updatedRoom).returning(ZIO.succeed(updatedRoom))
    (channelMock1
      .writeAndFlush(_: WebSocketFrame, _: Boolean)(_: zio.Trace))
      .expects(expectedWsFrame, *, *)
      .returning(ZIO.unit)

    run(
      RoomService
        .unRegisterChannel(testRoom.id, channelMock2)
        .provide(RoomServiceImpl.layer, roomRepoLayer, connectionRepoLayer)
    )
  }

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
