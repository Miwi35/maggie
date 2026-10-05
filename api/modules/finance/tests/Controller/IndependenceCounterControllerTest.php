<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class IndependenceCounterControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', '/api/finance/independence');

        self::assertResponseStatusCodeSame(401);
    }

    public function testItComparesTheMeasuredRentesToTheMeasuredLifestyle(): void
    {
        $counter = $this->counterFor('independence.yaml');

        // 30 000 consumed and 24 000 of rentes over the three-month sample.
        self::assertSame(10000, $counter['lifestyleCents']);
        self::assertSame(8000, $counter['passiveIncomeCents']);
        self::assertSame(80, $counter['coveragePercent']);
        self::assertSame(2000, $counter['gapCents']);
        self::assertSame(3, $counter['sampleMonths']);
        self::assertTrue($counter['isMeasurable']);
        self::assertTrue($counter['hasPassiveIncomeCategories']);
        self::assertFalse($counter['isReached']);
    }

    /**
     * A salary is income too, and it is the one thing the counter must not
     * count: 350 000 € of salary would read as independence reached.
     */
    public function testOnlyTheCategoriesDeclaredAsRentesAreCounted(): void
    {
        $counter = $this->counterFor('independence.yaml');

        self::assertSame(
            ['Loyers perçus', 'Dividendes'],
            array_column($counter['byCategory'], 'categoryName'),
        );
        self::assertSame(6000, $counter['byCategory'][0]['monthlyCents']);
        self::assertSame(75, $counter['byCategory'][0]['sharePercent']);
        self::assertSame(2000, $counter['byCategory'][1]['monthlyCents']);
        self::assertSame(25, $counter['byCategory'][1]['sharePercent']);
    }

    /**
     * The sale and the planned rent are both on a rente category, and neither
     * is a rente: the figure above is the proof they were left out, and
     * removing them must not move it.
     */
    public function testAOneOffAndAPlannedCreditAreLeftOut(): void
    {
        $this->loadFixtures('independence.yaml');
        $this->removeFixtures('flat_sale', 'rent_planned');

        self::assertSame(8000, $this->counter()['passiveIncomeCents']);
    }

    public function testWhileALoanRunsTheMonthCostsMoreThanTheTrainDeVie(): void
    {
        $counter = $this->counterFor('independence.yaml');

        self::assertSame(20000, $counter['loanPaymentsCents']);
        self::assertSame(30000, $counter['monthlyNeedCents']);
        self::assertSame(26, $counter['coverageWithDebtPercent']);
    }

    public function testTheMilestonesSayWhatIsBehindAndWhatTheNextOneTakes(): void
    {
        $counter = $this->counterFor('independence.yaml');

        self::assertSame(
            [[25, true], [50, true], [75, true], [100, false]],
            array_map(
                static fn (array $milestone) => [$milestone['percent'], $milestone['isReached']],
                $counter['milestones'],
            ),
        );
        self::assertSame(2500, $counter['milestones'][0]['monthlyIncomeNeededCents']);
        self::assertSame(100, $counter['nextMilestonePercent']);
        self::assertSame(2000, $counter['nextMilestoneGapCents']);
        // No date: the target date of independence is Premium, and an empty
        // one would read as a promise.
        self::assertArrayNotHasKey('targetDate', $counter);
    }

    /**
     * A rente just short of the train de vie must not read 100 %: the
     * percentage is truncated, so 100 means covered and nothing else.
     */
    public function testAlmostCoveredIsNotReportedAsCovered(): void
    {
        $this->loadFixtures('independence.yaml');
        $this->raiseRenteTo(29999);

        $counter = $this->counter();

        self::assertSame(99, $counter['coveragePercent']);
        self::assertFalse($counter['isReached']);
        self::assertSame(1, $counter['gapCents']);
    }

    public function testNothingMeasuredYetIsSaidRatherThanShownAsZeroPercent(): void
    {
        $counter = $this->counterFor('independence_bare.yaml');

        self::assertFalse($counter['isMeasurable']);
        self::assertSame(0, $counter['coveragePercent']);
        self::assertSame(0, $counter['lifestyleCents']);
        self::assertTrue($counter['hasPassiveIncomeCategories']);
        self::assertNull($counter['nextMilestonePercent']);
        self::assertNull($counter['nextMilestoneGapCents']);
    }

    public function testAUserWithoutAnyRenteCategoryIsToldSo(): void
    {
        // Same fixture as the debt timeline: a measured lifestyle, no rente.
        $counter = $this->counterFor('debt_timeline_lifestyle.yaml');

        self::assertFalse($counter['hasPassiveIncomeCategories']);
        self::assertTrue($counter['isMeasurable']);
        self::assertSame(0, $counter['passiveIncomeCents']);
        self::assertSame(0, $counter['coveragePercent']);
        self::assertSame([], $counter['byCategory']);
        self::assertSame(25, $counter['nextMilestonePercent']);
        self::assertSame(2500, $counter['nextMilestoneGapCents']);
    }

    /** Above 100 % the rentes cover more than the month costs — not capped. */
    public function testCoverageBeyondTheTrainDeVieIsNotCapped(): void
    {
        $counter = $this->counterFor('independence_reached.yaml');

        self::assertSame(200, $counter['coveragePercent']);
        self::assertTrue($counter['isReached']);
        self::assertSame(0, $counter['gapCents']);
        self::assertNull($counter['nextMilestonePercent']);
    }

    public function testTheDashboardServesTheCounterInItsSingleRead(): void
    {
        $this->loadFixtures('independence.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/dashboard', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(80, $data['independence']['coveragePercent']);
    }

    /** @return array<string, mixed> */
    private function counterFor(string $fixture): array
    {
        $this->loadFixtures($fixture);

        return $this->counter();
    }

    /** @return array<string, mixed> */
    private function counter(): array
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/independence', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** Brings the rentes to one cent short of the 10 000 lifestyle. */
    private function raiseRenteTo(int $amountCents): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->getFixture('rent_in')->setAmountCents($amountCents);
        $em->remove($this->getFixture('dividends_in'));
        $em->flush();
    }

    private function removeFixtures(string ...$refs): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        foreach ($refs as $ref) {
            $em->remove($this->getFixture($ref));
        }

        $em->flush();
    }
}
