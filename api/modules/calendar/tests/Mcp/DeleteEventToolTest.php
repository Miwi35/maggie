<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Mcp\Tool\DeleteEventTool;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class DeleteEventToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('DeleteEventToolTest.yaml');
        $this->loginFixtureUser();
    }

    public function testDeleteEventRemovesItFromDatabasePublishesAndDeletesFromIndex(): void
    {
        $id = (string) $this->getFixture('event_full')->getId();
        $tool = self::getContainer()->get(DeleteEventTool::class);

        $data = json_decode($tool($id), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->getRepository(Event::class)->find($id));

        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchDeleteDispatched('events');

        $deleted = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof DeleteDocumentCommand) {
                $deleted[] = [$message->indexName, $message->documentId];
            }
        }
        self::assertContains(['events', $id], $deleted);
    }

    public function testDeleteEventWithLowercaseIdTargetsTheIndexedDocumentId(): void
    {
        $id = (string) $this->getFixture('event_full')->getId();
        $tool = self::getContainer()->get(DeleteEventTool::class);

        $data = json_decode($tool(strtolower($id)), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);

        $deleted = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof DeleteDocumentCommand) {
                $deleted[] = [$message->indexName, $message->documentId];
            }
        }
        self::assertContains(['events', $id], $deleted);
    }

    public function testDeletingARecurringEventRemovesItsExceptionsFromTheIndex(): void
    {
        $id = (string) $this->getFixture('event_full')->getId();
        $exceptionId = (string) $this->getFixture('event_exception')->getId();
        $tool = self::getContainer()->get(DeleteEventTool::class);

        $data = json_decode($tool($id), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $deleted = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof DeleteDocumentCommand) {
                $deleted[] = [$message->indexName, $message->documentId];
            }
        }
        self::assertEqualsCanonicalizing([['events', $id], ['events', $exceptionId]], $deleted);
    }
}
