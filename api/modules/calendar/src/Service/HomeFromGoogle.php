<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Place;
use Maggie\Calendar\Google\GooglePeopleClient;
use Maggie\Calendar\Message\SavePlaceCommand;
use Maggie\Calendar\Repository\PlaceRepository;
use Maggie\Calendar\Routing\GeocodingClient;
use Maggie\Core\Entity\User;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Contracts\Cache\CacheInterface;
use Symfony\Contracts\Cache\ItemInterface;

/**
 * The « Maison » taken from the user's Google account (MAG-664, « Partir à
 * l'heure »): its « Domicile » address becomes an ordinary {@see Place}, which
 * `save_place` can change afterwards. Never replaces a home already saved, and
 * never fails visibly — no address, scope refused, Google down: no home, the
 * tools answer as before and Maggie asks the owner.
 */
class HomeFromGoogle
{
    public const int RETRY_SECONDS = 86400;

    public function __construct(
        private readonly PlaceRepository $places,
        private readonly GooglePeopleClient $people,
        private readonly GeocodingClient $geocoding,
        private readonly MessageBusInterface $bus,
        private readonly CacheInterface $cache,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** At sign-in the user has just granted the scope: try now, whatever the last attempt. */
    public function import(User $user): ?Place
    {
        if ($this->hasHome($user)) {
            return null;
        }

        $this->cache->delete($this->key($user));
        $this->claimAttempt($user);

        return $this->fetch($user);
    }

    /** For the tools: at most one attempt per 24 h and per user, and none once a home exists. */
    public function importIfDue(User $user): ?Place
    {
        if ($this->hasHome($user) || !$this->claimAttempt($user)) {
            return null;
        }

        return $this->fetch($user);
    }

    private function hasHome(User $user): bool
    {
        return [] !== $this->places->findHomes($user);
    }

    private function claimAttempt(User $user): bool
    {
        $claimed = false;
        $this->cache->get($this->key($user), static function (ItemInterface $item) use (&$claimed): bool {
            $item->expiresAfter(self::RETRY_SECONDS);
            $claimed = true;

            return true;
        });

        return $claimed;
    }

    private function key(User $user): string
    {
        return 'home_from_google.'.$user->getId();
    }

    private function fetch(User $user): ?Place
    {
        try {
            $address = $this->people->homeAddress($user);
            if (null === $address || mb_strlen($address) > 500) {
                return null;
            }

            $fields = ['name' => 'Maison', 'address' => $address, 'isHome' => true];
            try {
                $found = $this->geocoding->geocode($address);
                $fields['latitude'] = $found['latitude'];
                $fields['longitude'] = $found['longitude'];
            } catch (\Throwable $e) {
                // Kept without coordinates: the first trip from it geocodes it again.
                $this->logger->info('Google home address not located.', ['exception' => $e::class]);
            }

            $place = $this->bus->dispatch(new SavePlaceCommand((string) $user->getId(), null, $fields))
                ->last(HandledStamp::class)?->getResult();

            return $place instanceof Place ? $place : null;
        } catch (\Throwable $e) {
            // Not chained or logged whole: the HTTP client's message holds the URL, the URL the key.
            $this->logger->info('Home could not be imported from Google.', ['exception' => $e::class]);

            return null;
        }
    }
}
