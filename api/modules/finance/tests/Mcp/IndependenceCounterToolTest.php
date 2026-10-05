<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Mcp\Tool\GetIndependenceCounterTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class IndependenceCounterToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    public function testWithoutAUserBoundItReportsTheError(): void
    {
        $this->loadFixtures('independence.yaml');

        $data = $this->counter();

        self::assertArrayHasKey('error', $data);
    }

    public function testItReadsTheCoverageAndItsBreakdown(): void
    {
        $this->loadFixtures('independence.yaml');
        $this->loginFixtureUser();

        $data = $this->counter();

        self::assertSame(80, $data['coveragePercent']);
        self::assertSame(10000, $data['lifestyleCents']);
        self::assertSame(8000, $data['passiveIncomeCents']);
        self::assertSame(2000, $data['gapCents']);
        self::assertSame(100, $data['nextMilestonePercent']);
        self::assertSame(
            ['Loyers perçus', 'Dividendes'],
            array_column($data['byCategory'], 'categoryName'),
        );
    }

    /**
     * The agent must be able to tell "you have not declared a rente yet" from
     * "your rentes cover nothing": the first is something the user can act on.
     */
    public function testItSaysWhenThereIsNothingToMeasureYet(): void
    {
        $this->loadFixtures('independence_bare.yaml');
        $this->loginFixtureUser();

        $data = $this->counter();

        self::assertFalse($data['isMeasurable']);
        self::assertTrue($data['hasPassiveIncomeCategories']);
        self::assertSame(0, $data['coveragePercent']);
    }

    /**
     * « You have not declared a rente yet » is the one answer the agent can
     * turn into something to do; 0 % is not.
     */
    public function testItSaysWhenNoRenteIsDeclared(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $data = $this->counter();

        self::assertFalse($data['hasPassiveIncomeCategories']);
        self::assertSame([], $data['byCategory']);
        self::assertSame(0, $data['passiveIncomeCents']);
    }

    /** @return array<string, mixed> */
    private function counter(): array
    {
        $tool = self::getContainer()->get(GetIndependenceCounterTool::class);

        return json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);
    }
}
