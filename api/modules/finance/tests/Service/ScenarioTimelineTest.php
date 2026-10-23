<?php

namespace Maggie\Finance\Tests\Service;

use Maggie\Finance\Service\ScenarioTimeline;
use PHPUnit\Framework\TestCase;

/**
 * The events of the 20-year frise at their months (MAG-554), from a hand-made
 * planning, so every month below is arithmetic, not a snapshot.
 *
 * Exemption: N/A — pure builder, no persistence.
 */
class ScenarioTimelineTest extends TestCase
{
    /**
     * @param array{state: string, completeMonth: string|null} $cushion
     *
     * @return array<string, mixed>
     */
    private static function plan(array $cushion): array
    {
        return [
            'startMonth' => '2026-10',
            'cushion' => $cushion,
            'months' => [['cushionCents' => 15000], ['cushionCents' => 15000]],
            'projects' => [[
                'projectId' => 'p1',
                'name' => 'Voiture',
                'amountCents' => 900000,
                'wellbeing' => 'high',
                'savingFromMonth' => '2026-12',
                'purchaseMonth' => '2027-06',
                'loan' => ['startMonth' => '2027-07', 'endMonth' => '2029-06', 'monthlyPaymentCents' => 40000],
            ]],
        ];
    }

    /** @return list<array<string, mixed>> */
    private function build(array $cushion): array
    {
        $placements = [['id' => 'pl1', 'name' => 'PEA', 'initial' => 200000, 'monthlyDeposit' => 15000, 'years' => 10, 'final' => 3500000, 'rent' => 11000]];
        $effects = [
            'loans' => [['loanId' => 'l1', 'endMonth' => '2031-03']],
            'repayments' => [['id' => 'r1', 'loanId' => 'l1', 'month' => '2027-01', 'amountCents' => 400000, 'penaltyCents' => 0, 'freedMonthlyCents' => 45000, 'interestSavedCents' => 30000]],
        ];
        $independence = ['milestones' => [25 => '2029-01', 50 => '2033-05', 75 => '2038-02', 100 => '2044-11']];

        return (new ScenarioTimeline())->build(self::plan($cushion), $effects, $independence, ['p1' => 4], ['r1' => -2], ['l1' => 'Crédit conso'], $placements);
    }

    /** @return array<string, string> id → month */
    private static function months(array $events): array
    {
        return array_column($events, 'month', 'id');
    }

    public function testEveryEventOfTheRuleLandsOnItsMonth(): void
    {
        $events = $this->build(['state' => 'building', 'completeMonth' => '2027-02']);

        self::assertSame([
            'placement_start:pl1' => '2026-10',
            'project_start:p1' => '2026-12',
            'early_repayment:r1' => '2027-01',
            'cushion_complete' => '2027-02',
            'project_purchase:p1' => '2027-06',
            'independence_milestone:25' => '2029-01',
            'debt_end:project:p1' => '2029-06',
            'debt_end:loan:l1' => '2031-03',
            'independence_milestone:50' => '2033-05',
            'placement_milestone:pl1' => '2036-10',
            'independence_milestone:75' => '2038-02',
            'independence_reached' => '2044-11',
        ], self::months($events));
    }

    public function testEventsAreInDateOrderAndFollowTheContract(): void
    {
        $events = $this->build(['state' => 'building', 'completeMonth' => '2027-02']);

        $months = array_column($events, 'month');
        $sorted = $months;
        sort($sorted);
        self::assertSame($sorted, $months);
        foreach ($events as $event) {
            self::assertSame(array_keys(ScenarioTimeline::FIELDS), array_keys($event));
            self::assertSame(ScenarioTimeline::TYPES[$event['type']], $event['category']);
        }
    }

    public function testTheCushionIsRechargingAtTheMonthOfTheCalculationAndCompleteWhenReconstituted(): void
    {
        $events = $this->build(['state' => 'recharging', 'completeMonth' => '2027-02']);
        $months = self::months($events);

        self::assertSame('2026-10', $months['cushion_recharging']);
        self::assertSame('2027-02', $months['cushion_complete']);

        $recharge = array_values(array_filter($events, static fn (array $e): bool => 'cushion_recharging' === $e['type']))[0];
        self::assertSame('matelas', $recharge['category']);
        self::assertEquals(300, $recharge['amount_eur']);
        self::assertEquals(-150, $recharge['monthly_impact_eur']);
    }

    public function testACompleteCushionHasNoRechargeEvent(): void
    {
        $events = $this->build(['state' => 'complete', 'completeMonth' => null]);

        self::assertArrayNotHasKey('cushion_recharging', self::months($events));
        self::assertArrayNotHasKey('cushion_complete', self::months($events));
    }

    public function testAPlacementBeyondTwentyYearsHasNoMilestone(): void
    {
        $placements = [['id' => 'pl1', 'name' => 'PEA', 'initial' => 0, 'monthlyDeposit' => 0, 'years' => 25, 'final' => 1, 'rent' => 1]];

        $events = (new ScenarioTimeline())->build(self::plan(['state' => 'complete', 'completeMonth' => null]), ['loans' => [], 'repayments' => []], ['milestones' => []], [], [], [], $placements);

        self::assertArrayNotHasKey('placement_milestone:pl1', self::months($events));
    }

    public function testNoEventIsFiscalYet(): void
    {
        self::assertNotContains('fiscal', ScenarioTimeline::TYPES, 'No tax model exists: the category is filterable but carries no event.');
        self::assertSame(array_keys(ScenarioTimeline::COLORS), ScenarioTimeline::CATEGORIES);
        self::assertSame(array_keys(ScenarioTimeline::LABELS), ScenarioTimeline::CATEGORIES);
    }
}
