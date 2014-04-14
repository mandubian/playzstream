
/*
 * Copyright 2013 Pascal Voitot (@mandubian)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

 /** Simply Play Framework WS hacked with scalaz-stream */
package play.api.libs.ws

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ Future, Promise }
import play.api.libs.iteratee._
import play.api.libs.iteratee.Input._
import play.api.http.{ Writeable, ContentTypeOf }
import com.ning.http.client.{
  AsyncHttpClient,
  AsyncHttpClientConfig,
  RequestBuilderBase,
  FluentCaseInsensitiveStringsMap,
  HttpResponseBodyPart,
  HttpResponseHeaders,
  HttpResponseStatus,
  Response => AHCResponse,
  Cookie => AHCCookie,
  PerRequestConfig
}
import collection.immutable.TreeMap
import play.core.utils.CaseInsensitiveOrdered
import com.ning.http.util.AsyncHttpProviderUtils

import scalaz.stream._

import scala.concurrent.stm._


//import play.core.Execution.Implicits.internalContext

/**
 * Asynchronous API to to query web services, as an http client.
 *
 * Usage example:
 * {{{
 * WS.url("http://example.com/feed").get()
 * WS.url("http://example.com/item").post("content")
 * }}}
 *
 * The value returned is a Future[Response],
 * and you should use Play's asynchronous mechanisms to use this response.
 *
 */
object WSZ {

  import com.ning.http.client.Realm.{ AuthScheme, RealmBuilder }
  import javax.net.ssl.SSLContext

  private val clientHolder: AtomicReference[Option[AsyncHttpClient]] = new AtomicReference(None)

  /**
   * resets the underlying AsyncHttpClient
   */
  private[play] def resetClient(): Unit = {
    val oldClient = clientHolder.getAndSet(None)
    oldClient.map { clientRef =>
      clientRef.close()
    }
  }

  /**
   * retrieves or creates underlying HTTP client.
   */
  def client = {
    clientHolder.get.getOrElse {
      // Note, the following code may execute more than once, if there are several
      // simultaneous calls to this function on different threads.  In that case,
      // it's possible that an AsyncHttpClient will be created by the following
      // code, but then discarded, because another thread was able to create one
      // and store it in `clientHolder` first.
      val playConfig = play.api.Play.maybeApplication.map(_.configuration)
      val asyncHttpConfig = new AsyncHttpClientConfig.Builder()
        .setConnectionTimeoutInMs(playConfig.flatMap(_.getMilliseconds("ws.timeout")).getOrElse(120000L).toInt)
        .setRequestTimeoutInMs(playConfig.flatMap(_.getMilliseconds("ws.timeout")).getOrElse(120000L).toInt)
        .setFollowRedirects(playConfig.flatMap(_.getBoolean("ws.followRedirects")).getOrElse(true))
        .setUseProxyProperties(playConfig.flatMap(_.getBoolean("ws.useProxyProperties")).getOrElse(true))

      playConfig.flatMap(_.getString("ws.useragent")).map { useragent =>
        asyncHttpConfig.setUserAgent(useragent)
      }
      if (playConfig.flatMap(_.getBoolean("ws.acceptAnyCertificate")).getOrElse(false) == false) {
        asyncHttpConfig.setSSLContext(SSLContext.getDefault)
      }
      val innerClient = new AsyncHttpClient(asyncHttpConfig.build())
      // Only use our newly created AsyncHttpClient if clientHolder is still None, that is,
      // if no other thread has snuck in and stored a different one in clientHolder.
      clientHolder.compareAndSet(None, Some(innerClient))
      clientHolder.get.get
    }
  }

  /**
   * Prepare a new request. You can then construct it by chaining calls.
   *
   * @param url the URL to request
   */
  def url(url: String): WSRequestHolder = WSRequestHolder(url, Map(), Map(), None, None, None, None, None)

  /**
   * A WS Request.
   */
  class WSRequest(_method: String, _auth: Option[Tuple3[String, String, AuthScheme]], _calc: Option[SignatureCalculator]) extends RequestBuilderBase[WSRequest](classOf[WSRequest], _method, false) {

    import scala.collection.JavaConverters._

    def getStringData = body.getOrElse("")
    protected var body: Option[String] = None
    override def setBody(s: String) = { this.body = Some(s); super.setBody(s) }

    protected var calculator: Option[SignatureCalculator] = _calc

    protected var headers: Map[String, Seq[String]] = Map()

    protected var _url: String = null

    //this will do a java mutable set hence the {} response
    _auth.map(data => auth(data._1, data._2, data._3)).getOrElse({})

    /**
     * Add http auth headers. Defaults to HTTP Basic.
     */
    private def auth(username: String, password: String, scheme: AuthScheme = AuthScheme.BASIC): WSRequest = {
      this.setRealm((new RealmBuilder())
        .setScheme(scheme)
        .setPrincipal(username)
        .setPassword(password)
        .setUsePreemptiveAuth(true)
        .build())
    }

    /**
     * Return the current headers of the request being constructed
     */
    def allHeaders: Map[String, Seq[String]] = {
      mapAsScalaMapConverter(request.asInstanceOf[com.ning.http.client.Request].getHeaders()).asScala.map(e => e._1 -> e._2.asScala.toSeq).toMap
    }

    /**
     * Return the current query string parameters
     */
    def queryString: Map[String, Seq[String]] = {
      mapAsScalaMapConverter(request.asInstanceOf[com.ning.http.client.Request].getParams()).asScala.map(e => e._1 -> e._2.asScala.toSeq).toMap
    }

    /**
     * Retrieve an HTTP header.
     */
    def header(name: String): Option[String] = headers.get(name).flatMap(_.headOption)

    /**
     * The HTTP method.
     */
    def method: String = _method

    /**
     * The URL
     */
    def url: String = _url

    private def ningHeadersToMap(headers: java.util.Map[String, java.util.Collection[String]]) =
      mapAsScalaMapConverter(headers).asScala.map(e => e._1 -> e._2.asScala.toSeq).toMap

    private def ningHeadersToMap(headers: FluentCaseInsensitiveStringsMap) = {
      val res = mapAsScalaMapConverter(headers).asScala.map(e => e._1 -> e._2.asScala.toSeq).toMap
      //todo: wrap the case insensitive ning map instead of creating a new one (unless perhaps immutabilty is important)
      TreeMap(res.toSeq: _*)(CaseInsensitiveOrdered)
    }
    private[libs] def execute: Future[Response] = {
      import com.ning.http.client.AsyncCompletionHandler
      var result = Promise[Response]()
      calculator.map(_.sign(this))
      WS.client.executeRequest(this.build(), new AsyncCompletionHandler[AHCResponse]() {
        override def onCompleted(response: AHCResponse) = {
          result.success(Response(response))
          response
        }
        override def onThrowable(t: Throwable) = {
          result.failure(t)
        }
      })
      result.future
    }

    /**
     * Set an HTTP header.
     */
    override def setHeader(name: String, value: String) = {
      headers = headers + (name -> List(value))
      super.setHeader(name, value)
    }

    /**
     * Add an HTTP header (used for headers with multiple values).
     */
    override def addHeader(name: String, value: String) = {
      headers = headers + (name -> (headers.get(name).getOrElse(List()) :+ value))
      super.addHeader(name, value)
    }

    /**
     * Defines the request headers.
     */
    override def setHeaders(hdrs: FluentCaseInsensitiveStringsMap) = {
      headers = ningHeadersToMap(hdrs)
      super.setHeaders(hdrs)
    }

    /**
     * Defines the request headers.
     */
    override def setHeaders(hdrs: java.util.Map[String, java.util.Collection[String]]) = {
      headers = ningHeadersToMap(hdrs)
      super.setHeaders(hdrs)
    }

    /**
     * Defines the request headers.
     */
    def setHeaders(hdrs: Map[String, Seq[String]]) = {
      headers = hdrs
      hdrs.foreach(header => header._2.foreach(value =>
        super.addHeader(header._1, value)
      ))
      this
    }

    /**
     * Defines the query string.
     */
    def setQueryString(queryString: Map[String, Seq[String]]) = {
      for ((key, values) <- queryString; value <- values) {
        this.addQueryParameter(key, value)
      }
      this
    }

    /**
     * Defines the URL.
     */
    override def setUrl(url: String) = {
      _url = url
      super.setUrl(url)
    }

    private[libs] def staticStream: Future[Process.Process1[Array[Byte], Array[Byte]]] = {
      import com.ning.http.client.AsyncHandler
      var doneOrError = false
      calculator.map(_.sign(this))

      var statusCode = 0
      val processP = Promise[Process.Process1[Array[Byte], Array[Byte]]]()
      var process: Process.Process1[Array[Byte], Array[Byte]] = Process.await1[Array[Byte]]

      WS.client.executeRequest(this.build(), new AsyncHandler[Unit]() {
        import com.ning.http.client.AsyncHandler.STATE

        override def onStatusReceived(status: HttpResponseStatus) = {
          statusCode = status.getStatusCode()
          STATE.CONTINUE
        }

        override def onHeadersReceived(h: HttpResponseHeaders) = {
          val headers = h.getHeaders()
          STATE.CONTINUE
        }

        override def onBodyPartReceived(bodyPart: HttpResponseBodyPart) = {
          if (!doneOrError) {
            process = process match { 
              case Process.Await(_, recv, fb, err) => recv(bodyPart.getBodyPartBytes())
              case _ => process append Process.emit(bodyPart.getBodyPartBytes())
            }

            STATE.CONTINUE
          } else {
            process = Process.halt
            // Must close underlying connection, otherwise async http client will drain the stream
            bodyPart.markUnderlyingConnectionAsClosed()
            STATE.ABORT
          }
        }

        override def onCompleted() = {
          Option(process append Process.halt).map(processP.success(_))
        }

        override def onThrowable(t: Throwable) = {
          processP.failure(t)
        }
      })
      processP.future
    }

    private[libs] def realtimeStream: Process[Future, Array[Byte]] = {
      import com.ning.http.client.AsyncHandler
      var isFirst = true
      calculator.map(_.sign(this))

      var statusCode = 0
      val trigger = Promise[Array[Byte]]()
      // buffer here... WARNING
      val dataRef = Ref(List[Promise[Array[Byte]]](trigger))

      def step(a:Array[Byte]): Process[Future, Array[Byte]] = {
        val prevs = dataRef.single.getAndTransform( l => List(l.head) )

        def fold(
          curP: Process[Future, Array[Byte]], 
          curL: List[Promise[Array[Byte]]]
        ): Process[Future, Array[Byte]] = {
          curL match {
            case List(last) => curP append Process.await(last.future)(step)
            case List() => sys.error("impossible case")
            case h :: t => fold(curP append Process.await(h.future)(Process.emit), t)
          }
        }
        fold(Process.emit(a), prevs.reverse.tail)
        // prevs.tail.reverse.foldLeft(Process.emit(a):Process[Future, Array[Byte]]){ (p, next) =>
        //   p then Process.await(next.future)(Process.emit)
        // } then Process.await(prevs.head.future)(step)
      }
      val process = Process.await(trigger.future)(step).repeat

      WS.client.executeRequest(this.build(), new AsyncHandler[Unit]() {
        import com.ning.http.client.AsyncHandler.STATE

        override def onStatusReceived(status: HttpResponseStatus) = {
          statusCode = status.getStatusCode()
          STATE.CONTINUE
        }

        override def onHeadersReceived(h: HttpResponseHeaders) = {
          val headers = h.getHeaders()
          STATE.CONTINUE
        }

        override def onBodyPartReceived(bodyPart: HttpResponseBodyPart) = {
          if (!isFirst) {
            val next = Promise[Array[Byte]]()
            val prevs = dataRef.single.getAndTransform(l => next +: l)
            prevs.head.success(bodyPart.getBodyPartBytes())
            STATE.CONTINUE
          } else {
            isFirst = false
            val next = Promise[Array[Byte]]()
            val prevs = dataRef.single.getAndTransform(l => next +: l)
            trigger.success(bodyPart.getBodyPartBytes())
            STATE.CONTINUE
          }
        }

        override def onCompleted() = {
          // Manage end
          dataRef.single.get.head.failure(Process.End)
        }

        override def onThrowable(t: Throwable) = {
          // Manage error
          dataRef.single.get.head.failure(t)
        }
      })
      process
    }

  }

  /**
   * A WS Request builder.
   */
  case class WSRequestHolder(url: String,
      headers: Map[String, Seq[String]],
      queryString: Map[String, Seq[String]],
      calc: Option[SignatureCalculator],
      auth: Option[Tuple3[String, String, AuthScheme]],
      followRedirects: Option[Boolean],
      timeout: Option[Int],
      virtualHost: Option[String]) {

    /**
     * sets the signature calculator for the request
     * @param calc
     */
    def sign(calc: SignatureCalculator): WSRequestHolder = this.copy(calc = Some(calc))

    /**
     * sets the authentication realm
     * @param calc
     */
    def withAuth(username: String, password: String, scheme: AuthScheme): WSRequestHolder =
      this.copy(auth = Some((username, password, scheme)))

    /**
     * adds any number of HTTP headers
     * @param hdrs
     */
    def withHeaders(hdrs: (String, String)*): WSRequestHolder = {
      val headers = hdrs.foldLeft(this.headers)((m, hdr) =>
        if (m.contains(hdr._1)) m.updated(hdr._1, m(hdr._1) :+ hdr._2)
        else (m + (hdr._1 -> Seq(hdr._2)))
      )
      this.copy(headers = headers)
    }

    /**
     * adds any number of query string parameters to the
     */
    def withQueryString(parameters: (String, String)*): WSRequestHolder =
      this.copy(queryString = parameters.foldLeft(queryString) {
        case (m, (k, v)) => m + (k -> (v +: m.get(k).getOrElse(Nil)))
      })

    /**
     * Sets whether redirects (301, 302) should be followed automatically
     */
    def withFollowRedirects(follow: Boolean): WSRequestHolder =
      this.copy(followRedirects = Some(follow))

    /**
     * Sets the request timeout in milliseconds
     */
    def withTimeout(timeout: Int): WSRequestHolder =
      this.copy(timeout = Some(timeout))

    def withVirtualHost(vh: String): WSRequestHolder = {
      this.copy(virtualHost = Some(vh))
    }

    /**
     * performs a get with supplied body
     * @param consumer that's handling the response
     */
    def get(): Future[Process.Process1[Array[Byte], Array[Byte]]] =
      prepare("GET").staticStream

    def getRealTime(): Process[Future, Array[Byte]] =
      prepare("GET").realtimeStream

    private[play] def prepare(method: String) = {
      val request = new WSRequest(method, auth, calc).setUrl(url)
        .setHeaders(headers)
        .setQueryString(queryString)
      followRedirects.map(request.setFollowRedirects(_))
      timeout.map { t: Int =>
        val config = new PerRequestConfig()
        config.setRequestTimeoutInMs(t)
        request.setPerRequestConfig(config)
      }
      virtualHost.map { v =>
        request.setVirtualHost(v)
      }
      request
    }

  }
}

/**
 * A WS Cookie.  This is a trait so that we are not tied to a specific client.
 */
trait Cookie {

  /**
   * The underlying "native" cookie object for the client.
   */
  def underlying: AnyRef

  /**
   * The domain.
   */
  def domain: String

  /**
   * The cookie name.
   */
  def name: Option[String]

  /**
   * The cookie value.
   */
  def value: Option[String]

  /**
   * The path.
   */
  def path: String

  /**
   * The maximum age.
   */
  def maxAge: Int

  /**
   * If the cookie is secure.
   */
  def secure: Boolean

  /**
   * The cookie version.
   */
  def version: Int
}

/**
 * The Ning implementation of a WS cookie.
 */
private class NingCookie(ahcCookie: AHCCookie) extends Cookie {

  private def noneIfEmpty(value: String): Option[String] = {
    if (value.isEmpty) None else Some(value)
  }

  /**
   * The underlying cookie object for the client.
   */
  def underlying = ahcCookie

  /**
   * The domain.
   */
  def domain: String = ahcCookie.getDomain

  /**
   * The cookie name.
   */
  def name: Option[String] = noneIfEmpty(ahcCookie.getName)

  /**
   * The cookie value.
   */
  def value: Option[String] = noneIfEmpty(ahcCookie.getValue)

  /**
   * The path.
   */
  def path: String = ahcCookie.getPath

  /**
   * The maximum age.
   */
  def maxAge: Int = ahcCookie.getMaxAge

  /**
   * If the cookie is secure.
   */
  def secure: Boolean = ahcCookie.isSecure

  /**
   * The cookie version.
   */
  def version: Int = ahcCookie.getVersion

  /*
   * Cookie ports should not be used; cookies for a given host are shared across
   * all the ports on that host.
   */

  override def toString: String = ahcCookie.toString
}

/**
 * A WS HTTP response.
 */
case class Response(ahcResponse: AHCResponse) {

  import scala.xml._
  import play.api.libs.json._

  /**
   * Get the underlying response object.
   */
  def getAHCResponse = ahcResponse

  /**
   * The response status code.
   */
  def status: Int = ahcResponse.getStatusCode()

  /**
   * The response status message.
   */
  def statusText: String = ahcResponse.getStatusText()

  /**
   * Get a response header.
   */
  def header(key: String): Option[String] = Option(ahcResponse.getHeader(key))

  /**
   * Get all the cookies.
   */
  def cookies: Seq[Cookie] = {
    import scala.collection.JavaConverters._
    ahcResponse.getCookies.asScala.map(new NingCookie(_))
  }

  /**
   * Get only one cookie, using the cookie name.
   */
  def cookie(name: String): Option[Cookie] = cookies.find(_.name == Option(name))

  /**
   * The response body as String.
   */
  lazy val body: String = {
    // RFC-2616#3.7.1 states that any text/* mime type should default to ISO-8859-1 charset if not
    // explicitly set, while Plays default encoding is UTF-8.  So, use UTF-8 if charset is not explicitly
    // set and content type is not text/*, otherwise default to ISO-8859-1
    val contentType = Option(ahcResponse.getContentType).getOrElse("application/octet-stream")
    val charset = Option(AsyncHttpProviderUtils.parseCharset(contentType)).getOrElse {
      if (contentType.startsWith("text/"))
        AsyncHttpProviderUtils.DEFAULT_CHARSET
      else
        "utf-8"
    }
    ahcResponse.getResponseBody(charset)
  }

  /**
   * The response body as Xml.
   */
  lazy val xml: Elem = XML.loadString(body)

  /**
   * The response body as Json.
   */
  lazy val json: JsValue = Json.parse(ahcResponse.getResponseBodyAsBytes)

}

/**
 * An HTTP response header (the body has not been retrieved yet)
 */
case class ResponseHeaders(status: Int, headers: Map[String, Seq[String]])

/**
 * Sign a WS call.
 */
trait SignatureCalculator {

  /**
   * Sign it.
   */
  def sign(request: WSZ.WSRequest)

}

