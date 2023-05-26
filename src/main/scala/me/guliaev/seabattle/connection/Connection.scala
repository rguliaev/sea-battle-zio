package me.guliaev.seabattle.connection

import zio.http.Channel
import zio.http.socket.WebSocketFrame

final case class Connection(id: String, channel: Channel[WebSocketFrame])
