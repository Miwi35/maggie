<?php

declare(strict_types=1);

namespace Maggie\Core\Enum;

/**
 * An activity the user can leave and resume on any device (MAG-613): its
 * progress is an {@see \Maggie\Core\Entity\ActivityStatus}.
 */
enum Activity: string
{
    case Cuisine = 'cuisine';
    case Comptes = 'comptes';

    /**
     * What may launch it: the `origin.kind` of its status.
     *
     * @return list<string>
     */
    public function originKinds(): array
    {
        return match ($this) {
            self::Cuisine => ['meal', 'recipe'],
            self::Comptes => ['review'],
        };
    }

    /** @return list<string> */
    public static function values(): array
    {
        return array_map(static fn (self $activity): string => $activity->value, self::cases());
    }
}
