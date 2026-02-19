<?php

namespace Maggie\Calendar\Middleware;

use Maggie\Calendar\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
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
use Symfony\Component\Uid\Ulid;

class MercurePublishMiddleware implements MiddlewareInterface
{
    private LoggerInterface $logger;

    public function __construct(
        private readonly HubInterface $hub,
        private readonly Security $security,
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

        if ($parsed === null) {
            return $envelope;
        }

        [$action, $topic] = $parsed;

        try {
            if ($action === 'delete') {
                $idProp = lcfirst($parsed[2]) . 'Id';
                $userId = $this->getCurrentUserId();

                if ($userId !== null) {
                    $this->publishDelete($topic, $message->$idProp, $userId);
                }
            } else {
                $entity = $envelope->last(HandledStamp::class)?->getResult();

                if ($entity instanceof MercurePublishable) {
                    $userId = $entity instanceof OwnedByUserInterface
                        ? (string) $entity->getUser()->getId()
                        : $this->getCurrentUserId();

                    if ($userId !== null) {
                        $iri = $topic . '/' . $entity->getId();
                        $scopedTopic = '/users/' . $userId . $iri;
                        $this->hub->publish(new Update(
                            topics: [$scopedTopic],
                            data: json_encode(['@id' => $iri] + $entity->toMercurePayload(), JSON_THROW_ON_ERROR),
                        ));
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
                $topic = '/api/' . $snake . 's';

                return [strtolower($prefix), $topic, $entity];
            }
        }

        return null;
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

    private function getCurrentUserId(): ?string
    {
        $user = $this->security->getUser();

        if ($user === null) {
            return null;
        }

        $id = $user->getId();

        return $id instanceof Ulid ? (string) $id : null;
    }
}
