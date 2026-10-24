<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * `GET /api/tasks?completedAt[after]=…&completedAt[before]=…` (MAG-588): the domain
 * filter behind the Accueil's « faites aujourd'hui », read by every front and by Maggie.
 *
 * The test environment serves the collection from Doctrine; in production
 * Elasticsearch does, through the generic range clause the translator builds on
 * the same indexed `completedAt` field (see ElasticsearchFilterTranslatorTest).
 */
class TaskCompletionFilterApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('TaskCompletionFilterApiTest.yaml');
    }

    /** @param array<string, mixed> $query */
    private function titles(array $query, bool $sorted = true): array
    {
        $this->client->request('GET', '/api/tasks', $query, [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $titles = array_column($body['member'], 'title');
        if ($sorted) {
            sort($titles);
        }

        return $titles;
    }

    public function testItRequiresAuthentication(): void
    {
        $this->client->request('GET', '/api/tasks', ['completedAt' => ['after' => '2026-10-24T00:00:00+02:00']]);

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnUnreadableBoundIs400(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        foreach (['after' => 'not-a-date', 'before' => '2026-13-45T99:00:00', 'strictly_after' => ''] as $operator => $value) {
            $this->client->request('GET', '/api/tasks', ['completedAt' => [$operator => $value]], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));
            self::assertResponseStatusCodeSame(400, sprintf('completedAt[%s]=%s', $operator, $value));
        }
    }

    public function testAnUnknownBoundIs400(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/tasks', ['completedAt' => ['around' => '2026-10-24']], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));

        self::assertResponseStatusCodeSame(400);
    }

    public function testAfterAndBeforeKeepTheTasksDoneInThePeriodOfTheUserAlone(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        self::assertSame(
            ['Done this evening', 'Done this morning'],
            $this->titles(['completedAt' => ['after' => '2026-10-24T00:00:00+02:00', 'before' => '2026-10-24T23:59:59+02:00']]),
        );
    }

    public function testAfterAloneKeepsEverythingDoneSince(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        self::assertSame(
            ['Done this evening', 'Done this morning', 'Done yesterday'],
            $this->titles(['completedAt' => ['after' => '2026-10-23T00:00:00+02:00']]),
        );
    }

    public function testStrictBoundsExcludeTheInstantItself(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        self::assertSame(
            ['Done this evening'],
            $this->titles(['completedAt' => ['strictly_after' => '2026-10-24T08:00:00+02:00']]),
        );
        self::assertSame(
            ['Done this morning', 'Done yesterday'],
            $this->titles(['completedAt' => ['before' => '2026-10-24T08:00:00+02:00']]),
        );
    }

    public function testItComposesWithTheExistsFilter(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        self::assertSame(
            ['Done this evening', 'Done this morning'],
            $this->titles(['exists' => ['completedAt' => 'true'], 'completedAt' => ['after' => '2026-10-24T00:00:00+02:00']]),
        );
    }

    public function testTheLastDoneComeFirstWhenAskedAndTheCountIsCapped(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        self::assertSame(
            ['Done this evening', 'Done this morning'],
            $this->titles(['completedAt' => ['after' => '2026-10-24T00:00:00+02:00'], 'order' => ['completedAt' => 'desc'], 'itemsPerPage' => 2], false),
        );
        self::assertSame(
            ['Done this evening'],
            $this->titles(['completedAt' => ['after' => '2026-10-24T00:00:00+02:00'], 'order' => ['completedAt' => 'desc'], 'itemsPerPage' => 1], false),
        );
    }
}
