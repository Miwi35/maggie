<?php

declare(strict_types=1);

namespace Maggie\Core\Time;

/** The day of the week in French, worked out by the code so that the model never has to (MAG-431). */
final class Weekday
{
    private const array NAMES = ['lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi', 'dimanche'];

    /** The day the date or instant falls on, in the zone it carries. */
    public static function fr(\DateTimeInterface $moment): string
    {
        return self::NAMES[(int) $moment->format('N') - 1];
    }
}
