package com.matthewjones372.http.api

import nl.vroste.rezilience.Bulkhead
import zio.*
import zio.http.*
import zio.test.*

/**
 * The shedding is worth a test because the failure it guards against only
 * appears under load, which is exactly when nobody is watching: what matters is
 * that the work refused is refused quickly and the work admitted still
 * succeeds, rather than everything slowing down together.
 */
object LoadSheddingSpec extends ZIOSpecDefault:

  private val request = Request.get(URL.decode("/people/1").toOption.get)

  def spec = suite("shedding load")(
    test("refuses what it cannot hold, and still answers what it can"):
      ZIO.scoped:
        for
          // One in flight and one waiting, so six arrivals cannot all be taken.
          bulkhead <- Bulkhead.make(maxInFlightCalls = 1, maxQueueing = 1)
          release  <- Promise.make[Nothing, Unit]
          blocked   = Handler.fromFunctionZIO[Request](_ => release.await.as(Response.ok))
          shed      = SWHttpServer.shedding(bulkhead)(blocked)
          calls    <- ZIO.foreachPar(Chunk.fill(6)(()))(_ => shed(request)).fork
          // Nothing completes until the handler is let go, so whatever came
          // back before that was turned away rather than served.
          _       <- ZIO.sleep(300.millis)
          _       <- release.succeed(())
          answers <- calls.join
        yield assertTrue(
          answers.count(_.status == Status.ServiceUnavailable) >= 4,
          answers.count(_.status == Status.Ok) >= 1,
          answers.filter(_.status == Status.ServiceUnavailable).forall(_.header(Header.RetryAfter).isDefined)
        )
    ,
    test("is invisible while there is room"):
      ZIO.scoped:
        for
          bulkhead <- Bulkhead.make(SWHttpServer.maxInFlight, SWHttpServer.maxQueued)
          shed      = SWHttpServer.shedding(bulkhead)(Handler.ok)
          answers  <- ZIO.foreachPar(Chunk.fill(32)(()))(_ => shed(request))
        yield assertTrue(answers.forall(_.status == Status.Ok))
    ,
    test("passes a handler's own failure through untouched"):
      // A rejection and a genuine error must stay distinguishable, or the
      // bulkhead would quietly turn every failure into a retryable one.
      ZIO.scoped:
        for
          bulkhead <- Bulkhead.make(maxInFlightCalls = 1, maxQueueing = 1)
          failing   = Handler.fromFunctionZIO[Request](_ => ZIO.fail(Response.notFound))
          shed      = SWHttpServer.shedding(bulkhead)(failing)
          outcome  <- shed(request).either
        yield assertTrue(outcome.left.toOption.map(_.status).contains(Status.NotFound))
  ) @@ TestAspect.withLiveClock
