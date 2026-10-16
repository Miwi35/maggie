<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Mcp\Tool\PrepareDayTool;
use Maggie\Core\Mcp\MissingMcpUserException;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** Maggie's tool for the morning (MAG-498): prepare_day reads the proposal, writes nothing. */
class PrepareDayToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-10-14 08:30:00 Europe/Paris'));
        $this->loadFixtures('DayProposalToolTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loginFixtureUser();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function prepare(?string $date = null): array
    {
        return json_decode((self::getContainer()->get(PrepareDayTool::class))($date), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserItRefuses(): void
    {
        self::getContainer()->get('security.token_storage')->setToken(null);

        self::assertSame(MissingMcpUserException::MESSAGE, $this->prepare()['error']);
    }

    public function testABadDateIsRefused(): void
    {
        self::assertStringContainsString('YYYY-MM-DD', $this->prepare('demain')['error']);
    }

    public function testItProposesTodaysFiveShortestFirstAndWritesNothing(): void
    {
        $proposal = $this->prepare();

        self::assertSame('2026-10-14', $proposal['date']);
        self::assertSame(
            ['Signer la décharge', 'Écrire à Léa', 'Envoyer le devis', 'Rappeler le garage', 'Déclarer les impôts'],
            array_map(static fn (array $slot): string => $slot['task']['title'], $proposal['slots']),
        );
        self::assertSame(180, $proposal['freeMinutes']);
        self::assertSame(140, $proposal['totalMinutes']);
        self::assertCount(3, $proposal['reserveRanked']);
        self::assertSame([], self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(DayPlan::class)->findAll());
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(DayPlan::class);
    }
}
