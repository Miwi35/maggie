<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Mcp\Tool\GetProjectPlanningTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** « Quand pourrai-je acheter la voiture ? » (MAG-526). A pure read: it publishes and indexes nothing. */
class GetProjectPlanningToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/project_funding.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function read(): array
    {
        $tool = self::getContainer()->get(GetProjectPlanningTool::class);

        return json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserBoundItReportsTheError(): void
    {
        self::assertArrayHasKey('error', $this->read());
    }

    public function testItReadsThePlanningAndWritesNothing(): void
    {
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->read();

        self::assertSame('2026-10', $data['startMonth']);
        self::assertSame(['Voiture'], array_column($data['projects'], 'name'));
        self::assertSame('2028-02', $data['projects'][0]['purchaseMonth']);
        self::assertCount(36, $data['months']);
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Project::class);
    }

    public function testAUserNeverReadsTheProjectsOfAnother(): void
    {
        $this->loginFixtureUser('other_user');

        $data = $this->read();

        self::assertSame(['Projet secret'], array_column($data['projects'], 'name'));
        self::assertStringNotContainsString('Voiture', json_encode($data, JSON_THROW_ON_ERROR));
    }
}
