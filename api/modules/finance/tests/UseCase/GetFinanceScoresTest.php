<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\UseCase\GetFinanceScores;
use Maggie\Finance\UseCase\GetPeriodBalance;
use Maggie\Finance\UseCase\PlanningPeriod;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The week and month scores and their rhythm — every debit spent, the recurring
 * ones expected, no verdict (MAG-416, MAG-643) — on the October 2026 of
 * `FinanceScoresWorld`. A pure read: no Mercure update and no
 * index to assert.
 */
class GetFinanceScoresTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use FinanceScoresWorld;

    private GetFinanceScores $useCase;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->useCase = self::getContainer()->get(GetFinanceScores::class);
        $this->loadFixtures($this->financeScoresFixture());
    }

    /** @return array<string, mixed> */
    private function scores(string $day, string $as = 'scores_user'): array
    {
        /** @var User $user */
        $user = $this->getFixture($as);

        return $this->useCase->execute($user, new \DateTimeImmutable($day));
    }

    /** @return list<string> */
    private static function codes(array $score): array
    {
        return array_column($score['reasons'], 'code');
    }

    public function testSpentIsEveryRealDebitAndExpectedOnlyTheRecurringOnes(): void
    {
        $this->spendGroceries('2026-10-03', 22000);
        // A category with no envelope and a line no category claims: both are money gone.
        $this->spendGroceries('2026-10-04', 5000, 'scores_sport');
        $this->leaveUnclassified('2026-10-06', -7000);

        $rhythm = $this->scores('2026-10-13')['month']['rhythm'];

        // 780 € paid + 220 € + 50 € + 70 €.
        self::assertSame(112000, $rhythm['spentCents']);
        // The rent (5th) and the energy (10th) are due; the gym (20th) is not yet.
        self::assertSame(78000, $rhythm['expectedSpentCents']);
        self::assertSame(81000, $rhythm['plannedCents']);
    }

    public function testSpentIsTheSameFigureAsThePeriodBalance(): void
    {
        $this->spendGroceries('2026-10-03', 22000);
        $this->spendGroceries('2026-10-04', 5000, 'scores_sport');
        $this->leaveUnclassified('2026-10-06', -7000);
        $this->leaveUnclassified('2026-10-07', -50000, TransferKind::Internal);
        $this->leaveUnclassified('2026-10-08', -4000, TransferKind::Rejected);
        $this->leaveUnclassified('2026-10-09', 90000);

        /** @var User $user */
        $user = $this->getFixture('scores_user');
        $balance = self::getContainer()->get(GetPeriodBalance::class)->execute($user, PlanningPeriod::parse('2026-Q4'));

        $rhythm = $this->scores('2026-10-31')['month']['rhythm'];

        self::assertSame(112000, $rhythm['spentCents']);
        self::assertSame($balance['months'][0]['spentCents'], $rhythm['spentCents']);
        self::assertSame(112000, $rhythm['curve'][30]['spentCents']);
    }

    public function testInternalTransfersRejectionsCreditsAndPlannedLinesAreNotSpent(): void
    {
        $this->leaveUnclassified('2026-10-03', -50000, TransferKind::Internal);
        $this->leaveUnclassified('2026-10-04', -4000, TransferKind::Rejected);
        $this->leaveUnclassified('2026-10-05', 90000);
        $this->leaveUnclassified('2026-10-06', -7000, status: TransactionStatus::Planned);
        $this->leaveUnclassified('2026-10-07', -3000, as: 'other_user');
        $this->spendGroceries('2026-10-14', 90000, status: TransactionStatus::Planned);

        $month = $this->scores('2026-10-13')['month']['rhythm'];

        self::assertSame(78000, $month['spentCents']);
        self::assertSame(0, $month['unclassifiedCount']);
        self::assertSame(0, $month['unclassifiedCents']);
    }

    public function testTheRhythmGivesNoVerdictWhateverIsSpent(): void
    {
        $this->spendGroceries('2026-10-03', 60000);
        $this->spendGroceries('2026-10-04', 55000, 'scores_sport');

        $scores = $this->scores('2026-10-13');

        foreach (['week', 'month'] as $period) {
            foreach (['status', 'label', 'budgetCents', 'remainingCents', 'expectedRemainingCents'] as $key) {
                self::assertArrayNotHasKey($key, $scores[$period]['rhythm']);
            }
        }
        // 1 490 € spent against 780 € expected: normal, and no reason says otherwise.
        self::assertSame(193000, $scores['month']['rhythm']['spentCents']);
        $codes = self::codes($scores['month']);
        self::assertSame([], array_intersect($codes, ['above_rhythm', 'below_rhythm', 'on_rhythm', 'total_budget_exceeded', 'week_budget_exceeded']));
    }

    public function testMoreSpentThanTheEnvelopesTotalIsNotRedWhenEveryEnvelopeHolds(): void
    {
        // 1 800 € spent in all against 1 500 € of envelopes, but the food envelope holds
        // and the rest has no envelope: the colour only reads the envelopes.
        $this->spendGroceries('2026-10-03', 60000);
        $this->spendGroceries('2026-10-04', 90000, 'scores_sport');

        $month = $this->scores('2026-10-13')['month'];

        self::assertSame(228000, $month['rhythm']['spentCents']);
        self::assertSame('green', $month['status']);
        self::assertNotContains('total_budget_exceeded', self::codes($month));
    }

    public function testAnExceededEnvelopeStillMakesTheMonthOrange(): void
    {
        $this->spendGroceries('2026-10-03', 80000);

        $month = $this->scores('2026-10-13')['month'];

        self::assertSame('orange', $month['status']);
        self::assertContains('optional_category_exceeded', self::codes($month));
    }

    public function testTheMonthIsGreenWhenItsEnvelopesAreAndLastYearWasMore(): void
    {
        $month = $this->scores('2026-10-13')['month'];

        self::assertSame('green', $month['status']);
        self::assertSame('Dans le vert', $month['label']);
    }

    public function testTheCurveHasOnePointPerDayAndTheDebitsAtTheirDate(): void
    {
        $this->spendGroceries('2026-10-03', 22000);

        $rhythm = $this->scores('2026-10-13')['month']['rhythm'];

        self::assertCount(31, $rhythm['curve']);
        self::assertSame('2026-10-01', $rhythm['curve'][0]['date']);
        self::assertSame('2026-10-31', $rhythm['curve'][30]['date']);

        // Planned debits step the expected curve on the day they fall, and nothing in between.
        self::assertSame(0, $rhythm['curve'][3]['expectedCents']);
        self::assertSame(70000, $rhythm['curve'][4]['expectedCents']);
        self::assertSame(70000, $rhythm['curve'][8]['expectedCents']);
        self::assertSame(78000, $rhythm['curve'][9]['expectedCents']);
        self::assertSame(78000, $rhythm['curve'][18]['expectedCents']);
        self::assertSame(81000, $rhythm['curve'][19]['expectedCents']);
        self::assertSame(81000, $rhythm['curve'][30]['expectedCents']);

        // The spending is known up to the day asked for, and nothing after it.
        self::assertSame(22000, $rhythm['curve'][3]['spentCents']);
        self::assertSame(92000, $rhythm['curve'][4]['spentCents']);
        self::assertSame(100000, $rhythm['curve'][12]['spentCents']);
        self::assertNull($rhythm['curve'][13]['spentCents']);

        self::assertSame(
            [
                ['date' => '2026-10-05', 'label' => 'Loyer', 'amountCents' => 70000],
                ['date' => '2026-10-10', 'label' => 'Énergie', 'amountCents' => 8000],
                ['date' => '2026-10-20', 'label' => 'Salle de sport', 'amountCents' => 3000],
            ],
            array_map(
                static fn (array $debit): array => array_diff_key($debit, ['recurringOperationId' => true]),
                $rhythm['debits'],
            ),
        );
        self::assertNotSame('', $rhythm['debits'][0]['recurringOperationId']);
    }

    public function testTheWeekHoldsOnlyItsOwnSpendingAndDebits(): void
    {
        // Monday 12 to Sunday 18: the rent and the energy fell earlier, the gym is next week.
        $this->spendGroceries('2026-10-12', 4000);
        $this->spendGroceries('2026-10-13', 500);
        $this->spendGroceries('2026-10-14', 900);

        $week = $this->scores('2026-10-13')['week'];

        self::assertSame([], $week['rhythm']['debits']);
        self::assertCount(7, $week['rhythm']['curve']);
        self::assertSame('2026-10-12', $week['rhythm']['curve'][0]['date']);
        self::assertSame('2026-10-18', $week['rhythm']['curve'][6]['date']);
        self::assertSame(4500, $week['rhythm']['spentCents']);
        self::assertSame(0, $week['rhythm']['expectedSpentCents']);
        self::assertSame(0, $week['rhythm']['plannedCents']);

        // The week of the 5th holds the rent and the energy (the 10th).
        $first = $this->scores('2026-10-07')['week']['rhythm'];
        self::assertSame(['2026-10-05', '2026-10-10'], array_column($first['debits'], 'date'));
        self::assertSame(70000, $first['expectedSpentCents']);
        self::assertSame(78000, $first['plannedCents']);
        self::assertSame(70000, $first['spentCents']);
    }

    public function testTheWeekIsNeutralWithNoReasonWhateverIsSpent(): void
    {
        $this->spendGroceries('2026-10-12', 200000);

        $week = $this->scores('2026-10-13')['week'];

        self::assertSame('neutral', $week['status']);
        self::assertSame([], $week['reasons']);
        self::assertSame(200000, $week['rhythm']['spentCents']);
    }

    public function testAWeekOverTwoMonthsReadsBothMonths(): void
    {
        $week = $this->scores('2026-10-28')['week'];

        self::assertCount(7, $week['rhythm']['curve']);
        self::assertSame('2026-10-26', $week['rhythm']['curve'][0]['date']);
        self::assertSame('2026-11-01', $week['rhythm']['curve'][6]['date']);
    }

    public function testAnExceededAnnualEnvelopeMakesTheGlobalRedEvenWhenTheMonthIsGood(): void
    {
        $this->spendGroceries('2026-10-02', 120000, 'scores_travel');

        $scores = $this->scores('2026-10-13');

        self::assertSame('green', $scores['month']['status']);
        self::assertSame('red', $scores['global']['status']);
        self::assertSame('Dépassement', $scores['global']['label']);
        self::assertContains('annual_category_exceeded', self::codes($scores['global']));
        $reason = $scores['global']['reasons'][array_search('annual_category_exceeded', self::codes($scores['global']), true)];
        self::assertSame('Voyages', $reason['categoryName']);
        self::assertSame(20000, $reason['amountCents']);
    }

    public function testTheGlobalIsTheMonthWhenTheYearIsFine(): void
    {
        $scores = $this->scores('2026-10-13');

        self::assertSame($scores['month']['status'], $scores['global']['status']);
    }

    public function testNoEnvelopeIsNeutralWithItsReason(): void
    {
        $scores = $this->scores('2026-10-13', 'other_user');

        foreach (['month', 'global'] as $period) {
            self::assertSame('neutral', $scores[$period]['status']);
            self::assertSame('no_budget', $scores[$period]['reasons'][0]['code']);
        }
        self::assertSame(0, $scores['month']['rhythm']['spentCents']);
        self::assertSame(0, $scores['month']['rhythm']['plannedCents']);
    }

    public function testAnotherUsersSpendingIsNotCounted(): void
    {
        $this->spendGroceries('2026-10-03', 22000);

        self::assertSame(0, $this->scores('2026-10-13', 'other_user')['month']['rhythm']['spentCents']);
    }

    public function testUnclassifiedDebitsAreCountedPerPeriodAndStayInWhatWasSpent(): void
    {
        $this->leaveUnclassified('2026-10-03', -150000);
        $this->leaveUnclassified('2026-10-13', -20000);

        $scores = $this->scores('2026-10-13');

        self::assertSame(2, $scores['month']['rhythm']['unclassifiedCount']);
        self::assertSame(170000, $scores['month']['rhythm']['unclassifiedCents']);
        self::assertSame(78000 + 170000, $scores['month']['rhythm']['spentCents']);
        // The week of the 12th only holds the second one.
        self::assertSame(1, $scores['week']['rhythm']['unclassifiedCount']);
        self::assertSame(20000, $scores['week']['rhythm']['unclassifiedCents']);
        self::assertSame(20000, $scores['week']['rhythm']['spentCents']);
    }

    public function testAnUnclassifiedDebitAfterTheDayAskedIsNotCounted(): void
    {
        $this->leaveUnclassified('2026-10-20', -9000);

        self::assertSame(0, $this->scores('2026-10-13')['month']['rhythm']['unclassifiedCount']);
        self::assertSame(1, $this->scores('2026-10-20')['month']['rhythm']['unclassifiedCount']);
    }

    public function testTheDateIsEchoedBack(): void
    {
        self::assertSame('2026-10-13', $this->scores('2026-10-13')['date']);
    }

    public function testResolveDateRefusesWhatIsNotADay(): void
    {
        /** @var User $user */
        $user = $this->getFixture('scores_user');

        foreach (['2026-13-01', '2026-02-30', 'tomorrow', '13/10/2026', '1999-12-31'] as $bad) {
            try {
                $this->useCase->resolveDate($user, $bad);
                self::fail(sprintf('"%s" should be refused.', $bad));
            } catch (\InvalidArgumentException) {
                $this->addToAssertionCount(1);
            }
        }

        self::assertSame('2026-10-13', $this->useCase->resolveDate($user, '2026-10-13')->format('Y-m-d'));
    }

    public function testWithoutADateItIsTodayInTheUsersTimezone(): void
    {
        /** @var User $user */
        $user = $this->getFixture('scores_user');

        $today = (new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');

        self::assertSame($today, $this->useCase->resolveDate($user, null)->format('Y-m-d'));
    }
}
