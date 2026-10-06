<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Mcp\Tool\PlanAnnualBudgetTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class PlanAnnualBudgetToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    /** The world is shared with the controller suite: two copies would drift. */
    private const FIXTURE = __DIR__.'/../Controller/fixtures/annual_plan.yaml';

    protected function setUp(): void
    {
        self::bootKernel();
    }

    public function testItReportsTheYearToPrepare(): void
    {
        $this->signIn();

        $plan = $this->call(year: 2027);

        self::assertSame(2027, $plan['year']);
        self::assertSame(2026, $plan['sourceYear']);
        self::assertSame(165800, $plan['totalLastYearConsumedCents']);
        self::assertSame(143000, $plan['totalSuggestedCents']);
        self::assertSame(['Voyages', 'Courses', 'Loisirs'], array_column($plan['categories'], 'categoryName'));
    }

    public function testItDefaultsToTheYearBeingPrepared(): void
    {
        $this->signIn();

        $now = new \DateTimeImmutable();
        $expected = (int) $now->format('Y') + ((int) $now->format('n') >= 11 ? 1 : 0);

        self::assertSame($expected, $this->call()['year']);
    }

    public function testTheThresholdDecidesWhichExpensesComeBack(): void
    {
        $this->signIn();

        $plan = $this->call(year: 2027, thresholdCents: 20000);

        self::assertSame(
            ['Festival des Vieilles Charrues'],
            array_column($this->categoryNamed($plan, 'Loisirs')['lastYear']['events'], 'label'),
        );
    }

    public function testACallNobodyIsBoundToIsRefused(): void
    {
        $this->loadFixtures(self::FIXTURE);

        $result = $this->call(year: 2027);

        self::assertSame(MissingMcpUserException::MESSAGE, $result['error']);
        self::assertArrayNotHasKey('categories', $result);
    }

    public function testAYearNoSessionCouldBeAboutIsRefused(): void
    {
        $this->signIn();

        // The HTTP side has always checked the range; the tool used to have no
        // check at all, so the two channels disagreed.
        $result = $this->call(year: 20330);

        self::assertStringContainsString('year must be between 2000 and 2100', $result['error']);
        self::assertArrayNotHasKey('categories', $result);
    }

    public function testItOnlySeesItsOwnUser(): void
    {
        $this->loadFixtures(self::FIXTURE);
        $this->loginFixtureUser('other_user');

        $plan = $this->call(year: 2027);

        self::assertSame(['Loisirs'], array_column($plan['categories'], 'categoryName'));
        self::assertSame(99000, $plan['totalLastYearConsumedCents']);
    }

    private function signIn(): void
    {
        $this->loadFixtures(self::FIXTURE);
        $this->loginFixtureUser();
    }

    /** @return array<string, mixed> */
    private function call(?int $year = null, ?int $thresholdCents = null): array
    {
        /** @var PlanAnnualBudgetTool $tool */
        $tool = self::getContainer()->get(PlanAnnualBudgetTool::class);

        return json_decode($tool($year, $thresholdCents), true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * @param array<string, mixed> $plan
     *
     * @return array<string, mixed>
     */
    private function categoryNamed(array $plan, string $name): array
    {
        foreach ($plan['categories'] as $category) {
            if ($name === $category['categoryName']) {
                return $category;
            }
        }

        self::fail("No category named {$name} in the plan.");
    }
}
