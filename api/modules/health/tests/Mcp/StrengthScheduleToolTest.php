<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Health\Mcp\Tool\ManageWorkoutsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** manage_workouts refuses a week that cannot take a strength session and lists the week (MAG-776). */
class StrengthScheduleToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/workout.yaml');
        $this->loginUser($this->getFixture('test_user'));
    }

    /** @return array<string, mixed> */
    private function plan(string $startedAt): array
    {
        $tool = self::getContainer()->get(ManageWorkoutsTool::class);
        \assert($tool instanceof ManageWorkoutsTool);

        return json_decode($tool('create', type: 'strength', title: 'Force', startedAt: $startedAt, durationMinutes: 40, source: 'planned'), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testAFourthSessionIsRefusedWithTheWeekAsCurrent(): void
    {
        foreach (['2026-11-02T18:00:00+01:00', '2026-11-04T18:00:00+01:00', '2026-11-06T18:00:00+01:00'] as $start) {
            self::assertTrue($this->plan($start)['success']);
        }

        $data = $this->plan('2026-11-08T10:00:00+01:00');

        self::assertArrayNotHasKey('success', $data);
        self::assertSame('startedAt', $data['field']);
        self::assertCount(3, $data['current']);
    }
}
