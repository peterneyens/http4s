package org.http4s.client

import org.http4s._
import org.http4s.client.Client.DisposableResponse

import scalaz.concurrent.Task
import scalaz.stream.Process.eval_

object Client {
  final case class DisposableResponse(response: Response, dispose: Task[Unit])
  implicit class DisposableResponseTaskSyntax(pr: Task[DisposableResponse]) {
    /**
      * Streams a response.  The caller is responsible for running the response
      * body in order to free the underlying connection.
      *
      * This is a low-level method.  It is encouraged that callers use [[apply]]
      * instead.
      */
    def stream: Task[Response] =
      pr.map { case DisposableResponse(resp, dispose) =>
        resp.copy(body = resp.body ++ eval_(dispose))
      }

    /**
      * Fetches and handles a response asynchronously.  The underlying connection is
      * closed when the task returned by `f` completes, and no further reads from the
      * response body are permitted.
      */
    def apply[A](f: Response => Task[A]): Task[A] =
      pr.flatMap { case DisposableResponse(resp, dispose) =>
        f(resp).onFinish { case _ => dispose }
      }

    /**
      * Fetches and decodes a response asynchronously.
      */
    def as[A](implicit d: EntityDecoder[A]): Task[A] =
      apply(_.as[A])
  }
}

trait Client {

  /** Shutdown this client, closing any open connections and freeing resources */
  def shutdown(): Task[Unit]

  /**
    * Prepares a response to the given request.  See [[DisposableResponse]]
    * for various ways to handle the response in Task form.
    */
  def prepare(req: Request): Task[DisposableResponse]

  /** Alias for [[prepare]] */
  final def apply(req: Request): Task[DisposableResponse] =
    prepare(req)

  @deprecated("Use apply(req).as[A]", "0.12")
  final def prepAs[A](req: Request)(implicit d: EntityDecoder[A]): Task[A] =
    apply(req).as[A]

  /**
    * Prepares a response to a GET request on the given URI.  See [[DisposableResponse]]
    * for various ways to handle the response in Task form.
    */
  final def get(uri: Uri): Task[DisposableResponse] =
    prepare(Request(Method.GET, uri = uri))

  @deprecated("Use get(req).stream or a higher-level method on get(req)", "0.12")
  final def prepare(uri: Uri): Task[Response] =
    get(uri).stream

  @deprecated("Use get(req).stream or a higher-level method on get(req)", "0.12")
  final def apply(uri: Uri): Task[Response] =
    get(uri).stream

  @deprecated("Use get(req).as[A]", "0.12")
  final def prepAs[A](uri: Uri)(implicit d: EntityDecoder[A]): Task[A] =
    get(uri).as[A]

  /**
    * Prepares a response to the given request.  See [[DisposableResponse]]
    * for various ways to handle the response in Task form.
    */
  def prepare(req: Task[Request]): Task[DisposableResponse] =
    req.flatMap(prepare)

  /** Alias for [[prepare]] */
  def apply(req: Task[Request]): Task[DisposableResponse] =
    prepare(req)

  @deprecated("Use apply(req).as[A]", "0.12")
  final def prepAs[A](req: Task[Request])(implicit d: EntityDecoder[A]): Task[A] =
    apply(req).as[A]
}
