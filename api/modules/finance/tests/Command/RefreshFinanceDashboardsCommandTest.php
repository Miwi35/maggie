<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Command;

use App\Tests\Support\FinanceDashboardRefreshTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Command\RefreshFinanceDashboardsCommand;
use Maggie\Finance\Repository\FinanceDashboardSnapshotRepository;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * The hourly job that keeps the read model on the user's day (MAG-545): the
 * rings move with the date, so a model computed yesterday is rebuilt.
 */
class RefreshFinanceDashboardsCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use FinanceDashboardRefreshTrait;

    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/dashboard.yaml');
        $this->drainDashboardRefreshes();

        $application = new Application();
        $application->add(self::getContainer()->get(RefreshFinanceDashboardsCommand::class));
        $this->tester = new CommandTester($application->find('app:finance:refresh-dashboards'));
    }

    private function stale(): bool
    {
        return self::getContainer()->get(FinanceDashboardSnapshotRepository::class)
            ->stateOf((string) $this->getFixture('test_user')->getId())['stale'];
    }

    public function testAModelComputedOnTheUsersDayIsLeftAlone(): void
    {
        $this->tester->execute([]);

        self::assertStringContainsString('0 dashboard(s)', $this->tester->getDisplay());
        self::assertFalse($this->stale());
    }

    public function testAModelComputedOnAnotherDayIsMarkedStaleAndRebuilt(): void
    {
        $connection = self::getContainer()->get('doctrine.orm.entity_manager')->getConnection();
        $connection->executeStatement("UPDATE finance_dashboard_snapshot SET computed_on = '2020-01-01'");

        $this->tester->execute([]);

        self::assertStringContainsString('1 dashboard(s)', $this->tester->getDisplay());
        self::assertTrue($this->stale());

        $this->drainDashboardRefreshes();
        self::assertFalse($this->stale());
        self::assertNotSame('2020-01-01', $connection->fetchOne('SELECT computed_on FROM finance_dashboard_snapshot'));
    }

    public function testAModelComputedOnAnotherServerDayIsMarkedStaleAndRebuilt(): void
    {
        $connection = self::getContainer()->get('doctrine.orm.entity_manager')->getConnection();
        $connection->executeStatement("UPDATE finance_dashboard_snapshot SET computed_server_on = '2020-01-01'");

        $this->tester->execute([]);

        self::assertStringContainsString('1 dashboard(s)', $this->tester->getDisplay());
        self::assertTrue($this->stale());

        $this->drainDashboardRefreshes();
        self::assertFalse($this->stale());
        self::assertSame(
            (new \DateTimeImmutable('today'))->format('Y-m-d'),
            $connection->fetchOne('SELECT computed_server_on FROM finance_dashboard_snapshot'),
        );
    }

    public function testAModelStoredByAnEarlierReleaseIsMarkedStaleAndRebuilt(): void
    {
        $connection = self::getContainer()->get('doctrine.orm.entity_manager')->getConnection();
        $stored = json_decode((string) $connection->fetchOne('SELECT payload FROM finance_dashboard_snapshot'), true, 512, \JSON_THROW_ON_ERROR);
        unset($stored['shape']);
        $connection->executeStatement('UPDATE finance_dashboard_snapshot SET payload = :payload', ['payload' => json_encode($stored, \JSON_THROW_ON_ERROR)]);

        $this->tester->execute([]);

        self::assertStringContainsString('1 dashboard(s)', $this->tester->getDisplay());
        self::assertTrue($this->stale());

        $this->drainDashboardRefreshes();
        self::assertFalse($this->stale());
        self::assertStringContainsString('"shape":', (string) $connection->fetchOne('SELECT payload FROM finance_dashboard_snapshot'));
    }

    public function testAModelStoredWithoutAServerDayIsRebuilt(): void
    {
        self::getContainer()->get('doctrine.orm.entity_manager')->getConnection()->executeStatement('UPDATE finance_dashboard_snapshot SET computed_server_on = NULL');

        $this->tester->execute([]);

        self::assertStringContainsString('1 dashboard(s)', $this->tester->getDisplay());
    }

    public function testAUserWhoseModelWasNeverBuiltIsBuilt(): void
    {
        self::getContainer()->get('doctrine.orm.entity_manager')->getConnection()->executeStatement('DELETE FROM finance_dashboard_snapshot');

        $this->tester->execute([]);

        self::assertStringContainsString('1 dashboard(s)', $this->tester->getDisplay());
        self::assertTrue($this->stale());
    }
}
