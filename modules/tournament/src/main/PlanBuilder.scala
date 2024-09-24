package lila.tournament

import scala.collection.mutable
import scala.collection.SortedSet
// For comparing Instants
import scala.math.Ordering.Implicits.infixOrderingOps

import Schedule.Plan

object PlanBuilder:
  /** Max window for daily or better schedules to be considered overlapping (i.e. if one starts within X hrs
    * of the other ending). Using 11.5 hrs here ensures that at least one daily is always cancelled for the
    * more important event. But, if a higher importance tourney is scheduled nearly opposite of the daily
    * (i.e. 11:00 for a monthly and 00:00 for its daily), two dailies will be cancelled... so don't do this!
    */
  private[tournament] val SCHEDULE_DAILY_OVERLAP_MINS = 690 // 11.5 hours

  // 2/3 of a min ensures good spacing from tourneys starting the next minute, which aren't explicitly
  // considered when calculating ideal stagger. It also keeps at least or better spacing than the prior
  // random [0, 60) sec stagger.
  private[tournament] val MAX_STAGGER_MS = 40_000

  private[tournament] abstract class ScheduleWithInterval:
    def schedule: Schedule
    def duration: java.time.Duration
    def startsAt: Instant

    val endsAt = startsAt.plus(duration)

    def interval = TimeInterval(startsAt, endsAt)

    def overlaps(other: ScheduleWithInterval) = interval.overlaps(other.interval)

    // Note: must be kept in sync with [[SchedulerTestHelpers.ExperimentalPruner.pruneConflictsFailOnUsurp]]
    // In particular, pruneConflictsFailOnUsurp filters tourneys that couldn't possibly conflict based
    // on their hours -- so the same logic (overlaps and daily windows) must be used here.
    def conflictsWith(si2: ScheduleWithInterval) =
      val s1 = schedule
      val s2 = si2.schedule
      s1.variant == s2.variant && (
        // prevent daily && weekly within X hours of each other
        if s1.freq.isDailyOrBetter && s2.freq.isDailyOrBetter && s1.sameSpeed(s2) then
          si2.startsAt.minusMinutes(SCHEDULE_DAILY_OVERLAP_MINS).isBefore(endsAt) &&
          startsAt.minusMinutes(SCHEDULE_DAILY_OVERLAP_MINS).isBefore(si2.endsAt)
        else
          (
            s1.variant.exotic ||  // overlapping exotic variant
              s1.hasMaxRating ||  // overlapping same rating limit
              s1.similarSpeed(s2) // overlapping similar
          ) && s1.similarConditions(s2) && overlaps(si2)
      )

    /** Kept in sync with [[conflictsWithFailOnUsurp]].
      */
    def conflictsWith(scheds: Iterable[ScheduleWithInterval]): Boolean =
      scheds.exists(conflictsWith)

    /** Kept in sync with [[conflictsWith]].
      *
      * Raises an exception if a tourney is incorrectly usurped.
      */
    @throws[IllegalStateException]("if a tourney is incorrectly usurped")
    def conflictsWithFailOnUsurp(scheds: Iterable[ScheduleWithInterval]) =
      val conflicts   = scheds.filter(conflictsWith).toSeq
      val okConflicts = conflicts.filter(_.schedule.freq >= schedule.freq)
      if conflicts.nonEmpty && okConflicts.isEmpty then
        throw new IllegalStateException(s"Schedule [$schedule] usurped by ${conflicts}")
      conflicts.nonEmpty

  private final case class ConcreteSchedule(
      val schedule: Schedule,
      val startsAt: Instant,
      val duration: java.time.Duration
  ) extends ScheduleWithInterval

  private[tournament] def filterAndStaggerPlans(
      existingTourneys: Iterable[Tournament],
      plans: Iterable[Plan]
  ): List[Plan] =
    // Prune plans using the unstaggered scheduled start time.
    val existingWithScheduledStart = existingTourneys.flatMap { t =>
      // Ignore tournaments with schedule=None - they never conflict.
      t.schedule.map { s => ConcreteSchedule(s, s.atInstant, t.duration) }
    }

    val prunedPlans = pruneConflicts(existingWithScheduledStart, plans)

    // Unlike pruning, stagger plans even against Tourneys with schedule=None.
    plansWithStagger(existingTourneys, prunedPlans)

  /** Given a list of existing schedules and a list of possible new plans, returns a subset of the possible
    * plans that do not conflict with either the existing schedules or with themselves. Intended to produce
    * identical output to [[SchedulerTestHelpers.ExperimentalPruner.pruneConflictsFailOnUsurp]], but this
    * variant is more readable and has lower potential for bugs.
    */
  private[tournament] def pruneConflicts[A <: ScheduleWithInterval](
      existingSchedules: Iterable[ScheduleWithInterval],
      possibleNewPlans: Iterable[A]
  ): List[A] =
    var allPlannedSchedules = existingSchedules.toList
    possibleNewPlans
      .foldLeft(Nil): (newPlans, p) =>
        if p.conflictsWith(allPlannedSchedules) then newPlans
        else
          allPlannedSchedules ::= p
          p :: newPlans
      .reverse

  /** Given a plan, return an adjusted start time that minimizes conflicts with existing events.
    *
    * This method assumes that no event starts immediately before plan or after the max stagger, in terms of
    * impacting server performance by concurrent events.
    */
  private[tournament] def getAdjustedStart(
      plan: Plan,
      existingEvents: SortedSet[Instant],
      maxStaggerMs: Long
  ): Instant =

    val originalStart   = plan.startsAt
    val originalStartMs = originalStart.toEpochMilli
    val maxConflictAt   = originalStart.plusMillis(maxStaggerMs)

    // Find all events that start at a similar time to the plan.
    val offsetsWithSimilarStart = existingEvents
      .iteratorFrom(originalStart)
      .takeWhile(_ <= maxConflictAt)
      // Shift events times to be relative to original plan, to avoid loss of precision
      .map(_.toEpochMilli - originalStartMs)
      .toSeq

    val staggerMs = findMinimalGoodSlot(0L, maxStaggerMs, offsetsWithSimilarStart)
    originalStart.plusMillis(staggerMs)

  /** Given existing tourneys and possible new plans, returns new Plan objects that are staggered to avoid
    * starting at the exact same time as other plans or tourneys. Does NOT filter for conflicts.
    */
  private[tournament] def plansWithStagger(
      existingTourneys: Iterable[Tournament],
      plans: Iterable[Plan]
  ): List[Plan] =
    // Determine stagger using the actual (staggered) start time of existing and new tourneys.
    val allTourneyStarts = mutable.TreeSet.from(existingTourneys.map(_.startsAt))

    plans
      .foldLeft(Nil): (allAdjustedPlans, plan) =>
        val adjustedStart = getAdjustedStart(plan, allTourneyStarts, MAX_STAGGER_MS)
        allTourneyStarts += adjustedStart
        plan.copy(startsAt = adjustedStart) :: allAdjustedPlans
      .reverse

  /** This method is used find a good stagger value for a new tournament. We want stagger as low as possible,
    * because that means tourneys start sooner, but we also want tournaments to be spaced out to avoid server
    * DDOS.
    *
    * The method uses Longs for convenience based on usage, but it could easily be specialized to use floating
    * points or other representations.
    *
    * Overflows are *not* checked, because although this method uses Longs, its arguments are expected to be
    * small (e.g. smaller than [[Short.MaxValue]]), and so internal math is not expected to come anywhere near
    * a Long overflow.
    *
    * @param hi
    *   must be >= low
    * @param sortedExisting
    *   must be sorted and contain only values in [low, hi]
    *
    * @return
    *   the lowest value in [low, hi] with maximal distance to elts of sortedExisting
    */
  private[tournament] def findMinimalGoodSlot(low: Long, hi: Long, sortedExisting: Iterable[Long]): Long =
    if sortedExisting.isEmpty then low
    else
      val iter    = sortedExisting.iterator
      var prevElt = iter.next
      // nothing is at low element so gap is equiv to 2x size, centered at low
      var maxGapLow = low - (prevElt - low)
      var maxGapLen = (prevElt - low) * 2L
      while iter.hasNext do
        val elt = iter.next
        if elt - prevElt > maxGapLen then
          maxGapLow = prevElt
          maxGapLen = elt - prevElt
        prevElt = elt
      // Use hi only if it's strictly better than all other gaps.
      if (hi - prevElt) * 2L > maxGapLen then hi
      else maxGapLow + maxGapLen / 2L
