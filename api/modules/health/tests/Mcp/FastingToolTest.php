<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Health\Mcp\Tool\GetFastingTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** get_fasting (MAG-770): the same fields as the endpoint, the user's own journal. */
class FastingToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-11-10T15:00:00+01:00')));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/fasting.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function call(string $date): array
    {
        $tool = self::getContainer()->get(GetFastingTool::class);
        \assert($tool instanceof GetFastingTool);

        return json_decode($tool($date), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        self::assertArrayHasKey('error', $this->call('2026-11-10'));
    }

    public function testItReadsTheUsersOpenWindow(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        $data = $this->call('2026-11-10');

        self::assertSame('open', $data['state']);
        self::assertSame('20:00', $data['windowEnd']);
        self::assertSame(965, $data['nightFastMinutes']);
    }

    public function testABadDateIsRefused(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        self::assertArrayHasKey('error', $this->call('10/11/2026'));
    }
}
