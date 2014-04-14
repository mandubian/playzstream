
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

package controllers

import scala.concurrent._
import scala.concurrent.duration._

import scalaz.concurrent.Task
import scalaz.stream._

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.iteratee._
import play.api.libs.ws._
import play.api.libs.concurrent._

import play.modules.scalaz.stream._

import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Play.current

object Application extends Controller {
  /***********************************************************************
    * Sample1
    * Generates a stream from a Simple Emitter Process
    */
  def sample1 = Action {
    val process = Process.emitAll(Seq(1, 2, 3, 4)).map(_.toString)

    Ok.feed(enumerator(process))
  }

  /***********************************************************************
    * Sample2
    * Generates a stream from a continuous emitter (limited in nb)
    */

  /** A process generating an infinite stream of natural numbers */
  val numerals = Process.unfold(0){ s => val x = s+1; Some(x, x) }.repeat

  def sample2 = Action {
    Ok.feed(enumerator(numerals.map(_.toString).intersperse(",").take(40)))
  }

  /***********************************************************************
    * Sample 3
    * Generates a stream whose output frequency is controlled by a tee with
    * a ticker on the right
    */

  /** ticks constant every delay milliseconds */
  def ticker(constant: Int, delay: Long): Process[Task, Int] = Process.await(
    scalaFuture2scalazTask(delayedNumber(constant, delay))
  )(Process.emit).repeat

  def sample3 = Action {
    Ok.feed(enumerator(
      // creates a Tee outputting only numerals but consuming ticker to have 
      // the delayed effect
      (numerals tee ticker(0, 100))(processes.zipWith((a,b) => a))
        .take(100)
        .map(_.toString)
        .intersperse(",")
    ))
  }

  /***********************************************************************
    * Sample 4
    * Generates a stream using side-effect to control output frequency
    */

  /** Async generates this Int after delay*/
  def delayedNumber(i: Int, delay: Long): Future[Int] =
    play.api.libs.concurrent.Promise.timeout(i, delay)

  /** Creates a process generating an infinite stream natural numbers
    * every delay milliseconds
    */
  def delayedNumerals(delay: Long) = {
    def step(i: Int): Process[Task, Int] = {
      Process.emit(i) append (
        Process.await(scalaFuture2scalazTask(delayedNumber(i+1, delay)))(step)
      )
    }
    Process.await(scalaFuture2scalazTask(delayedNumber(0, delay)))(step)
  }

  def sample4 = Action {
    Ok.feed(enumerator(delayedNumerals(100).take(20).map(_.toString).intersperse(",")))
  }

  /***********************************************************************
    * Sample 5
    * Generates a stream by consuming completely another stream
    */
  val reader: Process.Process1[Array[Byte], String] = processes.fold1[Array[Byte]]((a, b) => a ++ b )
    .map{ arr => new String(arr) } |> processes.last

  def sample5 = Action { implicit request =>
    // the WS call with response consumer by previous Process1[Array[Byte], String] driving the Iteratee[Array[Byte], String]
    val maybeValues: Future[String] =
      WS.url(routes.Application.sample2().absoluteURL())
        .get(rh => iterateeFirstEmit(reader))
        .flatMap(_.run)

    Ok.feed(enumerator(
      // wraps the received String in a Process
      // re-splits it to remove ","
      // emits all chunks
     
     Process.eval(scalaFuture2scalazTask(maybeValues))
        .flatMap{ values => Process.emitAll(values.split(",")) }
    ))
  }



  /***********************************************************************
    * Sample 6
    * Generates a stream by forwarding/refolding another stream in realtime
    */
  /** A Process1 splitting input strings using splitter and re-grouping chunks */
  def splitFold(splitter: String): Process.Process1[String, String] = {
    // the recursive splitter / refolder
    def go(rest: String)(str: String): Process.Process1[String, String] = {
      val splitted = str.split(splitter)
      (splitted.length match {
        case 0 => 
          // string == splitter
          // emit rest
          // loop
          Process.emit(rest) append ( Process.await1[String].flatMap(go("")) )
        case 1 => 
          // splitter not found in string 
          // so waiting for next string
          // loop by adding current str to rest
          // but if we reach end of input, then we emit (rest+str) for last element
          Process.await1[String].flatMap(go(rest + str)).orElse(Process.emit(rest+str))
        case _ =>
          // splitter found
          // emit rest + splitted.head
          // emit all splitted elements but last
          // loops with rest = splitted last element
          Process.emit(rest + splitted.head)
                 .append( Process.emitAll(splitted.tail.init) )
                 .append( Process.await1[String].flatMap(go(splitted.last)) )
      })
    }
    // await1 simply means "await an input string and emits it"
    Process.await1[String].flatMap(go(""))
  }

  def sample6 = Action { implicit request =>
    val p = WSZ.url(routes.Application.sample4().absoluteURL()).getRealTime.translate(Task2FutureNT)

    Ok.feed(enumerator(p.map(new String(_)) |> splitFold(",")))
  }

  /***********************************************************************
    * Sample 7
    * Generates fibonacci series by self-injecting realtime stream
    */

  /** @param curDepth the current recursion depth
    * @param maxDepth the max recursion depth
    */
  def sample7(curDepth: Int, maxDepth: Int) = Action { implicit request =>

    // initializes serie with 2 first numerals output with a delay of 100ms
    val init: Process[Task, String] = delayedNumerals(100).take(2).map(_.toString)

    // Creates output Process
    // If didn't reach maxDepth, creates a process consuming my own action
    // If reach maxDepth, just emit 0
    val outputProcess = 
      if(curDepth < maxDepth) {
        // calling my own action and streaming chunks using getRealTime
        // splitFold isn't useful, just for demo
        val myself = WSZ.url(
          routes.Application.sample7(curDepth+1, maxDepth).absoluteURL()
        ).getRealTime.translate(Task2FutureNT).map(new String(_)) |> splitFold(",")

        // appends `init` output with `myself` output
        // pipe it through a helper provided scalaz-stream `processes.sum[Long]`
        // which sums elements and emits partial sums
        ((init append myself).map(_.toLong) |> processes.sum[Long])
        // just for output format
        .map(_.toString).intersperse(",")
      }
      else Process.emit(0).map(_.toString)

    Ok.feed(enumerator(outputProcess))
  }
}