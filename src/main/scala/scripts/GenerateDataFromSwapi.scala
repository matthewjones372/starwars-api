package scripts

import com.matthewjones372.api.client.{HttpClientConfig, SWAPIClientService}
import com.matthewjones372.domain.*
import zio.*
import zio.http.*
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

import java.net.URI
/*
 Scripts used to scrape data from swapi https://swapi.dev/api
 */
object GenerateDataFromSwapi extends ZIOAppDefault:

  def run =
    (for
      swapi     <- ZIO.service[SWAPIClientService]
      _         <- ZIO.logInfo("Getting data from swapi..")
      films     <- swapi.getFilms
      people    <- swapi.getPeople
      _         <- ZIO.logInfo(s"Got data films of size: ${films.size} and people: ${people.size}")
      filmJson   = encodeAs(films).replaceAll("https://swapi.dev/api/", "http://localhost:8080/")
      peopleJson = encodeAs(people).replaceAll("https://swapi.dev/api/", "http://localhost:8080/")
      _         <- ZIO.logInfo("Writing data to file")
      _         <- ZIO.writeFile("src/main/resources/people_data2.json", peopleJson)
      _         <- ZIO.writeFile("src/main/resources/film_data2.json", filmJson)
    yield ExitCode.success)
      .provide(
        SWAPIClientService.default,
//        ZLayer.succeed(HttpClientConfig(URL.fromURI(new URI("https://swapi.dev/api")).get, 1000)),
        ZLayer.succeed(HttpClientConfig(URL.fromURI(new URI("http://localhost:8080")).get, 1000)),
        Scope.default,
        Client.default
      )
