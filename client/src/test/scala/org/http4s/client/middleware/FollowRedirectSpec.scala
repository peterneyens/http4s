package org.http4s.client
package middleware

import org.http4s.{Uri, Status, Http4sSpec, Request, Response}
import org.http4s.Status._
import org.http4s.Method._
import org.http4s.headers.Location
import org.http4s.server.HttpService

import scalaz.concurrent.Task


class FollowRedirectSpec extends Http4sSpec {

  val route = HttpService {
    case r if r.method == GET && r.pathInfo == "/ok"       => Response(Ok).withBody("hello")
    case r if r.method == GET && r.pathInfo == "/redirect" => Response(MovedPermanently).replaceAllHeaders(Location(uri("/ok"))).withBody("Go there.")
    case r if r.method == GET && r.pathInfo == "/loop"     => Response(MovedPermanently).replaceAllHeaders(Location(uri("/loop"))).withBody("Go there.")
    case r if r.method == POST && r.pathInfo == "/303"      => 
      Response(SeeOther).replaceAllHeaders(Location(uri("/ok"))).withBody("Go to /ok")

    case r => sys.error("Path not found: " + r.pathInfo)
  }


  val defaultClient = new MockClient(route)
  val client = FollowRedirect(1)(defaultClient)
  
  "FollowRedirect" should {
    "Honor redirect" in {
      client(getUri(s"http://localhost/redirect")).mapR(_.status).run must_== Status.Ok
    }

    "Not redirect more than 'maxRedirects' iterations" in {
      client(getUri(s"http://localhost/loop")).mapR(_.status).run must_== Status.MovedPermanently
    }

    "Use a GET method on redirect with 303 response code" in {
      client(Request(method=POST, uri=getUri(s"http://localhost/303"))) { resp => Task.now {
        resp.status must_== Status.Ok
        resp.as[String].run must_== "hello"
      }}.run
    }
  }

  def getUri(s: String): Uri = Uri.fromString(s).getOrElse(sys.error("Bad uri."))
}
