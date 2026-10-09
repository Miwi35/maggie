<?php

namespace Maggie\Finance\Tests\MessageHandler;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\CreateTransactionCommand;
use Maggie\Finance\Message\DeleteTransactionCommand;
use Maggie\Finance\Message\ReleaseCounterpartCommand;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/** What the commands behind the transaction events decide, one rule each. */
class TransactionCommandHandlersTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Import/fixtures/two_accounts.yaml');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function dispatch(object $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function create(string $account, int $amountCents, string $label, string $bookedAt): void
    {
        $this->dispatch(new CreateTransactionCommand(
            userId: (string) $this->getFixture('test_user')->getId(),
            accountId: (string) $this->getFixture($account)->getId(),
            amountCents: $amountCents,
            label: $label,
            bookedAt: $bookedAt,
        ));
    }

    private function byLabel(string $label): Transaction
    {
        $this->em()->clear();

        return $this->em()->getRepository(Transaction::class)->findOneBy(['label' => $label]);
    }

    public function testAnImportedRejectionCreditIsPairedWithItsDebitAndAnnouncedOnce(): void
    {
        $this->create('checking', -20600, 'PRELEVEMENT ELECTRICITE DE FRANCE ECHEANCE', (new \DateTimeImmutable('-3 days'))->format('Y-m-d'));
        $this->create('checking', 20600, 'REJET PRLV ELECTRICITE DE FRANCE', (new \DateTimeImmutable('-2 days'))->format('Y-m-d'));

        $debit = $this->byLabel('PRELEVEMENT ELECTRICITE DE FRANCE ECHEANCE');
        $credit = $this->byLabel('REJET PRLV ELECTRICITE DE FRANCE');
        self::assertSame(TransferKind::Rejected, $debit->getTransferKind());
        self::assertSame(TransferKind::Rejected, $credit->getTransferKind());
        self::assertSame((string) $credit->getId(), (string) $debit->getCounterpart()?->getId());
        self::assertCount(1, $this->em()->getRepository(Notification::class)->findBy(['type' => NotificationType::Finance]));
    }

    public function testALegPairedAsItLandsIsNotFiledUnderACategory(): void
    {
        $user = $this->em()->find(User::class, $this->getFixture('test_user')->getId());
        foreach ([['savings', -300000, 'VIREMENT CARREFOUR EPARGNE', '2026-10-02'], ['checking', 300000, 'VIREMENT RECU', '2026-10-03']] as [$account, $amount, $label, $date]) {
            $this->em()->persist((new Transaction())
                ->setUser($user)
                ->setAccount($this->em()->find(Account::class, $this->getFixture($account)->getId()))
                ->setAmountCents($amount)
                ->setCurrency('EUR')
                ->setBookedAt(new \DateTimeImmutable($date))
                ->setLabel($label)
                ->setStatus(TransactionStatus::Spent));
        }
        $this->em()->flush();

        $leg = $this->byLabel('VIREMENT CARREFOUR EPARGNE');
        self::assertSame(TransferKind::Internal, $leg->getTransferKind());
        self::assertNull($leg->getCategory(), 'the CARREFOUR rule must not claim a movement between the owner\'s own accounts');
    }

    public function testRemovingARejectionDebitReleasesItsCredit(): void
    {
        $this->create('checking', -20600, 'PRELEVEMENT ELECTRICITE DE FRANCE ECHEANCE', (new \DateTimeImmutable('-3 days'))->format('Y-m-d'));
        $this->create('checking', 20600, 'REJET PRLV ELECTRICITE DE FRANCE', (new \DateTimeImmutable('-2 days'))->format('Y-m-d'));
        $debit = $this->byLabel('PRELEVEMENT ELECTRICITE DE FRANCE ECHEANCE');

        $this->dispatch(new DeleteTransactionCommand((string) $this->getFixture('test_user')->getId(), (string) $debit->getId()));

        $credit = $this->byLabel('REJET PRLV ELECTRICITE DE FRANCE');
        self::assertSame(TransferKind::None, $credit->getTransferKind());
        self::assertNull($credit->getCounterpart());
    }

    public function testALegPairedAgainWithAnotherLineIsNotReleased(): void
    {
        $out = $this->getFixture('transfer_out');
        $in = $this->getFixture('transfer_in');
        $out->markAsInternalTransfer($in, TransferSource::Auto);
        $this->em()->flush();

        $this->dispatch(new ReleaseCounterpartCommand((string) $in->getId(), '01J0SOMEONEELSE'));

        $this->em()->clear();
        self::assertSame(TransferKind::Internal, $this->em()->find(Transaction::class, $in->getId())->getTransferKind());
    }

    public function testReleasingALegThatIsAlreadyOrdinaryChangesNothing(): void
    {
        $this->dispatch(new ReleaseCounterpartCommand((string) $this->getFixture('plain_expense')->getId(), '01J0GONE'));

        $this->em()->clear();
        $plain = $this->em()->find(Transaction::class, $this->getFixture('plain_expense')->getId());
        self::assertSame(TransferKind::None, $plain->getTransferKind());
    }
}
