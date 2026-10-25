<?php

declare(strict_types=1);

namespace Maggie\Core\Home;

use Maggie\Core\Entity\User;
use Symfony\Component\DependencyInjection\Attribute\AutoconfigureTag;

/**
 * One card of the home page. A module implements this and is picked up by
 * HomeCardRegistry; GET /api/home/{module} serves its state.
 */
#[AutoconfigureTag(HomeCardRegistry::TAG)]
interface HomeCardProviderInterface
{
    /** The slug in the URL. */
    public function module(): string;

    /**
     * The card's JSON body for that user and day; the controller adds `module` and `day`.
     * Read only this user's data.
     *
     * @return array<string, mixed>
     */
    public function state(User $user, HomeDay $day): array;
}
