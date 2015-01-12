package org.http4s.server.middleware

import com.codahale.metrics._

import org.http4s.{Response, Request}
import org.http4s.server.{Service, HttpService}

import scalaz.concurrent.Task

object Metrics {

  def timer(m: MetricRegistry, name: String)(srvc: HttpService): HttpService = {
    val respTimer = m.timer(s"$name.response")

    def go(req: Request): Task[Option[Response]] = {
      val time = respTimer.time()
      srvc.run(req)
      .onFinish{ _ => Task.now(time.stop()) }
    }

    Service.lift(go)
  }
}
