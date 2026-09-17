<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Mcp\Tool\ManageEnvelopesTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * How a transaction's status weighs on its envelope, and how a period's
 * envelopes are carried over to the next one.
 */
class EnvelopeStatusToolsTest extends KernelTestCase
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

    private function tool(): ManageEnvelopesTool
    {
        return self::getContainer()->get(ManageEnvelopesTool::class);
    }

    /** @return array<string, mixed> */
    private function budgetFor(string $categoryName, int $year, int $month): array
    {
        $data = json_decode($this->tool()('status', year: $year, month: $month), true, 512, JSON_THROW_ON_ERROR);

        return array_column($data['budgets'], null, 'categoryName')[$categoryName];
    }

    public function testEachStatusWeighsOnTheEnvelopeAccordingToItsMeaning(): void
    {
        $this->loadFixtures('envelope_statuses.yaml');
        $this->loginFixtureUser();

        $travel = $this->budgetFor('Voyages', 2026, 9);

        self::assertSame(200000, $travel['amountCents']);
        self::assertSame(50000, $travel['spentCents']);
        self::assertSame(30000, $travel['committedCents']);
        self::assertSame(80000, $travel['plannedCents']);
        self::assertSame(120000, $travel['toArbitrateCents']);

        // Money already out: the past trip plus the ticket bought for June.
        self::assertSame(80000, $travel['consumedCents']);
        self::assertSame(120000, $travel['remainingCents']);

        // What is actually free once the planned trip is set aside.
        self::assertSame(40000, $travel['availableCents']);

        // The Japan trip is only being weighed up, so it changes nothing.
        self::assertFalse($travel['isOverspent']);
        self::assertFalse($travel['isOvercommitted']);
    }

    public function testPlansBeyondTheBudgetAreFlaggedWithoutCountingAsOverspent(): void
    {
        $this->loadFixtures('envelope_statuses.yaml');
        $this->loginFixtureUser();
        $envelope = $this->getFixture('travel_2026');

        // Drop the budget under consumed + planned, but above consumed alone.
        $this->tool()('update', envelopeId: (string) $envelope->getId(), amountCents: 100000);

        $travel = $this->budgetFor('Voyages', 2026, 9);

        self::assertFalse($travel['isOverspent']);
        self::assertTrue($travel['isOvercommitted']);
        self::assertSame(-60000, $travel['availableCents']);
    }

    public function testTotalsSeparateSpentCommittedAndPlanned(): void
    {
        $this->loadFixtures('envelope_statuses.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('status', year: 2026, month: 9), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(240000, $data['totalBudgetedCents']);
        self::assertSame(75000, $data['totalSpentCents']);
        self::assertSame(30000, $data['totalCommittedCents']);
        self::assertSame(80000, $data['totalPlannedCents']);
        self::assertSame(105000, $data['totalConsumedCents']);
        self::assertSame(135000, $data['totalRemainingCents']);
        self::assertSame(55000, $data['totalAvailableCents']);
    }

    public function testRolloverCopiesTheMonthlyEnvelopesForward(): void
    {
        $this->loadFixtures('envelope_statuses.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            $this->tool()('rollover', fromYear: 2026, fromMonth: 9, year: 2026, month: 10),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame(1, $data['created']);
        self::assertSame(0, $data['skipped']);
        self::assertSame(40000, $data['envelopes'][0]['amountCents']);
        self::assertSame(10, $data['envelopes'][0]['month']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(3, $em->getRepository(Envelope::class)->findAll());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testRolloverCanBudgetOnWhatWasActuallyConsumed(): void
    {
        $this->loadFixtures('envelope_statuses.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            $this->tool()('rollover', fromYear: 2026, fromMonth: 9, year: 2026, month: 10, useActualSpending: true),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        // September budgeted 400 € but only 250 € went out.
        self::assertSame(25000, $data['envelopes'][0]['amountCents']);
    }

    public function testRolloverLeavesAnExistingEnvelopeAlone(): void
    {
        $this->loadFixtures('envelope_statuses.yaml');
        $this->loginFixtureUser();

        $this->tool()('rollover', fromYear: 2026, fromMonth: 9, year: 2026, month: 10);
        $data = json_decode(
            $this->tool()('rollover', fromYear: 2026, fromMonth: 9, year: 2026, month: 10),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame(0, $data['created']);
        self::assertSame(1, $data['skipped']);
    }

    public function testRolloverCarriesAnnualEnvelopesToTheNextYear(): void
    {
        $this->loadFixtures('envelope_statuses.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            $this->tool()('rollover', fromYear: 2026, year: 2027),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame(1, $data['created']);
        self::assertSame('annual', $data['envelopes'][0]['mode']);
        self::assertSame(2027, $data['envelopes'][0]['year']);
        self::assertNull($data['envelopes'][0]['month']);
    }

    public function testRolloverRequiresBothYears(): void
    {
        $this->loadFixtures('envelope_statuses.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('rollover', fromYear: 2026), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }
}
