package org.http4s.client

import org.http4s._
import org.http4s.client.Client.{DisposableResponse, Execution}

import scalaz.EitherT
import scalaz.concurrent.Task
import scalaz.stream.Process.eval_

object Client {
  case class DisposableResponse(response: Response, dispose: Task[Unit])

  class Execution private[Client] (val _acquire: Task[DisposableResponse]) extends AnyVal {
    final def stream: Task[Response] =
      _acquire.map { case DisposableResponse(response, dispose) =>
        response.copy(body = response.body ++ eval_(dispose))
      }

    final def apply[A](f: Response => Task[A]): Task[A] =
      _acquire.flatMap { case DisposableResponse(response, dispose) =>
        f(response).onFinish(_ => dispose)
      }

    final def as[A](implicit decoder: EntityDecoder[A]): Task[A] =
      apply(_.as[A])

    final def attemptAs[A](implicit decoder: EntityDecoder[A]): DecodeResult[A] =
      EitherT.eitherT {
        _acquire.flatMap { case DisposableResponse(response, dispose) =>
          response.attemptAs[A].run.onFinish(_ => dispose)
        }
      }

    final def mapR[A](f: Response => A): Task[A] =
      apply(f andThen Task.now)

    final def void: Task[Unit] =
      mapR(_ => ())
  }
}

trait Client {
  /** Shutdown this client, closing any open connections and freeing resources */
  def shutdown(): Task[Unit]

  def open(req: Request): Task[DisposableResponse]

  final def apply(req: Request): Execution =
    new Execution(open(req))

  final def apply(uri: Uri): Execution =
    apply(Request(Method.GET, uri))

  final def apply(req: Task[Request]): Execution =
    new Execution(req.flatMap(open))
}
