<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * `?pagination=false` serves a whole collection in one answer (MAG-430).
 *
 * The mobile used to read the first page only: past 30 events in a period,
 * the rest never arrived.
 */
class EventUnpaginatedApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private const PERIOD = [
        'startAt[before]' => '2037-04-01T00:00:00+00:00',
        'endAt[after]' => '2037-03-01T00:00:00+00:00',
    ];

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('EventUnpaginatedApiTest.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
    }

    /**
     * @param array<string, string> $query
     *
     * @return array<string, mixed>
     */
    private function get(array $query, bool $authenticated = true): array
    {
        $this->client->request('GET', '/api/events?'.http_build_query($query), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $authenticated ? $this->authHeaders() : []));

        $content = (string) $this->client->getResponse()->getContent();

        return '' === $content ? [] : json_decode($content, true, 512, JSON_THROW_ON_ERROR);
    }

    public function testPaginationFalseReturnsEveryEventOfThePeriod(): void
    {
        $data = $this->get(['pagination' => 'false'] + self::PERIOD);

        self::assertResponseStatusCodeSame(200);
        self::assertCount(37, $data['member']);
        self::assertSame(37, $data['totalItems']);
        // The view is only the URL asked: no `next`, `first` or `last` to follow.
        self::assertSame(['@id', '@type'], array_keys($data['view'] ?? []));
        self::assertArrayNotHasKey('hydra:next', $data);
    }

    public function testWithoutTheParameterThePageIsStillThirty(): void
    {
        $data = $this->get(self::PERIOD);

        self::assertResponseStatusCodeSame(200);
        self::assertCount(30, $data['member']);
        self::assertSame(37, $data['totalItems']);
        self::assertArrayHasKey('next', $data['view']);
    }

    public function testThePeriodBoundsAreKept(): void
    {
        $data = $this->get(['pagination' => 'false'] + self::PERIOD);

        $summaries = array_column($data['member'], 'summary');
        self::assertNotContains('Outside', $summaries);
        self::assertNotContains('Stranger', $summaries);
    }

    public function testAnotherUsersEventsAreNeverServed(): void
    {
        $data = $this->get(['pagination' => 'false']);

        self::assertResponseStatusCodeSame(200);
        self::assertCount(38, $data['member']);
        self::assertNotContains('Stranger', array_column($data['member'], 'summary'));
    }

    public function testAnAnonymousReadIsRefused(): void
    {
        $this->get(['pagination' => 'false'] + self::PERIOD, authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }
}
