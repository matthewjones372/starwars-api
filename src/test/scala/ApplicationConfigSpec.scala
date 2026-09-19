import com.typesafe.config.ConfigFactory
import zio.test.*

import scala.jdk.CollectionConverters.*

object ApplicationConfigSpec extends ZIOSpecDefault:
  private def baseUrlGiven(environment: (String, String)*): String =
    ConfigFactory
      .parseResources("application.conf")
      .withFallback(ConfigFactory.parseMap(environment.toMap.asJava))
      .resolve()
      .getString("clientConfig.baseUrl")

  def spec = suite("application.conf")(
    test("HTTP_CLIENT_BASE_URL overrides the bundled base url") {
      assertTrue(baseUrlGiven("HTTP_CLIENT_BASE_URL" -> "https://swapi.dev/api") == "https://swapi.dev/api")
    },
    test("the bundled base url stands when nothing overrides it") {
      assertTrue(baseUrlGiven() == "http://localhost:8080")
    }
  )
