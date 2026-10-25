<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Command;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Command\DetectInternalTransfersCommand;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * `app:finance:detect-internal-transfers` — the catch-up over the history
 * stored before a debit worded with the holder's name alone was recognised
 * (MAG-642).
 */
final class DetectInternalTransfersCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private CommandTester $tester;
    private EntityManagerInterface $em;
    private Transaction $leg;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $application = new Application();
        $application->add(self::getContainer()->get(DetectInternalTransfersCommand::class));
        $this->tester = new CommandTester($application->find('app:finance:detect-internal-transfers'));

        $this->loadFixtures(\dirname(__DIR__).'/UseCase/fixtures/internal_transfers.yaml');

        /** @var User $user */
        $user = $this->getFixture('test_user');
        $user->setName('Jean Dupont');
        /** @var Account $account */
        $account = $this->getFixture('checking');

        $this->leg = (new Transaction())
            ->setUser($user)
            ->setAccount($account)
            ->setAmountCents(-87300)
            ->setCurrency('EUR')
            ->setBookedAt(new \DateTimeImmutable('2026-09-29'))
            ->setLabel('Jean Dupont')
            ->setStatus(TransactionStatus::Spent);
        $this->em->persist($this->leg);
        $this->flushWithoutTransactionEffects($this->em);
    }

    private function kind(): TransferKind
    {
        $this->em->clear();

        return $this->em->find(Transaction::class, $this->leg->getId())->getTransferKind();
    }

    public function testADryRunListsTheLinesItWouldMarkAndWritesNothing(): void
    {
        $this->tester->execute(['--dry-run' => true]);

        $this->tester->assertCommandIsSuccessful();
        self::assertStringContainsString('2026-09-29 Jean Dupont', $this->tester->getDisplay());
        self::assertStringContainsString('Dry run only', $this->tester->getDisplay());
        self::assertSame(TransferKind::None, $this->kind());
    }

    public function testItMarksTheLineAndASecondRunFindsNothingMore(): void
    {
        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        self::assertSame(TransferKind::Internal, $this->kind());

        $this->tester->execute([]);

        self::assertStringContainsString('0 pair(s) and 0 lone leg(s) marked', $this->tester->getDisplay());
    }

    public function testDaysMustBeAnInteger(): void
    {
        $this->tester->execute(['--days' => 'abc']);

        self::assertSame(2, $this->tester->getStatusCode());
    }
}
