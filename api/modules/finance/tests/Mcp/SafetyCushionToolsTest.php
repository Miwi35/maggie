<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Mcp\Tool\ManageSafetyCushionTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class SafetyCushionToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
    }

    private function tool(): ManageSafetyCushionTool
    {
        return self::getContainer()->get(ManageSafetyCushionTool::class);
    }

    /** @return array<string, mixed> */
    private function cushionStatus(): array
    {
        return json_decode($this->tool()('status'), true, 512, JSON_THROW_ON_ERROR);
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
        $this->loadFixtures('safety_cushion.yaml');
        $this->loginFixtureUser();

        $status = $this->cushionStatus();

        // 3000 € + 1500 €, and the current account is left out.
        self::assertSame(450000, $status['currentCents']);
        self::assertCount(2, $status['accounts']);
        self::assertSame(750000, $status['targetCents']);
    }

    public function testAnIncompleteCushionIsStillBuildingAndBlocksAGreenScore(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
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
        $this->loadFixtures('safety_cushion.yaml');
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
        $this->loadFixtures('safety_cushion.yaml');
        $this->loginFixtureUser();

        $this->tool()('configure', rechargeCapCents: 100000);
        $status = $this->cushionStatus();

        // 3000 € over 6 months = 500 €/month, which fits under a 1000 € cap.
        self::assertSame(50000, $status['monthlyRechargeCents']);
        self::assertSame(6, $status['rechargeMonths']);
        self::assertFalse($status['isCappedByRechargeCap']);
    }

    public function testReachingTheTargetCompletesTheCushion(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        $this->loginFixtureUser();
        $this->setBalance('livret', 600000);

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
        $this->loadFixtures('safety_cushion.yaml');
        $this->loginFixtureUser();

        // Fill it up first, so it remembers having been complete...
        $this->setBalance('livret', 600000);
        self::assertSame('complete', $this->cushionStatus()['state']);

        // ...then dip into it.
        $this->setBalance('livret', 400000);
        $status = $this->cushionStatus();

        self::assertSame('recharging', $status['state']);
        self::assertSame(200000, $status['deficitCents']);
        self::assertTrue($status['blocksGreenScore']);
    }

    public function testConfigureChangesTheTargetAndRecomputesIt(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            $this->tool()('configure', targetMonths: 6, monthlyNetIncomeCents: 300000),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame(1800000, $data['cushion']['targetCents']);
        self::assertSame(6, $data['cushion']['targetMonths']);

        $this->assertMercureUpdatePublished('/safety_cushions/');
    }

    public function testARaiseRecomputesTheTargetWithoutTouchingTheCushion(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        $this->loginFixtureUser();

        $this->tool()('configure', monthlyNetIncomeCents: 400000);
        $status = $this->cushionStatus();

        // The target follows the income; the balance did not move.
        self::assertSame(1200000, $status['targetCents']);
        self::assertSame(450000, $status['currentCents']);
        self::assertSame('building', $status['state']);
    }

    public function testConfigureRejectsAnImpossibleTarget(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('configure', targetMonths: 0), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testConfigureNeedsSomethingToChange(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('configure'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testUnknownActionIsReported(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('refill'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }
}
