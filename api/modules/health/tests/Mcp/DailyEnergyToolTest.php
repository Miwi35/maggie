<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Health\Mcp\Tool\GetDailyEnergyTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** get_daily_energy (MAG-746): the same fields as the endpoint. */
class DailyEnergyToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-25T09:00:00+00:00')));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/daily_energy.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function call(string $from, string $to): array
    {
        $tool = self::getContainer()->get(GetDailyEnergyTool::class);
        \assert($tool instanceof GetDailyEnergyTool);

        return json_decode($tool($from, $to), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        self::assertArrayHasKey('error', $this->call('2026-10-20', '2026-10-20'));
    }

    public function testItReturnsTheConsolidatedDaysOfTheUser(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        self::assertSame(
            ['days' => [['date' => '2026-10-20', 'steps' => 8000, 'activeKcal' => 400, 'bmrKcal' => 1648, 'workoutKcal' => 300, 'stepsKcal' => 100, 'expendedKcal' => 2048]]],
            $this->call('2026-10-20', '2026-10-20'),
        );
    }

    public function testABadPeriodIsRefused(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        self::assertArrayHasKey('error', $this->call('2026-10-21', '2026-10-20'));
        self::assertArrayHasKey('error', $this->call('nope', '2026-10-20'));
    }
}
