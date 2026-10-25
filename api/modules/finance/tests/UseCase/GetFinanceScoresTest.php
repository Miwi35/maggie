<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\UseCase\GetFinanceScores;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The week and month scores and their non-linear rhythm (MAG-416), on the
 * October 2026 of `FinanceScoresWorld`. A pure read: no Mercure update and no
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

    public function testThePaceIsNotLinear(): void
    {
        $this->spendGroceries('2026-10-03', 22000);

        $month = $this->scores('2026-10-13')['month'];

        // 1 500 € - (700 + 80) = 720 € to spread; on the 13th: 780 + 720 × 13 / 31.
        self::assertSame(150000, $month['rhythm']['budgetCents']);
        self::assertSame(108194, $month['rhythm']['expectedSpentCents']);
        self::assertSame(100000, $month['rhythm']['spentCents']);
        self::assertSame(50000, $month['rhythm']['remainingCents']);
        self::assertSame(150000 - 108194, $month['rhythm']['expectedRemainingCents']);
        self::assertSame('on_track', $month['rhythm']['status']);
        self::assertSame('Dans le rythme', $month['rhythm']['label']);
        self::assertSame('neutral', $month['status']);
        self::assertSame('Dans les clous', $month['label']);
    }

    public function testSpendingMoreThanThePaceIsAboveTheRhythmAndOrange(): void
    {
        $this->spendGroceries('2026-10-03', 42000);

        $month = $this->scores('2026-10-13')['month'];

        self::assertSame(120000, $month['rhythm']['spentCents']);
        self::assertSame('above', $month['rhythm']['status']);
        self::assertSame('Au-dessus du rythme', $month['rhythm']['label']);
        self::assertSame('orange', $month['status']);
        self::assertSame('Attention', $month['label']);
        self::assertContains('above_rhythm', self::codes($month));
        $reason = $month['reasons'][array_search('above_rhythm', self::codes($month), true)];
        self::assertSame(120000 - 108194, $reason['amountCents']);
    }

    public function testNinetyFivePercentOfThePaceIsTheOrangeLine(): void
    {
        // 95 % of 108 194 = 102 784,3: one cent under is on track, one over is above.
        $this->spendGroceries('2026-10-03', 102784 - 78000);
        self::assertSame('on_track', $this->scores('2026-10-13')['month']['rhythm']['status']);

        $this->spendGroceries('2026-10-04', 1);
        self::assertSame('above', $this->scores('2026-10-13')['month']['rhythm']['status']);
    }

    public function testAMonthUnderEightyPercentOfThePaceAndBetterThanLastYearIsGreen(): void
    {
        // Only the rent and the energy: 780 € against 1 081,94 € expected, 72 %.
        $month = $this->scores('2026-10-13')['month'];

        self::assertSame('green', $month['status']);
        self::assertSame('Dans le vert', $month['label']);
    }

    public function testAMonthBetweenEightyAndNinetyFivePercentLosesItsGreen(): void
    {
        // 780 + 120 = 900 € is 83 % of 1 081,94 €: in the nails, not in the green.
        $this->spendGroceries('2026-10-03', 12000);

        self::assertSame('neutral', $this->scores('2026-10-13')['month']['status']);
    }

    public function testAnExceededMonthBudgetIsRedWhateverTheRhythm(): void
    {
        $this->spendGroceries('2026-10-03', 80000);

        $month = $this->scores('2026-10-13')['month'];

        self::assertSame('red', $month['status']);
        self::assertSame('Dépassement', $month['label']);
        self::assertContains('total_budget_exceeded', self::codes($month));
    }

    public function testTheCurveHasOnePointPerDayAndTheDebitsAtTheirDate(): void
    {
        $this->spendGroceries('2026-10-03', 22000);

        $rhythm = $this->scores('2026-10-13')['month']['rhythm'];

        self::assertCount(31, $rhythm['curve']);
        self::assertSame('2026-10-01', $rhythm['curve'][0]['date']);
        self::assertSame('2026-10-31', $rhythm['curve'][30]['date']);

        // Debits weigh on the day they fall: the 5th jumps by 700 € plus a day of the rest.
        $fourth = $rhythm['curve'][3]['expectedCents'];
        $fifth = $rhythm['curve'][4]['expectedCents'];
        self::assertSame(70000 + (int) round(72000 * 5 / 31) - (int) round(72000 * 4 / 31), $fifth - $fourth);
        self::assertSame(150000, $rhythm['curve'][30]['expectedCents']);

        // The spending is known up to the day asked for, and nothing after it.
        self::assertSame(22000, $rhythm['curve'][3]['spentCents']);
        self::assertSame(92000, $rhythm['curve'][4]['spentCents']);
        self::assertSame(100000, $rhythm['curve'][12]['spentCents']);
        self::assertNull($rhythm['curve'][13]['spentCents']);

        self::assertSame(
            [
                ['date' => '2026-10-05', 'label' => 'Loyer', 'amountCents' => 70000],
                ['date' => '2026-10-10', 'label' => 'Énergie', 'amountCents' => 8000],
            ],
            array_map(
                static fn (array $debit): array => array_diff_key($debit, ['recurringOperationId' => true]),
                $rhythm['debits'],
            ),
        );
        self::assertNotSame('', $rhythm['debits'][0]['recurringOperationId']);
    }

    public function testTheWeekHoldsOnlyItsOwnDebitsAndDaysOfTheVariablePart(): void
    {
        // Monday 12 to Sunday 18: the rent and the energy fell earlier.
        $week = $this->scores('2026-10-13')['week'];

        self::assertSame([], $week['rhythm']['debits']);
        self::assertCount(7, $week['rhythm']['curve']);
        self::assertSame('2026-10-12', $week['rhythm']['curve'][0]['date']);
        self::assertSame('2026-10-18', $week['rhythm']['curve'][6]['date']);
        // 7 days of 720 € / 31, and two of them elapsed on the 13th.
        self::assertSame(16258, $week['rhythm']['budgetCents']);
        self::assertSame(4645, $week['rhythm']['expectedSpentCents']);

        // The week of the 5th holds the rent and the energy (the 10th).
        $first = $this->scores('2026-10-07')['week']['rhythm'];
        self::assertSame(['2026-10-05', '2026-10-10'], array_column($first['debits'], 'date'));
        self::assertSame(70000 + 8000 + (int) round(72000 * 7 / 31), $first['budgetCents']);
    }

    public function testTheWeekIsRedWhenItsOwnBudgetIsExceeded(): void
    {
        $this->spendGroceries('2026-10-12', 20000);

        $week = $this->scores('2026-10-13')['week'];

        self::assertSame('red', $week['status']);
        self::assertSame(['week_budget_exceeded'], self::codes($week));
        self::assertSame(20000 - 16258, $week['reasons'][0]['amountCents']);
        // The month, with 980 € spent, is not.
        self::assertNotSame('red', $this->scores('2026-10-13')['month']['status']);
    }

    public function testTheWeekFollowsItsPaceWhenItsBudgetHolds(): void
    {
        // Expected on the 13th: 46,45 €. 80 % is 37,16 €, 95 % is 44,13 €.
        self::assertSame('green', $this->scores('2026-10-13')['week']['status']);

        $this->spendGroceries('2026-10-12', 4000);
        $neutral = $this->scores('2026-10-13')['week'];
        self::assertSame('neutral', $neutral['status']);
        self::assertSame('on_track', $neutral['rhythm']['status']);

        $this->spendGroceries('2026-10-13', 500);
        $orange = $this->scores('2026-10-13')['week'];
        self::assertSame('orange', $orange['status']);
        self::assertSame('above', $orange['rhythm']['status']);
        self::assertSame('Au-dessus du rythme', $orange['rhythm']['label']);
        self::assertContains('above_rhythm', self::codes($orange));
    }

    public function testNotSpendingBringsTheWeekBackDownTheScale(): void
    {
        $this->spendGroceries('2026-10-12', 5000);
        self::assertSame('orange', $this->scores('2026-10-13')['week']['status']);

        // The same spending, three days later: the pace has caught up and gone past it.
        self::assertSame('green', $this->scores('2026-10-16')['week']['status']);
    }

    public function testAWeekOverTwoMonthsReadsBothBudgets(): void
    {
        $week = $this->scores('2026-10-28')['week'];

        self::assertCount(7, $week['rhythm']['curve']);
        self::assertSame('2026-10-26', $week['rhythm']['curve'][0]['date']);
        self::assertSame('2026-11-01', $week['rhythm']['curve'][6]['date']);
        // 6 days of October's variable part; November has no envelope.
        self::assertSame((int) round(72000 * 6 / 31), $week['rhythm']['budgetCents']);
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

        foreach (['week', 'month', 'global'] as $period) {
            self::assertSame('neutral', $scores[$period]['status']);
            self::assertSame('no_budget', $scores[$period]['reasons'][0]['code']);
        }
        self::assertSame(0, $scores['month']['rhythm']['budgetCents']);
        self::assertSame('on_track', $scores['month']['rhythm']['status']);
    }

    public function testAnotherUsersSpendingIsNotCounted(): void
    {
        $this->spendGroceries('2026-10-03', 22000);

        self::assertSame(0, $this->scores('2026-10-13', 'other_user')['month']['rhythm']['spentCents']);
    }

    public function testAPlannedSpendWeighsNothingOnThePaceYet(): void
    {
        $this->spendGroceries('2026-10-14', 90000, status: TransactionStatus::Planned);

        self::assertSame(78000, $this->scores('2026-10-13')['month']['rhythm']['spentCents']);
    }

    public function testUnclassifiedDebitsAreCountedPerPeriodAndTheRhythmIsIncomplete(): void
    {
        $this->leaveUnclassified('2026-10-03', -150000);
        $this->leaveUnclassified('2026-10-13', -20000);

        $scores = $this->scores('2026-10-13');

        self::assertSame(2, $scores['month']['rhythm']['unclassifiedCount']);
        self::assertSame(170000, $scores['month']['rhythm']['unclassifiedCents']);
        // The week of the 12th only holds the second one.
        self::assertSame(1, $scores['week']['rhythm']['unclassifiedCount']);
        self::assertSame(20000, $scores['week']['rhythm']['unclassifiedCents']);
        foreach (['week', 'month'] as $period) {
            self::assertSame('incomplete', $scores[$period]['rhythm']['status']);
            self::assertSame('Rythme incomplet', $scores[$period]['rhythm']['label']);
        }
    }

    public function testAnIncompleteRhythmNeverStaysGreenAndSaysWhy(): void
    {
        // Nothing classified spent: the pace alone would make the month and the week green.
        self::assertSame('green', $this->scores('2026-10-13')['month']['status']);

        $this->leaveUnclassified('2026-10-13', -200000);

        $scores = $this->scores('2026-10-13');

        foreach (['week', 'month'] as $period) {
            self::assertSame('neutral', $scores[$period]['status']);
            self::assertContains('unclassified_debits', self::codes($scores[$period]));
            $reason = $scores[$period]['reasons'][array_search('unclassified_debits', self::codes($scores[$period]), true)];
            self::assertSame(200000, $reason['amountCents']);
        }
        self::assertSame('neutral', $scores['global']['status']);
    }

    public function testClassifiedSpendingAboveThePaceStaysAboveEvenWithUnclassifiedDebits(): void
    {
        $this->spendGroceries('2026-10-03', 42000);
        $this->leaveUnclassified('2026-10-04', -1000);

        $month = $this->scores('2026-10-13')['month'];

        self::assertSame('above', $month['rhythm']['status']);
        self::assertSame(1, $month['rhythm']['unclassifiedCount']);
        self::assertSame('orange', $month['status']);
    }

    public function testInternalTransfersRejectionsCreditsAndPlannedLinesAreNotUnclassifiedDebits(): void
    {
        $this->leaveUnclassified('2026-10-03', -50000, TransferKind::Internal);
        $this->leaveUnclassified('2026-10-04', -4000, TransferKind::Rejected);
        $this->leaveUnclassified('2026-10-05', 90000);
        $this->leaveUnclassified('2026-10-06', -7000, status: TransactionStatus::Planned);
        $this->leaveUnclassified('2026-10-07', -3000, as: 'other_user');
        $this->spendGroceries('2026-10-08', 5000);

        $month = $this->scores('2026-10-13')['month']['rhythm'];

        self::assertSame(0, $month['unclassifiedCount']);
        self::assertSame(0, $month['unclassifiedCents']);
        self::assertSame('on_track', $month['status']);
        self::assertSame('Dans le rythme', $month['label']);
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
