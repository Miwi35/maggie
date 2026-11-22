<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Entity\TreasurySettings;
use Maggie\Finance\Mcp\Tool\ManageFinanceSettingsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** manage_finance_settings: the treasury and the safety cushion settings behind one tool. */
class FinanceSettingsToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/savings_advances.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageFinanceSettingsTool::class);
        \assert($tool instanceof ManageFinanceSettingsTool);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function treasuryOf(string $userRef): TreasurySettings
    {
        $settings = $this->em()->getRepository(TreasurySettings::class)->findOneBy(['user' => $this->getFixture($userRef)->getId()]);
        \assert($settings instanceof TreasurySettings);

        return $settings;
    }

    private function cushionOf(string $userRef): SafetyCushion
    {
        $cushion = $this->em()->getRepository(SafetyCushion::class)->findOneBy(['user' => $this->getFixture($userRef)->getId()]);
        \assert($cushion instanceof SafetyCushion);

        return $cushion;
    }

    public function testWithoutAUserReturnsError(): void
    {
        self::assertArrayHasKey('error', $this->call('get'));
        self::assertArrayHasKey('error', $this->call('update', salaryExpectedDay: 12));
        $this->assertMercureUpdateCount(0);
    }

    public function testGetReturnsTreasuryAndCushionSettings(): void
    {
        $this->loginFixtureUser();

        $settings = $this->call('get')['settings'];

        self::assertSame(25, $settings['salaryExpectedDay']);
        self::assertSame(0, $settings['overdraftLimitCents']);
        self::assertSame(3, $settings['targetMonths']);
        self::assertSame(495000, $settings['monthlyNetIncomeCents']);
        self::assertSame(15000, $settings['rechargeCapCents']);
        self::assertSame(6, $settings['rechargeTargetMonths']);
        self::assertSame(2, $settings['criticalMonths']);
        self::assertSame(60, $settings['cushionSharePercent']);
    }

    public function testUpdateChangesOnlyGivenFields(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', overdraftLimitCents: 30000, criticalMonths: 4);

        self::assertTrue($data['success']);
        self::assertSame(30000, $data['settings']['overdraftLimitCents']);
        self::assertSame(4, $data['settings']['criticalMonths']);
        $treasury = $this->treasuryOf('test_user');
        self::assertSame(30000, $treasury->getOverdraftLimitCents());
        self::assertSame(25, $treasury->getSalaryExpectedDay());
        $cushion = $this->cushionOf('test_user');
        self::assertSame(4, $cushion->getCriticalMonths());
        self::assertSame(3, $cushion->getTargetMonths());
        self::assertSame(60, $cushion->getCushionSharePercent());
        $this->assertMercureUpdatePublished('/api/treasury_settings/'.$treasury->getId());
        $this->assertMercureUpdatePublished('/api/safety_cushions/'.$cushion->getId());
        $this->assertNoElasticsearchIndexDispatched(TreasurySettings::class);

        $day = $this->call('update', salaryExpectedDay: 28);
        self::assertSame(28, $day['settings']['salaryExpectedDay']);
        self::assertSame(4, $this->cushionOf('test_user')->getCriticalMonths());

        $cleared = $this->call('update', clearSalaryExpectedDay: true);
        self::assertNull($cleared['settings']['salaryExpectedDay']);
        self::assertNull($this->treasuryOf('test_user')->getSalaryExpectedDay());
    }

    public function testOutOfRangeValuesAreRefused(): void
    {
        $this->loginFixtureUser();

        foreach ([
            ['salaryExpectedDay' => 32],
            ['salaryExpectedDay' => 0],
            ['overdraftLimitCents' => -1],
            ['targetMonths' => 0],
            ['monthlyNetIncomeCents' => -5],
            ['criticalMonths' => 25],
            ['criticalMonths' => -1],
            ['cushionSharePercent' => 101],
            ['rechargeCapCents' => -1],
            ['rechargeTargetMonths' => 0],
            [],
        ] as $arguments) {
            self::assertArrayHasKey('error', $this->call('update', ...$arguments), json_encode($arguments, JSON_THROW_ON_ERROR));
        }

        // All or nothing: a valid field next to an invalid one is not written.
        self::assertArrayHasKey('error', $this->call('update', overdraftLimitCents: 50000, criticalMonths: 99));

        self::assertSame(25, $this->treasuryOf('test_user')->getSalaryExpectedDay());
        self::assertSame(0, $this->treasuryOf('test_user')->getOverdraftLimitCents());
        self::assertSame(2, $this->cushionOf('test_user')->getCriticalMonths());
        self::assertSame(3, $this->cushionOf('test_user')->getTargetMonths());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnotherUsersSettingsUntouched(): void
    {
        $this->loginFixtureUser();

        $this->call('update', salaryExpectedDay: 5, targetMonths: 9);

        $theirTreasury = $this->treasuryOf('other_user');
        self::assertSame(25, $theirTreasury->getSalaryExpectedDay());
        self::assertSame(3, $this->cushionOf('other_user')->getTargetMonths());
        $this->assertNothingPublishedOn('/api/treasury_settings/'.$theirTreasury->getId());
        $this->assertNothingPublishedOn('/api/safety_cushions/'.$this->cushionOf('other_user')->getId());
    }

    public function testUnknownActionListsActions(): void
    {
        $this->loginFixtureUser();

        $error = $this->call('refill')['error'];

        self::assertStringContainsString('Unknown action', $error);
        self::assertStringContainsString('get', $error);
        self::assertStringContainsString('update', $error);
    }
}
