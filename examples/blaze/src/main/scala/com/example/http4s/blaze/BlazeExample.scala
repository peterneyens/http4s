package com.example.http4s.blaze

import com.example.http4s.ExampleService
import org.http4s.server.blaze.BlazeBuilder

object BlazeExample extends App {
  BlazeBuilder.bindHttp(8080)
    .mountService(ExampleService.service, "/http4s")
    .run
    .awaitShutdown()
}
