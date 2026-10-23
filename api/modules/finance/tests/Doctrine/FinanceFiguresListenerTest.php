<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Doctrine;

use App\Tests\Support\FinanceDashboardRefreshTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\PeerDebtShare;
use Maggie\Finance\Message\RefreshFinanceDashboardCommand;
use Maggie\Finance\Repository\FinanceDashboardSnapshotRepository;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Transport\InMemory\InMemoryTransport;

/**
 * Every flush that touches a user's finance rows says their dashboard is out
 * of date, whichever door the write came through (MAG-545).
 */
class FinanceFiguresListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use FinanceDashboardRefreshTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/dashboard.yaml');
        $this->drainDashboardRefreshes();
        $this->dashboardTransport()->reset();
    }

    private function dashboardTransport(): InMemoryTransport
    {
        return self::getContainer()->get('messenger.transport.dashboard');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function userId(): string
    {
        return (string) $this->getFixture('test_user')->getId();
    }

    /** @return array{version: int, stale: bool} */
    private function state(): array
    {
        return self::getContainer()->get(FinanceDashboardSnapshotRepository::class)->stateOf($this->userId());
    }

    /** @return list<string> the users whose rebuild was queued */
    private function queuedRebuilds(): array
    {
        $users = [];
        foreach ($this->dashboardTransport()->getSent() as $envelope) {
            if ($envelope->getMessage() instanceof RefreshFinanceDashboardCommand) {
                $users[] = $envelope->getMessage()->userId;
            }
        }

        return $users;
    }

    public function testAFinanceWriteMarksTheOwnerStaleAndQueuesTheRebuild(): void
    {
        $before = $this->state();
        self::assertFalse($before['stale']);

        $this->em()->find(Account::class, $this->getFixture('checking')->getId())->setBalanceCents(1);
        $this->em()->flush();

        $after = $this->state();
        self::assertTrue($after['stale']);
        self::assertSame($before['version'] + 1, $after['version']);
        self::assertSame([$this->userId()], $this->queuedRebuilds());
    }

    public function testSeveralRowsInOneFlushAreOneMark(): void
    {
        $before = $this->state();

        $this->em()->find(Account::class, $this->getFixture('checking')->getId())->setBalanceCents(1);
        $this->em()->find(Account::class, $this->getFixture('livret')->getId())->setBalanceCents(2);
        $this->em()->flush();

        self::assertSame($before['version'] + 1, $this->state()['version']);
        self::assertCount(1, $this->queuedRebuilds());
    }

    public function testAFlushThatOnlyChangesAShareOfAPeerDebtMarksTheOwnerStale(): void
    {
        $em = $this->em();
        $user = $em->find(User::class, $this->getFixture('test_user')->getId());
        $contact = (new Contact())->setName('Julie')->setUser($user);
        $debt = (new PeerDebt())->setObject('Gîte')->setUser($user);
        $share = (new PeerDebtShare())->setContact($contact)->setAmountCents(10000)->setPosition(0);
        $share->setPeerDebt($debt);
        $em->persist($contact);
        $em->persist($debt);
        $em->flush();
        $shareId = $share->getId();
        $this->drainDashboardRefreshes();
        $this->dashboardTransport()->reset();
        $em->clear();
        $before = $this->state();
        self::assertFalse($before['stale']);

        $em->find(PeerDebtShare::class, $shareId)->setRepaidCents(2500);
        $em->flush();

        $after = $this->state();
        self::assertTrue($after['stale'], 'the repaid amount feeds the dashboard');
        self::assertSame($before['version'] + 1, $after['version']);
        self::assertSame([$this->userId()], $this->queuedRebuilds());
    }

    public function testARowOfAnotherModuleDoesNotMarkAnything(): void
    {
        $before = $this->state();

        $this->em()->find(User::class, $this->getFixture('test_user')->getId())->setName('Someone else');
        $this->em()->flush();

        self::assertSame($before, $this->state());
        self::assertSame([], $this->queuedRebuilds());
    }

    public function testStoringTheRebuildDoesNotMarkItStaleAgain(): void
    {
        $this->em()->find(Account::class, $this->getFixture('checking')->getId())->setBalanceCents(1);
        $this->em()->flush();
        $this->drainDashboardRefreshes();

        self::assertFalse($this->state()['stale']);
    }

    public function testRemovingARowMarksTheOwnerStale(): void
    {
        $this->em()->getConnection()->executeStatement('DELETE FROM transaction');
        $this->em()->clear();
        $account = $this->em()->find(Account::class, $this->getFixture('livret')->getId());
        $this->em()->remove($account);
        $this->em()->flush();

        self::assertTrue($this->state()['stale']);
    }

    public function testAFlushThatFailsLeavesNothingBehindForTheNextOne(): void
    {
        $before = $this->state();
        $account = $this->em()->find(Account::class, $this->getFixture('checking')->getId());
        $account->setBalanceCents(1);
        $account->setName(str_repeat('x', 5000));

        try {
            $this->em()->flush();
            self::fail('The name is longer than its column.');
        } catch (\Throwable) {
        }

        self::assertSame($before, $this->state());
    }
}
