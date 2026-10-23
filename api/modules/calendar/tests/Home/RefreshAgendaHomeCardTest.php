<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Home;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** Creating, changing or deleting an event tells the Accueil to read the Agenda card again (MAG-562). */
class RefreshAgendaHomeCardTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('AgendaHomeCardTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function homeTopic(string $userFixture = 'test_user'): string
    {
        return '/users/'.$this->getFixture($userFixture)->getId().'/home/agenda';
    }

    /** @return list<string> */
    private function topics(): array
    {
        return array_merge(...array_map(static fn ($update) => $update->getTopics(), $this->getMercureHub()->getUpdates()));
    }

    /** @param array<string, mixed> $body */
    private function send(string $method, string $uri, array $body): void
    {
        $this->client->request($method, $uri, [], [], $this->authHeaders() + [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testCreatingAnEventPublishesTheHomeRefresh(): void
    {
        $this->send('POST', '/api/events', [
            'summary' => 'Coiffeur',
            'startAt' => '2026-10-14T18:00:00+02:00',
            'endAt' => '2026-10-14T19:00:00+02:00',
            'agenda' => '/api/agendas/'.$this->getFixture('perso')->getId(),
        ]);

        self::assertResponseStatusCodeSame(201);
        $this->assertMercureUpdatePublished($this->homeTopic());
        self::assertSame(1, \count(array_keys($this->topics(), $this->homeTopic(), true)));
        $update = array_values(array_filter(
            $this->getMercureHub()->getUpdates(),
            fn ($update) => [$this->homeTopic()] === $update->getTopics(),
        ))[0];
        self::assertTrue($update->isPrivate());
        self::assertSame(['action' => 'refresh', 'module' => 'agenda'], json_decode($update->getData(), true));
    }

    public function testChangingAnEventPublishesTheHomeRefresh(): void
    {
        $id = $this->getFixture('upcoming')->getId();

        $this->send('PATCH', '/api/events/'.$id, ['summary' => 'Dentiste (déplacé)']);

        self::assertResponseIsSuccessful();
        $this->assertMercureUpdatePublished($this->homeTopic());
    }

    public function testDeletingAnEventPublishesTheHomeRefresh(): void
    {
        $id = $this->getFixture('upcoming')->getId();

        $this->client->request('DELETE', '/api/events/'.$id, [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(204);
        $this->assertMercureUpdatePublished($this->homeTopic());
    }

    public function testOnlyTheOwnersHomeIsTold(): void
    {
        $this->send('PATCH', '/api/events/'.$this->getFixture('upcoming')->getId(), ['summary' => 'Autre titre']);

        self::assertNotContains($this->homeTopic('other_user'), $this->topics());
    }

    public function testASaveOutsideTheApiPublishesToo(): void
    {
        // A Google sync or a command saves through Doctrine, not through a request.
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $event = $em->find(Event::class, $this->getFixture('past')->getId());
        $event->setLocation('Salle 2');
        $em->flush();

        $this->assertMercureUpdatePublished($this->homeTopic());
    }

    public function testRenamingTheAgendaPublishesTheHomeRefresh(): void
    {
        $this->send('PATCH', '/api/agendas/'.$this->getFixture('perso')->getId(), ['name' => 'Perso renommé']);

        self::assertResponseIsSuccessful();
        $this->assertMercureUpdatePublished($this->homeTopic());
    }

    public function testNothingChangedPublishesNoHomeRefresh(): void
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $em->flush();

        self::assertNotContains($this->homeTopic(), $this->topics());
    }
}
