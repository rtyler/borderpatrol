package com.lookout.borderpatrol

import com.lookout.borderpatrol.session.id.{SignerComponent, ExpiryComponent, Marshaller, Generator}
import com.lookout.borderpatrol.session.secret._
import com.lookout.borderpatrol.session.store.{InMemorySessionStore, SessionStoreComponent}
import com.lookout.borderpatrol.session._
import com.twitter.util.Time
import org.jboss.netty.handler.codec.http.HttpRequest

trait SecureSession {
  val id: SessionId
  val originalRequest: HttpRequest
  val tokens: Tokens

}

case class Session(id: SessionId, originalRequest: HttpRequest, tokens: Tokens) extends SecureSession {
  def equals(o: SecureSession): Boolean =
    id == o.id && tokens == o.tokens && (originalRequest.getUri == o.originalRequest.getUri &&
      originalRequest.getMethod == o.originalRequest.getMethod)
}

trait SessionFactory {
  def apply(s: String, originalRequest: HttpRequest): Session
  def apply(request: RoutedRequest): Session
}

object Session extends SessionFactory with SignerComponent
                                      with ExpiryComponent
                                      with SecretStoreComponent
                                      with SessionStoreComponent {

  val cookieName = "border_session"
  val entropySize = Constants.SessionId.entropySize
  implicit val secretStore = getSecretStore
  implicit val marshaller = Marshaller(secretStore)
  implicit val generator: Generator = new Generator
  val sessionStore = new InMemorySessionStore

  def apply(s: String, originalRequest: HttpRequest): Session =
    sessionStore.get(s) getOrElse newSession(originalRequest)

  def apply(request: RoutedRequest): Session =
    request.borderCookie.flatMap(id => sessionStore.get(id)) getOrElse newSession(request.httpRequest)

  def newSession(originalRequest: HttpRequest): Session =
    Session(generator.next, originalRequest, Tokens.empty)

  def save(session: Session): Session =
    sessionStore.update(session)

  //TODO: This should be configurable(should be Memory for unit tests, and consul in run mode
  //def getSecretStore: SecretStoreApi = ConsulSecretStore(ConsulSecretsWatcher(new ConsulService))
  def getSecretStore: SecretStoreApi = InMemorySecretStore(Secrets(Secret(SecretExpiry.currentExpiry), Secret(Time.fromSeconds(100))))
}
