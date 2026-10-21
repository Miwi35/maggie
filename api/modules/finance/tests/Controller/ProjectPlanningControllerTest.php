<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Project;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** GET /api/finance/project-planning (MAG-526): the projects over 36 months, a pure read. */
class ProjectPlanningControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/project_funding.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', '/api/finance/project-planning');

        self::assertResponseStatusCodeSame(401);
    }

    public function testItServesThePlanningOfTheProjects(): void
    {
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $this->authenticateAsUser($user);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->client->request('GET', '/api/finance/project-planning', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(
            ['startMonth', 'horizonMonths', 'currency', 'netCapacityCents', 'isIncomeKnown', 'cushion', 'months', 'projects'],
            array_keys($data),
        );
        self::assertSame('2026-10', $data['startMonth']);
        self::assertCount(36, $data['months']);
        self::assertSame(['Voiture'], array_column($data['projects'], 'name'));
        self::assertSame('2028-02', $data['projects'][0]['purchaseMonth']);
        self::assertSame(150000, $data['projects'][0]['gatheredCents']);

        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Project::class);
    }
}
