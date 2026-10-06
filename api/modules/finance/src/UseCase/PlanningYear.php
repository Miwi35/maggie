<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

/**
 * The year a planning session is about.
 *
 * One place for the two rules both channels have to share: which year the
 * session opens on when nobody says, and which years it will accept at all.
 * Split across the HTTP controller and the MCP tool, they drifted — the tool
 * had no range check, so a year of 20330 wrote an envelope the admin cannot
 * show and a year of 0 built a date PHP refuses.
 */
final class PlanningYear
{
    /** The range `Envelope::$year` itself declares. */
    public const MIN = 2000;
    public const MAX = 2100;

    /**
     * The year the session is about when nobody says.
     *
     * It is an end-of-year appointment (doc §5.3): in November it is next year
     * that is being planned, not the one closing.
     */
    public static function default(\DateTimeImmutable $now): int
    {
        $year = (int) $now->format('Y');

        return (int) $now->format('n') >= 11 ? $year + 1 : $year;
    }

    /** @throws \InvalidArgumentException when no session could be about that year */
    public static function assert(int $year): void
    {
        if ($year < self::MIN || $year > self::MAX) {
            throw new \InvalidArgumentException(sprintf('year must be between %d and %d.', self::MIN, self::MAX));
        }
    }
}
