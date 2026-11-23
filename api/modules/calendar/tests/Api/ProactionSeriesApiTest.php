<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\EventStatus;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The agenda's « this occurrence only » on a proaction series (ADR-012-1, MAG-974). */
class ProactionSeriesApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->loadFixtures('ProactionSeriesApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    /** @param array<string, mixed> $payload */
    private function request(string $method, string $uri, array $payload = []): void
    {
        $this->client->request($method, $uri, [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), [] === $payload ? null : json_encode($payload, JSON_THROW_ON_ERROR));
    }

    /** @return list<Proaction> */
    private function instancesOfSeries(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Proaction::class)->findBy(['recurringEvent' => $this->getFixture('series')->getId()], ['originalStartAt' => 'ASC']);
    }

    public function testAnInstanceOfASeriesCannotTakeAnRrule(): void
    {
        $this->request('POST', '/api/events', [
            'recurringEvent' => '/api/events/'.$this->getFixture('series')->getId(),
            'originalStartAt' => '2037-01-16T18:00:00+01:00',
            'summary' => 'Point',
            'agenda' => '/api/agendas/'.$this->getFixture('maggie_agenda')->getId(),
            'startAt' => '2037-01-16T19:00:00+01:00',
            'endAt' => '2037-01-16T19:05:00+01:00',
        ]);
        self::assertResponseIsSuccessful();
        $instance = $this->instancesOfSeries()[0];

        $this->client->request('PATCH', '/api/events/'.$instance->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['rrule' => 'FREQ=DAILY'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(422);
    }

    public function testMovingOneOccurrenceCreatesAProactionExceptionAndLeavesTheSeries(): void
    {
        $this->request('POST', '/api/events', [
            'recurringEvent' => '/api/events/'.$this->getFixture('series')->getId(),
            'originalStartAt' => '2037-01-16T18:00:00+01:00',
            'summary' => 'Point',
            'agenda' => '/api/agendas/'.$this->getFixture('maggie_agenda')->getId(),
            'startAt' => '2037-01-16T19:00:00+01:00',
            'endAt' => '2037-01-16T19:05:00+01:00',
        ]);

        self::assertResponseIsSuccessful();
        $instances = array_values(array_filter($this->instancesOfSeries(), static fn (Proaction $i) => '2037-01-16' === $i->getOriginalStartAt()?->format('Y-m-d')));
        self::assertCount(1, $instances);
        self::assertEquals(new \DateTimeImmutable('2037-01-16T19:00:00+01:00'), $instances[0]->getStartAt());
        self::assertEquals(new \DateTimeImmutable('2037-01-16T18:00:00+01:00'), $instances[0]->getOriginalStartAt());
        self::assertSame('Fais le point', $instances[0]->getPrompt());
        $series = self::getContainer()->get('doctrine.orm.entity_manager')->find(Proaction::class, $this->getFixture('series')->getId());
        self::assertEquals(new \DateTimeImmutable('2037-01-09T18:00:00+01:00'), $series->getStartAt());
        $this->assertMercureUpdatePublished('/proactions/');
    }

    public function testAnOccurrenceOfAnotherUsersSeriesIsNotFound(): void
    {
        $this->request('POST', '/api/events', [
            'recurringEvent' => '/api/events/'.$this->getFixture('other_series')->getId(),
            'originalStartAt' => '2037-01-16T18:00:00+01:00',
            'summary' => 'Point',
            'agenda' => '/api/agendas/'.$this->getFixture('maggie_agenda')->getId(),
            'startAt' => '2037-01-16T19:00:00+01:00',
            'endAt' => '2037-01-16T19:05:00+01:00',
        ]);

        self::assertGreaterThanOrEqual(400, $this->client->getResponse()->getStatusCode());
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertCount(0, $em->getRepository(Proaction::class)->findBy(['recurringEvent' => $this->getFixture('other_series')->getId()]));
    }

    public function testDeletingAnInstanceOfASeriesCancelsIt(): void
    {
        $this->request('DELETE', '/api/events/'.$this->getFixture('series_instance')->getId());

        self::assertResponseStatusCodeSame(204);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $instance = $em->find(Proaction::class, $this->getFixture('series_instance')->getId());
        self::assertNotNull($instance);
        self::assertSame(EventStatus::Cancelled, $instance->getStatus());
        $this->assertMercureUpdatePublished('/proactions/');
    }
}
