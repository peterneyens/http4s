package org.http4s.client.blaze

import scala.concurrent.duration.Duration
import scalaz.concurrent.Task

// Inspired by commons-pool2
class KeyedPool[K, V] private {
  def borrowObject(key: K, maxWait: Duration = Duration.Inf): Task[V] = ???

  def returnObject(key: K, obj: V): Unit = ???

  def invalidateObject(key: K, obj: V): Unit = ???

  def addObject(key: K, maxWait: Duration = Duration.Inf): Task[V] = ???

  def numIdle(key: K): Option[Int] = ???

  def numActive(key: K): Option[Int] = ???

  def totalNumIdle: Int = ???

  def totalNumActive: Int = ???

  def close: Task[Unit] = ???
}

object KeyedPool {
  def apply[K, V](): KeyedPool[K, V] = new KeyedPool[K, V]()
}

