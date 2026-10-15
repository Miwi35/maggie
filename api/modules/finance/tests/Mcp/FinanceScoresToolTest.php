<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Mcp\Tool\GetFinanceScoresTool;
use Maggie\Finance\Tests\UseCase\FinanceScoresWorld;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** A pure read: no Mercure update is published and nothing is indexed. */
class FinanceScoresToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use FinanceScoresWorld;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures($this->financeScoresFixture());
    }

    /** @return array<string, mixed> */
    private function call(?string $date = null): array
    {
        $tool = self::getContainer()->get(GetFinanceScoresTool::class);

        return json_decode($tool($date), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserItRefuses(): void
    {
        self::assertArrayHasKey('error', $this->call('2026-10-13'));
    }

    public function testAnInvalidDateIsAnError(): void
    {
        $this->loginFixtureUser('scores_user');

        foreach (['2026-13-01', '2026-02-30', 'demain'] as $bad) {
            self::assertArrayHasKey('error', $this->call($bad), $bad);
        }
    }

    public function testReadsTheScoresAndTheRhythmOfTheUser(): void
    {
        $this->loginFixtureUser('scores_user');
        $this->spendGroceries('2026-10-03', 22000);

        $scores = $this->call('2026-10-13');

        self::assertSame('2026-10-13', $scores['date']);
        self::assertSame(100000, $scores['month']['rhythm']['spentCents']);
        self::assertSame(108194, $scores['month']['rhythm']['expectedSpentCents']);
        self::assertSame('Dans le rythme', $scores['month']['rhythm']['label']);
        self::assertSame('neutral', $scores['month']['status']);
        self::assertCount(31, $scores['month']['rhythm']['curve']);
        self::assertSame(16258, $scores['week']['rhythm']['budgetCents']);
    }

    public function testOnlyTheUsersOwnData(): void
    {
        $this->loginFixtureUser('other_user');
        $this->spendGroceries('2026-10-03', 22000);

        $scores = $this->call('2026-10-13');

        self::assertSame('no_budget', $scores['month']['reasons'][0]['code']);
        self::assertSame(0, $scores['month']['rhythm']['spentCents']);
    }
}
