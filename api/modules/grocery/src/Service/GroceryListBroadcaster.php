<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Mercure\MercureTopic;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Publishes a grocery list a handler changed but does not return.
 *
 * Both the Mercure middleware and the Elasticsearch middleware read the
 * handler's return value. A meal handler returns the meal, so the list it
 * just filled or emptied was neither pushed to the open screens nor
 * reindexed — and the collection is served from Elasticsearch, so a stale
 * index shows stale shopping (MAG-116).
 *
 * Call this only when the list is *not* the handler's result: a handler that
 * returns the list is already covered, and calling both publishes twice.
 */
class GroceryListBroadcaster
{
    public function __construct(
        private readonly HubInterface $hub,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function broadcast(?GroceryList $list): void
    {
        if (null === $list) {
            return;
        }

        // From the class, not the instance: a Doctrine proxy would spell the
        // topic after the proxy's own short name.
        $topic = MercureTopic::item(MercureTopic::collection(GroceryList::class), (string) $list->getId());

        $this->hub->publish(new Update(
            topics: [MercureTopic::scoped((string) $list->getUser()->getId(), $topic)],
            data: json_encode(['@id' => $topic] + $list->toMercurePayload(), JSON_THROW_ON_ERROR),
            private: true,
        ));

        $this->bus->dispatch(new IndexDocumentCommand(
            entityClass: GroceryList::class,
            entityId: (string) $list->getId(),
        ));
    }
}
