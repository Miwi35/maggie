<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Enum\LoanType;
use Maggie\Finance\Enum\PrepaymentPenaltyMode;
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

    public function testClearEmptiesTheLender(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();
        $loan = $this->getFixture('car');

        $data = json_decode(
            $this->tool()('update', loanId: (string) $loan->getId(), clear: ['lender', 'name']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertNull($data['loan']['lender']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Loan::class, $loan->getId());
        self::assertNull($refreshed->getLender());
        self::assertSame('Crédit auto', $refreshed->getName(), 'Required fields cannot be cleared');
        self::assertSame(240000, $refreshed->getPrincipalRemainingCents());

        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);
    }

    public function testClearOnAnUnknownLoanReturnsAnError(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            $this->tool()('update', loanId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['lender']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayHasKey('error', $data);
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

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        return json_decode($this->tool()($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function reloadLoan(string $id): Loan
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Loan::class, $id);
    }

    public function testEveryActionNeedsAUser(): void
    {
        $this->loadFixtures('loan.yaml');

        $data = $this->call('early_repayment', loanId: (string) $this->getFixture('car')->getId());

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
    }

    public function testCreateADetailedLoanPersistsAndPublishes(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $data = $this->call(
            'create',
            name: 'Prêt différé',
            principalRemainingCents: 1000000,
            monthlyPaymentCents: 50000,
            annualRateBasisPoints: 300,
            type: 'deferred_total',
            deferralMonths: 6,
            projected: true,
            insuranceRateBasisPoints: 30,
            initialAmountCents: 1000000,
            firstPaymentOn: '2026-12-05',
        );

        self::assertTrue($data['success']);
        self::assertSame('deferred_total', $data['loan']['type']);
        self::assertTrue($data['loan']['projected']);

        $loan = $this->reloadLoan($data['loan']['id']);
        self::assertSame(LoanType::DeferredTotal, $loan->getType());
        self::assertSame(6, $loan->getDeferralMonths());
        self::assertSame('2026-12-05', $loan->getFirstPaymentOn()?->format('Y-m-d'));
        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);
    }

    public function testAProjectedLoanIsListedWithItsFlagButLeftOutOfTheTimeline(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();
        $before = $this->timeline();

        $this->call('create', name: 'Prêt du projet', principalRemainingCents: 1000000, monthlyPaymentCents: 50000, projected: true);

        $listed = array_column($this->call('list')['loans'], 'projected', 'name');
        self::assertSame(['Crédit auto' => false, 'Prêt étudiant' => false, 'Prêt du projet' => true], $listed);
        self::assertSame($before, $this->timeline());
    }

    public function testACreateMissingWhatTheTypeNeedsAnswersTheErrorAndWhatWasGiven(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Prêt à paliers', principalRemainingCents: 1000000, monthlyPaymentCents: 50000, type: 'stepped');

        self::assertStringContainsString('steps', $data['error']);
        self::assertSame('stepped', $data['current']['type']);
        self::assertSame('Prêt à paliers', $data['current']['name']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(2, $em->getRepository(Loan::class)->findAll());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnUnknownValueIsRefusedWithTheAllowedOnes(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        $data = $this->call('update', loanId: (string) $this->getFixture('car')->getId(), prepaymentPenaltyMode: 'plafond');

        self::assertStringContainsString('legal_cap, fixed, exempt, consumer', $data['error']);
        $this->assertMercureUpdateCount(0);
    }

    public function testUpdateSetsThePenaltyModeAndPublishes(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();
        $id = (string) $this->getFixture('car')->getId();

        $data = $this->call('update', loanId: $id, prepaymentPenaltyMode: 'fixed', prepaymentPenaltyFixedCents: 150000);

        self::assertTrue($data['success']);
        self::assertSame('fixed', $data['loan']['prepaymentPenaltyMode']);
        $loan = $this->reloadLoan($id);
        self::assertSame(PrepaymentPenaltyMode::Fixed, $loan->getPrepaymentPenaltyMode());
        self::assertSame(150000, $loan->getPrepaymentPenaltyFixedCents());
        // Unchanged: only the fields given move.
        self::assertSame('Crédit auto', $loan->getName());
        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);
    }

    public function testARefusedUpdateAnswersTheLoanAsItStillIs(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();
        $id = (string) $this->getFixture('car')->getId();

        $data = $this->call('update', loanId: $id, name: 'Renommé', type: 'deferred_partial');

        self::assertStringContainsString('deferralMonths', $data['error']);
        self::assertSame('amortizing_fixed', $data['current']['type']);
        self::assertSame('Crédit auto', $data['current']['name']);
        self::assertSame(LoanType::AmortizingFixed, $this->reloadLoan($id)->getType());
        $this->assertMercureUpdateCount(0);
    }

    public function testClearEmptiesACondition(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();
        $id = (string) $this->getFixture('car')->getId();
        $this->call('update', loanId: $id, feesCents: 50000, noticeDays: 30);

        $data = $this->call('update', loanId: $id, clear: ['feesCents']);

        self::assertTrue($data['success']);
        self::assertNull($this->reloadLoan($id)->getFeesCents());
        self::assertSame(30, $this->reloadLoan($id)->getNoticeDays());
    }

    public function testEarlyRepaymentAtTheLegalCap(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();
        $created = $this->call('create', name: 'Prêt immo', principalRemainingCents: 9800000, monthlyPaymentCents: 62500, annualRateBasisPoints: 180);

        $data = $this->call('early_repayment', loanId: $created['loan']['id']);

        self::assertSame(9800000, $data['amountCents']);
        self::assertSame('legal_cap', $data['penaltyMode']);
        self::assertSame(88200, $data['penaltyCents']);
        self::assertSame($data['remainingInterestCents'] - 88200, $data['realGainCents']);
    }

    public function testEarlyRepaymentOfAPartOfTheCapital(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        // 2 400 € without interest: nothing saved, and the cap is 3 % of 2 400 €.
        $data = $this->call('early_repayment', loanId: (string) $this->getFixture('car')->getId(), amountCents: 100000);

        self::assertSame(100000, $data['amountCents']);
        self::assertSame(0, $data['penaltyCents']);
        self::assertSame(0, $data['remainingInterestCents']);
    }

    public function testEarlyRepaymentOnALoanThatForbidsItIsRefused(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();
        $id = (string) $this->getFixture('car')->getId();
        $this->call('update', loanId: $id, earlyRepaymentAllowed: false);

        $data = $this->call('early_repayment', loanId: $id);

        self::assertStringContainsString('earlyRepaymentAllowed', $data['error']);
        self::assertFalse($data['current']['earlyRepaymentAllowed']);
    }

    public function testEarlyRepaymentNeedsALoan(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->call('early_repayment'));
        self::assertStringContainsString('not found', $this->call('early_repayment', loanId: 'nope')['error']);
    }
}
