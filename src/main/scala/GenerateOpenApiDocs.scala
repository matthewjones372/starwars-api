import com.matthewjones372.http.api.SWHttpServer
import zio.*

object GenerateOpenApiDocs extends ZIOAppDefault:
  def run =
    for
      _          <- ZIO.logInfo("Generating OpenApi spec....")
      openApiSpec = SWHttpServer.openAPI.toJsonPretty
      _          <- ZIO.logInfo("Saving spec to docs/openapi")
      _          <- ZIO.writeFile("docs/openapi/openapi.json", openApiSpec)
      _          <- ZIO.logInfo("Done")
    yield ExitCode.success
