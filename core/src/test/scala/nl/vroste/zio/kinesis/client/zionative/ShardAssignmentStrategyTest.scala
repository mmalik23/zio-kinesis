package nl.vroste.zio.kinesis.client.zionative

import nl.vroste.zio.kinesis.client.zionative.LeaseRepository.Lease
import nl.vroste.zio.kinesis.client.zionative.ShardAssignmentStrategy.leasesToTake
import zio.test.Assertion._
import zio.test.{ Gen, ZIOSpecDefault, _ }

object ShardAssignmentStrategyTest extends ZIOSpecDefault {
  val leaseDistributionGen = leases(Gen.int(2, 100), Gen.int(2, 10))

  def workerId(w: Int): String = s"worker-${w}"

  def leases(nrShards: Gen[Any, Int], nrWorkers: Gen[Any, Int], allOwned: Boolean = true) =
    for {
      nrShards    <- nrShards
      nrWorkers   <- nrWorkers
      randomWorker = Gen.int(1, nrWorkers)
      leases      <- genTraverse(0 until nrShards) { shard =>
                       for {
                         worker     <-
                           (if (allOwned) randomWorker.map(Some(_)) else Gen.option(randomWorker)).map(_.map(workerId))
                         counter    <- Gen.long(1, 1000)
                         sequenceNr <-
                           Gen.option(Gen.int(0, Int.MaxValue / 2).map(_.toString).map(ExtendedSequenceNumber(_, 0L)))
                       } yield Lease(s"shard-${shard}", worker, counter, sequenceNr.map(Right(_)), Seq.empty)
                     }
    } yield leases

  override def spec =
    suite("Lease coordinator")(
      test("does not want to steal leases if its the only worker") {
        check(leases(nrShards = Gen.int(2, 100), nrWorkers = Gen.const(1))) { leases =>
          assertZIO(leasesToTake(leases, workerId(1)))(isEmpty)
        }
      },
      test("steals some leases when its not the only worker") {
        check(leases(nrShards = Gen.int(2, 100), nrWorkers = Gen.const(1))) { leases =>
          assertZIO(leasesToTake(leases, workerId(2)))(isNonEmpty)
        }
      },
      test("takes leases if it has less than its equal share") {
        check(leases(nrShards = Gen.int(2, 100), nrWorkers = Gen.int(1, 10), allOwned = false)) { leases =>
          val workers          = leases.map(_.owner).collect { case Some(owner) => owner }.toSet
          val nrWorkers        = (workers + workerId(1)).size
          val nrOwnedLeases    = leases.map(_.owner).collect { case Some(owner) if owner == workerId(1) => 1 }.size
          val minExpectedShare = Math.floor(leases.size * 1.0 / nrWorkers).toInt
          val maxExpectedShare = minExpectedShare + (leases.size % nrWorkers)
          println(
            s"For ${leases.size} leases, ${nrWorkers} workers, nr owned leases ${nrOwnedLeases}: expecting share between ${minExpectedShare} and ${maxExpectedShare}"
          )

          val minExpectedToSteal =
            Math.max(0, minExpectedShare - nrOwnedLeases) // We could own more than our fair share
          val maxExpectedToSteal =
            Math.max(0, maxExpectedShare - nrOwnedLeases) // We could own more than our fair share

          for {
            toSteal <- leasesToTake(leases, workerId(1))
          } yield assert(toSteal.size)(isWithin(minExpectedToSteal, maxExpectedToSteal))
        }
      },
      test("takes unclaimed leases first") {
        check(leases(nrShards = Gen.int(2, 100), nrWorkers = Gen.int(1, 10), allOwned = false)) { leases =>
          for {
            toTake          <- leasesToTake(leases, workerId(1))
            fromOtherWorkers = toTake.map(shard => leases.find(_.key == shard).get).dropWhile(_.owner.isEmpty)
          } yield assert(fromOtherWorkers)(forall(hasField("owner", _.owner, isNone)))
        }
      },
      test("steals leases randomly to reduce contention for the same lease") {
        check(leases(nrShards = Gen.int(2, 100), nrWorkers = Gen.int(1, 10), allOwned = true)) { leases =>
          for {
            toSteal1 <- leasesToTake(leases, workerId(1))
            toSteal2 <- leasesToTake(leases, workerId(1))
          } yield assert(toSteal1)(not(equalTo(toSteal2))) || assert(toSteal1)(hasSize(isLessThanEqualTo(1)))
        }
      } @@ TestAspect.flaky(3), // Randomness is randomly not-random
      test("steals from the busiest workers first") {
        check(leases(nrShards = Gen.int(2, 100), nrWorkers = Gen.int(1, 10))) { leases =>
          val leasesByWorker = leases
            .groupBy(_.owner)
            .collect { case (Some(owner), leases) => owner -> leases }
            .toList
            .sortBy { case (worker, leases) => (leases.size * -1, worker) }
          val busiestWorkers = leasesByWorker.map(_._1)

          for {
            toSteal       <- leasesToTake(leases, workerId(1))
            // The order of workers should be equal to the order of busiest workers
            toStealWorkers =
              changedElements(toSteal.map(shard => leases.find(_.key == shard).get).map(_.owner.get).toList)
          } yield assert(toStealWorkers)(equalTo(busiestWorkers.take(toStealWorkers.size)))
        }
      }
    )

  def changedElements[A](as: List[A]): List[A] =
    as.foldLeft(List.empty[A]) { case (acc, a) => if (acc.lastOption.contains(a)) acc else acc :+ a }

  def genTraverse[R, A, B](elems: Iterable[A])(f: A => Gen[R, B]): Gen[R, List[B]] =
    Gen.collectAll(elems.map(f))
}
