
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

package play.modules.scalaz

import scala.concurrent._
import scala.util.{Try, Success, Failure}

import play.api.libs.iteratee._
import scalaz.stream._
import scalaz.{\/,-\/,\/-, NaturalTransformation}
import scalaz.\/._
import scalaz.concurrent.Task

package object `stream` {

  /** Creates an enumerator from a Process[Task, O] */
	def enumerator[O](p: Process[Task, O])(implicit ctx: ExecutionContext) = new Enumerator[O] {

    def apply[A](it: Iteratee[O, A]): Future[Iteratee[O, A]] = {

      def step[A](curP: Process[Task, O], curIt: Iteratee[O, A]): Future[Iteratee[O, A]] = {
        curIt.fold {
          case Step.Done(a, e) => 
            Future.successful(Done(a, e))

          case Step.Cont(k) => 
            curP match {

              case Process.Await(req, recv, fb, err) =>
                scalazTask2scalaFuture(req).flatMap{ a =>
                  step(recv(a), curIt)
                }.recoverWith{
                  case Process.End => step(fb, curIt) // Normal termination
                  case e: Exception => err match {
                    case Process.Halt => throw e // ensure exception is eventually thrown;
                                         // without this we'd infinite loop
                    case _ => step(err ++ Process.wrap(Task.delay(throw e)), curIt)
                  }
                }

              case Process.Emit(h, t) =>
                enumerateSeq(h, Cont(k)).flatMap{ i => step(t, i) }

              case Process.Halt =>
                Future.successful(k(Input.EOF))
            }

          case Step.Error(msg, e) => 
            Future.successful(Error(msg, e))
        }
      }

      step(p, it)
    }
  }

  /** Folds an Iteratee from a Process1[I, O] till first emit of Process1 */
  def iterateeFirstEmit[I, O](p: Process.Process1[I, O])(implicit ctx: ExecutionContext): Iteratee[I, O] = {
    def step[I](curP: Process.Process1[I, O])(i: Input[I]): Iteratee[I, O] = {
      curP match {
        case Process.Await(_, recv, fb, c) =>
          i match {
            case Input.EOF => step(fb)(i)
            case Input.Empty => step(fb)(i)
            case Input.El(e) =>
              Cont(step(recv(e)))
          }

        case Process.Emit(h, t) => Done(h.head, i)

        case Process.Halt => Error("Halt before emit", i)
      }
    }
    Cont(step[I](p))
  }

  /* Task <- Future conversion */
  def scalazTask2scalaFuture[T](task: => Task[T]): Future[T] = {
    val p: Promise[T] = Promise()

    task.runAsync {
      case -\/(ex) => p.failure(ex)
      case \/-(r) => p.success(r)
    }

    p.future
  }

  /* Future -> Task conversion */
  def scalaFuture2scalazTask[T](fut: => Future[T])(implicit ctx: ExecutionContext): Task[T] = {
    Task.async {
      register =>
        fut.onComplete {
          case Success(v) => register(\/-(v))
          case Failure(ex) => register(-\/(ex))
        }
    }
  }

  /* Future -> Task NaturalTransformation */
  def Task2FutureNT(implicit ctx: ExecutionContext) = new NaturalTransformation[Future, Task]{
    def apply[A](fa: Future[A]): Task[A] = scalaFuture2scalazTask(fa)
  }

  def enumerateSeq[E, A](l: Seq[E], i: Iteratee[E, A])(implicit ctx: ExecutionContext): Future[Iteratee[E, A]] = {
    l.foldLeft(Future.successful(i))((i, e) =>
      i.flatMap(it => it.pureFold {
        case Step.Cont(k) => k(Input.El(e))
        case _ => it
      })
    )
  }

}