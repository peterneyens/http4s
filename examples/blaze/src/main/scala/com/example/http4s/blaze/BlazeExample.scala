package com.example.http4s.blaze

/// code_ref: blaze_example

import java.util.concurrent.TimeUnit

import com.example.http4s.ExampleService
import org.http4s.server.HttpService
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.server.middleware.Metrics
import org.http4s.dsl._



import com.codahale.metrics._
import com.codahale.metrics.json.MetricsModule

import com.fasterxml.jackson.databind.ObjectMapper

object BlazeExample extends App {

  val metrics = new MetricRegistry()

  val mapper = new ObjectMapper().registerModule(
          new MetricsModule(TimeUnit.SECONDS, TimeUnit.SECONDS, true)
        )

  val metricsPage = HttpService {
    case GET -> Root / "metrics" =>
      val writer = mapper.writerWithDefaultPrettyPrinter()
      Ok(writer.writeValueAsString(metrics))
  }

  val srvc = Metrics.timer(metrics, "Sample")(ExampleService.service orElse metricsPage)

  BlazeBuilder.bindHttp(8080)
    .mountService(srvc, "/http4s")
    .run
    .awaitShutdown()
}
/// end_code_ref
