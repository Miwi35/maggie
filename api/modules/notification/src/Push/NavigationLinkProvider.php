<?php

declare(strict_types=1);

namespace Maggie\Notification\Push;

use Symfony\Component\DependencyInjection\Attribute\AutoconfigureTag;

/**
 * Where a push opens when the thing it is about holds its own link (MAG-655: a
 * trip's navigation URL). The notification module knows no other module's
 * entities, so the module that owns the entity answers for its IRI.
 */
#[AutoconfigureTag(NavigationLinkProvider::TAG)]
interface NavigationLinkProvider
{
    public const TAG = 'maggie.push_navigation_link';

    /** The URL to open for this related entity, or null when this provider does not know it. */
    public function navigationUrl(string $relatedEntityIri): ?string;
}
