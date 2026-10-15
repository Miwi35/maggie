<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Repository\MealSettingsRepository;
use Maggie\Core\Entity\User;
use Symfony\Contracts\Service\ResetInterface;

/**
 * How many people a meal is cooked for (MAG-482): its own number, or its
 * owner's default from the meal settings.
 *
 * The default is not stored on the meal, so every door that writes a meal out
 * — the API, the MCP tools, Mercure — hands it the default first through
 * {@see resolve()}. Read once per user and per request (the kernel resets the
 * memo), and forgotten when the settings change.
 */
class MealGuests implements ResetInterface
{
    /** @var array<string, int> user id => default */
    private array $defaults = [];

    public function __construct(
        private readonly MealSettingsRepository $settingsRepository,
    ) {
    }

    /** The meal's guests, the default filled in when it names none. */
    public function of(Meal $meal, ?User $owner = null): int
    {
        return $this->resolve($meal, $owner)->getGuests();
    }

    /**
     * Hands the meal its owner's default. `$owner` spares loading the agenda
     * when the caller already knows whose meal it is.
     */
    public function resolve(Meal $meal, ?User $owner = null): Meal
    {
        if (null !== $meal->getOwnGuests()) {
            return $meal;
        }

        return $meal->useDefaultGuests($this->defaultFor($owner ?? $meal->getAgenda()->getUser()));
    }

    public function defaultFor(User $user): int
    {
        return $this->defaults[(string) $user->getId()] ??= $this->settingsRepository->defaultGuestsOf($user);
    }

    /** The default changed: the next read goes back to the database. */
    public function forget(User $user): void
    {
        unset($this->defaults[(string) $user->getId()]);
    }

    public function reset(): void
    {
        $this->defaults = [];
    }
}
