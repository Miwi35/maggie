<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Mcp\Tool\MonthlyReviewTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class MonthlyReviewToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function tool(): MonthlyReviewTool
    {
        return self::getContainer()->get(MonthlyReviewTool::class);
    }

    /** @return array<string, mixed> */
    private function review(int $year = 2026, int $month = 8): array
    {
        return json_decode($this->tool()('review', year: $year, month: $month), true, 512, JSON_THROW_ON_ERROR);
    }

    private function rate(string $fixture, string $verdict): void
    {
        $this->tool()('rate', transactionId: (string) $this->getFixture($fixture)->getId(), verdict: $verdict);
    }

    public function testOnlyNonMandatorySpendsAreOfferedForJudgement(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->loginFixtureUser();

        $review = $this->review();
        $labels = array_column($review['pending'], 'label');

        // Rent is mandatory: asking about it would help nobody.
        self::assertNotContains('Loyer août', $labels);
        self::assertContains('Concert', $labels);
        self::assertContains('Livraison sushi', $labels);
        // An uncategorized spend is exactly what deserves a second look.
        self::assertContains('Achat divers', $labels);
        self::assertSame(3, $review['pendingCount']);
    }

    public function testTheBiggestSpendsComeFirst(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->loginFixtureUser();

        $labels = array_column($this->review()['pending'], 'label');

        self::assertSame(['Concert', 'Achat divers', 'Livraison sushi'], $labels);
    }

    public function testAMonthNobodyHasLookedAtHasNoScoreRatherThanZero(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->loginFixtureUser();

        $review = $this->review();

        self::assertNull($review['optimisationScore']);
        self::assertFalse($review['isComplete']);
        self::assertSame(20000, $review['unratedCents']);
    }

    public function testRatingASpendMovesItOutOfThePendingList(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->loginFixtureUser();

        $result = json_decode(
            $this->tool()('rate', transactionId: (string) $this->getFixture('concert')->getId(), verdict: 'keep'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($result['success']);
        self::assertSame('keep', $result['transaction']['retrospect']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(
            RetrospectVerdict::Keep,
            $em->find(Transaction::class, $this->getFixture('concert')->getId())->getRetrospect(),
        );

        $review = $this->review();
        self::assertSame(2, $review['pendingCount']);
        self::assertSame(12000, $review['keptCents']);

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testTheScoreIsTheShareOfTheJudgedAmountWorthKeeping(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->loginFixtureUser();

        // 120 € kept, 30 € avoidable: 80 % of what was judged is worth keeping.
        $this->rate('concert', 'keep');
        $this->rate('takeaway', 'avoidable');

        $review = $this->review();

        self::assertSame(12000, $review['keptCents']);
        self::assertSame(3000, $review['avoidableCents']);
        self::assertSame(80, $review['optimisationScore']);
        // One spend is still waiting, so the review is not done.
        self::assertFalse($review['isComplete']);
    }

    public function testTheScoreWeighsAmountsNotLineCounts(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->loginFixtureUser();

        // Two small avoidable spends against one large kept one.
        $this->rate('concert', 'keep');
        $this->rate('takeaway', 'avoidable');
        $this->rate('unclassified', 'avoidable');

        $review = $this->review();

        // By line count this would be 33 %; by amount it is 60 %.
        self::assertSame(60, $review['optimisationScore']);
        self::assertTrue($review['isComplete']);
        self::assertSame(0, $review['pendingCount']);
    }

    public function testAFullyKeptMonthScoresFullMarks(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->loginFixtureUser();

        $this->rate('concert', 'keep');
        $this->rate('takeaway', 'keep');
        $this->rate('unclassified', 'keep');

        self::assertSame(100, $this->review()['optimisationScore']);
    }

    public function testAVerdictCanBeTakenBack(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->loginFixtureUser();

        $this->rate('concert', 'avoidable');
        $this->rate('concert', 'unrated');

        $review = $this->review();

        self::assertSame(0, $review['avoidableCents']);
        self::assertSame(3, $review['pendingCount']);
    }

    public function testAnUnknownVerdictIsRefused(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            $this->tool()('rate', transactionId: (string) $this->getFixture('concert')->getId(), verdict: 'maybe'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayHasKey('error', $data);
    }

    public function testTheMonthIsPutNextToRecentOnesAndLastYear(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->loginFixtureUser();

        $comparison = $this->review()['comparison'];

        // August: 900 + 120 + 30 + 50 €.
        self::assertSame(110000, $comparison['thisMonthCents']);
        // July: 600 €.
        self::assertSame(60000, $comparison['previousMonthCents']);
        // Three months to May: only July had anything.
        self::assertSame(20000, $comparison['recentAverageCents']);
        // August last year: 1 500 €.
        self::assertSame(150000, $comparison['sameMonthLastYearCents']);
    }

    public function testUnknownActionIsReported(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('summarise'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }
}
