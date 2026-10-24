<?php

declare(strict_types=1);

namespace Maggie\Core\Home;

use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\MercureTopic;
use Maggie\Core\Projection\Change;
use Monolog\Attribute\WithMonologChannel;
use Psr\Log\LoggerInterface;
use Psr\Log\NullLogger;
use Symfony\Component\DependencyInjection\Attribute\AutowireIterator;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;

/**
 * Tells the user's open home page that a card's data changed. A card that
 * implements {@see HomeCardWatcherInterface} is announced by the projection
 * (see {@see publishAffected()}); any other calls {@see publish()} itself.
 * Clients refetch the card.
 */
#[WithMonologChannel('mercure')]
class HomeCardPublisher
{
    private LoggerInterface $logger;

    /** @param iterable<HomeCardProviderInterface> $providers */
    public function __construct(
        private readonly HubInterface $hub,
        #[AutowireIterator(HomeCardRegistry::TAG)]
        private readonly iterable $providers = [],
        ?LoggerInterface $logger = null,
    ) {
        $this->logger = $logger ?? new NullLogger();
    }

    /**
     * The topic published, `/users/{userId}/home/{module}`: under the user
     * scope like every other topic, so the subscriber's existing
     * `/users/{id}/*` grant covers it (MercureSubscriberTokenFactory).
     */
    public static function topic(string $userId, string $module): string
    {
        return MercureTopic::scoped($userId, '/home/'.$module);
    }

    public function publish(string $module, User $user): void
    {
        $this->publishFor((string) $user->getId(), $module);
    }

    /**
     * Refreshes, for each owner of these changes, every watching card whose entities they touch — once per card and owner.
     *
     * @param list<Change> $changes
     * @param ?string      $messageClass the message whose changes these are, for the publication log
     */
    public function publishAffected(array $changes, ?string $messageClass = null): void
    {
        $announced = [];

        foreach ($this->providers as $provider) {
            if (!$provider instanceof HomeCardWatcherInterface) {
                continue;
            }

            foreach ($changes as $change) {
                $key = $change->ownerId.'|'.$provider->module();
                if (null === $change->ownerId || isset($announced[$key]) || !self::touches($provider, $change)) {
                    continue;
                }

                $announced[$key] = true;
                $this->publishFor($change->ownerId, $provider->module(), $messageClass);
            }
        }
    }

    private function publishFor(string $userId, string $module, ?string $messageClass = null): void
    {
        $this->hub->publish(new Update(
            topics: [self::topic($userId, $module)],
            data: json_encode(['action' => 'refresh', 'module' => $module], JSON_THROW_ON_ERROR),
            private: true,
        ));

        $this->logger->info('Mercure update published', [
            'topic' => self::topic($userId, $module),
            'type' => 'home-refresh',
            'message' => $messageClass,
        ]);
    }

    private static function touches(HomeCardWatcherInterface $watcher, Change $change): bool
    {
        foreach ($watcher->watchedEntities() as $class) {
            if (is_a($change->class, $class, true)) {
                return true;
            }
        }

        return false;
    }
}
