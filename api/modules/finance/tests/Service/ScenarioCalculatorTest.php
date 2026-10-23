<?php

namespace Maggie\Finance\Tests\Service;

use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Enum\PrepaymentPenaltyMode;
use Maggie\Finance\Enum\ProjectWellbeing;
use Maggie\Finance\Service\IndependenceBase;
use Maggie\Finance\Service\LoanSchedule;
use Maggie\Finance\Service\PlanningInputs;
use Maggie\Finance\Service\ScenarioCalculator;
use Maggie\Finance\Service\ScenarioSpec;
use Maggie\Finance\Service\ScenarioTimeline;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * What a scenario computes (MAG-550): the planning it implies, the metrics, the
 * date N and the timeline events. 1 000 € a month of capacity, a complete
 * cushion, a train de vie of 300 € a month, M0 = 2026-10.
 *
 * Exemption: N/A — pure computation, nothing is written.
 */
class ScenarioCalculatorTest extends KernelTestCase
{
    private ScenarioCalculator $calculator;
    private IndependenceBase $base;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->calculator = self::getContainer()->get(ScenarioCalculator::class);
        $this->base = new IndependenceBase(0, 30000);
    }

    private function project(string $name, int $cents, ProjectWellbeing $wellbeing, int $position): Project
    {
        return (new Project())->setName($name)->setAmountCents($cents)->setWellbeing($wellbeing)->setPosition($position);
    }

    /**
     * @param list<Project> $projects
     * @param list<Loan>    $loans
     */
    private function inputs(array $projects, array $loans = []): PlanningInputs
    {
        return new PlanningInputs(
            new \DateTimeImmutable('2026-10-21 12:00:00'),
            100000,
            true,
            ['state' => 'complete', 'deficitCents' => 0, 'monthlyRechargeCents' => 0],
            0,
            $projects,
            $loans,
        );
    }

    private function loan(): Loan
    {
        // 10 000 € over 24 months at 4 %, signed, no penalty.
        return (new Loan())->setName('Travaux')
            ->setPrincipalRemainingCents(1000000)
            ->setMonthlyPaymentCents(LoanSchedule::annuity(1000000, 0.04 / 12, 24))
            ->setAnnualRateBasisPoints(400)
            ->setDurationMonths(24)
            ->setEarlyRepaymentAllowed(true)
            ->setPrepaymentPenaltyMode(PrepaymentPenaltyMode::Exempt);
    }

    private function ids(Project ...$projects): array
    {
        return array_map(static fn (Project $project): string => (string) $project->getId(), $projects);
    }

    public function testTheMetricsOfAScenarioAreAboutItsOwnProjects(): void
    {
        $car = $this->project('Voiture', 900000, ProjectWellbeing::High, 0);
        $trip = $this->project('Japon', 500000, ProjectWellbeing::Comfort, 1);
        $inputs = $this->inputs([$car, $trip]);

        $both = $this->calculator->evaluate($inputs, $this->base, new ScenarioSpec($this->ids($car, $trip)));
        $carOnly = $this->calculator->evaluate($inputs, $this->base, new ScenarioSpec($this->ids($car)));

        // 14 000 € at 1 000 € a month: the second project is bought in the 14th month.
        self::assertSame('2027-11', $both['metrics']['projectsDoneBy']);
        self::assertSame('2027-06', $carOnly['metrics']['projectsDoneBy']);
        self::assertSame(4, $both['metrics']['wellbeing']);
        self::assertSame(3, $carOnly['metrics']['wellbeing']);
        self::assertSame(0, $both['metrics']['interestPaid']);
        self::assertNull($both['metrics']['debtsEndDate']);
        // Fewer projects, an earlier N: the capital grows sooner.
        self::assertLessThan($both['nDate'], $carOnly['nDate']);
        self::assertSame($both['nDate'], $both['metrics']['nDate']);
    }

    public function testAProjectBeyondThePlanningHorizonLeavesProjectsDoneByEmpty(): void
    {
        $house = $this->project('Maison', 5000000, ProjectWellbeing::Essential, 0);

        $result = $this->calculator->evaluate($this->inputs([$house]), $this->base, new ScenarioSpec($this->ids($house)));

        self::assertNull($result['metrics']['projectsDoneBy']);
        self::assertSame(0, $result['metrics']['wellbeing']);
        self::assertTrue($result['schedule'][0]['isBeyondHorizon']);
    }

    public function testAMonthlySavingReplacesTheMeasuredCapacity(): void
    {
        $car = $this->project('Voiture', 900000, ProjectWellbeing::High, 0);
        $inputs = $this->inputs([$car]);

        $plan = $this->calculator->plan($inputs, new ScenarioSpec($this->ids($car), 300000))['plan'];

        self::assertSame(300000, $plan['netCapacityCents']);
        // 9 000 € at 3 000 € a month: the third month.
        self::assertSame('2026-12', $plan['projects'][0]['purchaseMonth']);
        self::assertLessThan(
            $this->calculator->evaluate($inputs, $this->base, new ScenarioSpec($this->ids($car)))['nDate'],
            $this->calculator->evaluate($inputs, $this->base, new ScenarioSpec($this->ids($car), 300000))['nDate'],
        );
    }

    public function testAProjectOutsideTheScenarioIsNotPlanned(): void
    {
        $car = $this->project('Voiture', 900000, ProjectWellbeing::High, 0);
        $trip = $this->project('Japon', 500000, ProjectWellbeing::Comfort, 1);

        $plan = $this->calculator->plan($this->inputs([$car, $trip]), new ScenarioSpec($this->ids($trip)))['plan'];

        self::assertSame(['Japon'], array_column($plan['projects'], 'name'));
        self::assertSame('2027-02', $plan['projects'][0]['purchaseMonth']);
    }

    public function testAnEarlyRepaymentCostsItsAmountThenFreesTheMonthlyPayment(): void
    {
        $loan = $this->loan();
        $trip = $this->project('Japon', 300000, ProjectWellbeing::Comfort, 0);
        $inputs = $this->inputs([$trip], [$loan]);
        $repayment = ['id' => 'rep-1', 'loanId' => (string) $loan->getId(), 'month' => '2026-12', 'amountCents' => 500000];

        $without = $this->calculator->evaluate($inputs, $this->base, new ScenarioSpec($this->ids($trip)));
        $with = $this->calculator->evaluate($inputs, $this->base, new ScenarioSpec($this->ids($trip), null, [$repayment]));

        self::assertSame([], $without['repayments']);
        self::assertCount(1, $with['repayments']);
        $row = $with['repayments'][0];
        self::assertSame(['rep-1', 500000, 0], [$row['id'], $row['amountCents'], $row['penaltyCents']]);
        self::assertGreaterThan(0, $row['interestSavedCents']);
        self::assertGreaterThan(0, $row['freedMonthlyCents']);
        // The 5 000 € leave the capacity of the month: the trip is bought later than with no repayment.
        self::assertGreaterThan($without['metrics']['projectsDoneBy'], $with['metrics']['projectsDoneBy']);
        // The loan, rescheduled, still ends: a date and interest that follow from the repayment.
        self::assertNotNull($with['metrics']['debtsEndDate']);
        self::assertGreaterThan(0, $with['metrics']['interestPaid']);
    }

    public function testARepaymentOnAnUnknownLoanIsSkippedNotFatalInThePlanning(): void
    {
        $trip = $this->project('Japon', 300000, ProjectWellbeing::Comfort, 0);
        $repayment = ['id' => 'rep-1', 'loanId' => '01ARZ3NDEKTSV4RRFFQ69G5FAV', 'month' => '2026-12', 'amountCents' => 500000];

        $result = $this->calculator->evaluate($this->inputs([$trip]), $this->base, new ScenarioSpec($this->ids($trip), null, [$repayment]));

        self::assertSame([], $result['repayments']);
    }

    public function testTheTimelineEventsFollowTheContract(): void
    {
        $loan = $this->loan();
        $car = $this->project('Voiture', 900000, ProjectWellbeing::High, 0);
        $trip = $this->project('Japon', 500000, ProjectWellbeing::Comfort, 1);
        $inputs = $this->inputs([$car, $trip], [$loan]);
        $repayment = ['id' => 'rep-1', 'loanId' => (string) $loan->getId(), 'month' => '2027-01', 'amountCents' => 400000];

        $events = $this->calculator->evaluate($inputs, $this->base, new ScenarioSpec($this->ids($car, $trip), null, [$repayment]), true)['events'];

        self::assertNotEmpty($events);
        $months = array_column($events, 'month');
        $sorted = $months;
        sort($sorted);
        self::assertSame($sorted, $months, 'Events are in date order.');
        foreach ($events as $event) {
            self::assertSame(array_keys(ScenarioTimeline::FIELDS), array_keys($event));
            self::assertContains($event['category'], ScenarioTimeline::CATEGORIES);
            self::assertArrayHasKey($event['type'], ScenarioTimeline::TYPES);
            self::assertSame(ScenarioTimeline::TYPES[$event['type']], $event['category']);
            self::assertMatchesRegularExpression('/^\d{4}-\d{2}$/', $event['month']);
        }

        $types = array_count_values(array_column($events, 'type'));
        self::assertSame(2, $types['project_purchase']);
        self::assertSame(1, $types['early_repayment']);
        self::assertSame(1, $types['debt_end']);

        $purchase = array_values(array_filter($events, static fn (array $e): bool => 'project_purchase' === $e['type']))[0];
        self::assertSame('Voiture', $purchase['label']);
        self::assertSame(9000.0, (float) $purchase['amount_eur']);
        // What the project pushes N back by, in months: a whole number.
        self::assertIsInt($purchase['n_impact_months']);
        self::assertGreaterThan(0, $purchase['n_impact_months']);

        $repaid = array_values(array_filter($events, static fn (array $e): bool => 'early_repayment' === $e['type']))[0];
        self::assertSame('2027-01', $repaid['month']);
        self::assertSame(4000.0, (float) $repaid['amount_eur']);
    }

    public function testEventsAreOnlyBuiltOnRequest(): void
    {
        $car = $this->project('Voiture', 900000, ProjectWellbeing::High, 0);

        $result = $this->calculator->evaluate($this->inputs([$car]), $this->base, new ScenarioSpec($this->ids($car)));

        self::assertNull($result['events']);
    }
}
