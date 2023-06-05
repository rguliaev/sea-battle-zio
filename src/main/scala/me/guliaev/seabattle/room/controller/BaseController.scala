package me.guliaev.seabattle.room.controller

import io.circe.Encoder
import io.circe.generic.extras.Configuration
import me.guliaev.seabattle.http.WsEvent
import zio.Task
import zio.http.Channel
import zio.http.socket.WebSocketFrame
import io.circe.syntax._

trait BaseController {
  implicit val jsonConfig: Configuration =
    Configuration.default.withDiscriminator("type")

  implicit class ChannelJsonExtension(ch: Channel[WebSocketFrame]) {
    def sendJson(body: WsEvent)(implicit
      encoder: Encoder[WsEvent]
    ): Task[Unit] =
      ch.writeAndFlush(WebSocketFrame.text(body.asJson.noSpaces))
  }
}

object BaseController extends BaseController
