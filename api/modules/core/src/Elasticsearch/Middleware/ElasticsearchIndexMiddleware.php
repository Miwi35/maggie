<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Middleware;

use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Psr\Log\LoggerInterface;
use Psr\Log\NullLogger;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Middleware\MiddlewareInterface;
use Symfony\Component\Messenger\Middleware\StackInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Messenger\Stamp\ReceivedStamp;

final class ElasticsearchIndexMiddleware implements MiddlewareInterface
{
    private LoggerInterface $logger;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly IndexMetadataReader $metadataReader,
        ?LoggerInterface $logger = null,
    ) {
        $this->logger = $logger ?? new NullLogger();
    }

    public function handle(Envelope $envelope, StackInterface $stack): Envelope
    {
        // Skip the first pass; only index after handler runs.
        if (!$envelope->all(ReceivedStamp::class)) {
            return $stack->next()->handle($envelope, $stack);
        }

        $envelope = $stack->next()->handle($envelope, $stack);

        $message = $envelope->getMessage();
        $parsed = self::parseCommandClass($message::class);

        if ($parsed === null) {
            return $envelope;
        }

        [$action] = $parsed;

        try {
            if ($action === 'delete') {
                $this->handleDelete($message, $parsed);
            } else {
                $this->handleCreateOrUpdate($envelope);
            }
        } catch (\Throwable $e) {
            $this->logger->error('Failed to dispatch ES indexation: {error}', [
                'error' => $e->getMessage(),
                'message' => $message::class,
            ]);
        }

        return $envelope;
    }

    private function handleCreateOrUpdate(Envelope $envelope): void
    {
        $entity = $envelope->last(HandledStamp::class)?->getResult();

        if (!$entity instanceof IndexableInterface) {
            return;
        }

        $this->bus->dispatch(new IndexDocumentCommand(
            entityClass: $entity::class,
            entityId: (string) $entity->getId(),
        ));
    }

    /**
     * @param array{string, string, string} $parsed
     */
    private function handleDelete(object $message, array $parsed): void
    {
        $entityName = $parsed[2];
        $idProp = lcfirst($entityName) . 'Id';

        if (!property_exists($message, $idProp)) {
            return;
        }

        // Resolve index name from entity FQCN by scanning known entity classes
        // CamelCase to snake_case plural: GroceryList → grocery_lists
        $snake = strtolower(preg_replace('/(?<!^)[A-Z]/', '_$0', $entityName));
        $indexName = $snake . 's';

        $this->bus->dispatch(new DeleteDocumentCommand(
            indexName: $indexName,
            documentId: $message->$idProp,
        ));
    }

    /**
     * @return array{string, string, string}|null [action, topic, entity]
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
                $snake = strtolower(preg_replace('/(?<!^)[A-Z]/', '_$0', $entity));
                $topic = '/api/' . $snake . 's';

                return [strtolower($prefix), $topic, $entity];
            }
        }

        return null;
    }
}
