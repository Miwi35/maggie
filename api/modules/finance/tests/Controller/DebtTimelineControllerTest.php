<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
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
