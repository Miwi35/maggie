<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserPreferenceRepository;
use Maggie\Grocery\Entity\Product;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Contracts\Service\ResetInterface;

/**
 * « This month », as the seasonality reads it (MAG-494): the month of today in
 * the user's own timezone, so the badges switch at their midnight.
 */
final class SeasonCalendar implements ResetInterface
{
    public const DEFAULT_TIMEZONE = 'Europe/Paris';

    /** @var array<string, int> */
    private array $months = [];

    public function __construct(
        private readonly UserPreferenceRepository $preferenceRepository,
        private readonly ClockInterface $clock,
    ) {
    }

    public function currentMonth(User $user): int
    {
        $key = (string) $user->getId();

        return $this->months[$key] ??= (int) $this->clock->now()->setTimezone($this->zone($user))->format('n');
    }

    /** Fills the product's inSeason for its owner's month. */
    public function mark(Product $product): Product
    {
        return $product->markSeason($this->currentMonth($product->getUser()));
    }

    /** The month of today in Paris, for what has no user at hand — a Mercure payload. */
    public static function monthInParis(): int
    {
        return (int) Clock::get()->now()->setTimezone(new \DateTimeZone(self::DEFAULT_TIMEZONE))->format('n');
    }

    public function reset(): void
    {
        $this->months = [];
    }

    private function zone(User $user): \DateTimeZone
    {
        try {
            return new \DateTimeZone($this->preferenceRepository->findOneByUser($user)?->getTimezone() ?? self::DEFAULT_TIMEZONE);
        } catch (\Exception) {
            // A stored value that is not a timezone must not take the read down.
            return new \DateTimeZone(self::DEFAULT_TIMEZONE);
        }
    }
}
