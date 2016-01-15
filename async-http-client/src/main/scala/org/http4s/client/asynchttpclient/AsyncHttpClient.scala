package org.http4s
package client
package asynchttpclient

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

import org.asynchttpclient.AsyncHandler.State
import org.asynchttpclient.request.body.generator._
import org.asynchttpclient.{Request => AsyncRequest, Response => _, _}
import org.asynchttpclient.handler.StreamedAsyncHandler

import org.http4s.util.task
import org.reactivestreams.{Subscription, Subscriber, Publisher}
import scodec.bits.ByteVector

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scalaz.stream.Cause.{EarlyCause, End, Kill}
import scalaz.stream.Process.{Await, Step, Emit, Halt}

import scalaz.stream.{Cause, async, Process}
import scalaz.concurrent.Task

import scala.concurrent.Promise
import scala.concurrent.ExecutionContext.Implicits.global

import org.log4s.getLogger

object AsyncHttpClient {
  private[this] val log = getLogger

  val defaultConfig = new DefaultAsyncHttpClientConfig.Builder()
    .setMaxConnectionsPerHost(200)
    .setMaxConnections(400)
    .setRequestTimeout(30000)
    .build()

  def apply(config: AsyncHttpClientConfig = defaultConfig): Client = {
    val client = new DefaultAsyncHttpClient(config)
    Client(Service.lift { req =>
      val p = Promise[DisposableResponse]
      client.executeRequest(toAsyncRequest(req), asyncHandler(p))
      task.futureToTask(p.future)
    }, Task(client.close()))
  }

  private def asyncHandler(promise: Promise[DisposableResponse]): AsyncHandler[Unit] =
    new StreamedAsyncHandler[Unit] {
      var state: State = State.CONTINUE
      val queue = async.boundedQueue[ByteVector](1)

      var disposableResponse = DisposableResponse(Response(body = queue.dequeue), Task {
        state = State.ABORT
        queue.close.run
      })

      override def onStream(publisher: Publisher[HttpResponseBodyPart]): State = {
        publisher.subscribe(new Subscriber[HttpResponseBodyPart] {
          var subscription: Option[Subscription] = None

          override def onError(t: Throwable): Unit = {
            subscription = None
            queue.fail(t).run
          }

          override def onSubscribe(s: Subscription): Unit = {
            subscription = Some(s)
            s.request(1)
          }

          override def onComplete(): Unit = {
            subscription = None
            queue.close.run
          }

          override def onNext(t: HttpResponseBodyPart): Unit = {
            subscription foreach { s =>
              state match {
                case State.CONTINUE =>
                  queue.enqueueOne(ByteVector(t.getBodyPartBytes)).run
                  s.request(1)
                case State.ABORT =>
                  s.cancel()
                  subscription = None
                case State.UPGRADE =>
                  queue.fail(new IllegalStateException("UPGRADE not implemented")).run
                  subscription = None
              }
            }
          }
        })
        state
      }

      override def onBodyPartReceived(httpResponseBodyPart: HttpResponseBodyPart): State =
        throw org.http4s.util.bug("Expected it to call onStream instead.")

      override def onStatusReceived(status: HttpResponseStatus): State = {
        disposableResponse = disposableResponse.copy(response = disposableResponse.response.copy(status = getStatus(status)))
        state
      }

      override def onHeadersReceived(headers: HttpResponseHeaders): State = {
        disposableResponse = disposableResponse.copy(response = disposableResponse.response.copy(headers = getHeaders(headers)))
        state
      }

      override def onThrowable(throwable: Throwable): Unit = {
        promise.failure(throwable)
      }

      override def onCompleted(): Unit = {
        promise.success(disposableResponse)
      }
    }

  private def toAsyncRequest(request: Request): AsyncRequest =
    new RequestBuilder(request.method.toString)
      .setUrl(request.uri.toString)
      .setHeaders(request.headers
        .groupBy(_.name.toString)
        .mapValues(_.map(_.value).asJavaCollection)
        .asJava
      ).setBody(getBodyGenerator(request.body))
      .build()

  private def getBodyGenerator(body: EntityBody): BodyGenerator = {
    var cur = body
    val requests = new AtomicLong()
    val publisher = new Publisher[ByteBuffer] {
      override def subscribe(s: Subscriber[_ >: ByteBuffer]): Unit = {
        s.onSubscribe(new Subscription {
          override def cancel(): Unit = {
            requests.set(0)
          }

          override def request(n: Long): Unit = {
            requests.addAndGet(n) > 0
            step(s)
          }
        })
      }

      // Based on scalaz.stream.io.toInputStream
      @tailrec
      def step(subscriber: Subscriber[_ >: ByteBuffer]): Unit = {
        cur.step match {
          case h@Halt(End | Kill) =>
            cur = h
            subscriber.onComplete()

          case h@Halt(Cause.Error(e)) =>
            cur = h
            subscriber.onError(e)

          case Step(Emit(as), cont) =>
            @tailrec
            def loop(chunks: List[ByteVector]): Boolean = {
              chunks match {
                case chunk :: tail if requests.get > 0 =>
                  val pending = requests.get()
                  if (pending > 0) {
                    if (requests.compareAndSet(pending, pending - 1)) {
                      subscriber.onNext(ByteBuffer.wrap(chunk.toArray))
                      loop(tail)
                    }
                    else {
                      loop(chunks)
                    }
                  }
                  else {
                    cur = Emit(chunks) ++ cont.continue
                    false
                  }
                case Nil =>
                  cur = cont.continue
                  true
              }
            }
            if (loop(as.toList))
              step(subscriber)

          case Step(Await(request, receive, _), cont) => { // todo: ??? Cleanup
            // yay! run the Task
            cur =
              try receive(EarlyCause.fromTaskResult(request.attempt.run)).run +: cont
              catch {
                case e: Throwable =>
                  Process.fail(e)
              }
            step(subscriber) // push things onto the stack and then step further (tail recursively)
          }
        }
      }
    }
    new ReactiveStreamsBodyGenerator(publisher)
  }

  private def getStatus(status: HttpResponseStatus): Status =
    Status.fromInt(status.getStatusCode).valueOr(e => throw new ParseException(e))

  private def getHeaders(headers: HttpResponseHeaders): Headers = {
    Headers(headers.getHeaders.iterator.asScala.map { header =>
      Header(header.getKey, header.getValue)
    }.toList)
  }
}
