<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Mcp\Tool\GetFinanceDashboardTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class FinanceDashboardToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    /** @return array<string, mixed> */
    private function dashboard(int $year = 2026, int $month = 9): array
    {
        $tool = self::getContainer()->get(GetFinanceDashboardTool::class);

        return json_decode($tool($year, $month), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testItGathersEveryPieceOfTheMonthInOneCall(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->loginFixtureUser();

        $dashboard = $this->dashboard();

        self::assertArrayHasKey('score', $dashboard);
        self::assertArrayHasKey('balance', $dashboard);
        self::assertArrayHasKey('monthlyFlows', $dashboard);
        self::assertArrayHasKey('budgets', $dashboard);
        self::assertArrayHasKey('topPosts', $dashboard);
        self::assertArrayHasKey('savingCapacity', $dashboard);
    }

    public function testTheBalanceSeparatesWhatIsSetAsideAsTheCushion(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->loginFixtureUser();

        $balance = $this->dashboard()['balance'];

        // 2 000 € on the current account, 7 500 € on the cushion savings.
        self::assertSame(950000, $balance['totalCents']);
        self::assertSame(750000, $balance['cushionCents']);
        self::assertSame(200000, $balance['availableCents']);
        self::assertCount(2, $balance['accounts']);
    }

    public function testTwelveMonthsComeBackWithoutHoles(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->loginFixtureUser();

        $flows = $this->dashboard()['monthlyFlows'];

        self::assertCount(12, $flows);
        self::assertSame('2025-10', $flows[0]['month']);
        self::assertSame('2026-09', $flows[11]['month']);

        // A month without a movement is present at zero rather than missing.
        self::assertSame(0, $flows[0]['incomeCents']);
        self::assertSame(0, $flows[0]['expenseCents']);
    }

    public function testTheMonthSeparatesMoneyInFromMoneyOut(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->loginFixtureUser();

        $september = array_column($this->dashboard()['monthlyFlows'], null, 'month')['2026-09'];

        // 3 500 € of salary against 200 € + 50 € of spending.
        self::assertSame(350000, $september['incomeCents']);
        self::assertSame(25000, $september['expenseCents']);
        self::assertSame(325000, $september['netCents']);
    }

    public function testTopPostsAreRankedAndComparedWithLastMonth(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->loginFixtureUser();

        $posts = $this->dashboard()['topPosts'];

        self::assertSame('Alimentation', $posts[0]['categoryName']);
        self::assertSame(20000, $posts[0]['spentCents']);
        // August had 300 € on the same post: spending is down 100 €.
        self::assertSame(30000, $posts[0]['previousMonthCents']);
        self::assertSame(-10000, $posts[0]['changeCents']);

        self::assertSame('Loisirs', $posts[1]['categoryName']);
        // Nothing last month, so the whole amount is the change.
        self::assertSame(0, $posts[1]['previousMonthCents']);
        self::assertSame(5000, $posts[1]['changeCents']);
    }

    public function testItCarriesTheSameScoreAsTheScoreTool(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->loginFixtureUser();

        $dashboard = $this->dashboard();

        self::assertSame('green', $dashboard['score']['score']);
        self::assertCount(2, $dashboard['budgets']);
    }

    public function testAMonthWithNothingInItStillAnswers(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->loginFixtureUser();

        $dashboard = $this->dashboard(2026, 11);

        self::assertSame([], $dashboard['topPosts']);
        self::assertSame([], $dashboard['budgets']);
        self::assertCount(12, $dashboard['monthlyFlows']);
        self::assertSame('neutral', $dashboard['score']['score']);
    }
}
