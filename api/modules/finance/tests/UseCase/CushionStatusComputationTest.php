<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FinanceDashboardRefreshTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Mcp\Tool\ManagePeerDebtsTool;
use Maggie\Finance\UseCase\GetCushionStatus;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** Cushion state computation : the use case behind get_finance_summary. */
class CushionStatusComputationTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FinanceDashboardRefreshTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function cushionStatus(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $user = $em->find(User::class, $this->getFixture('test_user')->getId());

        return self::getContainer()->get(GetCushionStatus::class)->execute($user);
    }

    private function setBalance(string $fixture, int $balanceCents): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $account = $em->find(Account::class, $this->getFixture($fixture)->getId());
        $account->setBalanceCents($balanceCents);
        $em->flush();
    }

    public function testCurrentAmountIsTheBalanceOfTheCushionAccounts(): void
    {
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/safety_cushion.yaml');
        $this->loginFixtureUser();

        $status = $this->cushionStatus();

        // 3000 € + 1500 €, and the current account is left out.
        self::assertSame(450000, $status['currentCents']);
        self::assertCount(2, $status['accounts']);
        self::assertSame(750000, $status['targetCents']);
    }

    public function testAnIncompleteCushionIsStillBuildingAndBlocksAGreenScore(): void
    {
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/safety_cushion.yaml');
        $this->loginFixtureUser();

        $status = $this->cushionStatus();

        self::assertSame('building', $status['state']);
        self::assertSame(300000, $status['deficitCents']);
        self::assertSame(60, $status['coveragePercent']);
        self::assertSame(1.8, $status['monthsCovered']);
        self::assertTrue($status['blocksGreenScore']);
    }

    public function testTheRechargeCapStretchesTheDurationRatherThanTheEffort(): void
    {
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/safety_cushion.yaml');
        $this->loginFixtureUser();

        $status = $this->cushionStatus();

        // 3000 € missing over 6 months would be 500 €/month, above the 150 € cap,
        // so the cap applies and the recharge takes 20 months instead of 6.
        self::assertSame(15000, $status['monthlyRechargeCents']);
        self::assertSame(20, $status['rechargeMonths']);
        self::assertTrue($status['isCappedByRechargeCap']);
    }

    public function testAComfortableCapKeepsTheWishedForHorizon(): void
    {
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/safety_cushion.yaml');
        $this->loginFixtureUser();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->find(SafetyCushion::class, $this->getFixture('cushion')->getId())->setRechargeCapCents(100000);
        $em->flush();
        $status = $this->cushionStatus();

        // 3000 € over 6 months = 500 €/month, which fits under a 1000 € cap.
        self::assertSame(50000, $status['monthlyRechargeCents']);
        self::assertSame(6, $status['rechargeMonths']);
        self::assertFalse($status['isCappedByRechargeCap']);
    }

    public function testReachingTheTargetCompletesTheCushion(): void
    {
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/safety_cushion.yaml');
        $this->loginFixtureUser();
        $this->setBalance('livret', 600000);
        // Reading never records the completion: the rebuild the balance change queued does.
        $this->drainDashboardRefreshes();

        $status = $this->cushionStatus();

        self::assertSame('complete', $status['state']);
        self::assertSame(0, $status['deficitCents']);
        self::assertSame(100, $status['coveragePercent']);
        self::assertSame(0, $status['monthlyRechargeCents']);
        self::assertFalse($status['blocksGreenScore']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNotNull(
            $em->find(SafetyCushion::class, $this->getFixture('cushion')->getId())->getCompletedAt(),
        );
    }

    public function testDippingIntoACompletedCushionPutsItInRecharge(): void
    {
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/safety_cushion.yaml');
        $this->loginFixtureUser();

        // Fill it up first, so it remembers having been complete...
        $this->setBalance('livret', 600000);
        $this->drainDashboardRefreshes();
        self::assertSame('complete', $this->cushionStatus()['state']);

        // ...then dip into it.
        $this->setBalance('livret', 400000);
        $status = $this->cushionStatus();

        self::assertSame('recharging', $status['state']);
        self::assertSame(200000, $status['deficitCents']);
        self::assertTrue($status['blocksGreenScore']);
    }

    public function testAnUnconfiguredUserReadsTheDefaultThresholdAndShare(): void
    {
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/user.yaml');
        $this->loginFixtureUser();

        $status = $this->cushionStatus();

        self::assertSame(2, $status['criticalMonths']);
        self::assertSame(60, $status['cushionSharePercent']);
    }

    public function testTheCriticalThresholdIsCountedInMonthsOfSpending(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/debt_timeline_lifestyle.yaml');
        $this->loginFixtureUser();

        $status = $this->cushionStatus();

        // A train de vie of 100 €/month and an empty cushion: 200 € to reach the threshold.
        self::assertSame(20000, $status['criticalCents']);
        self::assertSame(20000, $status['criticalDeficitCents']);
    }

    public function testStatusLowersTheCushionByTheOpenCreances(): void
    {
        $this->loadFixtures(__DIR__.'/../Api/fixtures/cushion_peer_claims.yaml');
        $this->loginFixtureUser();

        $status = $this->cushionStatus();

        // Livret A 5 200 € - 535 €; the settled créance, the dette (280 €), the
        // treasury advance (800 €) and another user's créance change nothing.
        self::assertSame(53500, $status['peerClaimsCents']);
        self::assertSame(466500, $status['currentCents']);
        self::assertSame(283500, $status['deficitCents']);
        self::assertEqualsCanonicalizing(['Gîte de Crozon', 'Anniversaire de Léa', 'Hellfest'], $status['peerClaimObjects']);
    }

    public function testARepaymentFillsTheCushionBackUpAndRepublishesIt(): void
    {
        $this->loadFixtures(__DIR__.'/../Api/fixtures/cushion_peer_claims.yaml');
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $peerDebts = self::getContainer()->get(ManagePeerDebtsTool::class);
        $allocation = json_decode($peerDebts(
            'allocate',
            transactionId: (string) $this->getFixture('emma_in')->getId(),
            kind: 'repayment',
            shareId: (string) $this->getFixture('gite_emma')->getId(),
            amountCents: 20000,
        ), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($allocation['success']);
        $this->assertMercureUpdatePublished('/safety_cushions/'.$this->getFixture('cushion')->getId());
        $this->assertMercureUpdatePublished('/peer_debts/'.$this->getFixture('gite_debt')->getId());
        $this->assertElasticsearchIndexDispatchedFor(PeerDebt::class, (string) $this->getFixture('gite_debt')->getId());

        $status = $this->cushionStatus();
        self::assertSame(33500, $status['peerClaimsCents']);
        self::assertSame(486500, $status['currentCents']);
    }

    public function testRepayingADetteDoesNotTouchTheCushion(): void
    {
        $this->loadFixtures(__DIR__.'/../Api/fixtures/cushion_peer_claims.yaml');
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $peerDebts = self::getContainer()->get(ManagePeerDebtsTool::class);
        $allocation = json_decode($peerDebts(
            'allocate',
            transactionId: (string) $this->getFixture('tom_out')->getId(),
            kind: 'repayment',
            shareId: (string) $this->getFixture('petrol_tom')->getId(),
            amountCents: 28000,
        ), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($allocation['success']);
        $this->assertMercureUpdatePublished('/peer_debts/'.$this->getFixture('petrol_debt')->getId());
        $this->assertNothingPublishedOn('/safety_cushions/'.$this->getFixture('cushion')->getId());
        self::assertSame(466500, $this->cushionStatus()['currentCents']);
    }
}
