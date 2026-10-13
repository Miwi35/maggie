<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\TreasurySettings;
use Maggie\Finance\UseCase\GetSalaryDelay;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The salary delay is a period (shape decision 13): it opens the day after the
 * expected day and closes at 90 % of the reference salary, the credits filling
 * the open months oldest first. The owner's three cases are here by name.
 *
 * The salary is expected on the 25th, the reference is 4 950 € declared.
 */
class GetSalaryDelayTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SavingsAdvancesWorld;

    private const int REFERENCE = 495000;

    private GetSalaryDelay $delay;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->delay = self::getContainer()->get(GetSalaryDelay::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('savings_advances.yaml');
    }

    /** @return array<string, mixed> */
    private function readOn(string $today, string $as = 'test_user'): array
    {
        return $this->delay->execute($this->user($as), new \DateTimeImmutable($today));
    }

    /** Case 1: expected on the 25th, nothing received by the 25th of the next month. */
    public function testThirtyDaysWithoutSalaryIsAThirtyDayDelayShortOfTheWholeReference(): void
    {
        $delay = $this->readOn('2026-10-25');

        self::assertTrue($delay['isConfigured']);
        self::assertTrue($delay['isLate']);
        self::assertSame(30, $delay['delayDays']);
        self::assertSame(self::REFERENCE, $delay['shortfallCents']);

        [$september, $october] = $delay['months'];
        self::assertSame('2026-09-25', $september['expectedOn']);
        self::assertSame(30, $september['delayDays']);
        self::assertSame(self::REFERENCE, $september['shortfallCents']);
        self::assertFalse($september['isReceived']);
        // The current month is expected today: not late yet.
        self::assertFalse($october['isDue']);
        self::assertSame(0, $october['delayDays']);
        self::assertSame(0, $october['shortfallCents']);
    }

    /** Case 2: one credit at ~200 % on the 26th closes September, then October. */
    public function testTwoMonthsPaidAtOnceCloseBothOldestFirst(): void
    {
        $this->salary(2 * self::REFERENCE, '2026-10-26');

        $delay = $this->readOn('2026-10-27');

        self::assertFalse($delay['isLate']);
        self::assertSame(0, $delay['delayDays']);
        self::assertSame(0, $delay['shortfallCents']);

        [$september, $october] = $delay['months'];
        self::assertTrue($september['isReceived']);
        self::assertSame(self::REFERENCE, $september['receivedCents']);
        self::assertSame('2026-10-26', $september['receivedOn']);
        self::assertTrue($october['isReceived']);
        self::assertSame(self::REFERENCE, $october['receivedCents']);
    }

    public function testOneSalaryLateClosesTheOldestMonthAndLeavesTheCurrentOneToCome(): void
    {
        $this->salary(self::REFERENCE, '2026-10-26');

        [$september, $october] = $this->readOn('2026-10-27')['months'];

        self::assertTrue($september['isReceived']);
        self::assertFalse($october['isReceived']);
        self::assertSame(2, $october['delayDays']);
        self::assertSame(self::REFERENCE, $october['shortfallCents']);
    }

    public function testAPartialSalaryLeavesTheDelayOpenAndReducesTheShortfall(): void
    {
        $this->salary(297000, '2026-10-01');

        $delay = $this->readOn('2026-10-20');

        self::assertTrue($delay['isLate']);
        self::assertSame(25, $delay['delayDays']);
        self::assertSame(self::REFERENCE - 297000, $delay['shortfallCents']);
        self::assertSame(297000, $delay['months'][0]['receivedCents']);
    }

    /** 90 %, not 100 %: a transfer docked a fee still closes the month. */
    public function testNinetyPercentOfTheReferenceClosesTheMonth(): void
    {
        $this->salary(445500, '2026-09-25');

        self::assertFalse($this->readOn('2026-10-01')['isLate']);
    }

    /** A month closed at 92 % takes nothing more from the next month's salary. */
    public function testTwoSalariesInARowAtNinetyTwoPercentLeaveNothingLate(): void
    {
        $this->salary(455400, '2026-09-25');
        $this->salary(455400, '2026-10-25');

        $delay = $this->readOn('2026-10-26');

        self::assertFalse($delay['isLate']);
        self::assertSame([455400, 455400], array_column($delay['months'], 'receivedCents'));
    }

    /** Case 3: a bonus is in « Primes », not in « Salaire »: it closes nothing. */
    public function testABonusTheSameDayAsTheSalaryClosesNoDelay(): void
    {
        $this->salary(297000, '2026-09-25');
        $this->add('checking', 198000, '2026-09-25', 'bonus', 'VIR ACME PRIME');

        $delay = $this->readOn('2026-10-01');

        self::assertTrue($delay['isLate']);
        self::assertSame(297000, $delay['months'][0]['receivedCents']);
        self::assertSame(self::REFERENCE - 297000, $delay['shortfallCents']);
    }

    public function testASalaryPaidOnTimeLeavesNothingLate(): void
    {
        $this->salary(self::REFERENCE, '2026-09-25');

        $delay = $this->readOn('2026-10-01');

        self::assertFalse($delay['isLate']);
        self::assertSame('2026-09-25', $delay['months'][0]['receivedOn']);
    }

    /** Paid on Friday the 23rd because the 25th is a Sunday: on time. */
    public function testASalaryPaidAFewDaysEarlyStillPaysItsMonth(): void
    {
        $this->salary(self::REFERENCE, '2026-09-23');

        self::assertFalse($this->readOn('2026-10-01')['isLate']);
    }

    public function testAnExpectedDayPastTheEndOfTheMonthFallsOnItsLastDay(): void
    {
        $settings = $this->getFixture('settings');
        \assert($settings instanceof TreasurySettings);
        $settings->setSalaryExpectedDay(31);
        $this->em->flush();

        $delay = $this->readOn('2026-10-05');

        self::assertSame('2026-09-30', $delay['months'][0]['expectedOn']);
        self::assertSame(5, $delay['delayDays']);
    }

    public function testWithoutAnExpectedDayNothingIsLate(): void
    {
        $delay = $this->readOn('2026-10-25', 'bare_user');

        self::assertFalse($delay['isConfigured']);
        self::assertFalse($delay['isLate']);
        self::assertSame(0, $delay['delayDays']);
        self::assertSame([], $delay['months']);
        self::assertNull($delay['salaryExpectedDay']);
    }

    public function testWithoutAReferenceSalaryNothingIsLate(): void
    {
        $delay = $this->readOn('2026-10-25', 'unknown_reference_user');

        self::assertFalse($delay['isConfigured']);
        self::assertFalse($delay['isLate']);
        self::assertSame(25, $delay['salaryExpectedDay']);
        self::assertNull($delay['referenceCents']);
    }

    public function testAnotherUsersSalaryClosesNothing(): void
    {
        // Even filed under this user's « Salaire »: the line is not his.
        $this->add('other_checking', self::REFERENCE, '2026-09-25', 'salary', 'Salaire de l\'autre');

        self::assertTrue($this->readOn('2026-10-01')['isLate']);
    }
}
