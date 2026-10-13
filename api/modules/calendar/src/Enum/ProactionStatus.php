<?php

declare(strict_types=1);

namespace Maggie\Calendar\Enum;

/** Where a proaction stands: waiting for its start, being run by the agent, then done or failed (ADR-012). */
enum ProactionStatus: string
{
    case Pending = 'pending';
    case Running = 'running';
    case Completed = 'completed';
    case Failed = 'failed';

    /**
     * A status a caller filters on, or null when none was asked for.
     *
     * @throws \DomainException on anything else
     */
    public static function fromFilter(?string $value): ?self
    {
        if (null === $value || '' === trim($value)) {
            return null;
        }

        return self::tryFrom(strtolower(trim($value)))
            ?? throw new \DomainException(sprintf('Invalid status "%s": use pending, running, completed or failed.', $value));
    }
}
