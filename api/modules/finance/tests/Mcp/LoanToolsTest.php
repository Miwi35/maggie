<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Mcp\Tool\ManageLoansTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class LoanToolsTest extends KernelTestCase
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

    private function tool(): ManageLoansTool
    {
        return self::getContainer()->get(ManageLoansTool::class);
    }

    /** @return array<string, mixed> */
    private function timeline(?int $horizonMonths = null): array
    {
        return json_decode(
            $this->tool()('timeline', horizonMonths: $horizonMonths),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
    }

    public function testCreateLoanPersistsAndPublishes(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $result = $this->tool()(
            'create',
            name: 'Prêt travaux',
            principalRemainingCents: 600000,
            monthlyPaymentCents: 50000,
            annualRateBasisPoints: 350,
        );

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Prêt travaux', $data['loan']['name']);
        self::assertSame(350, $data['loan']['annualRateBasisPoints']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(3, $em->getRepository(Loan::class)->findAll());

        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);
    }

    public function testALoanThatWouldNeverBeRepaidIsRefused(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        // 10 000 € at 5 % is about 41 € of interest a month; paying 20 € never ends.
        $data = json_decode(
            $this->tool()('create', name: 'Piège', principalRemainingCents: 1000000, monthlyPaymentCents: 2000, annualRateBasisPoints: 500),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('never be repaid', $data['error']);
    }

    public function testCreateRequiresTheThreeFiguresThatDefineALoan(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('create', name: 'Incomplet'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testListReturnsLoansByPriority(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('list'), true, 512, JSON_THROW_ON_ERROR);

        self::assertCount(2, $data['loans']);
        self::assertSame('Crédit auto', $data['loans'][0]['name']);
    }

    public function testUpdateChangesTheLoanAndPublishes(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();
        $loan = $this->getFixture('car');

        $data = json_decode(
            $this->tool()('update', loanId: (string) $loan->getId(), principalRemainingCents: 180000),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame(180000, $data['loan']['principalRemainingCents']);

        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);
    }

    public function testDeleteRemovesPublishesAndDeletes(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();
        $loan = $this->getFixture('car');

        $data = json_decode($this->tool()('delete', loanId: (string) $loan->getId()), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Loan::class, $loan->getId()));

        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchDeleteDispatched('loans');
    }

    public function testAnInterestFreeLoanEndsAfterExactlyItsPayments(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $byName = array_column($this->timeline()['loans'], null, 'name');

        // 2 400 € at 200 €/month, no interest.
        self::assertSame(12, $byName['Crédit auto']['monthsRemaining']);
        self::assertSame(0, $byName['Crédit auto']['totalInterestCents']);
        self::assertFalse($byName['Crédit auto']['endsBeyondHorizon']);

        // 1 200 € at 400 €/month.
        self::assertSame(3, $byName['Prêt étudiant']['monthsRemaining']);
    }

    public function testInterestStretchesTheRepaymentBeyondTheBareDivision(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        // 12 000 € at 500 €/month would be 24 months without interest.
        $this->tool()('create', name: 'Prêt immo', principalRemainingCents: 1200000, monthlyPaymentCents: 50000, annualRateBasisPoints: 300);

        $byName = array_column($this->timeline()['loans'], null, 'name');

        self::assertGreaterThan(24, $byName['Prêt immo']['monthsRemaining']);
        self::assertGreaterThan(0, $byName['Prêt immo']['totalInterestCents']);
    }

    public function testALoanOutlivingTheHorizonIsReportedAsSuch(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $byName = array_column($this->timeline(6)['loans'], null, 'name');

        // The car loan needs 12 months; the horizon only shows 6.
        self::assertTrue($byName['Crédit auto']['endsBeyondHorizon']);
        self::assertNull($byName['Crédit auto']['monthsRemaining']);
        self::assertNull($byName['Crédit auto']['freedOn']);
    }

    public function testTheTimelineSaysWhenEachChargeFreesUpAndHowMuch(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $timeline = $this->timeline();
        $relief = $timeline['reliefByMonth'];

        // Student loan first (3 months, 400 €), then the car (12 months, 200 €).
        self::assertCount(2, $relief);
        self::assertSame(40000, $relief[0]['freedCents']);
        self::assertSame(['Prêt étudiant'], $relief[0]['loans']);
        self::assertSame(40000, $relief[0]['cumulativeFreedCents']);

        self::assertSame(20000, $relief[1]['freedCents']);
        self::assertSame(60000, $relief[1]['cumulativeFreedCents']);
        self::assertTrue($relief[0]['month'] < $relief[1]['month']);
    }

    public function testTheTimelineTotalsWhatIsOwedAndPaidEveryMonth(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $timeline = $this->timeline();

        self::assertSame(360000, $timeline['totalPrincipalRemainingCents']);
        self::assertSame(60000, $timeline['totalMonthlyPaymentCents']);
        self::assertSame(60, $timeline['horizonMonths']);
    }

    public function testSavingCapacityTakesLoansAndLifestyleOutOfTheIncome(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $capacity = $this->timeline()['savingCapacity'];

        // No transactions in the fixture, so lifestyle is nil and the capacity
        // is income minus the loan payments.
        self::assertSame(350000, $capacity['monthlyNetIncomeCents']);
        self::assertSame(60000, $capacity['loanPaymentsCents']);
        self::assertSame(0, $capacity['estimatedLifestyleCents']);
        self::assertSame(290000, $capacity['netCapacityCents']);
        self::assertTrue($capacity['isIncomeKnown']);
    }

    public function testUnknownActionIsReported(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('refinance'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }
}
