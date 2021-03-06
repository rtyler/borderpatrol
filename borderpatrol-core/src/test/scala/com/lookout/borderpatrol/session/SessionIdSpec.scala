package com.lookout.borderpatrol.session

import java.util.concurrent.TimeUnit

import com.lookout.borderpatrol.session.id.{Marshaller, Generator => IdGenerator}
import com.lookout.borderpatrol.session.secret.InMemorySecretStore
import com.twitter.bijection.{Base64String, Injection}
import com.twitter.util.{Duration, Time}
import org.scalactic.Equality
import org.scalatest.{FlatSpec, Matchers, TryValues}

class SessionIdSpec extends FlatSpec with Matchers with TryValues {

  import com.lookout.borderpatrol.session.SecretExpiry._
  def expiredExpiry: Time = Time.fromSeconds(42)

  def mockSecret = Secret(currentExpiry)
  implicit val mockSecretStore = new InMemorySecretStore(Secrets(mockSecret, Secret(expiredExpiry)))
  def mockGenerator = new IdGenerator
  implicit val marshaller = Marshaller(mockSecretStore)
  /*
  implicit val sessionIdEq =
    new Equality[SessionId] {
      def areEqual(a: SessionId, b: Any): Boolean =
        b match {
          case s: SessionId => (a.toSeq == s.toSeq) && (a.expires.inLongSeconds == s.expires.inLongSeconds)
          case _ => false
        }
    }
    */

  behavior of "Generator"

  it should "create valid SessionId instances" in {
    val sid = mockGenerator.next
    val sig = mockSecretStore.current.sign(sid.payload).toVector
    sid.expired shouldBe false
    sid.signature shouldEqual sig
    sid.secretId shouldEqual mockSecretStore.current.id
    sid.entropy should have size Constants.SessionId.entropySize
  }

  behavior of "Marshaller"

  it should "create a base64 string from a SessionId" in {
    val sid = mockGenerator.next
    implicit lazy val bytes2String = Injection.connect[Array[Byte], Base64String, String]
    val str = bytes2String(sid.toBytes)
    val encoded = marshaller.encode(sid)
    encoded shouldBe a [String]
    encoded should fullyMatch regex ("[a-zA-Z0-9+/]+")
    encoded shouldEqual str
  }

  it should "create a SessionId from a valid base64 string" in {
    val sid = mockGenerator.next
    val sidPrime = marshaller.decode(marshaller.encode(sid)).get
    sidPrime shouldEqual sid
  }

  it should "create a (SessionId,Secret) from a valid base64 string" in {
    val sid = mockGenerator.next
    val sidPrime = marshaller.decodeWithSecret(marshaller.encode(sid)).get
    sidPrime shouldEqual (sid, mockSecretStore.current)
  }

  it should "fail to create a session id if expired" in {
    val sid = mockGenerator.next
    val expiredSid = SessionId(Time.fromSeconds(0), sid.entropy, sid.secretId, sid.signature)
    val decoded = marshaller.decode(expiredSid.asString)
    decoded.failure.exception should have message "Time has expired"
  }

  it should "fail to create a session id if no valid secret was found" in {
    val invalidSecret = Secret(Time.fromSeconds(0))
    implicit val store = InMemorySecretStore(Secrets(invalidSecret, Secret(expiredExpiry)))
    val sid = mockGenerator.next(store)
    val decoded = marshaller.decode(sid.asString)
    decoded.failure.exception should have message "No matching secrets found"
  }

  it should "fail to create a session id when signature is invalid" in {
    val sid = mockGenerator.next
    val invalidSignature = Secret(Time.now).sign(sid.entropy).toVector
    val invalidSid = SessionId(sid.expires, sid.entropy, sid.secretId, invalidSignature)
    val decoded = marshaller.decode(invalidSid.asString)
    decoded.failure.exception should have message "Invalid signature"
  }

  it should "fail to create a session id when decoded value is invalid" in {
    val decoded = marshaller.decode("123abcd")
    decoded.failure.exception should have message "Not a session string"
  }

  it should "have identity property for valid sessionid and secret values" in {
    val sid = mockGenerator.next
    marshaller.injector.idAndSecret2Id.invert(
      marshaller.injector.idAndSecret2Id(sid, mockSecretStore.current)).success.value shouldBe (sid, mockSecretStore.current)
  }

  it should "fail to create a (SessionId, Secret) from a sessionId with an invalid secret" in {
    val sid = mockGenerator.next
    val invalidSecretId = ~sid.secretId
    val invalid = SessionId(sid.expires, sid.entropy, invalidSecretId.toByte, sid.signature)
    marshaller.injector.idAndSecret2Id.invert(invalid).failure.exception should have message "No matching secrets found"
  }
}