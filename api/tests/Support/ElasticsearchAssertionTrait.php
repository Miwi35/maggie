<?php

namespace App\Tests\Support;

use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Symfony\Component\Messenger\Transport\InMemory\InMemoryTransport;

trait ElasticsearchAssertionTrait
{
    protected function getAsyncTransport(): InMemoryTransport
    {
        /** @var InMemoryTransport $transport */
        $transport = self::getContainer()->get('messenger.transport.async');

        return $transport;
    }

    protected function resetAsyncTransport(): void
    {
        $this->getAsyncTransport()->reset();
    }

    protected function assertElasticsearchIndexDispatched(?string $entityClass = null): void
    {
        $sent = $this->getAsyncTransport()->getSent();

        $found = false;
        foreach ($sent as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof IndexDocumentCommand) {
                if (null === $entityClass || $message->entityClass === $entityClass) {
                    $found = true;
                    break;
                }
            }
        }

        self::assertTrue($found, sprintf(
            'Expected IndexDocumentCommand%s to be dispatched to async transport.',
            null !== $entityClass ? " for {$entityClass}" : '',
        ));
    }

    /**
     * One named entity was sent for reindexing.
     *
     * A change that touches two rows — the two legs of an internal transfer,
     * say — passes `assertElasticsearchIndexDispatched()` on the first one
     * while the second stays stale in the index, and the lists are served from
     * the index.
     */
    protected function assertElasticsearchIndexDispatchedFor(string $entityClass, string $entityId): void
    {
        self::assertContains(
            $entityId,
            $this->reindexedIdsOf($entityClass),
            sprintf('Expected %s %s to be sent for reindexing.', $entityClass, $entityId),
        );
    }

    protected function assertNoElasticsearchIndexDispatched(string $entityClass): void
    {
        self::assertSame([], $this->reindexedIdsOf($entityClass), 'Nothing should have been reindexed.');
    }

    /** @return string[] */
    protected function reindexedIdsOf(string $entityClass): array
    {
        $ids = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof IndexDocumentCommand && $message->entityClass === $entityClass) {
                $ids[] = $message->entityId;
            }
        }

        return $ids;
    }

    protected function assertElasticsearchDeleteDispatched(?string $indexName = null): void
    {
        $sent = $this->getAsyncTransport()->getSent();

        $found = false;
        foreach ($sent as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof DeleteDocumentCommand) {
                if (null === $indexName || $message->indexName === $indexName) {
                    $found = true;
                    break;
                }
            }
        }

        self::assertTrue($found, sprintf(
            'Expected DeleteDocumentCommand%s to be dispatched to async transport.',
            null !== $indexName ? " for index {$indexName}" : '',
        ));
    }
}
