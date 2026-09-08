package com.matthewjones372.api.client

import com.matthewjones372.api.client.ApiRequestResponseStubs.*
import com.matthewjones372.domain.Character
import com.matthewjones372.http.api.SWHttpServer
import zio.*
import zio.http.*
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
          f1  <- SWAPIClientService.getFilmsFromCharacter(1).fork
          _   <- TestClock.adjust(5.seconds)
          res <- f1.join
        yield assertTrue(res == Set("A New Hope", "The Empire Strikes Back"))
      },
      test("maps every person to the films they appear in") {
        for
          _ <- TestClient.addRequestResponse(personPagedRequest, response = pagedPersonResponse)
          // each person in the paged fixture points at their own film url
          _ <- ZIO.foreachDiscard(1 to 12) { person =>
                 TestClient.addRequestResponse(
                   Request.get(URL.decode(s"http://localhost/$person").unsafeGet),
                   response = film1Response
                 )
               }
          res <- SWAPIClientService.getFilmsFromCharacters
        yield assertTrue(
          res.size == 12,
          res.values.forall(_ == Set("The Empire Strikes Back"))
        )
      } @@ TestAspect.withLiveClock,
      test("can resolve all people from a paged response") {
        for
          _   <- TestClient.addRequestResponse(personPagedRequest, response = pagedPersonResponse)
          res <- SWAPIClientService.getCharacters
        yield assertTrue(res.size == 12)
      } @@ TestAspect.withLiveClock,
      test("returns the correct error when failing to get paged responses") {
        val expectedFailure = ClientError.FailedToGetPagedResponse
        for
          _   <- TestClient.addRequestResponse(personPagedRequest, response = Response.notFound)
          f1  <- SWAPIClientService.getCharacters.exit.fork
          _   <- TestClock.adjust(5.seconds)
          res <- f1.join
        yield assert(res)(fails(equalTo(expectedFailure)))
      },
      test("Can get all films from a paged response") {
        for
          _   <- TestClient.addRequestResponse(filmPagedRequest, response = pagedFilmResponse)
          res <- SWAPIClientService.getFilms
        yield assertTrue(res.size == 12)
      } @@ TestAspect.withLiveClock
    ),
    suite("API Error Behavior")(
      test("returns the correct error when an entity is not found") {
        val expectedFailure = ClientError.NotFound(s"$baseUrl/$personUrl?format=json")

        for
          _   <- TestClient.addRequestResponse(personRequest, response = Response.notFound)
          f1  <- SWAPIClientService.getFilmsFromCharacter(1).exit.fork
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
          f1  <- SWAPIClientService.getFilmsFromCharacter(1).exit.fork
          _   <- TestClock.adjust(5.seconds)
          res <- f1.join
        yield {
          assert(res)(fails(equalTo(expectedFailure)))
        }
      },
      test("returns the correct error when poorly formatted json is returned") {
        (for
          _  <- TestClient.addRequestResponse(personRequest, response = Response.text("BAD JSON"))
          f1 <- SWAPIClientService.getFilmsFromCharacter(1).fork
          _  <- TestClock.adjust(5.seconds)
          _  <- f1.join
        yield assertCompletes)
      } @@ TestAspect.failing[ClientError] {
        case TestFailure
              .Runtime(Cause.Fail(ClientError.ResponseDeserializationError("Error decoding response"), _), _) =>
          true
        case _ =>
          false
      },
      suite("Retrying Behavior")(
        test("API retries a request when there is a sever error") {
          def getPersonWithInitialFailure(state: Ref[Int]) = SWHttpServer.getCharacterEndpoint.implement { _ =>
            state.getAndUpdate(_ + 1).flatMap {
              case 0 => ZIO.fail(throw new RuntimeException("Boom"))
              case _ =>
                ZIO.succeed(person)
            }
          }.sandbox

          for
            state <- Ref.make(0)
            _     <- TestClient.addRoutes(Routes(getPersonWithInitialFailure(state), getFilmSuccess))
            f1    <- SWAPIClientService.getFilmsFromCharacter(1).fork
            _     <- TestClock.adjust(10.seconds)
            res   <- f1.join
          yield assertTrue(res == Set("The Empire Strikes Back"))
        },
        test("API does not retry a request when there is a clientError") {
          for
            state         <- Ref.make(0)
            _             <- addCallWithClientError(state)
            request       <- SWAPIClientService.getFilmsFromCharacter(1).exit.fork
            _             <- TestClock.adjust(10.seconds)
            result        <- request.join
            numberOfCalls <- state.get
          yield assertTrue(result.isFailure, numberOfCalls == 1) // There should only be one call
        }
      )
    ),
    suite("API Caching behavior")(
      test("calls the cache on the first call") {
        def successfulPersonCall(state: Ref[Boolean]) = SWHttpServer.getCharacterEndpoint.implement { _ =>
          state.modify {
            case false => (person, true)
            case true  => (throw ClientError.UnreachableError, true)
          }
        }.sandbox

        for
          callRef <- Ref.make(false)
          _       <- TestClient.addRoutes(Routes(successfulPersonCall(callRef), getFilmSuccess))
          r1      <- SWAPIClientService.getFilmsFromCharacter(1)
          r2      <- SWAPIClientService.getFilmsFromCharacter(1)
          _       <- TestClock.adjust(5.seconds)
        yield assertTrue(r1 == r2)

      }
    )
  ).provide(
    SWAPIClientService.default,
    Scope.default,
    TestClient.layer
  ) @@ TestAspect.timeout(10.seconds) @@ TestAspect.withConfigProvider(
    ConfigProvider.fromMap(
      Map(
        "clientConfig.baseUrl"   -> "http://localhost",
        "clientConfig.cacheSize" -> "100"
      )
    )
  )
