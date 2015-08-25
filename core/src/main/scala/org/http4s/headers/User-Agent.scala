package org.http4s
package headers

import org.http4s.util.{Renderable, Writer}

object `User-Agent` extends HeaderKey.Internal[`User-Agent`] with HeaderKey.Singleton

sealed trait AgentToken extends Renderable

case class AgentProduct(name: String, version: Option[String] = None) extends AgentToken {
  override def render(writer: Writer): writer.type = {
    writer << name
    version.foreach { v => writer << '/' << v }
    writer
  }
}
case class AgentComment(comment: String) extends AgentToken {
  override def renderString = comment
  override def render(writer: Writer): writer.type = writer << comment
}

case class `User-Agent`(product: AgentProduct, other: Seq[AgentToken] = Seq.empty) extends Header.Parsed {
  def key = `User-Agent`

  override def renderValue(writer: Writer): writer.type = {
    writer << product
    other.foreach { 
      case p: AgentProduct => writer << ' ' << p
      case AgentComment(c) => writer << ' ' << '(' << c << ')'
    }
    writer
  }
}
