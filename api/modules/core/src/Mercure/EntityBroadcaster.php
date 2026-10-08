<?php

declare(strict_types=1);

namespace Maggie\Core\Mercure;

use Doctrine\Persistence\Proxy;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Publishes and reindexes an entity a handler changed but does not return.
 *
 * The Mercure middleware and the Elasticsearch middleware both read the
 * handler's return value only. Whatever else the handler persists on the way
 * (a list a meal filled, the "Repas" agenda a first meal created) is neither
 * pushed to the open screens nor reindexed — and collections are served from
 * Elasticsearch, so the entity exists in the database and shows nowhere
 * (MAG-116, MAG-176).
 *
 * Call this only for an entity that is *not* the handler's result: a handler
 * that returns it is already covered, and calling both publishes twice.
 * Call it after the flush, so the indexer finds the row.
 */
class EntityBroadcaster
{
    public function __construct(
        private readonly HubInterface $hub,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function broadcast(MercurePublishable&IndexableInterface&OwnedByUserInterface $entity): void
    {
        // The real class: a Doctrine proxy would spell the topic after the
        // proxy's own short name.
        $class = $entity instanceof Proxy ? get_parent_class($entity) : $entity::class;
        $userId = (string) $entity->getUser()->getId();
        $payload = $entity->toMercurePayload();

        $this->publish($class, (string) $entity->getId(), $userId, $payload);

        // An Ingredient is a Product row: the products screens subscribe to
        // /api/products, as the Mercure middleware already assumes.
        $parent = get_parent_class($class);
        if (false !== $parent && is_subclass_of($parent, MercurePublishable::class)) {
            $this->publish($parent, (string) $entity->getId(), $userId, $payload);
        }

        $this->bus->dispatch(new IndexDocumentCommand(
            entityClass: $class,
            entityId: (string) $entity->getId(),
        ));
    }

    /**
     * The same for an entity removed outside a Delete command: the open
     * screens drop it, and the index stops serving it. Call it after the flush.
     */
    public function broadcastRemoval(MercurePublishable&OwnedByUserInterface $entity, string $indexName): void
    {
        $class = $entity instanceof Proxy ? get_parent_class($entity) : $entity::class;
        $topic = MercureTopic::item(MercureTopic::collection($class), (string) $entity->getId());

        $this->hub->publish(new Update(
            topics: [MercureTopic::scoped((string) $entity->getUser()->getId(), $topic)],
            data: json_encode(['@id' => $topic, 'deleted' => true], JSON_THROW_ON_ERROR),
            private: true,
        ));

        $this->bus->dispatch(new DeleteDocumentCommand(indexName: $indexName, documentId: (string) $entity->getId()));
    }

    /** @param array<string, mixed> $payload */
    private function publish(string $class, string $id, string $userId, array $payload): void
    {
        $topic = MercureTopic::item(MercureTopic::collection($class), $id);

        $this->hub->publish(new Update(
            topics: [MercureTopic::scoped($userId, $topic)],
            data: json_encode(['@id' => $topic] + $payload, JSON_THROW_ON_ERROR),
            private: true,
        ));
    }
}
