import sbt.*
import sbt.Keys.*

object Projects {
  def create(name: String): Project = create(name, name)

  def create(name: String, fileName: String): Project =
    Project(name, base = file(fileName))
      .settings(
        Compile / javacOptions ++= Seq("-source", "21", "release", "17")
      )
      .settings(
        Test / fork := true,
        run / fork  := true
      )
}
