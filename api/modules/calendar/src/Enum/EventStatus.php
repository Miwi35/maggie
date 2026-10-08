<?php

namespace Maggie\Calendar\Enum;

enum EventStatus: string
{
    case Confirmed = 'confirmed';
    case Tentative = 'tentative';
    case Cancelled = 'cancelled';

    /**
     * The status a caller may set by hand: cancelling goes through deletion.
     *
     * @throws \DomainException on anything but `confirmed` or `tentative`
     */
    public static function settable(?string $value): ?self
    {
        if (null === $value || '' === trim($value)) {
            return null;
        }

        $status = self::tryFrom(strtolower(trim($value)));
        if (null === $status || self::Cancelled === $status) {
            throw new \DomainException(sprintf('Invalid status "%s": use "confirmed" or "tentative" (to cancel an event, delete it).', $value));
        }

        return $status;
    }
}
