<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

/**
 * A stretch of calendar months a planning session is about: a quarter, a
 * semester or a year (MAG-462). Keyed `2027-Q1`, `2027-S2` or `2027`.
 *
 * It is also what the augmented reviews close: the review of the last month of
 * a quarter, a semester or a year looks back on that period and plans the
 * next one ({@see self::closingAt()}).
 */
final readonly class PlanningPeriod
{
    public const QUARTER = 'quarter';
    public const SEMESTER = 'semester';
    public const YEAR = 'year';

    private const MONTHS = [self::QUARTER => 3, self::SEMESTER => 6, self::YEAR => 12];

    private function __construct(
        public string $level,
        public int $year,
        /** Position in the year: 1-4 for a quarter, 1-2 for a semester, 1 for a year. */
        public int $index,
    ) {
    }

    /** @throws \InvalidArgumentException on a key no session could be about */
    public static function parse(string $key): self
    {
        if (1 !== preg_match('/^(\d{4})(?:-(Q[1-4]|S[12]))?$/', $key, $matches)) {
            throw new \InvalidArgumentException('period must be a year (2027), a quarter (2027-Q1) or a semester (2027-S2).');
        }

        $year = (int) $matches[1];
        PlanningYear::assert($year);

        $part = $matches[2] ?? null;

        return match (true) {
            null === $part => new self(self::YEAR, $year, 1),
            'Q' === $part[0] => new self(self::QUARTER, $year, (int) $part[1]),
            default => new self(self::SEMESTER, $year, (int) $part[1]),
        };
    }

    public static function ofYear(int $year): self
    {
        PlanningYear::assert($year);

        return new self(self::YEAR, $year, 1);
    }

    /**
     * The period the review of this month closes, null when the month ends
     * none: March and September close a quarter, June a semester (its
     * quarter too), December a year — the longest wins.
     *
     * @throws \InvalidArgumentException on a year no session could be about
     */
    public static function closingAt(\DateTimeImmutable $month): ?self
    {
        $year = (int) $month->format('Y');
        PlanningYear::assert($year);

        return match ((int) $month->format('n')) {
            12 => new self(self::YEAR, $year, 1),
            6 => new self(self::SEMESTER, $year, 1),
            3, 9 => new self(self::QUARTER, $year, 3 === (int) $month->format('n') ? 1 : 3),
            default => null,
        };
    }

    public function key(): string
    {
        return match ($this->level) {
            self::QUARTER => \sprintf('%04d-Q%d', $this->year, $this->index),
            self::SEMESTER => \sprintf('%04d-S%d', $this->year, $this->index),
            default => \sprintf('%04d', $this->year),
        };
    }

    public function months(): int
    {
        return self::MONTHS[$this->level];
    }

    /** The first day of the first month. */
    public function start(): \DateTimeImmutable
    {
        $month = ($this->index - 1) * $this->months() + 1;

        return new \DateTimeImmutable(\sprintf('%04d-%02d-01', $this->year, $month));
    }

    /** The first day after the period (half-open upper bound). */
    public function end(): \DateTimeImmutable
    {
        return $this->start()->modify(\sprintf('+%d months', $this->months()));
    }

    /** @return list<\DateTimeImmutable> the first day of each month */
    public function monthStarts(): array
    {
        $start = $this->start();

        return array_map(
            static fn (int $offset): \DateTimeImmutable => $start->modify(\sprintf('+%d months', $offset)),
            range(0, $this->months() - 1),
        );
    }

    public function contains(\DateTimeImmutable $day): bool
    {
        return $day >= $this->start() && $day < $this->end();
    }

    /** The period right before, of the same length. */
    public function previous(): self
    {
        return $this->shifted(-1);
    }

    /** The period right after, of the same length. */
    public function next(): self
    {
        return $this->shifted(1);
    }

    /** @return array{key: string, level: string, from: string, to: string, months: int} */
    public function describe(): array
    {
        return [
            'key' => $this->key(),
            'level' => $this->level,
            'from' => $this->start()->format('Y-m'),
            'to' => $this->end()->modify('-1 month')->format('Y-m'),
            'months' => $this->months(),
        ];
    }

    private function shifted(int $step): self
    {
        $perYear = intdiv(12, $this->months());
        $position = $this->year * $perYear + ($this->index - 1) + $step;

        $period = new self($this->level, intdiv($position, $perYear), $position % $perYear + 1);
        PlanningYear::assert($period->year);

        return $period;
    }
}
