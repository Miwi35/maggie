<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Mcp\Tool\GetDailyScoreTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class DailyScoreToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    /** @return array<string, mixed> */
    private function score(int $year = 2026, int $month = 9): array
    {
        $tool = self::getContainer()->get(GetDailyScoreTool::class);

        return json_decode($tool($year, $month), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return list<string> */
    private function reasonCodes(array $score): array
    {
        return array_column($score['reasons'], 'code');
    }

    private function spend(string $categoryFixture, int $amountCents, string $bookedAt, string $status = 'spent'): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $transaction = new Transaction();
        $transaction->setUser($this->getFixture('test_user'));
        $transaction->setAccount($this->getFixture('checking'));
        $transaction->setCategory($this->getFixture($categoryFixture));
        $transaction->setAmountCents($amountCents);
        $transaction->setLabel('Test');
        $transaction->setBookedAt(new \DateTimeImmutable($bookedAt));
        $transaction->setStatus(TransactionStatus::from($status));

        $em->persist($transaction);
        $em->flush();
    }

    public function testAHealthyMonthWithAFullCushionIsGreen(): void
    {
        $this->loadFixtures('daily_score.yaml');
        $this->loginFixtureUser();

        $score = $this->score();

        self::assertSame('green', $score['score']);
        self::assertSame('complete', $score['cushion']['state']);
        self::assertContains('below_last_year', $this->reasonCodes($score));
        self::assertSame([], $score['budget']['overspentCategories']);
    }

    public function testAnIncompleteCushionHoldsGreenBackWithoutDegradingFurther(): void
    {
        $this->loadFixtures('daily_score.yaml');
        $this->loginFixtureUser();

        // Drain the cushion account: the target is no longer covered.
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->getFixture('livret')->setBalanceCents(100000);
        $em->flush();

        $score = $this->score();

        self::assertSame('neutral', $score['score']);
        self::assertTrue($score['cushion']['blocksGreenScore']);
        self::assertContains('cushion_incomplete', $this->reasonCodes($score));
    }

    public function testSpendingMoreThanLastYearOnlyHoldsGreenBack(): void
    {
        $this->loadFixtures('daily_score.yaml');
        $this->loginFixtureUser();

        // Still inside every envelope (350 € of 400 €, 150 € of 200 €), but the
        // month now totals 500 € against last September's 450 €.
        $this->spend('food', -15000, '2026-09-10');
        $this->spend('leisure', -10000, '2026-09-11');

        $score = $this->score();

        self::assertSame('neutral', $score['score']);
        self::assertContains('above_last_year', $this->reasonCodes($score));
        self::assertSame([], $score['budget']['overspentCategories']);
    }

    public function testOverspendingANonMandatoryCategoryIsOrange(): void
    {
        $this->loadFixtures('daily_score.yaml');
        $this->loginFixtureUser();

        // Leisure is budgeted 200 €; push it to 250 €.
        $this->spend('leisure', -20000, '2026-09-12');

        $score = $this->score();

        self::assertSame('orange', $score['score']);
        self::assertContains('Loisirs', $score['budget']['overspentCategories']);
        self::assertContains('optional_category_exceeded', $this->reasonCodes($score));
    }

    public function testPlannedSpendingBeyondTheBudgetIsOrangeToo(): void
    {
        $this->loadFixtures('daily_score.yaml');
        $this->loginFixtureUser();

        // Nothing is overspent yet, but this plan would take Leisure over.
        $this->spend('leisure', -18000, '2026-09-20', 'planned');

        $score = $this->score();

        self::assertSame('orange', $score['score']);
        self::assertContains('plans_exceed_category_budget', $this->reasonCodes($score));
        self::assertSame([], $score['budget']['overspentCategories']);
    }

    public function testOverspendingAMandatoryCategoryIsRed(): void
    {
        $this->loadFixtures('daily_score.yaml');
        $this->loginFixtureUser();

        // Food is budgeted 400 €; push it to 450 €.
        $this->spend('food', -25000, '2026-09-15');

        $score = $this->score();

        self::assertSame('red', $score['score']);
        self::assertContains('Alimentation', $score['budget']['overspentCategories']);
        self::assertContains('mandatory_category_exceeded', $this->reasonCodes($score));
    }

    public function testBlowingTheWholeBudgetIsRedEvenOnOptionalCategories(): void
    {
        $this->loadFixtures('daily_score.yaml');
        $this->loginFixtureUser();

        // Leisure alone carries the total past the 600 € budgeted.
        $this->spend('leisure', -60000, '2026-09-18');

        $score = $this->score();

        self::assertSame('red', $score['score']);
        self::assertContains('total_budget_exceeded', $this->reasonCodes($score));
    }

    public function testAMonthWithoutEnvelopesIsNeutralAndSaysSo(): void
    {
        $this->loadFixtures('daily_score.yaml');
        $this->loginFixtureUser();

        // October has no envelope of its own.
        $score = $this->score(2026, 10);

        self::assertSame('neutral', $score['score']);
        self::assertSame(['no_budget'], $this->reasonCodes($score));
    }

    public function testTheComparisonReportsBothMonths(): void
    {
        $this->loadFixtures('daily_score.yaml');
        $this->loginFixtureUser();

        $comparison = $this->score()['comparison'];

        self::assertSame(25000, $comparison['thisMonthCents']);
        self::assertSame(45000, $comparison['sameMonthLastYearCents']);
        self::assertSame(-20000, $comparison['differenceCents']);
        self::assertTrue($comparison['isBetter']);
    }
}
