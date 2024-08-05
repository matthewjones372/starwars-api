package com.matthewjones372.api.client

import ApiRequestResponseStubs.*
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

object SWAPIServiceSpec extends ZIOSpecDefault:

  def spec = suite("SWAPIServiceLive Spec")(
    suite("API Response Behavior")(
      test("can resolve the correct films from a person") {
        for
          _   <- TestClient.addRequestResponse(personRequest, response = personResponse)
          _   <- TestClient.addRequestResponse(filmRequest1, response = film1Response)
          _   <- TestClient.addRequestResponse(filmRequest2, response = film2Response)
          f1  <- SWAPIClientService.getFilmsFromPerson(1).fork
          _   <- TestClock.adjust(5.seconds)
          res <- f1.join
        yield assertTrue(res == Set("A New Hope", "The Empire Strikes Back"))
      },
      test("can resolve all people from a paged response") {
        for
          _ <- TestClient.addRequestResponse(personPagedRequest, response = pagedPersonResponse)
          _ <- ZIO.foreachDiscard(1 to 2) { page =>
                 for _ <- TestClient.addRequestResponse(personPagedUrlWith(page), response = pagedPersonResponse)
                 yield ()
               }
          f1  <- SWAPIClientService.getPeople.fork
          _   <- TestClock.adjust(5.seconds)
          res <- f1.join
        yield assertTrue(res.size == 12)
      },
      test("returns the correct error when failing to get paged responses") {
        val expectedFailure = ClientError.FailedToGetPagedResponse
        for
          _   <- TestClient.addRequestResponse(personPagedRequest, response = Response.notFound)
          f1  <- SWAPIClientService.getPeople.exit.fork
          _   <- TestClock.adjust(5.seconds)
          res <- f1.join
        yield assert(res)(fails(equalTo(expectedFailure)))
      }
    ),
    suite("API Error Behavior")(
      test("returns the correct error when an entity is not found") {
        val expectedFailure = ClientError.NotFound(s"$baseUrl/$personUrl?format=json")

        for
          _   <- TestClient.addRequestResponse(personRequest, response = Response.notFound)
          f1  <- SWAPIClientService.getFilmsFromPerson(1).exit.fork
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
          f1  <- SWAPIClientService.getFilmsFromPerson(1).exit.fork
          _   <- TestClock.adjust(5.seconds)
          res <- f1.join
        yield {
          assert(res)(fails(equalTo(expectedFailure)))
        }
      },
      test("returns the correct error when poorly formatted json is returned") {
        val expectedFailure = ClientError.JsonDeserializationError(body = "BAD JSON", msg = "(expected '{' got 'B')")

        for
          _   <- TestClient.addRequestResponse(personRequest, response = Response.text("BAD JSON"))
          f1  <- SWAPIClientService.getFilmsFromPerson(1).exit.fork
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
                ZIO.succeed(person.toJson)
            }
          }.sandbox

          for
            state <- Ref.make(0)
            _     <- TestClient.addRoutes(Routes(getPersonWithInitialFailure(state), getFilmSuccess))
            f1    <- SWAPIClientService.getFilmsFromPerson(1).fork
            _     <- TestClock.adjust(10.seconds)
            res   <- f1.join
          yield assertTrue(res == Set("The Empire Strikes Back"))
        },
        test("API does not retry a request when there is a clientError") {
          for
            state         <- Ref.make(0)
            _             <- addCallWithClientError(state)
            f1            <- SWAPIClientService.getFilmsFromPerson(1).fork
            _             <- TestClock.adjust(10.seconds)
            numberOfCalls <- state.get
          yield assertTrue(numberOfCalls == 1) // There should only be one call
        }
      )
    ),
    suite("API Caching behavior")(
      test("calls the cache on the first call") {
        def successfulPersonCall(state: Ref[Int]) = getPersonEndpoint.implement { _ =>
          state.getAndUpdate(_ + 1).as(person.toJson)
        }.sandbox

        for
          callRef   <- Ref.make(1)
          _         <- TestClient.addRoutes(Routes(successfulPersonCall(callRef), getFilmSuccess))
          f1        <- SWAPIClientService.getFilmsFromPerson(1).fork
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
