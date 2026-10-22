<?php

declare(strict_types=1);

namespace Maggie\Core\Home;

use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\MercureTopic;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;

/**
 * Tells the user's open home page that a card's data changed. Card tickets
 * call it when their module's data changes; clients refetch the card.
 */
class HomeCardPublisher
{
    public function __construct(private readonly HubInterface $hub)
    {
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
        $this->hub->publish(new Update(
            topics: [self::topic((string) $user->getId(), $module)],
            data: json_encode(['action' => 'refresh', 'module' => $module], JSON_THROW_ON_ERROR),
            private: true,
        ));
    }
}
