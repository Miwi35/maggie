<?php

namespace Maggie\Calendar\Tests\Service;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Entity\Period;
use Maggie\Calendar\Service\DayContext;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** The context of a day (MAG-811): weekend, and the periods covering it, series unfolded. */
class DayContextTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/PeriodApiTest.yaml');
    }

    /** @return list<string> */
    private function names(string $day): array
    {
        $service = self::getContainer()->get(DayContext::class);
        \assert($service instanceof DayContext);
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $paris = new \DateTimeZone('Europe/Paris');

        return array_map(
            static fn ($period) => $period->getName(),
            $service->of($user, new \DateTimeImmutable($day, $paris))->periods,
        );
    }

    private function weekend(string $day): bool
    {
        $service = self::getContainer()->get(DayContext::class);
        \assert($service instanceof DayContext);
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);

        return $service->of($user, new \DateTimeImmutable($day, new \DateTimeZone('Europe/Paris')))->weekend;
    }

    public function testSaturdayIsWeekend(): void
    {
        self::assertTrue($this->weekend('2037-03-07'));
        self::assertTrue($this->weekend('2037-03-08'));
        self::assertFalse($this->weekend('2037-03-10'));
    }

    public function testAFortnightlyCustodyCoversItsFirstAndLastDayOnly(): void
    {
        // Friday 6 to Friday 13 March, then the 20th to the 27th.
        self::assertSame(['Garde de Léo'], $this->names('2037-03-06'));
        self::assertSame(['Garde de Léo'], $this->names('2037-03-10'));
        self::assertSame(['Garde de Léo'], $this->names('2037-03-13'));
        self::assertSame([], $this->names('2037-03-14'));
        self::assertSame([], $this->names('2037-03-19'));
        self::assertSame(['Garde de Léo'], $this->names('2037-03-20'));
    }

    public function testAOneDayAllDayPeriodCoversThatDayOnly(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $period = $this->getFixture('vacances');
        \assert($period instanceof Period);
        $period->scheduleAllDay(new \DateTimeImmutable('2037-05-05'));
        $em->flush();

        self::assertNotContains('Vacances de printemps', $this->names('2037-05-04'));
        self::assertContains('Vacances de printemps', $this->names('2037-05-05'));
        self::assertNotContains('Vacances de printemps', $this->names('2037-05-06'));
    }
}
