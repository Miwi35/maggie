<?php

namespace Maggie\Health\Tests\Repository;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Maggie\Health\Repository\BodyMeasurementRepository;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** The day's weight (MAG-706): the first weighing, not excluded, of the local day. */
class BodyMeasurementRepositoryTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/body_measurement.yaml');
    }

    private function firstOfDay(string $day, string $zone = 'Europe/Paris', string $user = 'test_user'): ?string
    {
        $repository = self::getContainer()->get(BodyMeasurementRepository::class);
        \assert($repository instanceof BodyMeasurementRepository);
        $owner = $this->getFixture($user);
        \assert($owner instanceof User);

        $found = $repository->firstOfDay($owner, new \DateTimeImmutable($day, new \DateTimeZone($zone)));

        return null === $found ? null : (string) $found->getId();
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    public function testTheFirstOfTwoWeighingsOfTheDayWins(): void
    {
        self::assertSame($this->id('morning'), $this->firstOfDay('2026-10-21 21:00'));
    }

    public function testAnExcludedWeighingIsSkipped(): void
    {
        self::assertSame($this->id('imported'), $this->firstOfDay('2026-10-22'));
    }

    public function testADayWithoutWeighingIsNullAndNotTheDayBefores(): void
    {
        // The 19th has only a waist size, the 20th nothing at all.
        self::assertNull($this->firstOfDay('2026-10-19'));
        self::assertNull($this->firstOfDay('2026-10-20'));
    }

    public function testTheDayIsTheLocalDayOfTheGivenZone(): void
    {
        // 23:30 UTC on the 23rd is already 01:30 on the 24th in Paris.
        self::assertSame($this->id('boundary'), $this->firstOfDay('2026-10-24', 'Europe/Paris'));
        self::assertNull($this->firstOfDay('2026-10-23', 'Europe/Paris'));
        self::assertSame($this->id('boundary'), $this->firstOfDay('2026-10-23', 'UTC'));
        self::assertNull($this->firstOfDay('2026-10-24', 'UTC'));
    }

    public function testAnotherUsersWeighingIsNeverTheUsers(): void
    {
        self::assertSame($this->id('secret'), $this->firstOfDay('2026-10-23', 'Europe/Paris', 'other_user'));
        self::assertNull($this->firstOfDay('2026-10-21', 'Europe/Paris', 'other_user'));
    }
}
