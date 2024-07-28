package com.jones
package client

import client.ClientError.UnexpectedSeverError

import nl.vroste.rezilience.{Policy, Retry}
import zio.*

object ResiliencyPolicy:
  private val exponentialBackoff = Retry.make(
    Retry.Schedules.whenCase { case _: UnexpectedSeverError => }(
      Retry.Schedules.exponentialBackoff(min = 1.second, max = 5.second)
    )
  )

  private val policy = for {
    backOff <- exponentialBackoff
  } yield backOff.toPolicy

  def run[R, E, A](zio: ZIO[R, E, A]) =
    policy.flatMap(r => r(zio)).mapError(Policy.unwrap)
