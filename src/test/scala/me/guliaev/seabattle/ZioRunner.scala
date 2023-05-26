package me.guliaev.seabattle

import zio.{Unsafe, ZIO}

trait ZioRunner {
  def run[E, A](action: ZIO[Any, E, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.default.unsafe
        .run(
          action
        )
        .getOrThrowFiberFailure
    }
}
