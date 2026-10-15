<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\UseCase\GetRecurringOperations;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * What the owner reads of the recurring operations (MAG-306): cost, next due
 * date, a due date that did not come, a price that moved, the attachments
 * waiting for a yes — all of it a read, so every test also checks that the
 * database is left as it was.
 *
 * The world is `recurring_overview.yaml`, read on 2027-05-20.
 */
class GetRecurringOperationsTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private const string TODAY = '2027-05-20';

    private GetRecurringOperations $overview;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->overview = self::getContainer()->get(GetRecurringOperations::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('recurring_overview.yaml');
    }

    /** @return array{operations: list<array<string, mixed>>, proposals: list<array<string, mixed>>, totals: array<string, int>} */
    private function read(string $today = self::TODAY, string $as = 'test_user'): array
    {
        /** @var User $user */
        $user = $this->getFixture($as);

        return $this->overview->execute($user, new \DateTimeImmutable($today));
    }

    /** @return array<string, mixed> */
    private function operation(string $fixture, string $today = self::TODAY): array
    {
        $id = (string) $this->getFixture($fixture)->getId();

        foreach ($this->read($today)['operations'] as $operation) {
            if ($operation['id'] === $id) {
                return $operation;
            }
        }

        self::fail(sprintf('The overview does not list "%s".', $fixture));
    }

    public function testItRendersTheIdentityOfASeries(): void
    {
        $flixo = $this->operation('flixo');

        self::assertSame('Abonnement Flixo', $flixo['label']);
        self::assertSame((string) $this->getFixture('subscriptions')->getId(), $flixo['categoryId']);
        self::assertSame('Abonnements', $flixo['categoryName']);
        self::assertSame((string) $this->getFixture('checking')->getId(), $flixo['accountId']);
        self::assertSame('Courant', $flixo['accountName']);
        self::assertSame('Crédit Agricole', $flixo['accountBank']);
        self::assertSame('monthly', $flixo['period']);
        self::assertSame('fixed_day', $flixo['dayRule']);
        self::assertSame(-1799, $flixo['referenceAmountCents']);
        self::assertSame('measured', $flixo['referenceSource']);
        self::assertSame('EUR', $flixo['currency']);
        self::assertSame(5, $flixo['attachmentCount']);
        self::assertSame('2027-05-12', $flixo['lastAttachedOn']);
    }

    public function testAnAccountWithoutBankReadsTheBankOfItsLiveConnection(): void
    {
        $connection = new BankConnection();
        $connection->setUser($this->getFixture('test_user'));
        $connection->setBankName('Revolut');
        $connection->activate('session-1', new \DateTimeImmutable('+60 days'));
        $this->em->persist($connection);
        $this->getFixture('checking')->setBank(null)->setBankConnection($connection);
        $this->em->flush();

        self::assertSame('Revolut', $this->operation('flixo')['accountBank']);
    }

    public function testAnAccountTypedInWithoutBankHasNone(): void
    {
        $this->getFixture('checking')->setBank(null);
        $this->em->flush();

        self::assertNull($this->operation('flixo')['accountBank']);
    }

    public function testTheCostIsRightForTheFourPeriodicities(): void
    {
        // Monthly.
        self::assertSame(-1799, $this->operation('flixo')['monthlyCostCents']);
        self::assertSame(-21588, $this->operation('flixo')['yearlyCostCents']);
        // Quarterly: four a year, integer division by twelve.
        self::assertSame(-4000, $this->operation('insurance')['monthlyCostCents']);
        self::assertSame(-48000, $this->operation('insurance')['yearlyCostCents']);
        // Yearly: 350 € / 12 = 29,16 €, truncated, never rounded.
        self::assertSame(-2916, $this->operation('property_tax')['monthlyCostCents']);
        self::assertSame(-35000, $this->operation('property_tax')['yearlyCostCents']);
        // Weekly: 52 weeks.
        self::assertSame(-2166, $this->operation('newspaper')['monthlyCostCents']);
        self::assertSame(-26000, $this->operation('newspaper')['yearlyCostCents']);
    }

    public function testTheNextOccurrenceSkipsTheOnesAlreadySettled(): void
    {
        // May's 12th is settled by the debit of the 13th.
        self::assertSame('2027-06-12', $this->operation('flixo')['nextOccurrenceOn']);
        self::assertSame('2027-08-10', $this->operation('insurance')['nextOccurrenceOn']);
        self::assertSame('2027-09-15', $this->operation('property_tax')['nextOccurrenceOn']);
        self::assertSame('2027-05-24', $this->operation('newspaper')['nextOccurrenceOn']);
    }

    public function testTheNextOccurrenceHoldsTheLastDayOfShortMonths(): void
    {
        self::assertSame('2027-05-31', $this->operation('rent')['nextOccurrenceOn']);
        self::assertSame('2027-06-30', $this->operation('rent', '2027-06-01')['nextOccurrenceOn']);
        self::assertSame('2028-02-29', $this->operation('rent', '2028-02-01')['nextOccurrenceOn']);
    }

    public function testADueDateOfTodayIsStillAhead(): void
    {
        self::assertSame('2027-05-31', $this->operation('rent', '2027-05-31')['nextOccurrenceOn']);
    }

    public function testASettledOccurrenceTodayLooksToTheNextOne(): void
    {
        // Flixo's June 12th has not been paid: on the 12th it is still ahead.
        self::assertSame('2027-06-12', $this->operation('flixo', '2027-06-12')['nextOccurrenceOn']);
        // May's 12th is paid: on the 12th of May the next one is June's.
        self::assertSame('2027-06-12', $this->operation('flixo', '2027-05-12')['nextOccurrenceOn']);
    }

    public function testAMissedOccurrenceIsReportedBeyondTheDateTolerance(): void
    {
        // April's last day, 20 days ago, 5 days of tolerance, no line.
        self::assertSame(
            ['occurrenceOn' => '2027-04-30', 'daysOverdue' => 20],
            $this->operation('rent')['missedOccurrence'],
        );
    }

    public function testAnOccurrenceWithinTheToleranceIsNotMissed(): void
    {
        // Flixo's 12 June may still be debited until the 17th included...
        self::assertNull($this->operation('flixo', '2027-06-12')['missedOccurrence']);
        self::assertNull($this->operation('flixo', '2027-06-17')['missedOccurrence']);
        // ... and is missed on the 18th.
        self::assertSame(
            ['occurrenceOn' => '2027-06-12', 'daysOverdue' => 6],
            $this->operation('flixo', '2027-06-18')['missedOccurrence'],
        );
    }

    public function testASettledOccurrenceIsNotMissed(): void
    {
        self::assertNull($this->operation('flixo')['missedOccurrence']);
    }

    public function testAnOccurrenceWaitingAsAProposalIsNotMissed(): void
    {
        // May 1st of the gym: its debit came on the 2nd, dearer, and awaits a yes.
        self::assertNull($this->operation('gym')['missedOccurrence']);
    }

    public function testOnlyTheLatestDueDateIsLookedAt(): void
    {
        // The rent is also missing from the months before April: not news.
        self::assertSame('2027-04-30', $this->operation('rent')['missedOccurrence']['occurrenceOn']);
        // Once June's is past its tolerance, it replaces April's.
        self::assertSame(
            ['occurrenceOn' => '2027-05-31', 'daysOverdue' => 10],
            $this->operation('rent', '2027-06-10')['missedOccurrence'],
        );
    }

    public function testASeriesNotStartedYetMissesNothing(): void
    {
        self::assertNull($this->operation('property_tax')['missedOccurrence']);
    }

    public function testAnEndedSeriesMissesNothingAndHasNoNextOccurrence(): void
    {
        $old = $this->operation('old_gym');

        self::assertNull($old['missedOccurrence']);
        self::assertNull($old['nextOccurrenceOn']);
        self::assertSame('2027-03-31', $old['endsOn']);
    }

    public function testAPriceChangeIsRenderedWithItsPercent(): void
    {
        self::assertSame(
            ['previousCents' => -1349, 'currentCents' => -1799, 'percent' => 33, 'since' => '2027-04-12'],
            $this->operation('flixo')['priceChange'],
        );
    }

    public function testASeriesWhoseAmountDidNotMoveHasNoPriceChange(): void
    {
        $this->getFixture('flixo_apr')->setAmountCents(-1349);
        $this->getFixture('flixo_may')->setAmountCents(-1349);
        $this->flushWithoutTransactionEffects($this->em);

        self::assertNull($this->operation('flixo')['priceChange']);
    }

    public function testAPriceCutIsRenderedWithANegativePercent(): void
    {
        $this->getFixture('flixo_apr')->setAmountCents(-1000);
        $this->getFixture('flixo_may')->setAmountCents(-1000);
        $this->flushWithoutTransactionEffects($this->em);

        $change = $this->operation('flixo')['priceChange'];

        self::assertSame(-1349, $change['previousCents']);
        self::assertSame(-1000, $change['currentCents']);
        self::assertSame(-25, $change['percent']);
    }

    public function testARaiseOnAnIncomeIsAPositivePercent(): void
    {
        self::assertSame(
            ['previousCents' => 250000, 'currentCents' => 325000, 'percent' => 30, 'since' => '2027-05-05'],
            $this->operation('salary')['priceChange'],
        );
    }

    public function testAMoveWithinTheAmountToleranceIsNotAPriceChange(): void
    {
        // 13,49 € to 15,99 €: +18 %, inside the 20 % the series expects.
        $this->getFixture('flixo_apr')->setAmountCents(-1599);
        $this->getFixture('flixo_may')->setAmountCents(-1599);
        $this->flushWithoutTransactionEffects($this->em);

        self::assertNull($this->operation('flixo')['priceChange']);
    }

    public function testAZeroCentLineBeforeThePriceIsNotDividedBy(): void
    {
        $this->getFixture('flixo_mar')->setAmountCents(0);
        $this->flushWithoutTransactionEffects($this->em);

        self::assertNull($this->operation('flixo')['priceChange']);
    }

    public function testASingleAttachmentIsNotAPriceChange(): void
    {
        self::assertNull($this->operation('power')['priceChange']);
    }

    public function testTheLastOccurrenceCarriesTheDeviationsOfItsAttachment(): void
    {
        $power = $this->operation('power')['lastOccurrence'];

        self::assertSame('2027-05-10', $power['occurrenceOn']);
        self::assertSame('2027-05-19', $power['bookedAt']);
        self::assertSame(9, $power['dateGapDays']);
        self::assertSame(-12500, $power['amountCents']);
        self::assertSame(-9000, $power['expectedAmountCents']);
        self::assertSame((string) $this->getFixture('power_may')->getId(), $power['transactionId']);
        self::assertSame(['late', 'amount_up'], $power['deviations']);
    }

    public function testAnAttachmentWithinBothTolerancesCarriesNoDeviation(): void
    {
        // May's Flixo came a day late at the price the series already had.
        $flixo = $this->operation('flixo')['lastOccurrence'];

        self::assertSame(1, $flixo['dateGapDays']);
        self::assertSame(-1799, $flixo['expectedAmountCents']);
        self::assertSame([], $flixo['deviations']);
    }

    public function testASeriesWithoutAttachmentHasNoLastOccurrence(): void
    {
        $rent = $this->operation('rent');

        self::assertNull($rent['lastOccurrence']);
        self::assertNull($rent['lastAttachedOn']);
        self::assertSame(0, $rent['attachmentCount']);
        self::assertNull($rent['priceChange']);
    }

    public function testThePendingProposalsAreListedWithTheirReason(): void
    {
        $proposals = $this->read()['proposals'];

        self::assertCount(1, $proposals);
        self::assertSame((string) $this->getFixture('gym_may_dearer')->getId(), $proposals[0]['transactionId']);
        self::assertSame('CB CLUB FORME 02/05', $proposals[0]['label']);
        self::assertSame('2027-05-02', $proposals[0]['bookedAt']);
        self::assertSame(-3990, $proposals[0]['amountCents']);
        self::assertSame((string) $this->getFixture('gym')->getId(), $proposals[0]['recurringOperationId']);
        self::assertSame('amount_up', $proposals[0]['reason']);
    }

    public function testAnEndedSeriesIsListedButLeftOutOfTheTotals(): void
    {
        $ids = array_column($this->read()['operations'], 'id');

        self::assertContains((string) $this->getFixture('old_gym')->getId(), $ids);
        // 8 series run; the ended one would have made 9 and -2500 more.
        self::assertSame(8, $this->read()['totals']['activeCount']);
        self::assertSame(-112881, $this->read()['totals']['monthlyCostCents']);
    }

    public function testTheTotalsSeparateCostsFromIncomes(): void
    {
        $totals = $this->read()['totals'];

        // flixo 1799 + insurance 4000 + tax 2916 + newspaper 2166 + rent 90000 + gym 3000 + power 9000.
        self::assertSame(-112881, $totals['monthlyCostCents']);
        // 21588 + 48000 + 35000 + 26000 + 1080000 + 36000 + 108000.
        self::assertSame(-1354588, $totals['yearlyCostCents']);
        self::assertSame(250000, $totals['monthlyIncomeCents']);
        self::assertSame(8, $totals['activeCount']);
    }

    public function testASeriesEndingTodayIsStillActive(): void
    {
        // old_gym ends on 2027-03-31: counted that day, left out from the next.
        self::assertSame(9, $this->read('2027-03-31')['totals']['activeCount']);
        self::assertSame(8, $this->read('2027-04-01')['totals']['activeCount']);
    }

    public function testNoSeriesGivesZeroTotalsAndEmptyLists(): void
    {
        $read = $this->read(self::TODAY, 'empty_user');

        self::assertSame([], $read['operations']);
        self::assertSame([], $read['proposals']);
        self::assertSame(
            ['monthlyCostCents' => 0, 'yearlyCostCents' => 0, 'monthlyIncomeCents' => 0, 'activeCount' => 0],
            $read['totals'],
        );
    }

    public function testAUserOnlyReadsTheirOwnSeries(): void
    {
        $read = $this->read(self::TODAY, 'other_user');

        self::assertSame(['Abonnement secret'], array_column($read['operations'], 'label'));
        self::assertSame(-999, $read['totals']['monthlyCostCents']);
        self::assertSame([], $read['proposals']);

        $mine = array_column($this->read()['operations'], 'label');
        self::assertNotContains('Abonnement secret', $mine);
    }

    public function testReadingWritesNothing(): void
    {
        $snapshot = fn (): array => [
            $this->em->getConnection()->fetchAllAssociative('SELECT * FROM recurring_operation ORDER BY id'),
            $this->em->getConnection()->fetchAllAssociative('SELECT * FROM transaction ORDER BY id'),
        ];
        $before = $snapshot();

        $this->read();
        $this->read('2027-06-20');

        self::assertSame($before, $snapshot());
    }
}
