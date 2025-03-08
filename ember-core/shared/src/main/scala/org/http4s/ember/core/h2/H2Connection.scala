/*
 * Copyright 2019 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.ember.core.h2

import cats._
import cats.data.Chain
import cats.effect._
import cats.effect.kernel.Outcome
import cats.syntax.all._
import com.comcast.ip4s.Host
import com.comcast.ip4s.SocketAddress
import fs2._
import fs2.io.net.Socket
import fs2.io.net.SocketException
import fs2.io.net.unixsocket.UnixSocketAddress
import org.typelevel.log4cats.Logger
import scodec.bits._

import scala.annotation.switch

import H2Frame.Settings.SettingsInitialWindowSize

private[h2] class H2Connection[F[_]](
    address: Either[UnixSocketAddress, SocketAddress[Host]],
    connectionType: H2Connection.ConnectionType,
    localSettings: H2Frame.Settings.ConnectionSettings,
    val mapRef: Ref[F, Map[Int, H2Stream[F]]],
    val state: Ref[F, H2Connection.State[F]], // odd if client, even if server
    val outgoing: cats.effect.std.Queue[F, Chunk[H2Frame]],
    // val outgoingData: cats.effect.std.Queue[F, Frame.Data], // TODO split data rather than backpressuring frames totally

    val createdStreams: cats.effect.std.Queue[F, Int],
    val closedStreams: cats.effect.std.Queue[F, Int],
    hpack: Hpack[F],
    val streamCreateAndHeaders: Resource[F, Unit],
    val settingsAck: Deferred[F, Either[Throwable, H2Frame.Settings.ConnectionSettings]],
    acc: ByteVector, // Any Bytes Already Read
    socket: Socket[F],
    logger: Logger[F],
)(implicit F: Temporal[F]) {

  private[this] def addrStr = address.fold(_.toString, _.toString)

  def initiateLocalStream: F[H2Stream[F]] = for {
    t <- state.modify { s =>
      val highestIsEven = s.highestStream % 2 == 0
      val newHighest = connectionType match {
        case H2Connection.ConnectionType.Server =>
          if (highestIsEven) s.highestStream + 2 else s.highestStream + 1
        case H2Connection.ConnectionType.Client =>
          if (highestIsEven) s.highestStream + 1 else s.highestStream + 2
      }
      (s.copy(highestStream = newHighest), (s.remoteSettings, newHighest))
    }
    (settings, id) = t

    refState <- H2Stream.initState[F](localSettings = localSettings, remoteSettings = settings)
    stream = new H2Stream(
      id,
      localSettings,
      connectionType,
      state.get.map(_.remoteSettings),
      refState,
      hpack,
      outgoing,
      closedStreams.offer(id),
      goAway[Unit],
      logger,
    )
    _ <- mapRef.update(m => m + (id -> stream))
  } yield stream

  def initiateRemoteStreamById(id: Int): F[H2Stream[F]] = for {
    t <- state.get.map(s => (s.remoteSettings, s.remoteHighestStream))
    (settings, highestStream) = t
    refState <- H2Stream.initState[F](localSettings = localSettings, remoteSettings = settings)
    stream = new H2Stream(
      id,
      localSettings,
      connectionType,
      state.get.map(_.remoteSettings),
      refState,
      hpack,
      outgoing,
      closedStreams.offer(id),
      goAway[Unit],
      logger,
    )
    _ <- mapRef.update(m => m + (id -> stream))
    _ <- state.update(s =>
      s.copy(
        highestStream = Math.max(s.highestStream, id),
        remoteHighestStream = Math.max(s.remoteHighestStream, id),
      )
    )
  } yield stream

  def goAway[A](error: H2Error): F[A] =
    state.get.map(_.remoteHighestStream).flatMap { i =>
      val g = error.toGoAway(i)
      outgoing.offer(Chunk.singleton(g))
    } >>
      H2Connection.KillWithoutMessage().raiseError

  private[this] def writeChunk(chunk: Chunk[H2Frame]): F[Unit] = {
    def go(chunk: Chunk[H2Frame]): F[Unit] = state.get.flatMap { s =>
      val fullDataSize = chunk.foldLeft(0) {
        case (init, H2Frame.Data(_, data, _, _)) => init + data.size.toInt
        case (init, _) => init
      }
      // println(s"Next Write Block Window - data: $fullDataSize window:${s.writeWindow} $s")

      if (fullDataSize <= s.writeWindow && s.writeWindow > 0) {
        val bv = chunk.foldLeft(ByteVector.empty) { case (acc, frame) =>
          acc ++ H2Frame.toByteVector(frame)
        }
        state.update(s => s.copy(writeWindow = s.writeWindow - fullDataSize)) >>
          socket.isOpen.ifM(
            socket.write(Chunk.byteVector(bv)) >>
              chunk.traverse_(frame => logger.debug(s"$addrStr Write - $frame")),
            new SocketException("Socket closed when attempting to write").raiseError,
          )
      } else {
        val (nonData, after) = chunk.indexWhere(_.isInstanceOf[H2Frame.Data]) match {
          case None => (chunk, Chunk.empty[H2Frame])
          case Some(ix) => chunk.splitAt(ix)
        }

        val bv = nonData.foldLeft(ByteVector.empty) { case (acc, frame) =>
          acc ++ H2Frame.toByteVector(frame)
        }
        socket.isOpen.ifM(
          socket.write(Chunk.byteVector(bv)) >>
            nonData.traverse_(frame => logger.debug(s"$addrStr Write - $frame")),
          new SocketException("Socket closed when attempting to write").raiseError,
        ) >>
          s.writeBlock.get.rethrow >>
          go(after)
      }
    }
    val firstGoAway = chunk.collectFirst { case g: H2Frame.GoAway =>
      foreachStream(_.receiveGoAway(g)) >> close
    }
    firstGoAway.getOrElse(F.unit) >> go(chunk)
  }

  def writeLoop: Stream[F, Nothing] =
    Stream
      .fromQueueUnterminated[F, Chunk[H2Frame]](outgoing, Int.MaxValue)
      .foreach(writeChunk)
      .handleErrorWith(ex => Stream.exec(logger.debug(ex)("writeLoop terminated")))

  // TODO Split Frames between Data and Others Hold Data If we are at cap
  //  Currently will backpressure at the data frame till its cleared

  def readLoop: F[Unit] = {
    def connectionTerminated: String = s"Connection $addrStr readLoop Terminated"
    val readFromSocket: F[Option[Chunk[Byte]]] =
      socket.read(localSettings.initialWindowSize.windowSize)

    def readNextFrame(acc: ByteVector): F[Option[(H2Frame, ByteVector)]] =
      if (acc.isEmpty) {
        readFromSocket.flatMap {
          case Some(chunk) => readNextFrame(chunk.toByteVector)
          case None =>
            logger.debug(s"$connectionTerminated with empty").as(None)
        }
      } else
        H2Frame.RawFrame.fromByteVector(acc) match {
          case Some((raw, leftover)) =>
            H2Frame.fromRaw(raw) match {
              case Right(frame) => F.pure(Some((frame, leftover)))
              case Left(e) =>
                logger.warn(s"$connectionTerminated invalid Raw to Frame $e") >>
                  goAway(e) >> F.pure(None)
            }
          case None =>
            readFromSocket.flatMap {
              case Some(chunk) => readNextFrame(acc ++ chunk.toByteVector)
              case None => logger.debug(s"$connectionTerminated with $acc").as(None)
            }
        }

    sealed trait Reading
    case object Stateless extends Reading
    sealed trait Stateful extends Reading
    object Stateful {
      final case class Headers(
          headers: H2Frame.Headers,
          continuations: Chain[H2Frame.Continuation],
      ) extends Stateful
      final case class PushPromise(
          promise: H2Frame.PushPromise,
          continuations: Chain[H2Frame.Continuation],
      ) extends Stateful
    }
    val continueStateless: F[Reading] = F.pure(Stateless)

    def processFrameStateless(frame: H2Frame): F[Reading] = (frame.tag: @switch) match {
      case H2Frame.Data.`type` =>
        handleData(frame.asInstanceOf[H2Frame.Data]).as(Stateless)
      case H2Frame.Headers.`type` =>
        state.get.flatMap(handleHeaders(frame.asInstanceOf[H2Frame.Headers], _))
      case H2Frame.Priority.`type` =>
        val priority = frame.asInstanceOf[H2Frame.Priority]
        if (priority.identifier == priority.streamDependency)
          goAway(H2Error.ProtocolError) // Can't depend on yourself
        else continueStateless // We Do Nothing with these presently
      case H2Frame.RstStream.`type` =>
        val rst = frame.asInstanceOf[H2Frame.RstStream]
        getStream(rst.identifier).flatMap {
          case Some(s) =>
            s.receiveRstStream(rst).as(Stateless)
          case None =>
            logger.warn(
              s"Received RstStream for Idle or Closed Stream ${rst.identifier} - Protocol Error - Issuing GoAway"
            ) >>
              goAway(H2Error.ProtocolError)
        }
      case H2Frame.Settings.`type` =>
        handleSettings(frame.asInstanceOf[H2Frame.Settings]).as(Stateless)
      case H2Frame.PushPromise.`type` =>
        state.get.flatMap(handlePushPromise(frame.asInstanceOf[H2Frame.PushPromise], _))
      case H2Frame.Ping.`type` =>
        frame.asInstanceOf[H2Frame.Ping] match {
          case H2Frame.Ping(0, false, bv) =>
            outgoing.offer(Chunk.singleton(H2Frame.Ping.ack.copy(data = bv))).as(Stateless)
          case H2Frame.Ping(0, true, _) => continueStateless
          case H2Frame.Ping(_, _, _) => goAway(H2Error.ProtocolError)
        }
      case H2Frame.GoAway.`type` =>
        val g = frame.asInstanceOf[H2Frame.GoAway]
        if (g.identifier == 0)
          foreachStream(_.receiveGoAway(g)) >> outgoing
            .offer(Chunk.singleton(H2Frame.Ping.ack))
            .as(Stateless)
        else goAway(H2Error.ProtocolError)
      case H2Frame.WindowUpdate.`type` =>
        handleWindowUpdate(frame.asInstanceOf[H2Frame.WindowUpdate]).as(Stateless)
      case H2Frame.Continuation.`type` =>
        goAway(H2Error.ProtocolError)
      case _ => // H2Frame.Unknown
        continueStateless // Ignore Unknown Frames
    }

    def processFrameStateful(frame: H2Frame, stateful: Stateful): F[Reading] = stateful match {
      // Headers and Continuation Frames are Stateful
      // Headers if not closed MUST
      case Stateful.Headers(h, cs) =>
        frame match {
          case c @ H2Frame.Continuation(id, last, _) =>
            if (h.identifier == id) {
              if (last)
                getStream(id)
                  .flatMap {
                    case Some(s) =>
                      s.receiveHeaders(h, cs.append(c))
                    case None =>
                      streamCreateAndHeaders.use(_ =>
                        for {
                          stream <- initiateRemoteStreamById(id)
                          _ <- createdStreams.offer(id)
                          _ <- stream.receiveHeaders(h, cs.append(c))
                        } yield ()
                      )
                  }
                  .as(Stateless)
              else
                F.pure(Stateful.Headers(h, cs.append(c)))
            } else {
              logger.warn("Invalid Continuation - Protocol Error - Issuing GoAway") >>
                goAway(H2Error.ProtocolError)
            }
          case f =>
            // Only Continuation Frames Are Valid While there is a value
            logger.warn(
              s"Continuation for headers in process, retrieved unexpected frame $f -  Protocol Error - Issuing GoAway"
            ) >>
              goAway(H2Error.ProtocolError)
        }
      case Stateful.PushPromise(p, cs) =>
        frame match {
          case c @ H2Frame.Continuation(id, last, _) =>
            if (p.promisedStreamId == id) {
              if (last)
                getStream(id)
                  .flatMap {
                    case Some(s) =>
                      s.receivePushPromise(p, cs.append(c))
                    case None =>
                      streamCreateAndHeaders.use(_ =>
                        for {
                          stream <- initiateRemoteStreamById(id)
                          _ <- createdStreams.offer(id)
                          _ <- stream.receivePushPromise(p, cs.append(c))
                        } yield ()
                      )
                  }
                  .as(Stateless)
              else
                F.pure(Stateful.PushPromise(p, cs.append(c)))
            } else {
              logger.warn("Invalid Continuation - Protocol Error - Issuing GoAway") >>
                goAway(H2Error.ProtocolError)
            }
          case f =>
            // Only Continuation Frames Are Valid While there is a value
            logger.warn(
              s"Continuation for push promise in process, retrieved unexpected frame $f -  Protocol Error - Issuing GoAway"
            ) >>
              goAway(H2Error.ProtocolError)
        }
    }

    def handleHeaders(h: H2Frame.Headers, s: H2Connection.State[F]): F[Reading] = h match {
      case h @ H2Frame.Headers(i, sd, _, true, headerBlock, _) =>
        val size = headerBlock.size.toInt
        if (size > s.remoteSettings.maxFrameSize.frameSize) {
          logger.warn("Header Size too large for frame size - FrameSizeError - Issuing GoAway") >>
            goAway(H2Error.FrameSizeError)
        } else if (sd.exists(s => s.dependency == i)) {
          goAway(H2Error.ProtocolError)
        } else {
          getStream(i).flatMap {
            case Some(s) =>
              s.receiveHeaders(h, Chain.empty).as(Stateless)
            case None =>
              val isValidToCreate = connectionType match {
                case H2Connection.ConnectionType.Server => i % 2 != 0
                case H2Connection.ConnectionType.Client => i % 2 == 0
              }
              if (!isValidToCreate || i <= s.remoteHighestStream) {
                logger.warn(
                  s"Not Valid Stream to Create $i - $isValidToCreate, ${s.highestStream} - Protocol Error - Issuing GoAway"
                ) >>
                  goAway(H2Error.ProtocolError)
              } else {
                streamCreateAndHeaders.use(_ =>
                  for {
                    stream <- initiateRemoteStreamById(i)
                    _ <- createdStreams.offer(i)
                    _ <- stream.receiveHeaders(h, Chain.empty)
                  } yield Stateless
                )
              }
          }
        }
      case h @ H2Frame.Headers(i, sd, _, false, headerBlock, _) =>
        val size = headerBlock.size.toInt
        if (size > s.remoteSettings.maxFrameSize.frameSize) goAway(H2Error.FrameSizeError)
        else if (sd.exists(s => s.dependency == i)) goAway(H2Error.ProtocolError)
        else F.pure(Stateful.Headers(h, Chain.empty))
    }

    def handlePushPromise(
        pp: H2Frame.PushPromise,
        s: H2Connection.State[F],
    ): F[Reading] = pp match {
      case H2Frame.PushPromise(_, true, i, headerBlock, _) =>
        val size = headerBlock.size.toInt
        if (connectionType == H2Connection.ConnectionType.Server) {
          logger.warn(
            "Encountered Push Promise Frame a a Server - Protocol Error - Issuing GoAway"
          ) >>
            goAway(H2Error.ProtocolError)
        } else if (size > s.remoteSettings.maxFrameSize.frameSize) {
          logger.warn("Header Size too large for frame size - FrameSizeError - Issuing GoAway") >>
            goAway(H2Error.FrameSizeError)
        } else {
          getStream(i).flatMap {
            case Some(s) =>
              s.receivePushPromise(pp, Chain.empty).as(Stateless)
            case None =>
              val isValidToCreate = i % 2 == 0
              if (!isValidToCreate || i <= s.remoteHighestStream) {
                logger.warn(
                  s"Not Valid Stream to Create $i - $isValidToCreate, ${s.remoteHighestStream} - Protocol Error - Issuing GoAway"
                )
                goAway(H2Error.ProtocolError)
              } else {
                streamCreateAndHeaders.use(_ =>
                  for {
                    stream <- initiateRemoteStreamById(i)
                    _ <- createdStreams.offer(i)
                    _ <- stream.receivePushPromise(pp, Chain.empty)
                  } yield Stateless
                )
              }
          }
        }
      case H2Frame.PushPromise(_, false, _, headerBlock, _) =>
        val size = headerBlock.size.toInt
        if (size > s.remoteSettings.maxFrameSize.frameSize) goAway(H2Error.FrameSizeError)
        else F.pure(Stateful.PushPromise(pp, Chain.empty))
    }

    def handleSettings(settings: H2Frame.Settings): F[Unit] = settings match {
      case H2Frame.Settings(0, false, _) =>
        for {
          newWriteBlock <- Deferred[F, Either[Throwable, Unit]]
          t <- state.modify { s =>
            val newSettings = H2Frame.Settings.updateSettings(settings, s.remoteSettings)
            val differenceInWindow =
              newSettings.initialWindowSize.windowSize - s.remoteSettings.initialWindowSize.windowSize
            (
              s.copy(
                remoteSettings = newSettings,
                writeWindow = s.writeWindow,
                writeBlock = newWriteBlock,
              ),
              (newSettings, differenceInWindow, s.writeBlock),
            )
          }
          (settings, difference, oldWriteBlock) = t
          _ <- oldWriteBlock.complete(Either.unit)
          _ <- foreachStream(_.modifyWriteWindow(difference))
          _ <- outgoing.offer(Chunk.singleton(H2Frame.Settings.Ack))
          _ <- settingsAck.complete(Either.right(settings)).void
        } yield ()
      case H2Frame.Settings(0, true, _) => Applicative[F].unit
      case H2Frame.Settings(_, _, _) =>
        logger.warn("Received Settings Not Oriented at Identifier 0 - Issuing goAway") >>
          goAway(H2Error.ProtocolError)
    }

    def handleWindowUpdate(w: H2Frame.WindowUpdate): F[Unit] = w match {
      case H2Frame.WindowUpdate(_, 0) =>
        logger.warn("Encountered 0 Sized Window Update - Procol Error - Issuing GoAway") >>
          goAway(H2Error.ProtocolError)
      case H2Frame.WindowUpdate(i, size) =>
        i match {
          case 0 =>
            for {
              newWriteBlock <- Deferred[F, Either[Throwable, Unit]]
              t <- state.modify { s =>
                val newSize = s.writeWindow + size
                val sizeValid =
                  (s.writeWindow >= 0 && newSize >= 0) || s.writeWindow < 0 // Less than 2^31-1 and didn't overflow, going negative
                (
                  s.copy(writeBlock = newWriteBlock, writeWindow = s.writeWindow + size),
                  (s.writeBlock, sizeValid),
                )
              }
              (oldWriteBlock, valid) = t
              _ <- oldWriteBlock.complete(Either.unit)
              _ <- {
                if (!valid) goAway(H2Error.FlowControlError)
                else Applicative[F].unit
              }
            } yield ()
          case otherwise =>
            getStream(otherwise).flatMap {
              case Some(s) =>
                s.receiveWindowUpdate(w)
              case None =>
                logger.warn(s"Received WindowUpdate for Closed or Idle Stream - $w, $i") >>
                  goAway(H2Error.ProtocolError)
            }
        }
    }

    def handleData(d: H2Frame.Data): F[Unit] = {
      val size = d.data.size.toInt
      if (size > localSettings.maxFrameSize.frameSize) {
        logger.warn(
          "Receive Data Size Larger than Allowed Frame Size - Frame Size Error - Issuing GoAway"
        ) >>
          goAway(H2Error.FrameSizeError)
      } else {
        getStream(d.identifier).flatMap {
          case Some(s) =>
            for {
              st <- state.get
              newSize = st.readWindow - d.data.size.toInt

              needsWindowUpdate = newSize <= (localSettings.initialWindowSize.windowSize / 2)
              _ <- state.update(s =>
                s.copy(readWindow =
                  if (needsWindowUpdate) localSettings.initialWindowSize.windowSize
                  else newSize.toInt
                )
              )
              _ <-
                if (needsWindowUpdate)
                  outgoing.offer(
                    Chunk.singleton(
                      H2Frame.WindowUpdate(
                        0,
                        st.remoteSettings.initialWindowSize.windowSize - newSize.toInt,
                      )
                    )
                  )
                else Applicative[F].unit
              _ <- s.receiveData(d)
            } yield ()
          case None =>
            logger.warn(
              s"Received Data Frame for Idle or Closed Stream ${d.identifier} - Protocol Error - Issuing GoAway"
            ) >>
              goAway(H2Error.ProtocolError)
        }
      }
    }

    def readLoopAux(acc: ByteVector, readingState: Reading): F[Unit] =
      readNextFrame(acc).flatMap {
        case Some((frame, nacc)) =>
          logger.debug(s"$addrStr Read - $frame") >>
            (readingState match {
              case Stateless => processFrameStateless(frame)
              case stateful: Stateful => processFrameStateful(frame, stateful)
            }).flatMap(readLoopAux(nacc, _))
        case None => F.unit
      }

    F.guaranteeCase(readLoopAux(acc, Stateless)) {
      case Outcome.Errored(H2Connection.KillWithoutMessage()) =>
        logger.debug(s"ReadLoop has received that is should kill") >>
          close
      case Outcome.Errored(e) =>
        logger.error(e)(s"ReadLoop has errored") >>
          goAway(H2Error.InternalError) >>
          close
      case _ => close
    }
  }

  private def foreachStream(f: H2Stream[F] => F[Unit]): F[Unit] =
    mapRef.get.flatMap { map =>
      map.valuesIterator.foldLeft(F.unit)((acc, stream) => F.productR(acc)(f(stream)))
    }

  private def getStream(id: Int): F[Option[H2Stream[F]]] =
    mapRef.get.map(_.get(id))

  private def close: F[Unit] =
    state.update(s => s.copy(closed = true))
}

private[h2] object H2Connection {
  final case class State[F[_]](
      remoteSettings: H2Frame.Settings.ConnectionSettings,
      writeWindow: Int,
      writeBlock: Deferred[F, Either[Throwable, Unit]],
      readWindow: Int,
      highestStream: Int,
      remoteHighestStream: Int,
      closed: Boolean,
  )

  def initState[F[_]](
      remoteSettings: H2Frame.Settings.ConnectionSettings,
      writeWindow: SettingsInitialWindowSize,
      readWindow: SettingsInitialWindowSize,
  )(implicit F: Async[F]): F[Ref[F, State[F]]] =
    Deferred[F, Either[Throwable, Unit]].flatMap { writeBlock =>
      val state = H2Connection.State(
        remoteSettings,
        writeWindow.windowSize,
        writeBlock,
        readWindow.windowSize,
        highestStream = 0,
        remoteHighestStream = 0,
        closed = false,
      )
      F.ref(state)
    }

  final case class KillWithoutMessage()
      extends RuntimeException
      with scala.util.control.NoStackTrace

  sealed trait ConnectionType
  object ConnectionType {
    case object Server extends ConnectionType
    case object Client extends ConnectionType
  }

}
