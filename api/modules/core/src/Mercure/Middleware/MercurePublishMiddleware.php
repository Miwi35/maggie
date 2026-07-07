<?php

namespace Maggie\Core\Mercure\Middleware;

use Maggie\Core\Contract\MercureActionPayload;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Contract\OwnedThroughInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\ChangesetStore;
use Psr\Log\LoggerInterface;
use Psr\Log\NullLogger;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Middleware\MiddlewareInterface;
use Symfony\Component\Messenger\Middleware\StackInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Messenger\Stamp\ReceivedStamp;

class MercurePublishMiddleware implements MiddlewareInterface
{
    private LoggerInterface $logger;

    public function __construct(
        private readonly HubInterface $hub,
        private readonly Security $security,
        private readonly ChangesetStore $changesetStore,
        ?LoggerInterface $logger = null,
    ) {
        $this->logger = $logger ?? new NullLogger();
    }

    public function handle(Envelope $envelope, StackInterface $stack): Envelope
    {
        // Skip the first pass (before transport); only publish after the handler runs.
        // With sync transport the bus re-dispatches with ReceivedStamp, causing a double run.
        if (!$envelope->all(ReceivedStamp::class)) {
            return $stack->next()->handle($envelope, $stack);
        }

        $envelope = $stack->next()->handle($envelope, $stack);

        $message = $envelope->getMessage();
        $parsed = self::parseCommandClass($message::class);

        try {
            if ($parsed !== null && $parsed[0] === 'delete') {
                $idProp = lcfirst($parsed[2]) . 'Id';
                $userId = $this->getCurrentUserId();

                if ($userId !== null) {
                    $this->publishDelete($parsed[1], $message->$idProp, $userId);
                    $this->publishDeleteToParentTopics($message::class, $parsed[2], $message->$idProp, $userId);
                }
            } else {
                // For CRUD commands, use the parsed topic; for non-CRUD commands
                // (Add, Check, End, Move, Remove, Generate…), derive topic from the entity class.
                $entity = $envelope->last(HandledStamp::class)?->getResult();

                if ($entity instanceof MercurePublishable) {
                    $topic = $parsed[1] ?? self::topicFromEntity($entity);
                    $userId = self::resolveUserId($entity) ?? $this->getCurrentUserId();

                    // Determine payload: action payload > differential update > full payload
                    if ($message instanceof MercureActionPayload) {
                        $payload = $message->toMercureActionPayload();
                    } elseif ($parsed !== null && $parsed[0] === 'update') {
                        $changedProperties = $this->changesetStore->get($entity);
                        $payload = $entity->toMercurePayload($changedProperties);
                    } else {
                        $payload = $entity->toMercurePayload();
                    }

                    if ($userId !== null) {
                        $this->publishEntity($entity, $topic, $userId, $payload);
                        $this->publishEntityToParentTopics($entity, $topic, $userId, $payload);
                    }
                }
            }
        } catch (\Throwable $e) {
            $this->logger->error('Failed to publish Mercure update: {error}', [
                'error' => $e->getMessage(),
                'message' => $message::class,
            ]);
        }

        return $envelope;
    }

    /**
     * Parses "{Action}{Entity}Command" → [action, topic, entity].
     *
     * @return array{string, string, string}|null
     */
    private static function parseCommandClass(string $fqcn): ?array
    {
        $short = substr($fqcn, strrpos($fqcn, '\\') + 1);

        if (!str_ends_with($short, 'Command')) {
            return null;
        }

        $name = substr($short, 0, -7); // Strip "Command"

        foreach (['Create', 'Update', 'Delete'] as $prefix) {
            if (str_starts_with($name, $prefix)) {
                $entity = substr($name, strlen($prefix));
                // Convert CamelCase to snake_case: GroceryList → grocery_list
                $snake = strtolower(preg_replace('/(?<!^)[A-Z]/', '_$0', $entity));
                $topic = '/api/' . self::pluralize($snake);

                return [strtolower($prefix), $topic, $entity];
            }
        }

        return null;
    }

    private static function topicFromEntity(object $entity): string
    {
        return self::topicFromClassName((new \ReflectionClass($entity))->getShortName());
    }

    private static function topicFromClassName(string $shortName): string
    {
        $snake = strtolower(preg_replace('/(?<!^)[A-Z]/', '_$0', $shortName));

        return '/api/' . self::pluralize($snake);
    }

    /**
     * Pluralize a snake_case entity name to match API Platform's collection route.
     * Handles the consonant+"y" → "ies" case (e.g. category → categories);
     * every other entity keeps the simple "+s" form.
     */
    private static function pluralize(string $snake): string
    {
        if (preg_match('/[bcdfghjklmnpqrstvwxz]y$/', $snake)) {
            return substr($snake, 0, -1) . 'ies';
        }

        return $snake . 's';
    }

    private static function resolveUserId(object $entity): ?string
    {
        if ($entity instanceof OwnedByUserInterface) {
            return (string) $entity->getUser()->getId();
        }

        if ($entity instanceof OwnedThroughInterface) {
            try {
                $relation = $entity::getOwnerRelation();
                $getter = 'get' . ucfirst($relation);
                $parent = $entity->$getter();

                if ($parent instanceof OwnedByUserInterface) {
                    return (string) $parent->getUser()->getId();
                }
            } catch (\Error) {
                // Relation not initialized — fall through to Security fallback
                return null;
            }
        }

        return null;
    }

    /** @param array<string, mixed> $payload */
    private function publishEntity(MercurePublishable $entity, string $topic, string $userId, array $payload): void
    {
        $iri = $topic . '/' . $entity->getId();
        $scopedTopic = '/users/' . $userId . $iri;
        $this->hub->publish(new Update(
            topics: [$scopedTopic],
            data: json_encode(['@id' => $iri] + $payload, JSON_THROW_ON_ERROR),
        ));
    }

    /**
     * For entity inheritance hierarchies (e.g. Meal extends Event),
     * also publish to parent class topics.
     *
     * @param array<string, mixed> $payload
     */
    private function publishEntityToParentTopics(MercurePublishable $entity, string $primaryTopic, string $userId, array $payload): void
    {
        $parentClass = get_parent_class($entity);

        if ($parentClass === false || !(new \ReflectionClass($parentClass))->implementsInterface(MercurePublishable::class)) {
            return;
        }

        $parentTopic = self::topicFromClassName((new \ReflectionClass($parentClass))->getShortName());
        if ($parentTopic !== $primaryTopic) {
            $this->publishEntity($entity, $parentTopic, $userId, $payload);
        }
    }

    private function publishDelete(string $topic, string $id, string $userId): void
    {
        $iri = $topic . '/' . $id;
        $scopedTopic = '/users/' . $userId . $iri;
        $this->hub->publish(new Update(
            topics: [$scopedTopic],
            data: json_encode(['@id' => $iri, 'deleted' => true], JSON_THROW_ON_ERROR),
        ));
    }

    /**
     * For delete commands on entities with parent MercurePublishable classes,
     * also publish a delete to the parent topic.
     */
    private function publishDeleteToParentTopics(string $commandFqcn, string $entityShortName, string $id, string $userId): void
    {
        // Derive entity FQCN: replace \Message\Delete{X}Command with \Entity\{X}
        $entityFqcn = preg_replace(
            '/\\\\Message\\\\Delete' . preg_quote($entityShortName, '/') . 'Command$/',
            '\\Entity\\' . $entityShortName,
            $commandFqcn,
        );

        if (!class_exists($entityFqcn)) {
            return;
        }

        $parentClass = get_parent_class($entityFqcn);
        if ($parentClass === false || !(new \ReflectionClass($parentClass))->implementsInterface(MercurePublishable::class)) {
            return;
        }

        $parentTopic = self::topicFromClassName((new \ReflectionClass($parentClass))->getShortName());
        $parsedTopic = self::topicFromClassName($entityShortName);
        if ($parentTopic !== $parsedTopic) {
            $this->publishDelete($parentTopic, $id, $userId);
        }
    }

    private function getCurrentUserId(): ?string
    {
        $user = $this->security->getUser();

        if (!$user instanceof User) {
            return null;
        }

        return (string) $user->getId();
    }
}
