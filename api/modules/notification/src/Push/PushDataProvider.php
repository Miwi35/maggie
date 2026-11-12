<?php

declare(strict_types=1);

namespace Maggie\Notification\Push;

use Symfony\Component\DependencyInjection\Attribute\AutoconfigureTag;

/**
 * Extra data a push carries so the phone needs no network call to word its buttons
 * (MAG-772: a make-up's minutes). The notification module knows no other module's
 * entities, so the module that owns the entity answers for its IRI.
 */
#[AutoconfigureTag(PushDataProvider::TAG)]
interface PushDataProvider
{
    public const TAG = 'maggie.push_data';

    /** @return array<string, string> FCM data (strings only); empty when this provider does not know the entity */
    public function dataFor(string $relatedEntityIri): array;
}
