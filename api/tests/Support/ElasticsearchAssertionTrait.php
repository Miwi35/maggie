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
