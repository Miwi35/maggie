<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Proaction;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The agenda moves and deletes a proaction through the event routes (MAG-326). */
class ProactionEventApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('ProactionEventApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    /** @param array<string, mixed> $payload */
    private function patch(array $payload): void
    {
        $this->client->request('PATCH', '/api/events/'.$this->getFixture('proaction')->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function reload(): ?Proaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Proaction::class, $this->getFixture('proaction')->getId());
    }

    /** Its own index is `proactions`, like a meal's: the agenda reads one through the event item route, not `/api/events`. */
    public function testTheEventRouteServesItAtItsTimeInTheMaggieAgenda(): void
    {
        $this->client->request('GET', '/api/events/'.$this->getFixture('proaction')->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertEquals(new \DateTimeImmutable('2037-01-15T08:30:00+01:00'), new \DateTimeImmutable($body['startAt']));
        self::assertEquals(new \DateTimeImmutable('2037-01-15T08:35:00+01:00'), new \DateTimeImmutable($body['endAt']));
        self::assertSame('/api/agendas/'.$this->reload()->getAgenda()->getId(), $body['agenda']);
    }

    /** @return array{member: list<array<string, mixed>>} */
    private function collection(string $query = ''): array
    {
        $this->client->request('GET', '/api/proactions'.$query, [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));

        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testTheCollectionRequiresAuthentication(): void
    {
        $this->client->request('GET', '/api/proactions', [], [], ['HTTP_ACCEPT' => 'application/ld+json']);

        self::assertResponseStatusCodeSame(401);
    }

    public function testTheCollectionListsOnlyTheUsersOwnByStartWithWhatTheAgendaShows(): void
    {
        $members = $this->collection('?order[startAt]=asc')['member'];

        self::assertCount(2, $members);
        self::assertSame(['Rappelle de déposer Ael à l\'école', 'Prépare le résumé de la semaine'], array_column($members, 'prompt'));
        self::assertSame(['pending', 'failed'], array_column($members, 'executionStatus'));
        self::assertSame('Le modèle ne répond pas', $members[1]['error']);
        self::assertEquals(new \DateTimeImmutable('2037-01-15T08:35:00+01:00'), new \DateTimeImmutable($members[0]['endAt']));
    }

    public function testTheCollectionIsReadOverAPeriod(): void
    {
        $members = $this->collection('?startAt[after]=2037-01-17T00:00:00%2B01:00&startAt[before]=2037-01-17T23:59:59%2B01:00')['member'];

        self::assertSame(['Prépare le résumé de la semaine'], array_column($members, 'prompt'));
    }

    public function testTheCollectionIsFilteredByExecutionStatus(): void
    {
        $members = $this->collection('?executionStatus=failed')['member'];

        self::assertSame(['Prépare le résumé de la semaine'], array_column($members, 'prompt'));
    }

    public function testTheEventCollectionStillNeverListsThem(): void
    {
        $this->client->request('GET', '/api/events', [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));

        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame([], array_column($body['member'], 'prompt'));
    }

    public function testAnotherUserCannotReadIt(): void
    {
        $this->authenticateAsUser($this->getFixture('other_user'));

        $this->client->request('GET', '/api/events/'.$this->getFixture('proaction')->getId(), [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(404);
    }

    public function testMovingAProactionKeepsItFiveMinutesLongAndInItsAgenda(): void
    {
        $this->patch(['startAt' => '2037-01-15T09:15:00+01:00', 'endAt' => '2037-01-15T10:00:00+01:00']);

        self::assertResponseIsSuccessful();
        $proaction = $this->reload();
        self::assertEquals(new \DateTimeImmutable('2037-01-15T09:15:00+01:00'), $proaction->getStartAt());
        self::assertEquals(new \DateTimeImmutable('2037-01-15T09:20:00+01:00'), $proaction->getEndAt());
        self::assertSame('agent', $proaction->getAgenda()->getModule());
        self::assertSame('Rappelle de déposer Ael à l\'école', $proaction->getPrompt());
        $this->assertMercureUpdatePublished('/proactions/');
        $this->assertElasticsearchIndexDispatched(Proaction::class);
    }

    public function testAProactionCannotBeMovedOutOfTheMaggieAgenda(): void
    {
        $this->patch(['agenda' => '/api/agendas/'.$this->getFixture('perso')->getId()]);

        self::assertSame('agent', $this->reload()->getAgenda()->getModule());
    }

    public function testAnRruleIsRefused(): void
    {
        $this->patch(['rrule' => 'FREQ=DAILY']);

        self::assertResponseStatusCodeSame(422);
        self::assertNull($this->reload()->getRrule());
    }

    public function testMakingItAllDayIsRefused(): void
    {
        $this->patch(['allDay' => true, 'startDate' => '2037-01-15', 'startAt' => null, 'endAt' => null]);

        self::assertResponseStatusCodeSame(422);
        self::assertFalse($this->reload()->isAllDay());
    }

    public function testDeletingItFromTheAgendaRemovesIt(): void
    {
        $this->client->request('DELETE', '/api/events/'.$this->getFixture('proaction')->getId(), [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(204);
        self::assertNull($this->reload());
        $this->assertElasticsearchDeleteDispatched('proactions');
    }

    public function testAnotherUserCannotTouchIt(): void
    {
        $this->authenticateAsUser($this->getFixture('other_user'));

        $this->patch(['startAt' => '2037-01-15T09:15:00+01:00', 'endAt' => '2037-01-15T09:20:00+01:00']);
        self::assertResponseStatusCodeSame(404);

        $this->client->request('DELETE', '/api/events/'.$this->getFixture('proaction')->getId(), [], [], $this->authHeaders());
        self::assertResponseStatusCodeSame(404);

        self::assertEquals(new \DateTimeImmutable('2037-01-15T08:30:00+01:00'), $this->reload()->getStartAt());
    }
}
