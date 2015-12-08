package org.http4s.client.blaze

import org.http4s.Request
import org.http4s.util.string._
import org.http4s.Uri.{Authority, Scheme}
import org.log4s.getLogger

import scalaz.concurrent.Task


/* implementation bits for the pooled client manager */
private final class PoolManager (maxPooledConnections: Int, builder: ConnectionBuilder) extends ConnectionManager {
  require(maxPooledConnections > 0, "Must have finite connection pool size")

  private case class RequestKey(scheme: Scheme, auth: Authority)

  private[this] val logger = getLogger
  private var closed = false  // All access in synchronized blocks, no need to be volatile
  private val pool = KeyedPool[RequestKey, BlazeClientStage]()

  private def requestKey(req: Request) = {
    val uri = req.uri
    RequestKey(uri.scheme.getOrElse("http".ci), uri.authority.getOrElse(Authority()))
  }

  /** Shutdown this client, closing any open connections and freeing resources */
  override def shutdown(): Task[Unit] = Task.delay {
    logger.debug(s"Shutting down ${getClass.getName}.")
    pool.close
  }

  override def recycleClient(request: Request, stage: BlazeClientStage): Unit = {
    logger.debug("Recycling connection.")
    val key = requestKey(request)
    pool.returnObject(key, stage)
  }

  override def getClient(request: Request, freshClient: Boolean): Task[BlazeClientStage] = {
    val key = requestKey(request)
    if (freshClient)
      pool.addObject(key)
    else
      pool.borrowObject(key)
  }
}
