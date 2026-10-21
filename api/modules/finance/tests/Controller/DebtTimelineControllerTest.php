<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Enum\LoanType;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class DebtTimelineControllerTest extends WebTestCase
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
        $this->client->request('GET', '/api/finance/debt-timeline');

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnAbsurdHorizonReturns400(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/debt-timeline?months=0', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testReturnsTheScheduleAndTheSavingCapacity(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/debt-timeline', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(60, $data['horizonMonths']);
        self::assertSame(360000, $data['totalPrincipalRemainingCents']);
        self::assertCount(2, $data['loans']);
        self::assertCount(2, $data['reliefByMonth']);
        self::assertSame(290000, $data['savingCapacity']['netCapacityCents']);
    }

    public function testTheLifestyleIgnoresExceptionalSpendsAndLoanPayments(): void
    {
        $this->loadFixtures('debt_timeline_lifestyle.yaml');

        self::assertSame(10000, $this->estimatedLifestyleCents());
    }

    public function testAnExceptionalSpendIsLeftOutOfTheLifestyle(): void
    {
        $this->loadFixtures('debt_timeline_lifestyle.yaml');
        $this->removeFixture('car_payment');

        self::assertSame(10000, $this->estimatedLifestyleCents());
    }

    public function testALoanPaymentIsLeftOutOfTheLifestyle(): void
    {
        $this->loadFixtures('debt_timeline_lifestyle.yaml');
        $this->removeFixture('one_off');

        self::assertSame(10000, $this->estimatedLifestyleCents());
    }

    public function testTheHorizonCanBeNarrowed(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/debt-timeline?months=6', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(6, $data['horizonMonths']);
        // Only the student loan ends within six months.
        self::assertCount(1, $data['reliefByMonth']);
    }

    /** @return array<string, mixed> */
    private function read(string $path): array
    {
        $this->client->request('GET', $path, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function addLoan(Loan $loan): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        // Requests reboot the kernel: the fixture user belongs to another manager by now.
        $em->persist($loan->setUser($em->find(User::class, $this->getFixture('test_user')->getId())));
        $em->flush();
    }

    public function testAProjectedLoanChangesNeitherTheTimelineNorTheCapacityNorTheDashboard(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $timeline = $this->read('/api/finance/debt-timeline');
        $dashboard = $this->read('/api/finance/dashboard')['savingCapacity'];

        $this->addLoan((new Loan())
            ->setName('Prêt du projet')
            ->setPrincipalRemainingCents(1000000)
            ->setMonthlyPaymentCents(50000)
            ->setAnnualRateBasisPoints(300)
            ->setInsuranceMonthlyCents(1500)
            ->setProjected(true));

        self::assertSame($timeline, $this->read('/api/finance/debt-timeline'));
        self::assertSame($dashboard, $this->read('/api/finance/dashboard')['savingCapacity']);
    }

    public function testTheInsuranceIsPaidOutOfTheSavingCapacity(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->addLoan((new Loan())
            ->setName('Prêt assuré')
            ->setPrincipalRemainingCents(120000)
            ->setMonthlyPaymentCents(10000)
            ->setInsuranceMonthlyCents(1500));

        $data = $this->read('/api/finance/debt-timeline');

        // 200 + 400 + 100 € of payments, and 15 € of insurance.
        self::assertSame(71500, $data['savingCapacity']['loanPaymentsCents']);
        self::assertSame(290000 - 11500, $data['savingCapacity']['netCapacityCents']);
        self::assertSame(70000, $data['totalMonthlyPaymentCents']);
    }

    public function testAnInFineLoanFreesUpAtItsLastPayment(): void
    {
        $this->loadFixtures('loan.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->addLoan((new Loan())
            ->setName('Prêt in fine')
            ->setType(LoanType::InFine)
            ->setPrincipalRemainingCents(1000000)
            ->setMonthlyPaymentCents(2500)
            ->setAnnualRateBasisPoints(300)
            ->setDurationMonths(12));

        $loans = array_column($this->read('/api/finance/debt-timeline')['loans'], null, 'name');

        self::assertSame(12, $loans['Prêt in fine']['monthsRemaining']);
        self::assertSame('in_fine', $loans['Prêt in fine']['type']);
        self::assertSame(30000, $loans['Prêt in fine']['totalInterestCents']);
    }

    private function removeFixture(string $ref): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->remove($this->getFixture($ref));
        $em->flush();
    }

    private function estimatedLifestyleCents(): int
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/debt-timeline', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        return $data['savingCapacity']['estimatedLifestyleCents'];
    }
}
