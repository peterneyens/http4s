package org.http4s.server.middleware

import java.util.concurrent.TimeUnit

import com.codahale.metrics._

import org.http4s.{Method, Response, Request}
import org.http4s.server.{Service, HttpService}

import scalaz.{\/, -\/, \/-}
import scalaz.concurrent.Task

object Metrics {

  def timer(m: MetricRegistry, name: String)(srvc: HttpService): HttpService = {

    val serviceFailure = m.timer(name + ".service-error")
    val activeRequests = m.counter(name + ".active-requests")

    val resp1xx = m.timer(name + ".1xx-responses")
    val resp2xx = m.timer(name + ".2xx-responses")
    val resp3xx = m.timer(name + ".3xx-responses")
    val resp4xx = m.timer(name + ".4xx-responses")
    val resp5xx = m.timer(name + ".5xx-responses")
//    "org.eclipse.jetty.servlet.ServletContextHandler.async-dispatches"
//    "org.eclipse.jetty.servlet.ServletContextHandler.async-timeouts"

//    "http.connections"

//    "org.eclipse.jetty.servlet.ServletContextHandler.dispatches"
    val get_req = m.timer(name + ".get-requests")
    val post_req = m.timer(name + ".post-requests")
    val put_req = m.timer(name + ".put-requests")
    val head_req = m.timer(name + ".head-requests")
    val move_req = m.timer(name + ".move-requests")
    val options_req = m.timer(name + ".options-requests")

    val trace_req = m.timer(name + ".trace-requests")
    val connect_req = m.timer(name + ".connect-requests")
    val delete_req = m.timer(name + ".delete-requests")
    val other_req = m.timer(name + ".other-requests")
    val total_req = m.timer(name + ".requests")

    def onFinish(method: Method, start: Long, r: Throwable\/Option[Response]): Unit = {
      activeRequests.dec()
      val elapsed = System.nanoTime() - start

      total_req.update(elapsed, TimeUnit.NANOSECONDS)

      method match {
        case Method.GET     => get_req.update(elapsed, TimeUnit.NANOSECONDS)
        case Method.POST    => post_req.update(elapsed, TimeUnit.NANOSECONDS)
        case Method.PUT     => put_req.update(elapsed, TimeUnit.NANOSECONDS)
        case Method.HEAD    => head_req.update(elapsed, TimeUnit.NANOSECONDS)
        case Method.MOVE    => move_req.update(elapsed, TimeUnit.NANOSECONDS)
        case Method.OPTIONS => options_req.update(elapsed, TimeUnit.NANOSECONDS)
        case Method.TRACE   => trace_req.update(elapsed, TimeUnit.NANOSECONDS)
        case Method.CONNECT => connect_req.update(elapsed, TimeUnit.NANOSECONDS)
        case Method.DELETE  => delete_req.update(elapsed, TimeUnit.NANOSECONDS)
        case _              => other_req.update(elapsed, TimeUnit.NANOSECONDS)
      }

      r match {
        case -\/(_)       =>
          resp5xx.update(elapsed, TimeUnit.NANOSECONDS)
          serviceFailure.update(elapsed, TimeUnit.NANOSECONDS)

        case \/-(Some(r)) =>
          val code = r.status.code
          if (code < 200)      resp1xx.update(elapsed, TimeUnit.NANOSECONDS)
          else if (code < 300) resp2xx.update(elapsed, TimeUnit.NANOSECONDS)
          else if (code < 400) resp3xx.update(elapsed, TimeUnit.NANOSECONDS)
          else if (code < 500) resp4xx.update(elapsed, TimeUnit.NANOSECONDS)
          else                 resp5xx.update(elapsed, TimeUnit.NANOSECONDS)

        case \/-(None)    =>   resp4xx.update(elapsed, TimeUnit.NANOSECONDS)
      }
    }

    def go(req: Request): Task[Option[Response]] = {
      val now = System.nanoTime()
      activeRequests.inc()
      new Task(srvc.run(req).get.map { r => onFinish(req.method, now, r); r })
    }

    Service.lift(go)
  }
}
