<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\FinanceDashboardSnapshot;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Message\RefreshFinanceDashboardCommand;
use Maggie\Finance\Repository\FinanceDashboardSnapshotRepository;
use Maggie\Finance\Service\FinanceReviewAppointment;
use Maggie\Finance\UseCase\EnsureSafetyCushion;
use Maggie\Finance\UseCase\GetCushionStatus;
use Maggie\Finance\UseCase\GetFinanceDashboard;
use Maggie\Finance\UseCase\GetFinanceScores;
use Maggie\Finance\UseCase\RecordCushionCompletion;
use Maggie\Finance\UseCase\RefreshFinanceDashboard;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Ulid;

/**
 * The rebuild of the Finance Dashboard read model (MAG-545), and the two small
 * writes the read used to make and no longer does: the cushion's completion and
 * the cushion itself.
 */
class RefreshFinanceDashboardTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/dashboard.yaml');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function user(): User
    {
        return $this->em()->find(User::class, $this->getFixture('test_user')->getId());
    }

    private function userId(): string
    {
        return (string) $this->user()->getId();
    }

    private function snapshots(): FinanceDashboardSnapshotRepository
    {
        return self::getContainer()->get(FinanceDashboardSnapshotRepository::class);
    }

    private function snapshot(): FinanceDashboardSnapshot
    {
        $this->em()->clear();

        return $this->snapshots()->findOneByUser($this->user());
    }

    public function testItStoresTheDashboardOfTheUsersCurrentMonth(): void
    {
        $today = self::getContainer()->get(GetFinanceScores::class)->resolveDate($this->user(), null);
        $expected = (new JsonResponse(self::getContainer()->get(GetFinanceDashboard::class)->execute(
            $this->user(),
            (int) $today->format('Y'),
            (int) $today->format('n'),
        )))->getContent();

        self::getContainer()->get(RefreshFinanceDashboard::class)->execute($this->userId());

        $snapshot = $this->snapshot();
        self::assertFalse($snapshot->isStale());
        self::assertSame($expected, $snapshot->getPayload());
        self::assertSame((int) $today->format('Y'), $snapshot->getYear());
        self::assertSame((int) $today->format('n'), $snapshot->getMonth());
        self::assertSame($today->format('Y-m-d'), $snapshot->getComputedOn()?->format('Y-m-d'));
    }

    public function testItLaysDownTheReviewAppointmentTheReadUsedToLayDown(): void
    {
        $agendas = fn (): int => (int) $this->em()->getConnection()->fetchOne("SELECT COUNT(*) FROM agenda WHERE module = 'finance'");
        self::assertSame(0, $agendas());

        self::getContainer()->get(RefreshFinanceDashboard::class)->execute($this->userId());
        self::getContainer()->get(RefreshFinanceDashboard::class)->execute($this->userId());

        self::assertSame(1, $agendas());
    }

    public function testAnUpToDateModelIsLeftAlone(): void
    {
        $refresh = self::getContainer()->get(RefreshFinanceDashboard::class);
        $refresh->execute($this->userId());
        $this->em()->getConnection()->executeStatement("UPDATE finance_dashboard_snapshot SET payload = '{\"marker\":true}'");

        $refresh->execute($this->userId());

        self::assertSame('{"marker":true}', $this->snapshot()->getPayload());
    }

    public function testAWriteDuringTheComputationLeavesTheModelStaleAndAsksForAnotherRebuild(): void
    {
        $snapshots = $this->snapshots();
        $userId = $this->userId();
        $writeDuringTheComputation = new class($snapshots, $userId) extends GetFinanceDashboard {
            public function __construct(private readonly FinanceDashboardSnapshotRepository $snapshots, private readonly string $userId)
            {
            }

            public function execute(User $user, int $year, int $month): array
            {
                $this->snapshots->markStale($this->userId);

                return ['figures' => 'computed before the write landed'];
            }
        };
        $sent = [];
        $bus = new class($sent) implements MessageBusInterface {
            /** @param list<object> $sent */
            public function __construct(private array &$sent)
            {
            }

            public function dispatch(object $message, array $stamps = []): Envelope
            {
                $this->sent[] = $message;

                return new Envelope($message);
            }
        };
        $container = self::getContainer();
        $refresh = new RefreshFinanceDashboard(
            $container->get(\Maggie\Core\Repository\UserRepository::class),
            $snapshots,
            $writeDuringTheComputation,
            $container->get(GetFinanceScores::class),
            $container->get(RecordCushionCompletion::class),
            $container->get(FinanceReviewAppointment::class),
            $this->em(),
            $bus,
        );
        $snapshots->markStale($userId);

        $refresh->execute($userId);

        self::assertTrue($this->snapshot()->isStale(), 'the figures it stored predate the write: not fresh');
        self::assertCount(1, $sent);
        self::assertInstanceOf(RefreshFinanceDashboardCommand::class, $sent[0]);
        self::assertSame($userId, $sent[0]->userId);
        self::assertNull(
            $snapshots->fetchFreshPayload($this->user(), $this->snapshot()->getYear(), $this->snapshot()->getMonth(), $this->snapshot()->getComputedOn(), $this->snapshot()->getComputedServerOn()),
            'and the read does not serve them',
        );
    }

    public function testAUserWhoIsGoneOrNeverHadAnythingToRebuildIsSkipped(): void
    {
        $this->em()->getConnection()->executeStatement('UPDATE account SET balance_cents = 1000 WHERE is_cushion');
        $this->em()->getConnection()->executeStatement('DELETE FROM finance_dashboard_snapshot');
        $this->em()->clear();

        self::getContainer()->get(RefreshFinanceDashboard::class)->execute((new Ulid())->toBase32());
        self::getContainer()->get(RefreshFinanceDashboard::class)->execute($this->userId());

        self::assertSame(0, $this->em()->getRepository(FinanceDashboardSnapshot::class)->count([]), 'no row is made by a rebuild, only by a write');
    }

    public function testReadingTheCushionStatusNeitherCreatesTheCushionNorRecordsItsCompletion(): void
    {
        $em = $this->em();
        $em->getConnection()->executeStatement('DELETE FROM safety_cushion');
        $em->clear();

        $status = self::getContainer()->get(GetCushionStatus::class)->execute($this->user());

        self::assertSame(0, $em->getRepository(SafetyCushion::class)->count([]));
        self::assertArrayHasKey('state', $status);
    }

    public function testACompleteCushionHasItsCompletionRecordedOnce(): void
    {
        $record = self::getContainer()->get(RecordCushionCompletion::class);
        self::assertNull($this->em()->find(SafetyCushion::class, $this->getFixture('cushion')->getId())->getCompletedAt());

        $record->execute($this->user());

        $this->em()->clear();
        $completedAt = $this->em()->find(SafetyCushion::class, $this->getFixture('cushion')->getId())->getCompletedAt();
        self::assertNotNull($completedAt);

        $record->execute($this->user());
        $this->em()->clear();
        self::assertEquals($completedAt, $this->em()->find(SafetyCushion::class, $this->getFixture('cushion')->getId())->getCompletedAt());
    }

    public function testAnIncompleteCushionIsNotMarkedComplete(): void
    {
        $this->em()->getConnection()->executeStatement('UPDATE account SET balance_cents = 1000 WHERE is_cushion');
        $this->em()->clear();

        self::getContainer()->get(RecordCushionCompletion::class)->execute($this->user());

        $this->em()->clear();
        self::assertNull($this->em()->find(SafetyCushion::class, $this->getFixture('cushion')->getId())->getCompletedAt());
    }

    public function testTheCushionIsLaidDownOnceWithItsDefaults(): void
    {
        $this->em()->getConnection()->executeStatement('DELETE FROM safety_cushion');
        $this->em()->clear();
        $ensure = self::getContainer()->get(EnsureSafetyCushion::class);

        $first = $ensure->execute($this->user());
        $second = $ensure->execute($this->user());

        self::assertSame((string) $first->getId(), (string) $second->getId());
        self::assertSame(1, $this->em()->getRepository(SafetyCushion::class)->count([]));
    }
}
