<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\HealthDay;
use Maggie\Health\Enum\HealthDayAuthor;
use Maggie\Health\Mcp\Tool\GetHealthDayTool;
use Maggie\Health\Mcp\Tool\UpdateHealthDayTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** get_health_day and update_health_day (MAG-750): the evening review writes mood, fatigue and a note. */
class HealthDayToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-25T21:42:00+01:00')));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/health_day.yaml');
        $this->resetMercure();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function get(string $date): array
    {
        $tool = self::getContainer()->get(GetHealthDayTool::class);
        \assert($tool instanceof GetHealthDayTool);

        return json_decode($tool($date), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function update(mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(UpdateHealthDayTool::class);
        \assert($tool instanceof UpdateHealthDayTool);

        return json_decode($tool(...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function stored(string $date): ?HealthDay
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em->getRepository(HealthDay::class)->findOneBy(['user' => $this->getFixture('test_user'), 'date' => new \DateTimeImmutable($date)]);
    }

    public function testWithoutAUserBothAnswerAnError(): void
    {
        self::assertArrayHasKey('error', $this->get('2026-10-20'));
        self::assertArrayHasKey('error', $this->update(date: '2026-10-20', mood: 7));
        self::assertNull($this->stored('2026-10-20')?->getMood());
    }

    public function testItReadsTheUsersDay(): void
    {
        $this->loginFixtureUser();

        $day = $this->get('2026-10-20')['day'];

        self::assertSame(72.4, $day['weightKg']);
        self::assertSame(78, $day['eatenKcal']);
        self::assertSame(32, $day['sportMinutes']);
    }

    public function testABadDateIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->get('demain'));
        self::assertSame('date', $this->update(date: '2026-13-01', mood: 7)['field']);
    }

    public function testMaggieWritesTheEveningAnswers(): void
    {
        $this->loginFixtureUser();

        $result = $this->update(date: '2026-10-25', mood: 7, fatigue: 4, note: 'Journée chargée mais bonne.');

        self::assertTrue($result['success']);
        self::assertSame('maggie', $result['day']['notedBy']);
        $day = $this->stored('2026-10-25');
        self::assertSame(7, $day?->getMood());
        self::assertSame(4, $day?->getFatigue());
        self::assertSame('Journée chargée mais bonne.', $day?->getNote());
        self::assertSame(HealthDayAuthor::Maggie, $day?->getNotedBy());
        self::assertSame('2026-10-25T21:42:00+01:00', $day?->getNotedAt()?->setTimezone(new \DateTimeZone('Europe/Paris'))->format(\DATE_ATOM));
        $this->assertMercureUpdatePublished('/api/health/days/2026-10-25');
    }

    public function testAScoreOutOfRangeIsRefusedWithTheDayAsItIs(): void
    {
        $this->loginFixtureUser();

        $result = $this->update(date: '2026-10-20', mood: 11);

        self::assertSame('mood', $result['field']);
        self::assertSame(72.4, $result['current']['weightKg']);
        self::assertNull($this->stored('2026-10-20')?->getMood());
        $this->assertMercureUpdateCount(0);
    }

    public function testNothingToWriteIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->update(date: '2026-10-20'));
    }

    public function testAnotherUsersDayIsNeverReadNorWritten(): void
    {
        $this->loginFixtureUser('other_user');

        self::assertEquals(95.0, $this->get('2026-10-20')['day']['weightKg']);
        $this->update(date: '2026-10-20', mood: 3);
        self::assertNull($this->stored('2026-10-20')?->getMood());
    }
}
