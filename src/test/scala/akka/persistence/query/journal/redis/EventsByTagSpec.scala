/** Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package akka.persistence.query.journal.redis

import scala.concurrent.duration._
import akka.persistence.journal.Tagged
import akka.persistence.journal.WriteEventAdapter
import akka.persistence.query.{ EventEnvelope, PersistenceQuery, Sequence }
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.stream.ActorMaterializer
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.persistence.query.NoOffset

object EventsByTagSpec {
  val config = """
    akka.loglevel = INFO
    akka.persistence.journal.plugin = "akka-persistence-redis.journal"
    akka-persistence-redis.journal {
      event-adapters {
        color-tagger  = akka.persistence.query.journal.redis.ColorTagger
      }
      event-adapter-bindings = {
        "java.lang.String" = color-tagger
      }
    }
    akka.test.single-expect-default = 10s
    """

}

class ColorTagger extends WriteEventAdapter {
  val colors = Set("green", "black", "blue")
  override def toJournal(event: Any): Any = event match {
    case s: String =>
      var tags = colors.foldLeft(Set.empty[String])((acc, c) => if (s.contains(c)) acc + c else acc)
      if (tags.isEmpty) event
      else Tagged(event, tags)
    case _ => event
  }

  override def manifest(event: Any): String = ""
}

class EventsByTagSpec extends AkkaSpec(EventsByTagSpec.config)
    with Cleanup with ImplicitSender {

  implicit val mat = ActorMaterializer()(system)

  val queries = PersistenceQuery(system).readJournalFor[ScalaReadJournal](RedisReadJournal.Identifier)

  "Redis query EventsByTag" must {
    "implement standard EventsByTagQuery" in {
      queries.isInstanceOf[EventsByTagQuery] should ===(true)
    }

    "find existing events" in {
      val a = system.actorOf(TestActor.props("a"))
      val b = system.actorOf(TestActor.props("b"))
      a ! "hello"
      expectMsg(s"hello-done")
      a ! "a green apple"
      expectMsg(s"a green apple-done")
      b ! "a black car"
      expectMsg(s"a black car-done")
      a ! "a green banana"
      expectMsg(s"a green banana-done")
      b ! "a green leaf"
      expectMsg(s"a green leaf-done")

      val greenSrc = queries.currentEventsByTag(tag = "green", offset = NoOffset)
      greenSrc.runWith(TestSink.probe[Any])
        .request(2)
        .expectNext(EventEnvelope(Sequence(0L), "a", 2L, "a green apple"))
        .expectNext(EventEnvelope(Sequence(1L), "a", 3L, "a green banana"))
        .expectNoMsg(500.millis)
        .request(2)
        .expectNext(EventEnvelope(Sequence(2L), "b", 2L, "a green leaf"))
        .expectComplete()

      val blackSrc = queries.currentEventsByTag(tag = "black", offset = Sequence(0L))
      blackSrc.runWith(TestSink.probe[Any])
        .request(5)
        .expectNext(EventEnvelope(Sequence(0L), "b", 1L, "a black car"))
        .expectComplete()
    }

    "not see new events after complete" in {
      val c = system.actorOf(TestActor.props("c"))

      val greenSrc = queries.currentEventsByTag(tag = "green", offset = Sequence(0L))
      val probe = greenSrc.runWith(TestSink.probe[Any])
        .request(2)
        .expectNext(EventEnvelope(Sequence(0L), "a", 2L, "a green apple"))
        .expectNext(EventEnvelope(Sequence(1L), "a", 3L, "a green banana"))
        .expectNoMsg(100.millis)

      probe
        .expectNoMsg(100.millis)
        .request(5)
        .expectNext(EventEnvelope(Sequence(2L), "b", 2L, "a green leaf"))
        .expectComplete() // green cucumber not seen

      c ! "a green cucumber"
      expectMsg(s"a green cucumber-done")
    }

    "find events from offset (inclusive)" in {
      val greenSrc = queries.currentEventsByTag(tag = "green", offset = Sequence(2L))
      val probe = greenSrc.runWith(TestSink.probe[Any])
        .request(10)
        // note that banana is not included, since exclusive offset
        .expectNext(EventEnvelope(Sequence(2L), "b", 2L, "a green leaf"))
        .expectNext(EventEnvelope(Sequence(3L), "c", 1L, "a green cucumber"))
        .expectComplete()
    }
  }

  "Redis live query EventsByTag" must {
    "find new events" in {
      val d = system.actorOf(TestActor.props("d"))

      val blackSrc = queries.eventsByTag(tag = "black", offset = NoOffset)
      val probe = blackSrc.runWith(TestSink.probe[Any])
        .request(2)
        .expectNext(EventEnvelope(Sequence(0L), "b", 1L, "a black car"))
        .expectNoMsg(100.millis)

      d ! "a black dog"
      expectMsg(s"a black dog-done")
      d ! "a black night"
      expectMsg(s"a black night-done")

      probe
        .expectNext(EventEnvelope(Sequence(1L), "d", 1L, "a black dog"))
        .expectNoMsg(100.millis)
        .request(10)
        .expectNext(EventEnvelope(Sequence(2L), "d", 2L, "a black night"))
    }

    "find events from offset (inclusive)" in {
      val greenSrc = queries.eventsByTag(tag = "green", offset = Sequence(2L))
      val probe = greenSrc.runWith(TestSink.probe[Any])
        .request(10)
        // note that banana is not included, since exclusive offset
        .expectNext(EventEnvelope(Sequence(2L), "b", 2L, "a green leaf"))
        .expectNext(EventEnvelope(Sequence(3L), "c", 1L, "a green cucumber"))
        .expectNoMsg(100.millis)
    }

  }

}
