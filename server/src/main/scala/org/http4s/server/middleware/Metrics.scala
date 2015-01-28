package org.http4s.server.middleware

import java.util.concurrent.TimeUnit

import com.codahale.metrics._

import org.http4s.{Method, Response, Request}
import org.http4s.server.{Service, HttpService}

import scalaz.{\/, -\/, \/-}
import scalaz.concurrent.Task
import scalaz.stream.Process.halt

object Metrics {

  def timer(m: MetricRegistry, name: String)(srvc: HttpService): HttpService = {

    val active_requests = m.counter(name + ".active-requests")

    val service_failure = m.timer(name + ".service-error")
    val headers_times = m.timer(name + ".headers-times")

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


    def generalMetrics(method: Method, elapsed: Long): Unit = {
      method match {
        case Method.GET     => get_req.update(elapsed, TimeUnit.MILLISECONDS)
        case Method.POST    => post_req.update(elapsed, TimeUnit.MILLISECONDS)
        case Method.PUT     => put_req.update(elapsed, TimeUnit.MILLISECONDS)
        case Method.HEAD    => head_req.update(elapsed, TimeUnit.MILLISECONDS)
        case Method.MOVE    => move_req.update(elapsed, TimeUnit.MILLISECONDS)
        case Method.OPTIONS => options_req.update(elapsed, TimeUnit.MILLISECONDS)
        case Method.TRACE   => trace_req.update(elapsed, TimeUnit.MILLISECONDS)
        case Method.CONNECT => connect_req.update(elapsed, TimeUnit.MILLISECONDS)
        case Method.DELETE  => delete_req.update(elapsed, TimeUnit.MILLISECONDS)
        case _              => other_req.update(elapsed, TimeUnit.MILLISECONDS)
      }

      total_req.update(elapsed, TimeUnit.MILLISECONDS)
      active_requests.dec()
    }

    def onFinish(method: Method, start: Long)(r: Throwable\/Option[Response]): Throwable\/Option[Response] = {
      val elapsed = System.currentTimeMillis() - start

      r match {
        case \/-(Some(r)) =>
          headers_times.update(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS)
          val code = r.status.code
          val body = r.body.onComplete {
            val elapsed = System.currentTimeMillis() - start

            generalMetrics(method, elapsed)

            if (code < 200) resp1xx.update(elapsed, TimeUnit.MILLISECONDS)
            else if (code < 300) resp2xx.update(elapsed, TimeUnit.MILLISECONDS)
            else if (code < 400) resp3xx.update(elapsed, TimeUnit.MILLISECONDS)
            else if (code < 500) resp4xx.update(elapsed, TimeUnit.MILLISECONDS)
            else resp5xx.update(elapsed, TimeUnit.MILLISECONDS)
            halt
          }

          \/-(Some(r.copy(body = body)))

        case r@ \/-(None)    =>
          generalMetrics(method, elapsed)
          resp4xx.update(elapsed, TimeUnit.MILLISECONDS)
          r

        case e@ -\/(_)       =>
          generalMetrics(method, elapsed)
          resp5xx.update(elapsed, TimeUnit.MILLISECONDS)
          service_failure.update(elapsed, TimeUnit.MILLISECONDS)
          e
      }
    }

    def go(req: Request): Task[Option[Response]] = {
      val now = System.currentTimeMillis()
      active_requests.inc()
      new Task(srvc.run(req).get.map(onFinish(req.method, now)))
    }

    Service.lift(go)
  }
}
