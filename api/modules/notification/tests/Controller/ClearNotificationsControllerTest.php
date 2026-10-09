<?php

declare(strict_types=1);

namespace Maggie\Notification\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Notification\Entity\Notification;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** Emptying the bell: `DELETE /api/notifications` (MAG-362). */
final class ClearNotificationsControllerTest extends WebTestCase
{
    use AuthenticatedTestTrait;
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('clear_notifications.yaml');
    }

    public function testUnauthenticatedReturns401AndDeletesNothing(): void
    {
        $this->client->request('DELETE', '/api/notifications');

        self::assertResponseStatusCodeSame(401);
        self::assertCount(3, $this->stored());
        $this->assertMercureUpdateCount(0);
    }

    public function testDeletesEveryNotificationOfTheUserAndOnlyTheirs(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->clear();

        self::assertResponseStatusCodeSame(200);
        self::assertSame(['deleted' => 2], json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR));
        $remaining = $this->stored();
        self::assertCount(1, $remaining);
        self::assertSame('Pas pour nous', $remaining[0]->getTitle());
    }

    public function testPublishesTheDeletionOfEachNotificationOnMercureAndInTheIndex(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $ids = [
            (string) $this->getFixture('own_unread')->getId(),
            (string) $this->getFixture('own_read')->getId(),
        ];
        $otherId = (string) $this->getFixture('other_user_notification')->getId();

        $this->clear();

        $updates = $this->getMercureHub()->getUpdates();
        self::assertCount(2, $updates);
        $published = [];
        foreach ($updates as $update) {
            $data = json_decode($update->getData(), true, 512, \JSON_THROW_ON_ERROR);
            self::assertTrue($data['deleted']);
            self::assertTrue($update->isPrivate());
            $published[] = $data['@id'];
        }
        foreach ($ids as $id) {
            self::assertCount(1, array_filter($published, static fn (string $iri) => str_ends_with($iri, '/api/notifications/'.$id)), "No deletion published for {$id}");
        }
        self::assertNotContains($otherId, array_map(static fn (string $iri) => basename($iri), $published));

        $this->assertElasticsearchDeleteDispatched('notifications');
        $deletedDocuments = array_map(
            static fn ($envelope) => $envelope->getMessage()->documentId,
            array_filter($this->getAsyncTransport()->getSent(), static fn ($envelope) => $envelope->getMessage() instanceof DeleteDocumentCommand),
        );
        self::assertCount(2, $deletedDocuments);
        self::assertNotContains($otherId, $deletedDocuments);
    }

    public function testAnEmptyBellAnswersZero(): void
    {
        $this->authenticateAsUser($this->getFixture('other_user'));
        $this->clear();
        self::assertSame(['deleted' => 1], json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR));

        $this->resetMercure();
        $this->clear();

        self::assertResponseStatusCodeSame(200);
        self::assertSame(['deleted' => 0], json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR));
        $this->assertMercureUpdateCount(0);
    }

    private function clear(): void
    {
        $this->client->request('DELETE', '/api/notifications', [], [], $this->authHeaders());
    }

    /** @return list<Notification> */
    private function stored(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em->getRepository(Notification::class)->findAll();
    }
}
