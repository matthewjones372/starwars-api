package com.jones
package client

import client.ApiRequestResponseStubs.*

import zio.http.*
import zio.http.codec.QueryCodec
import zio.http.endpoint.Endpoint
import zio.test.*
import zio.test.Assertion.*
import zio.{Ref, *}

object SWAPIServiceLiveSpec extends ZIOSpecDefault:

  def spec = suite("SWAPIServiceLive Spec")(
    test("can resolve the correct films from a person") {
      (for
        _   <- TestClient.addRequestResponse(personRequest, response = personResponse)
        _   <- TestClient.addRequestResponse(filmRequest1, response = film1Response)
        _   <- TestClient.addRequestResponse(filmRequest2, response = film2Response)
        f1  <- SWAPIService.getFilmsFromPerson(1).fork
        _   <- TestClock.adjust(5.seconds)
        res <- f1.join
      yield assertTrue(res == Set("A New Hope", "The Empire Strikes Back")))
    },
    test("returns the correct error when an entity is not found") {
      val expectedFailure = ClientError.NotFound(s"$baseUrl/$personUrl")

      (for
        _   <- TestClient.addRequestResponse(personRequest, response = Response.notFound)
        f1  <- SWAPIService.getFilmsFromPerson(1).exit.fork
        _   <- TestClock.adjust(5.seconds)
        res <- f1.join
      yield {
        assert(res)(fails(equalTo(expectedFailure)))
      })
    },
    test("returns the correct error when an entity is not found") {
      val expectedFailure = ClientError.RateLimited("Rate limited")

      (for
        _   <- TestClient.addRequestResponse(personRequest, response = Response.status(Status.TooManyRequests))
        f1  <- SWAPIService.getFilmsFromPerson(1).exit.fork
        _   <- TestClock.adjust(5.seconds)
        res <- f1.join
      yield {
        assert(res)(fails(equalTo(expectedFailure)))
      })
    },
    test("returns the correct error when poorly formatted json is returned") {
      val expectedFailure = ClientError.JsonDeserializationError(msg = "(expected '{' got 'B')")

      (for
        _   <- TestClient.addRequestResponse(personRequest, response = Response.text("BAD JSON"))
        f1  <- SWAPIService.getFilmsFromPerson(1).exit.fork
        _   <- TestClock.adjust(5.seconds)
        res <- f1.join
      yield {
        assert(res)(fails(equalTo(expectedFailure)))
      })
    },
    test("API retries a request when there is a sever error") {
      val getPersonEndpoint =
        Endpoint(Method.GET / "people" / int("person") / "?format=json" / trailing)
          .out[String]

      def getPersonImpl(state: Ref[Int]) = getPersonEndpoint.implement { _ =>
        state.getAndUpdate(_ + 1).flatMap {
          case 0 => ZIO.fail(throw new RuntimeException("Boom"))
          case _ =>
            ZIO.succeed(personJson)
        }
      }

      def getFilmsEndpoint =
        Endpoint(Method.GET / "films" / int("filmId") / trailing)
          .query(QueryCodec.query("format"))
          .out[String]

      def getFilmsImpl = getFilmsEndpoint.implement { _ =>
        ZIO.succeed(film1Json)
      }

      (for
        state <- Ref.make(0)
        routes = Routes(getPersonImpl(state), getFilmsImpl)
        _     <- TestClient.addRoutes(routes)
        f1    <- SWAPIService.getFilmsFromPerson(1).fork
        _     <- TestClock.adjust(10.seconds)
        res   <- f1.join
      yield assertTrue(res == Set("The Empire Strikes Back")))
    }
  ).provide(
    testEnv,
    TestClient.layer
  ) @@ TestAspect.timeout(10.seconds)
