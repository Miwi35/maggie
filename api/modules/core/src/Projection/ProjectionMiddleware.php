<?php

declare(strict_types=1);

namespace Maggie\Core\Projection;

use Lcobucci\JWT\Exception as JwtException;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercureActionPayload;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Identifier\CanonicalId;
use Maggie\Core\Mercure\MercureTopic;
use Psr\Log\LoggerInterface;
use Psr\Log\NullLogger;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Middleware\MiddlewareInterface;
use Symfony\Component\Messenger\Middleware\StackInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Messenger\Stamp\ReceivedStamp;

/**
 * Publishes on Mercure and indexes in Elasticsearch everything a message changed.
 *
 * What a handler changes is read from Doctrine ({@see WorkCollector}), not from
 * what it returns: a handler that creates a store and a product on the way to a
 * grocery line, or removes a meal's list items, announces all of it without
 * saying a word. Projection is done once, for the root message, after its
 * handler: a nested command's changes are folded into its parent's.
 *
 * Each row is published to the user who owns it, never to the one who is
 * logged in: a message handled by a worker has nobody logged in.
 */
final class ProjectionMiddleware implements MiddlewareInterface
{
    private LoggerInterface $logger;

    public function __construct(
        private readonly HubInterface $hub,
        private readonly MessageBusInterface $bus,
        private readonly WorkCollector $collector,
        ?LoggerInterface $logger = null,
    ) {
        $this->logger = $logger ?? new NullLogger();
    }

    public function handle(Envelope $envelope, StackInterface $stack): Envelope
    {
        // Skip the first pass (before transport): with the sync transport the
        // bus dispatches again with a ReceivedStamp, and the work is done then.
        // The indexing messages are the projection's own output.
        $message = $envelope->getMessage();
        if (!$envelope->all(ReceivedStamp::class)
            || $message instanceof IndexDocumentCommand
            || $message instanceof DeleteDocumentCommand) {
            return $stack->next()->handle($envelope, $stack);
        }

        $this->collector->begin();

        try {
            $envelope = $stack->next()->handle($envelope, $stack);
        } catch (\Throwable $e) {
            // What the handler flushed before failing is committed: the screens must know.
            $this->project($this->collector->finish(), $message);

            throw $e;
        }

        $returned = $envelope->last(HandledStamp::class)?->getResult();
        if ($returned instanceof MercurePublishable) {
            $this->collector->returned(
                $returned,
                $message instanceof MercureActionPayload ? $message->toMercureActionPayload() : null,
            );
        }

        $this->project($this->collector->finish(), $message);

        return $envelope;
    }

    private function project(?Work $work, object $message): void
    {
        if (null === $work) {
            return;
        }

        foreach ($work->changes() as $change) {
            try {
                $this->publish($change);
            } catch (JwtException $e) {
                // Not rethrown: the write is done and a worker would retry the command.
                $this->logger->critical('Mercure updates cannot be signed, check MERCURE_JWT_SECRET (at least 32 bytes): {error}', [
                    'error' => $e->getMessage(),
                    'message' => $message::class,
                ]);
            } catch (\Throwable $e) {
                $this->logger->error('Failed to publish Mercure update: {error}', [
                    'error' => $e->getMessage(),
                    'message' => $message::class,
                ]);
            }

            try {
                $this->index($change);
            } catch (\Throwable $e) {
                $this->logger->error('Failed to dispatch ES indexation: {error}', [
                    'error' => $e->getMessage(),
                    'message' => $message::class,
                ]);
            }
        }
    }

    private function publish(Change $change): void
    {
        if (!is_subclass_of($change->class, MercurePublishable::class)) {
            return;
        }

        $ownerId = $change->ownerId ?? (null !== $change->entity ? WorkCollector::ownerOf($change->entity) : null);
        if (null === $ownerId) {
            return;
        }

        $id = CanonicalId::of($change->id);

        if (ChangeKind::Deleted === $change->kind) {
            $payload = ['deleted' => true];
        } elseif (null !== $change->actionPayload) {
            $payload = $change->actionPayload;
        } else {
            $entity = $change->entity;
            \assert($entity instanceof MercurePublishable);
            $differential = ChangeKind::Updated === $change->kind && [] !== $change->properties;
            $payload = $entity->toMercurePayload($differential ? $change->properties : null);
        }

        // The class itself, then its ancestors that are published too: a Meal is an
        // Event and an Ingredient a Product, the screens of each one listen to it.
        for ($class = $change->class; false !== $class; $class = get_parent_class($class)) {
            if (!is_subclass_of($class, MercurePublishable::class)) {
                break;
            }

            $iri = MercureTopic::item(MercureTopic::collection($class), $id);
            $this->hub->publish(new Update(
                topics: [MercureTopic::scoped($ownerId, $iri)],
                data: json_encode(['@id' => $iri] + $payload, JSON_THROW_ON_ERROR),
                private: true,
            ));
        }
    }

    private function index(Change $change): void
    {
        $id = CanonicalId::of($change->id);

        if (ChangeKind::Deleted === $change->kind) {
            foreach ($change->indices as $indexName) {
                $this->bus->dispatch(new DeleteDocumentCommand(indexName: $indexName, documentId: $id));
            }

            return;
        }

        if ($change->entity instanceof IndexableInterface) {
            $this->bus->dispatch(new IndexDocumentCommand(entityClass: $change->class, entityId: $id));
        }
    }
}
