<?php

declare(strict_types=1);

namespace Maggie\Calendar\Specification;

use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\ProactionDelivery;
use Maggie\Calendar\Enum\ProactionStatus;

/**
 * A run just completed with something worth an interruption (MAG-886): the owner
 * is told in one sentence, with a link to the message. A message left in the
 * thread, a run that said nothing, or the same completed run saved again is none.
 */
final readonly class IsProactionInterrupting
{
    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet what Doctrine is about to write: field => [before, after] */
    public function __construct(
        private array $changeSet,
    ) {
    }

    public function isSatisfiedBy(Proaction $proaction): bool
    {
        if (!isset($this->changeSet['executionStatus'])) {
            return false;
        }

        [$before, $after] = $this->changeSet['executionStatus'];
        if (!self::is($before, ProactionStatus::Running) || !self::is($after, ProactionStatus::Completed)) {
            return false;
        }

        return ProactionDelivery::Interrupt === $proaction->getDelivery() && null !== $proaction->getMessageLink();
    }

    /** The changeset holds the enum, or its column value when read from a raw change. */
    private static function is(mixed $value, ProactionStatus $status): bool
    {
        return $value === $status || $value === $status->value;
    }
}
