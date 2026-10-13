<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Entity\TreasurySettings;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\UseCase\GetTreasuryOutlook;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The treasury outlook (MAG-279) reads only what is entered (shape decision
 * 17): the checking balance, the planned and committed movements to come and
 * the expected salary — every balance of the projection is recomputed here
 * from the lines, by hand.
 *
 * The salary is expected on the 25th, the reference is 4 950 € declared; the
 * Courant is at -120 €, the Livret A at 6 000 €, the PEA at 2 000 € and the
 * cushion account « Matelas » at 9 000 €.
 */
class GetTreasuryOutlookTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SavingsAdvancesWorld;

    private GetTreasuryOutlook $outlook;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->outlook = self::getContainer()->get(GetTreasuryOutlook::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('treasury_outlook.yaml');
    }

    /** @return array<string, mixed> */
    private function readOn(string $today, string $as = 'test_user'): array
    {
        return $this->outlook->execute($this->user($as), new \DateTimeImmutable($today));
    }

    private function planned(string $accountRef, int $amountCents, string $bookedAt, TransactionStatus $status = TransactionStatus::Planned, string $label = 'Prévu'): void
    {
        $this->add($accountRef, $amountCents, $bookedAt, label: $label)->setStatus($status);
        $this->flushWithoutTransactionEffects($this->em);
    }

    private function balance(string $accountRef, int $balanceCents): void
    {
        $account = $this->getFixture($accountRef);
        \assert($account instanceof Account);
        $account->setBalanceCents($balanceCents);
        $this->em->flush();
    }

    private function overdraftLimit(int $cents): void
    {
        $settings = $this->getFixture('settings');
        \assert($settings instanceof TreasurySettings);
        $settings->setOverdraftLimitCents($cents);
        $this->em->flush();
    }

    /** @param array<string, mixed> $outlook */
    private function rowOn(array $outlook, string $date): array
    {
        foreach ($outlook['projection'] as $row) {
            if ($row['date'] === $date) {
                return $row;
            }
        }
        self::fail("No projection row on {$date}");
    }

    /** Case 1: expected on the 25th, nothing since the 25th of last month, the Courant at -120 €. */
    public function testAThirtyDayDelayForecastsTheOverdraftAndAdvisesTheLargestSavings(): void
    {
        $this->planned('checking', -5000, '2026-10-27', TransactionStatus::Committed, 'Assurance');
        $this->planned('checking', -30000, '2026-10-31', label: 'Loyer');

        $outlook = $this->readOn('2026-10-25');

        $salary = $outlook['salary'];
        self::assertTrue($salary['isConfigured']);
        self::assertTrue($salary['isLate']);
        self::assertFalse($salary['isReceived']);
        self::assertSame(30, $salary['delayDays']);
        self::assertSame('2026-09-25', $salary['expectedOn']);
        self::assertSame(495000, $salary['referenceCents']);
        self::assertSame(495000, $salary['shortfallCents']);
        self::assertSame(-12000, $outlook['balanceCents']);

        self::assertTrue($outlook['overdraft']['isForecast']);
        self::assertSame('2026-10-25', $outlook['overdraft']['onDate']);
        self::assertSame(-47000, $outlook['overdraft']['worstBalanceCents']);
        self::assertSame(0, $outlook['overdraft']['limitCents']);

        $advice = $outlook['advice'];
        self::assertSame(47000, $advice['amountCents']);
        self::assertSame((string) $this->getFixture('livret')->getId(), $advice['sourceAccountId']);
        self::assertSame('Livret A', $advice['sourceAccountName']);
        self::assertSame(47000, $advice['sourceAmountCents']);
        self::assertFalse($advice['isCushion']);
        self::assertSame([], $advice['alternatives']);
        self::assertSame(0, $advice['uncoveredCents']);
        self::assertFalse($advice['touchesCushion']);

        // Late: the balance before the salary is today's.
        self::assertSame(['onDate' => '2026-10-25', 'balanceCents' => -12000], $outlook['balanceBeforeSalary']);
    }

    public function testEveryBalanceOfTheProjectionIsTheDayBeforePlusTheDaysMovements(): void
    {
        $this->balance('checking', 20000);
        $this->planned('checking', -5000, '2026-10-27', TransactionStatus::Committed, 'Assurance');
        $this->planned('checking', 12000, '2026-10-29', label: 'Remboursement mutuelle');
        $this->planned('checking', -30000, '2026-10-31', label: 'Loyer');
        $this->planned('checking', -1000, '2026-10-31', label: 'Abonnement');

        $outlook = $this->readOn('2026-10-25');

        $previous = $outlook['balanceCents'];
        foreach ($outlook['projection'] as $row) {
            self::assertSame($previous + array_sum(array_column($row['movements'], 'amountCents')), $row['balanceCents'], $row['date']);
            $previous = $row['balanceCents'];
        }
        self::assertSame(15000, $this->rowOn($outlook, '2026-10-27')['balanceCents']);
        self::assertSame(['Loyer', 'Abonnement'], array_column($this->rowOn($outlook, '2026-10-31')['movements'], 'label'));
        self::assertSame(-4000, $this->rowOn($outlook, '2026-10-31')['balanceCents']);
        $committed = $this->rowOn($outlook, '2026-10-27')['movements'][0];
        self::assertSame('committed', $committed['status']);
        self::assertSame('Courant', $committed['accountName']);
        // Positive today: the overdraft is forecast on the day it starts.
        self::assertSame('2026-10-31', $outlook['overdraft']['onDate']);
        self::assertSame(4000, $outlook['advice']['amountCents']);
    }

    public function testTheProjectionStopsTheEveOfTheExpectedSalary(): void
    {
        $this->salary(495000, '2026-09-25');
        $this->balance('checking', 100000);
        $this->planned('checking', -20000, '2026-10-24', label: 'Avant le salaire');
        $this->planned('checking', -90000, '2026-10-25', label: 'Le jour du salaire');

        $outlook = $this->readOn('2026-10-20');

        self::assertFalse($outlook['salary']['isLate']);
        self::assertSame('2026-10-25', $outlook['salary']['expectedOn']);
        self::assertSame(['2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24'], array_column($outlook['projection'], 'date'));
        self::assertSame(['onDate' => '2026-10-24', 'balanceCents' => 80000], $outlook['balanceBeforeSalary']);
        self::assertFalse($outlook['overdraft']['isForecast']);
        self::assertNull($outlook['advice']);
    }

    public function testALateSalaryIsProjectedToTheNextExpectedDayAndNeverPastSixtyDays(): void
    {
        $this->planned('checking', -1000, '2026-11-24', label: 'Dernier jour');
        $this->planned('checking', -1000, '2026-11-25', label: 'Après');

        $outlook = $this->readOn('2026-10-25');

        self::assertCount(31, $outlook['projection']);
        self::assertLessThanOrEqual(GetTreasuryOutlook::MAX_DAYS, \count($outlook['projection']));
        self::assertSame('2026-10-25', $outlook['projection'][0]['date']);
        self::assertSame('2026-11-24', $outlook['projection'][30]['date']);
        self::assertSame(-13000, $outlook['projection'][30]['balanceCents']);
    }

    public function testAReceivedSalaryAnnouncesNoOverdraftAndAdvisesNothing(): void
    {
        $this->salary(495000, '2026-09-25');
        $this->salary(495000, '2026-10-25');
        $this->balance('checking', 300000);
        $this->planned('checking', -50000, '2026-11-01', label: 'Loyer');

        $outlook = $this->readOn('2026-10-27');

        self::assertTrue($outlook['salary']['isReceived']);
        self::assertFalse($outlook['salary']['isLate']);
        self::assertSame(0, $outlook['salary']['delayDays']);
        self::assertSame('2026-11-25', $outlook['salary']['expectedOn']);
        self::assertFalse($outlook['overdraft']['isForecast']);
        self::assertNull($outlook['overdraft']['onDate']);
        self::assertNull($outlook['advice']);
        self::assertSame('2026-11-24', $outlook['projection'][array_key_last($outlook['projection'])]['date']);
        self::assertSame(['onDate' => '2026-11-24', 'balanceCents' => 250000], $outlook['balanceBeforeSalary']);
    }

    /** Once the salary is in, a dip is a budget matter: shown, but no advance from savings is advised. */
    public function testAfterTheSalaryADipIsStillShownButNoAdvanceIsAdvised(): void
    {
        $this->salary(495000, '2026-09-25');
        $this->salary(495000, '2026-10-25');
        $this->balance('checking', 10000);
        $this->planned('checking', -50000, '2026-11-01', label: 'Loyer');

        $outlook = $this->readOn('2026-10-27');

        self::assertTrue($outlook['overdraft']['isForecast']);
        self::assertSame('2026-11-01', $outlook['overdraft']['onDate']);
        self::assertNull($outlook['advice']);
    }

    public function testWhenNoSourceSufficesAloneTheAlternativesMakeUpTheRestCushionLast(): void
    {
        $this->planned('checking', -1000000, '2026-10-30', label: 'Travaux');

        $advice = $this->readOn('2026-10-25')['advice'];

        self::assertSame(1012000, $advice['amountCents']);
        self::assertSame('Livret A', $advice['sourceAccountName']);
        self::assertSame(600000, $advice['sourceAmountCents']);
        self::assertSame(600000, $advice['sourceBalanceCents']);
        self::assertSame(['PEA', 'Matelas'], array_column($advice['alternatives'], 'accountName'));
        self::assertSame([200000, 212000], array_column($advice['alternatives'], 'amountCents'));
        self::assertSame([false, true], array_column($advice['alternatives'], 'isCushion'));
        self::assertTrue($advice['touchesCushion']);
        self::assertSame(0, $advice['uncoveredCents']);
    }

    public function testWhenAllTheSavingsFallShortTheMissingTotalIsSaid(): void
    {
        $this->planned('checking', -2000000, '2026-10-30', label: 'Travaux');

        $advice = $this->readOn('2026-10-25')['advice'];

        self::assertSame(2012000, $advice['amountCents']);
        self::assertSame([200000, 900000], array_column($advice['alternatives'], 'amountCents'));
        self::assertSame(312000, $advice['uncoveredCents']);
    }

    public function testTheOnlySourceLeftBeingTheCushionItIsProposedAndFlagged(): void
    {
        $this->balance('livret', 0);
        $this->balance('pea', 0);

        $advice = $this->readOn('2026-10-25')['advice'];

        self::assertSame('Matelas', $advice['sourceAccountName']);
        self::assertSame(12000, $advice['sourceAmountCents']);
        self::assertTrue($advice['isCushion']);
        self::assertTrue($advice['touchesCushion']);
        self::assertSame([], $advice['alternatives']);
    }

    public function testWithoutAnExpectedDayNothingIsLateNorForecast(): void
    {
        $outlook = $this->readOn('2026-10-25', 'bare_user');

        self::assertFalse($outlook['salary']['isConfigured']);
        self::assertFalse($outlook['salary']['isLate']);
        self::assertSame(0, $outlook['salary']['delayDays']);
        self::assertNull($outlook['salary']['expectedOn']);
        self::assertSame(-50000, $outlook['balanceCents']);
        self::assertSame([], $outlook['projection']);
        self::assertNull($outlook['balanceBeforeSalary']);
        self::assertFalse($outlook['overdraft']['isForecast']);
        self::assertNull($outlook['advice']);
    }

    public function testTheOverdraftIsOnlyAnnouncedBelowTheAllowedLimit(): void
    {
        $this->overdraftLimit(30000);
        $this->planned('checking', -18000, '2026-10-28', label: 'Courses');

        $within = $this->readOn('2026-10-25');

        self::assertSame(-30000, $within['overdraft']['worstBalanceCents']);
        self::assertSame(30000, $within['overdraft']['limitCents']);
        self::assertFalse($within['overdraft']['isForecast']);
        self::assertNull($within['advice']);

        $this->planned('checking', -5000, '2026-11-02', label: 'Essence');

        $below = $this->readOn('2026-10-25');

        self::assertTrue($below['overdraft']['isForecast']);
        self::assertSame('2026-11-02', $below['overdraft']['onDate']);
        self::assertSame(5000, $below['advice']['amountCents']);
    }

    public function testOnlyPlannedAndCommittedMovementsOfOpenCheckingAccountsToComeCount(): void
    {
        $this->planned('checking', -1000, '2026-10-24', label: 'Prévu passé');
        $this->planned('checking', -2000, '2026-10-26', TransactionStatus::Spent, 'Déjà débité');
        $this->planned('checking', -3000, '2026-10-26', TransactionStatus::ToArbitrate, 'À arbitrer');
        $this->planned('livret', -4000, '2026-10-26', label: 'Sur le livret');
        $this->planned('other_checking', -5000, '2026-10-26', label: 'Chez l\'autre');
        $this->planned('checking', -6000, '2026-10-26', label: 'Compté');

        $outlook = $this->readOn('2026-10-25');

        self::assertSame(['Compté'], array_column($this->rowOn($outlook, '2026-10-26')['movements'], 'label'));
        self::assertSame(-18000, $outlook['overdraft']['worstBalanceCents']);
    }

    public function testAClosedCheckingAccountLeavesTheBalanceAndTheProjection(): void
    {
        $closed = (new Account())->setName('Ancien courant')->setBalanceCents(-90000)->setUser($this->user())->setClosedAt(new \DateTimeImmutable('2026-01-01'));
        $this->em->persist($closed);
        $this->em->persist((new Transaction())->setUser($this->user())->setAccount($closed)->setAmountCents(-7000)
            ->setBookedAt(new \DateTimeImmutable('2026-10-26'))->setLabel('Sur le compte clos')->setStatus(TransactionStatus::Planned));
        $this->flushWithoutTransactionEffects($this->em);

        $outlook = $this->readOn('2026-10-25');

        self::assertSame(-12000, $outlook['balanceCents']);
        self::assertSame([], $this->rowOn($outlook, '2026-10-26')['movements']);
    }

    public function testEachUserOnlySeesHisOwnTreasury(): void
    {
        $this->planned('other_checking', -100000, '2026-10-28', label: 'Chez l\'autre');

        $mine = $this->readOn('2026-10-25');
        $theirs = $this->readOn('2026-10-25', 'other_user');

        self::assertSame(-12000, $mine['balanceCents']);
        self::assertSame(-12000, $mine['overdraft']['worstBalanceCents']);
        self::assertNotContains('Livret de l\'autre', [$mine['advice']['sourceAccountName'], ...array_column($mine['advice']['alternatives'], 'accountName')]);
        self::assertSame(-50000, $theirs['overdraft']['worstBalanceCents']);
        self::assertSame('Livret de l\'autre', $theirs['advice']['sourceAccountName']);
    }

    public function testTheOutstandingAdvancesAreWhatIsLeftToGiveBack(): void
    {
        [$advance] = $this->transfer('livret', 'checking', 150000, '2026-10-10', TransferKind::Advance);
        $this->repay($advance, 'livret', 50000, '2026-10-20');

        self::assertSame(100000, $this->readOn('2026-10-25')['outstandingAdvancesCents']);
    }
}
