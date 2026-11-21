<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp;

/** The filters of `get_tasks`, combined with AND. */
final readonly class TaskCriteria
{
    public const STATUSES = ['pending', 'done', 'all'];
    public const DEFAULT_LIMIT = 50;
    public const MAX_LIMIT = 200;

    public function __construct(
        public string $status = 'pending',
        public ?\DateTimeImmutable $day = null,
        public ?\DateTimeImmutable $dueFrom = null,
        public ?\DateTimeImmutable $dueTo = null,
        public ?\DateTimeImmutable $scheduledFrom = null,
        public ?\DateTimeImmutable $scheduledTo = null,
        public ?bool $dated = null,
        public ?string $parentId = null,
        public int $limit = self::DEFAULT_LIMIT,
    ) {
    }

    /** @throws \DomainException with the name of the faulty field */
    public static function fromArguments(
        string $status = 'pending',
        ?string $day = null,
        ?string $dueFrom = null,
        ?string $dueTo = null,
        ?string $scheduledFrom = null,
        ?string $scheduledTo = null,
        ?bool $dated = null,
        ?string $parentId = null,
        ?int $limit = null,
    ): self {
        if (!in_array($status, self::STATUSES, true)) {
            throw new \DomainException(sprintf('status must be one of %s, got "%s".', implode(', ', self::STATUSES), $status));
        }

        $dueFromDay = self::date('dueFrom', $dueFrom);
        $dueToDay = self::date('dueTo', $dueTo);
        $scheduledFromDay = self::date('scheduledFrom', $scheduledFrom);
        $scheduledToDay = self::date('scheduledTo', $scheduledTo);
        if (null !== $dueFromDay && null !== $dueToDay && $dueToDay < $dueFromDay) {
            throw new \DomainException("dueTo {$dueTo} is before dueFrom {$dueFrom}.");
        }
        if (null !== $scheduledFromDay && null !== $scheduledToDay && $scheduledToDay < $scheduledFromDay) {
            throw new \DomainException("scheduledTo {$scheduledTo} is before scheduledFrom {$scheduledFrom}.");
        }

        return new self(
            $status,
            self::date('day', $day),
            $dueFromDay,
            $dueToDay,
            $scheduledFromDay,
            $scheduledToDay,
            $dated,
            '' === $parentId ? null : $parentId,
            max(1, min($limit ?? self::DEFAULT_LIMIT, self::MAX_LIMIT)),
        );
    }

    private static function date(string $field, ?string $value): ?\DateTimeImmutable
    {
        if (null === $value) {
            return null;
        }

        $date = \DateTimeImmutable::createFromFormat('!Y-m-d', $value);
        if (false === $date || $date->format('Y-m-d') !== $value) {
            throw new \DomainException("{$field} must be a date as YYYY-MM-DD, got \"{$value}\".");
        }

        return $date;
    }
}
