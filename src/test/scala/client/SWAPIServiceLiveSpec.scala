package com.jones
package client

import client.ApiRequestResponseStubs.*

import zio.http.*
import zio.test.*
import zio.test.Assertion.*
import zio.*

object SWAPIServiceLiveSpec extends ZIOSpecDefault:

  def spec = suite("SWAPIServiceLive Spec")(
    suite("API Response Behavior")(test("can resolve the correct films from a person") {
      for
        _   <- TestClient.addRequestResponse(personRequest, response = personResponse)
        _   <- TestClient.addRequestResponse(filmRequest1, response = film1Response)
        _   <- TestClient.addRequestResponse(filmRequest2, response = film2Response)
        f1  <- SWAPIService.getFilmsFromPerson(1).fork
        _   <- TestClock.adjust(5.seconds)
        res <- f1.join
      yield assertTrue(res == Set("A New Hope", "The Empire Strikes Back"))
    }),
    suite("API Error Behavior")(
      test("returns the correct error when an entity is not found") {
        val expectedFailure = ClientError.NotFound(s"$baseUrl/$personUrl")

        for
          _   <- TestClient.addRequestResponse(personRequest, response = Response.notFound)
          f1  <- SWAPIService.getFilmsFromPerson(1).exit.fork
          _   <- TestClock.adjust(5.seconds)
          res <- f1.join
        yield {
          assert(res)(fails(equalTo(expectedFailure)))
        }
      },
      test("returns the correct error when an entity is not found") {
        val expectedFailure = ClientError.RateLimited("Rate limited")

        for
          _   <- TestClient.addRequestResponse(personRequest, response = Response.status(Status.TooManyRequests))
          f1  <- SWAPIService.getFilmsFromPerson(1).exit.fork
          _   <- TestClock.adjust(5.seconds)
          res <- f1.join
        yield {
          assert(res)(fails(equalTo(expectedFailure)))
        }
      },
      test("returns the correct error when poorly formatted json is returned") {
        val expectedFailure = ClientError.JsonDeserializationError(msg = "(expected '{' got 'B')")

        for
          _   <- TestClient.addRequestResponse(personRequest, response = Response.text("BAD JSON"))
          f1  <- SWAPIService.getFilmsFromPerson(1).exit.fork
          _   <- TestClock.adjust(5.seconds)
          res <- f1.join
        yield {
          assert(res)(fails(equalTo(expectedFailure)))
        }
      },
      suite("Retrying Behavior")(
        test("API retries a request when there is a sever error") {
          def getPersonWithInitialFailure(state: Ref[Int]) = getPersonEndpoint.implement { _ =>
            state.getAndUpdate(_ + 1).flatMap {
              case 0 => ZIO.fail(throw new RuntimeException("Boom"))
              case _ =>
                ZIO.succeed(personJson)
            }
          }.sandbox

          for
            state <- Ref.make(0)
            routes = Routes(getPersonWithInitialFailure(state), getFilmSuccess)
            _     <- TestClient.addRoutes(routes)
            f1    <- SWAPIService.getFilmsFromPerson(1).fork
            _     <- TestClock.adjust(10.seconds)
            res   <- f1.join
          yield assertTrue(res == Set("The Empire Strikes Back"))
        },
        test("API does not retry a request when there is a clientError") {
          for
            state         <- Ref.make(0)
            _             <- addCallWithClientError(state)
            f1            <- SWAPIService.getFilmsFromPerson(1).fork
            _             <- TestClock.adjust(10.seconds)
            numberOfCalls <- state.get
          yield assertTrue(numberOfCalls == 1) // There should only be one call
        }
      )
    ),
    suite("API Caching behavior")(
      test("calls the cache on the first call") {
        def successfulPersonCall(state: Ref[Int]) = getPersonEndpoint.implement { _ =>
          state.getAndUpdate(_ + 1).as(personJson)
        }.sandbox

        for
          callRef   <- Ref.make(1)
          routes     = Routes(successfulPersonCall(callRef), getFilmSuccess)
          _         <- TestClient.addRoutes(routes)
          f1        <- SWAPIService.getFilmsFromPerson(1).fork
          _         <- TestClock.adjust(5.seconds)
          _         <- f1.join
          cacheHits <- callRef.get
        yield assertTrue(cacheHits == 2)

      }
    )
  ).provide(
    testEnv,
    TestClient.layer
  ) @@ TestAspect.timeout(10.seconds)
