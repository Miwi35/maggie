<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\EventListener\RefreshWrittenIndicesListener;
use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\IndexManager;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Event\ResponseEvent;
use Symfony\Component\HttpKernel\HttpKernelInterface;

final class RefreshWrittenIndicesListenerTest extends TestCase
{
    use RecordingElasticsearchTrait;

    private function listenerAfterAWrite(): RefreshWrittenIndicesListener
    {
        $reader = new IndexMetadataReader();
        $manager = new IndexManager(
            $this->recordingClient(static fn (): array => [200, ['result' => 'created']]),
            $reader,
            new IndexableEntityRegistry($this->createStub(EntityManagerInterface::class), $reader),
            $this->createStub(LoggerInterface::class),
        );
        $manager->indexDocument('events', '01J0000000000000000000000A', ['summary' => 'Lunch']);

        return new RefreshWrittenIndicesListener($manager);
    }

    private function response(int $type): ResponseEvent
    {
        return new ResponseEvent($this->createStub(HttpKernelInterface::class), new Request(), $type, new Response());
    }

    public function testTheIndexIsRefreshedBeforeTheMainResponseLeaves(): void
    {
        $this->listenerAfterAWrite()->onKernelResponse($this->response(HttpKernelInterface::MAIN_REQUEST));

        self::assertSame('POST /events/_refresh', $this->requests[1]['method'].' '.$this->requests[1]['path']);
    }

    public function testASubRequestDoesNotRefresh(): void
    {
        $this->listenerAfterAWrite()->onKernelResponse($this->response(HttpKernelInterface::SUB_REQUEST));

        self::assertCount(1, $this->requests);
    }
}
