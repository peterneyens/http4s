name := "http4s-examples-blaze"

description := "Runs the examples in http4s' blaze runner"

publishArtifact := false

fork := true

libraryDependencies ++= Seq(
  "com.codahale.metrics" % "metrics-json" % "3.0.2"
)

seq(Revolver.settings: _*)

(mainClass in Revolver.reStart) := Some("com.example.http4s.blaze.BlazeExample")



