<?php

declare(strict_types=1);

namespace Maggie\Calendar\Enum;

/**
 * What a proaction is for (MAG-886): a reminder the owner asked for, which always
 * interrupts, or a check that verifies or watches, which speaks only when useful.
 */
enum ProactionKind: string
{
    case Reminder = 'reminder';
    case Check = 'check';

    /**
     * The kind a caller asks for, the reminder when none is named.
     *
     * @throws \DomainException on anything else
     */
    public static function fromInput(?string $value): self
    {
        if (null === $value || '' === trim($value)) {
            return self::Reminder;
        }

        return self::tryFrom(strtolower(trim($value)))
            ?? throw new \DomainException(sprintf('Invalid kind "%s": use reminder or check.', $value));
    }
}
